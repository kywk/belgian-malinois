package com.bpm.core.form.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.form.model.FormData;
import com.bpm.core.form.service.FormService;
import com.bpm.core.form.validation.FormSchemaValidator;
import com.bpm.core.notify.NotifyPublisher;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
import org.flowable.engine.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 表單資料的 CRUD。
 *
 * <h2>改動前：整個 class 沒有 {@code @CallerId}，三個端點等於零授權</h2>
 *
 * <p>這三個端點處理的是「一張單的薪資、理由、金額」，而依 spec §8.5
 * 表單欄位 id 就是流程變數名 —— 也就是說 {@code bpm_form_data.dataJson} 與
 * {@code GET /api/process-instances/{id}/variables} 是<b>同一批敏感資料的兩條路徑</b>。
 * #71 已經把後者擋下來，卻沒有擋這裡，於是那一版的保護是可繞過的。
 *
 * <ul>
 *   <li>{@code GET /api/form-data/{pid}}：零檢查。任何登入者讀得到任何案件的表單資料
 *       （實測 token=user001 讀 user002 的案件回 200，
 *       {@code dataJson: {"salary":"95000","reason":"機密"}}）。</li>
 *   <li>{@code PUT /api/form-data/{id}}：連「這筆資料屬於誰」都沒查。
 *       <b>任何登入者都能改任何人的表單資料</b>（實測回 200，
 *       之後擁有者讀到的就是被改掉的內容）。</li>
 *   <li>{@code POST}／{@code PUT}：{@code submittedBy} 來自 request body，
 *       而且同時被當成稽核的 operatorId → 稽核記的是冒用者，
 *       且被 hash chain 永久固定。與 #66 修掉的 {@code ProcessController.initiator}、
 *       {@code DocumentController.createdBy} 完全同型。</li>
 * </ul>
 *
 * <h2>三組政策（沿用 #66／#71 定的，不可各自發明）</h2>
 *
 * <ol>
 *   <li><b>讀：</b>{@link ProcessAccessGuard#requireReadAccess} ——
 *       參與者，或持有 {@code audit:log:read} 的稽核人員，且旁路必留痕。</li>
 *   <li><b>寫：</b>{@link ProcessAccessGuard#requireParticipant} ＋ #58 的
 *       <b>送件人</b>與<b>退回狀態</b>兩道閘門（見 {@link #update}）——
 *       <b>刻意沒有稽核旁路</b>：稽核人員的職責是查閱，不是替案件補件
 *       或改寫別人填的薪資數字。理由與 {@code AttachmentController.upload} 相同。</li>
 *   <li><b>身分：</b>{@code submittedBy} 一律由 {@code @CallerId} 決定；
 *       冒用（帶了與自己不符的值）→ <b>明確 400</b>；省略／空白 → 登入者。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/form-data")
public class FormDataController {

    private final FormService formService;
    private final AuditEventPublisher auditPublisher;
    private final ProcessAccessGuard accessGuard;
    // #55 的 schema 驗證。規則全部收在 FormSchemaValidator，controller 只負責
    // 把違規清單轉成 400（狀態碼與訊息形狀是 HTTP 邊界的事）。
    private final FormSchemaValidator schemaValidator;
    // #58 的退回狀態判定：查「現任任務」。任務查詢不經過 accessGuard ——
    // 它不是物件層授權（那個仍在 ProcessAccessGuard），而是本端點的狀態前提。
    private final TaskService taskService;

    public FormDataController(FormService formService, AuditEventPublisher auditPublisher,
                              // #71 抽出的共用授權判斷。附件、variables、表單資料
                              // 三者必須共用同一份 isParticipant：兩處各自維護時，
                              // 只要有人改了其中一處，就會出現「這個看得到、那個看不到」
                              // 的組合，而那種差異比沒有檢查更難察覺。
                              ProcessAccessGuard accessGuard,
                              FormSchemaValidator schemaValidator,
                              TaskService taskService) {
        this.formService = formService;
        this.auditPublisher = auditPublisher;
        this.accessGuard = accessGuard;
        this.schemaValidator = schemaValidator;
        this.taskService = taskService;
    }

    // ── POST /api/form-data ───────────────────────────────────────

    /**
     * 送出表單資料。
     *
     * <h2>為什麼要有 requireParticipant</h2>
     *
     * <p>{@code processInstanceId} 是 body 的一個純資料欄位，而這筆資料會
     * <b>掛到那個案件上並被它的參與者讀到</b>。所以「能把資料塞進哪個案件」
     * 本身就是一個授權問題，不只是「能不能呼叫這個 API」。
     *
     * <h2>⚠️ 為什麼先擋 null／空白 processInstanceId（400）</h2>
     *
     * <p>不是為了輸入驗證，而是<b>為了讓守衛不會踩到 Flowable 的 null 陷阱</b>：
     * {@code processInstanceId(null)} 在 {@code ProcessInstanceQueryImpl} 裡
     * 會被當成「沒有這個條件」而原樣返回，於是 {@code singleResult()}
     * 變成對<b>全公司案件</b>取單筆 —— 結果超過一筆時拋 FlowableException → 500。
     * 也就是說少了這道檢查，一個 {@code processInstanceId} 沒帶的請求
     * 就能讓守衛自己炸掉。請求形狀不完整是 400，不是 404，理由與
     * {@code ProcessController.startProcess} 的「缺 key」相同。
     *
     * <h2>#55：為什麼 schema 驗證在 submitData 之前、授權與身分之後</h2>
     *
     * <p>順序沿用本方法的既有原則：形狀 → 授權 → 身分 → <b>內容</b>。
     * 「能不能寫、用誰的名義寫」比「內容對不對」更根本，所以
     * {@code FormSchemaValidator} 放在 {@code requireParticipant} 與
     * {@code requireSelf} 之後；而它必須在 {@code submitData} 之前 ——
     * 驗證若在寫入之後，違規資料已經落地，「零副作用」就不可能。
     *
     * <p>違規一律 400 並指名欄位（沿用 #66／#72 的訊息政策：呼叫端要知道
     * 改哪一個欄位，而不是只看到 400）。schemaJson 毀損是 500（伺服器端
     * 資料問題，fail-closed）；查不到表單定義則略過驗證並 warn
     * （已知缺口，理由見 {@link FormSchemaValidator} 類別註解）。
     *
     * <p>⚠️ {@code PUT /api/form-data/{id}} 不走這條驗證：#58 處理了
     * 授權、退回狀態與版本化，<b>仍未</b>納入 schema 驗證 ——
     * 「新增時驗、退回修改時不驗」是仍然存在的已知落差。
     */
    @PostMapping
    @Transactional("formTransactionManager")
    public FormData submit(@RequestBody FormData data, @CallerId String callerId) {
        // 未認證回 401，不 fallback 到任何標頭 —— 見 CallerIdArgumentResolver
        // 類別註解。正常情況 SecurityConfig 的 authenticated() 擋在前頭；
        // 這裡是為了「授權矩陣日後放寬」時不會靜默退化成可偽造的身分。
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "無法確認送表單的身分，請先登入");
        }

        String pid = data.getProcessInstanceId();
        if (pid == null || pid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "processInstanceId 為必填 —— 表單資料必須掛在某個案件上。");
        }

        // 順序：形狀 → 授權 → 身分欄位。請求本身不完整與「你沒權」是兩件事，
        // 先把形狀講清楚，呼叫端才知道要改 payload 還是改流程選擇。
        accessGuard.requireParticipant(pid, callerId);

        // submittedBy 一律由登入身分決定。
        //
        // ⚠️ 明確 400 拒絕冒用，而不是「先接受再覆寫」：覆寫會讓呼叫端以為
        // 自己指定的值生效了（與 #66 的 initiator、DocumentController 的 createdBy
        // 同一取捨）。
        //
        // 為什麼是「不同才拒絕」而不是「body 有帶就拒絕」：initiator 是專用 DTO
        // 欄位，server 可以用 containsKey 判斷「有沒有送」；submittedBy 是
        // JPA entity 上的欄位、Jackson 反序列化後無法分辨「沒送」與「送了 null」，
        // 而送出與自己相同的身分並不構成冒用（見 DocumentController 的同型判斷）。
        //
        // 省略時必須<b>寫入</b> callerId 而不是留 null：這一欄是這筆資料的
        // 送件人，欄位可為 null 會讓「這張表單是誰送出的」變成無解。
        String submitter = accessGuard.requireSelf(data.getSubmittedBy(), callerId, "submittedBy");
        data.setSubmittedBy(submitter);

        // 內容驗證（#55）：dataJson 必須符合 formDefinitionId 指向的 schemaJson。
        // 這是本方法唯一一個「不改任何資料、只讀」的步驟 —— 違規時在此中止，
        // submitData 與稽核都不會執行（零副作用）。規則本身不在這裡，
        // 見 FormSchemaValidator（規則只能有一份）。
        List<FormSchemaValidator.Violation> violations =
                schemaValidator.validate(data.getFormDefinitionId(), data.getDataJson());
        if (!violations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "表單資料不符表單定義的 schema：" + violations.stream()
                            .map(v -> "'" + v.fieldId() + "' " + v.reason())
                            .collect(Collectors.joining("；")));
        }

        FormData saved = formService.submitData(data);
        // operatorId 用 callerId，不用 data.getSubmittedBy()：稽核要記「誰做的」，
        // 不是「這筆資料掛在誰名下」。改動前這裡記的是冒用者，會讓真正的送件人
        // 在稽核裡完全不存在 —— 而且被 hash chain 永久固定（與 #66 的 initiator 同一理由）。
        auditPublisher.publish(new AuditEvent(OperationType.FORM_SUBMIT.name(), callerId,
                saved.getProcessInstanceId(), null,
                Map.of("formDefinitionId", saved.getFormDefinitionId(),
                        "formDataId", saved.getId())));
        return saved;
    }

    // ── GET /api/form-data/{processInstanceId} ─────────────────────

    /**
     * 某案件的表單資料。
     *
     * <h2>授權政策＝{@code GET /api/process-instances/{id}/variables}，逐字對齊</h2>
     *
     * <p>兩者回傳同一批資料（欄位 id == 流程變數名，spec §8.5），
     * 因此走的是同一個方法 {@link ProcessAccessGuard#requireReadAccess}：
     * 參與者，或持有 {@code audit:log:read}（<b>不認 {@code ROLE_ADMIN}</b>）的
     * 稽核人員；其餘 404（不是 403 —— 403 會確認「這個案件存在」，
     * 對可枚舉的 id 等於把枚舉管道留著）。
     *
     * <h2>留痕政策：只記旁路，不記參與者</h2>
     *
     * <p>與 {@code ProcessController.getVariables} 相同：讀自己的表單是日常操作，
     * 參與者讀到的正是他自己填的值；而「誰以稽核身分調閱了哪些案件」本身
     * 就必須可被稽核。與 {@code AttachmentController.download} 那種
     * 「每次下載都留痕」不同 —— 若連參與者讀表單都留痕，等於要為日常操作
     * 另外建立一套行為稽核。
     *
     * <p>⚠️ 與 getVariables 刻意差異化的一處：<b>已結束的案件在這裡回 200 + 資料</b>，
     * 那裡是 200 + {}。因為表單資料在 {@code bpm_form_db} 是持久列，
     * 不像 runtime 流程變數會被 Flowable 在結案時清除 —— 審結的單仍然
     * 應該看得到當時填了什麼。
     *
     * <p>刻意<b>不</b>加 {@code @Transactional}：這是唯讀查詢 ＋ 一次稽核寫入，
     * 與 {@code ProcessController.getVariables}／{@code AttachmentController.list}
     * 同一型（稽核失敗 → 503，見 {@code AuditFailClosedTest}）。
     * 加交易反而會讓稽核掛在 beforeCommit，而回應組裝階段的例外
     * 會讓它永遠寫不進去。
     */
    @GetMapping("/{processInstanceId}")
    public List<FormData> getByProcess(@PathVariable String processInstanceId,
                                       @CallerId String callerId) {
        if (accessGuard.requireReadAccess(processInstanceId, callerId)) {
            // 參與者讀自己的表單不留痕（那是日常操作）；稽核旁路必須留痕。
            auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                    processInstanceId, null,
                    Map.of("action", "get_form_data", "auditBypass", true)));
        }
        return formService.getDataByProcess(processInstanceId);
    }

    // ── PUT /api/form-data/{id} ───────────────────────────────────

    /**
     * 修改一筆表單資料 —— 只有該列的送件人、在退回（補件）狀態下、以新增版本的方式。
     *
     * <h2>改動前：任何登入者都能改任何人的表單資料</h2>
     *
     * <p>路徑參數只有 {@code id}，而 {@code updateData} 內部
     * {@code findById(id)} 之後<b>完全沒有判斷呼叫者與那個案件的關係</b>。
     * 實測：以 user001 的身分 PUT user002 案件的表單回 200，
     * 之後 user002 讀到的就是被改掉的內容 —— 也就是薪資欄位可以由無關的人改寫。
     *
     * <h2>#72 只做到「參與者」，#58 補上「送件人」與「退回狀態」</h2>
     *
     * <p>{@code requireParticipant} 擋下的是無關的人，但簽核人、加簽人
     * 也都是參與者 —— 他們能改寫申請人填的薪資數字。而 #72 的
     * {@code requireSelf} 只比對 <b>body 的 {@code submittedBy}</b>（可省略），
     * 根本沒有看這筆資料實際的 {@code existing.getSubmittedBy()}：
     * 非送件人的參與者只要省略 body 的欄位就能通過。
     *
     * <h2>#58 的三道閘門（順序：授權 → 身分 → 狀態）</h2>
     *
     * <ol>
     *   <li><b>送件人（404）。</b>{@code existing.getSubmittedBy()} 必須等於
     *       caller。非送件人即使參與者也是 404，與「記錄不存在」不可分辨
     *       —— 呼叫端不能拿 PUT 當枚舉管道。被拒絕的嘗試留一筆 detached
     *       稽核（與 {@link ProcessAccessGuard#denyNonParticipant} 同一政策）。</li>
     *   <li><b>身分欄位（400）。</b>body 的 {@code submittedBy} 若帶了
     *       與自己不符的值，維持 #72 的明確 400（不靜默忽略）。</li>
     *   <li><b>退回狀態（409）。</b>現任 runtime 任務中必須存在一個指派給
     *       caller、且 {@link NotifyPublisher#isRevisionTask 判定為補件}的任務。
     *       補件任務的判定只有一份（與 {@code TaskController} 的
     *       {@code TASK_RESUBMIT}、通知端共用），這裡不重寫「name contains 補件」。</li>
     * </ol>
     *
     * <h2>⚠️ 為什麼非退回狀態是 409 而不是 403／404</h2>
     *
     * <p>呼叫者已經通過送件人檢查 ——「你是這筆資料的主人，但案件現在不是
     * 退回狀態」是<b>狀態衝突</b>，不是權限問題。回 404 會讓「案件已結束」
     * 與「不是你的」塌成同一個回應，而這兩種情況該做的事正好相反。
     *
     * <h2>⚠️ 為什麼狀態判定看 runtime 任務，而不是看變數或 BPMN 定義</h2>
     *
     * <p>「退回」在 BPMN 裡不是一個變數值，而是流程停在補件關卡這件事本身。
     * 讀 {@code approved=false} 會把「曾被退回、但已重送回主管」誤判成可改；
     * 掃 BPMN 定義則會把「流程定義裡有補件節點」誤判成「現在正在補件」。
     * 現任任務是唯一同時反映「當下」與「誰該動」的來源。
     *
     * <p>已知限制：{@code taskAssignee(caller)} 只看<b>直接指派</b>。
     * 若自訂流程把補件關卡設成候選群組（沒有 assignee），這裡會回 409；
     * 目前兩支內建 BPMN 的補件任務都是 {@code applicantResolver} 直接指派，
     * 不受影響。
     *
     * <h2>版本化：不覆寫原列，新增一列（見 {@code FormService.updateData}）</h2>
     *
     * <p>舊列保留、新列生效；可追溯性由 {@code FORM_UPDATE} 稽核的
     * {@code formDataId}（新列）與 {@code supersededFormDataId}（被取代的舊列）
     * 串起來。{@code submittedBy} 一律沿用舊列，body 只當拒絕閘門
     * —— 修改者由稽核的 operatorId 記錄，不會把「送件人」改寫成「最後修改的人」。
     */
    @PutMapping("/{id}")
    @Transactional("formTransactionManager")
    public FormData update(@PathVariable String id, @RequestBody FormData data,
                           @CallerId String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "無法確認修改表單的身分，請先登入");
        }

        // 先確認這筆資料存在。不存在 → 404（與「不屬於你」同一個狀態碼，
        // 不可分辨，理由見類別註解）。
        FormData existing = formService.getDataById(id);

        // #58 第一道：只有該列的送件人。非送件人（即使參與者）→ 404，
        // 與「記錄不存在」不可分辨。
        if (!callerId.equals(existing.getSubmittedBy())) {
            auditPublisher.publishDetached(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                    existing.getProcessInstanceId(), null,
                    Map.of("denied", true, "reason", "not the submitter",
                            "formDataId", id)));
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // 寫入端<b>沒有</b>稽核旁路：requireParticipant 沒有 audit:log:read 分支，
        // 持有稽核權限的人仍然改不了別人的表單（見 AttachmentAuthorizationTest
        // 的 auditorCannotUpload —— 旁路是唯讀的）。
        // 送件人本來就是參與者（POST 的規則）；這裡保留為第二層，
        // 讓歷史／人工寫入的資料列（送件人不是參與者）仍然 fail-closed。
        accessGuard.requireParticipant(existing.getProcessInstanceId(), callerId);

        // 冒用 → 400。放棄這裡的回傳值是刻意的：它只當拒絕閘門使用，
        // 真正的送件人欄位以 existing 為準（見下方版本化）。
        accessGuard.requireSelf(data.getSubmittedBy(), callerId, "submittedBy");

        // #58 第二道：案件必須停在指派給 caller 的補件關卡。
        requireRevisionTask(existing.getProcessInstanceId(), callerId);

        FormData saved = formService.updateData(id, data);
        // formDataId 是新列（實際生效的版本），supersededFormDataId 是被取代的
        // 舊列。只記其中一個會讓版本鏈斷掉 —— 事後調查就無法從稽核走到
        // 「改動前是什麼」。
        auditPublisher.publish(new AuditEvent(OperationType.FORM_UPDATE.name(), callerId,
                saved.getProcessInstanceId(), null,
                Map.of("formDataId", saved.getId(),
                        "supersededFormDataId", id)));
        return saved;
    }

    /**
     * 案件目前是否停在指派給 {@code callerId} 的補件關卡；不是 → 409。
     *
     * <p>「補件」的判定只有一份：{@link NotifyPublisher#isRevisionTask}。
     * 這裡查的是 runtime 的現任任務，不是歷史任務 —— 歷史任務代表
     * 「曾經退回過」，而重送之後它就不再是「現在可以改」的理由。
     */
    private void requireRevisionTask(String processInstanceId, String callerId) {
        boolean onRevisionTask = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskAssignee(callerId)
                .list().stream()
                .anyMatch(t -> NotifyPublisher.isRevisionTask(t.getName()));
        if (!onRevisionTask) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "案件目前不在退回補件狀態（或補件任務不在你手上），不可修改表單資料。");
        }
    }
}
