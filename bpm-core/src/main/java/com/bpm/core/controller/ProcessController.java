package com.bpm.core.controller;

import com.bpm.core.service.ApplicantIdentityLookup;
import com.bpm.core.service.InitialAssigneeResolver;
import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.dto.StartProcessRequest;
import com.bpm.core.notify.NotifyPublisher;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
import com.bpm.core.service.FormVersionLocker;
import com.bpm.core.service.ProcessInvolvementService;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/process-instances")
public class ProcessController {

    /**
     * 不可由呼叫端在<b>啟動流程</b>時指定的流程變數。
     *
     * <p>與 {@code TaskController.PROTECTED_VARIABLES} 同一份清單、同一個理由 ——
     * 見該處的註解。啟動路徑比完成路徑更危險，因為它一次決定了：
     *
     * <ul>
     *   <li><b>簽核人</b>：兩支已部署的 BPMN 都用
     *       {@code ${assigneeResolver.resolve(execution)}} 決定第一關受理人
     *       （該 resolver 內部讀 {@code initiator}），於是冒用 initiator
     *       等於把單子送到共犯的主管手上。</li>
     *   <li><b>補件任務的 assignee</b>：{@code applicantResolver}（#83 之前
     *       是直接 {@code ${initiator}}）。</li>
     *   <li><b>通知信的申請人</b>。</li>
     *   <li><b>稽核的 operatorId</b> —— 這一項最惡劣：它會被 hash chain
     *       永久固定成一筆「可信」的紀錄，之後再談竊取者都無法從紀錄裡分辨。</li>
     * </ul>
     *
     * <p>為什麼連 {@code _} 前綴的一律拒絕：那是伺服器的內部狀態
     * （{@code _formVersions}、{@code _externalSystemId}、{@code _callbackUrl}）。
     * 特別是 {@code _externalSystemId} —— 改動前呼叫端可以在 body 的
     * {@code variables} 裡夾帶它，把案件偽裝成某個外部系統擁有，
     * <b>繞過 R-09 的精確比對</b>（該比對本來是可信的，前提是這個值
     * 只由 {@code ExternalApiController} 寫入）。
     *
     * <p>{@code onBehalfOf} 同理：它是 R-20 為外部系統代發而設的
     * <b>系統授權</b>（{@code allowOnBehalfOf}）。允許一般登入者透過
     * {@code variables} 夾帶它，等於把一條需要管理員授權的權限
     * 變成任何登入者都能行使。
     *
     * <p>⚠️ 這仍然是<b>保護名單</b>（deny-list）而非完整白名單。真正的白名單
     * 應該來自 formKey 的 schema（spec §8.5：欄位 id == 變數名），
     * 但那需要在此查詢 form-service 並處理它不可用時的行為，範圍更大。
     * 這裡沿用與 {@code TaskController} 相同的取捨：擋住所有「改寫引擎
     * 與身分語意」的變數，一般業務欄位照常放行。兩處必須維持同一份清單 ——
     * 啟動路徑放行的、完成路徑擋掉的組合，會讓一份表單資料在流程的兩個
     * 時點有不同意義。
     */
    private static final java.util.Set<String> PROTECTED_VARIABLES =
            java.util.Set.of("initiator", "effectiveInitiator",
                    InitialAssigneeResolver.ON_BEHALF_OF_VAR);

    private static boolean isProtectedVariable(String name) {
        if (name == null || name.isBlank()) return true;
        return name.startsWith("_") || PROTECTED_VARIABLES.contains(name);
    }

    /**
     * 撤回案件時 body 沒帶 {@code reason} 的固定值（#7）。
     *
     * <p>它同時是 Flowable 的刪除原因（{@code ACT_HI_PROCINST.DELETE_REASON_}）
     * 與稽核 detail 的 {@code reason}。用固定字串而不是 null：規格 §9.4 的
     * {@code cancelled} 判定是「{@code endTime} 有值且 {@code deleteReason} 非 null」，
     * 空的刪除原因會讓已撤回的案件在外部 API 與歷史端點上看起來像
     * 「完成但沒有結果」。
     */
    private static final String DEFAULT_CANCEL_REASON = "applicant-cancel";

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final AuditEventPublisher auditPublisher;
    private final FormVersionLocker formVersionLocker;
    private final ProcessAccessGuard accessGuard;
    private final ProcessInvolvementService involvementService;
    private final ApplicantIdentityLookup applicantLookup;
    private final NotifyPublisher notifyPublisher;

    public ProcessController(RuntimeService runtimeService, RepositoryService repositoryService,
                             TaskService taskService, HistoryService historyService,
                             AuditEventPublisher auditPublisher,
                             FormVersionLocker formVersionLocker,
                             ProcessAccessGuard accessGuard,
                             ProcessInvolvementService involvementService,
                             ApplicantIdentityLookup applicantLookup,
                             NotifyPublisher notifyPublisher) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.auditPublisher = auditPublisher;
        this.formVersionLocker = formVersionLocker;
        this.accessGuard = accessGuard;
        this.involvementService = involvementService;
        this.applicantLookup = applicantLookup;
        this.notifyPublisher = notifyPublisher;
    }

    /**
     * 啟動流程（#66：initiator 一律由已認證的身分決定）。
     *
     * <h2>改動前</h2>
     *
     * <p>{@code req.initiator()} 直接被寫進流程變數，也直接作為稽核的
     * operatorId。也就是說<b>登入的使用者可以用任何人的名義送單</b>，
     * 而那張單會被派給那位「受害者」的主管、通知信上也會寫著他的名字、
     * 稽核裡的操作人也是他 —— 而且被 hash chain 固定成不可否認的紀錄。
     *
     * <h2>三條不可協商的規則</h2>
     *
     * <ol>
     *   <li><b>伺服器是 initiator 的唯一來源。</b>不接受呼叫端指定，也不
     *       「先接受再覆寫」—— 後者會讓呼叫端以為它指定的值生效了。</li>
     *   <li><b>明確 400，不靜默忽略。</b>靜默丟棄會讓呼叫端誤以為案件
     *       是以某人的名義發起，而實際不是。外部 API 那條路
     *       （{@code ExternalApiController}，R-20）用的是同一個語意。</li>
     *   <li><b>稽核的 operatorId 與流程變數的 initiator 必須同源。</b>
     *       兩者不一致時，稽核會記下真正做事的人，而流程路由記下別人 ——
     *       那正是最難偵測的組合。</li>
     * </ol>
     *
     * <h2>為什麼在交易內先檢查再啟動，而不是讓 Flowable 丟例外</h2>
     *
     * <p>{@code startProcessInstanceByKey} 對不存在的 key 會丟
     * {@code FlowableObjectNotFoundException} → 500。使用者看到 500 的
     * 直覺是「再按一次」，於是<b>重送造成重複案件</b>。這裡先查
     * {@code RepositoryService}（該服務本來就注入了，只是從未用於啟動路徑），
     * 缺 key 立刻 404。
     *
     * <p>仍然保留對 {@code FlowableObjectNotFoundException} 的轉譯：
     * 預先檢查與實際啟動之間定義<b>真的可能</b>被刪（管理員停用流程的
     * 動作與使用者的啟動請求同時發生）。那是預先檢查無法消除的
     * 競爭窗口，沒有這一層它就會變回 500。
     */
    @PostMapping
    @Transactional("primaryTransactionManager")
    public Map<String, Object> startProcess(@RequestBody StartProcessRequest req,
                                            @CallerId String callerId) {
        // ⚠️ 為什麼 callerId 為 null 要回 401，而不是 fallback 到任何標頭：
        // CallerIdArgumentResolver 的類別註解已說明 —— 退回讀標頭會讓整套
        // 認證變成裝飾：任何一條沒被授權規則涵蓋的路徑，身分就退回可偽造的
        // 標頭，而且完全沒有痕跡。而啟動流程是全 repo 唯一一個<b>產生</b>
        // 身分語意卻沒有用 @CallerId 的寫入路徑，所以這裡不能是例外。
        // 正常情況下 SecurityConfig 的 authenticated() 擋在前頭；
        // 這道檢查是為了「授權矩陣日後放寬」時不會靜默退化成可偽造的身分。
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "無法確認發起人身分，請先登入");
        }

        String key = req.processDefinitionKey();
        if (key == null || key.isBlank()) {
            // 缺 key 是請求本身不完整（400），不是「找不到東西」（404）。
            // Flowable 對 null key 丟的例外語意不明，必須自己擋。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 processDefinitionKey");
        }

        // body 帶了 initiator → 明確拒絕。訊息要說清楚它是由誰決定的，
        // 否則呼叫端只會看到「400」而不知道要改哪裡。
        if (req.initiator() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "initiator 由登入身分（" + callerId + "）決定，不可由呼叫端指定。"
                            + "請移除 body 的 initiator 欄位。");
        }

        Map<String, Object> vars = req.variables() != null ? new HashMap<>(req.variables()) : new HashMap<>();
        // variables 是自由 map —— 改動前呼叫端可以在這裡夾帶 onBehalfOf /
        // _externalSystemId / initiator，效果與直接指定完全相同。
        // 逐個 key 檢查，訊息指出是哪個變數名（呼叫端才知道要改什麼）。
        vars.keySet().stream()
                .filter(ProcessController::isProtectedVariable)
                .findFirst()
                .ifPresent(name -> {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "不允許以流程變數指定受保護的變數: " + name
                                    + "（initiator 由登入身分決定；_ 前綴為伺服器內部狀態）");
                });

        // 存在性檢查放在所有 400 之後：請求形狀不對與資源不存在是兩件事，
        // 先把形狀講清楚，呼叫端才知道要改 payload 還是改流程選擇。
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程定義不存在: " + key);
        }

        // ⚠️ 必須在 startProcessInstanceByKey 之前寫入：BPMN 在流程啟動的當下
        // 就求值 assignee 運算式（見 ExternalApiController 的同型註解）。
        // 由 server 寫在最後，body 無法蓋掉它。
        vars.put("initiator", callerId);

        ProcessInstance pi;
        try {
            pi = runtimeService.startProcessInstanceByKey(key, req.businessKey(), vars);
        } catch (FlowableObjectNotFoundException e) {
            // race condition 保險：預先檢查之後、真正啟動之前，定義被刪了。
            // 沒有這一層它會變成 500 → 使用者重送 → 重複案件。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程定義不存在: " + key, e);
        }

        formVersionLocker.lockVersions(pi.getProcessInstanceId(), pi.getProcessDefinitionId());

        // operatorId 用 callerId，不用 req.initiator() —— 稽核必須記「誰做的」，
        // 而不只是「這張單掛在誰名下」。改動前這裡記的是冒用者，
        // 會讓真正的發起人在稽核裡完全不存在。
        auditPublisher.publish(new AuditEvent("PROCESS_START", callerId,
                pi.getProcessInstanceId(), null,
                Map.of("processDefinitionKey", key,
                        "businessKey", req.businessKey() != null ? req.businessKey() : "")));

        Map<String, Object> result = new HashMap<>();
        result.put("processInstanceId", pi.getProcessInstanceId());
        result.put("businessKey", req.businessKey() != null ? req.businessKey() : "");
        result.put("status", "running");

        // Include currentTask info
        //
        // ⚠️ 不可用 singleResult()：它在結果超過一筆時拋 FlowableException。
        // 平行閘道或 multi-instance 會簽會產生併發任務 —— 而這一行在流程
        // 已經成功啟動「之後」才執行，因此例外會讓使用者收到 500 並重送，
        // 造成重複案件（security-audit P1-2）。
        List<Task> currentTasks = taskService.createTaskQuery()
                .processInstanceId(pi.getProcessInstanceId())
                .orderByTaskCreateTime().asc().list();
        if (!currentTasks.isEmpty()) {
            Task currentTask = currentTasks.get(0);
            result.put("currentTask", Map.of(
                    "taskId", currentTask.getId(),
                    "taskName", currentTask.getName() != null ? currentTask.getName() : "",
                    "assignee", currentTask.getAssignee() != null ? currentTask.getAssignee() : ""));
            result.put("currentTaskCount", currentTasks.size());
        }
        return result;
    }

    /**
     * 撤回（撤案）—— 申請人本人撤回「第一關尚未處理」的案件（#7）。
     *
     * <h2>這個端點接上的是既有的預留值</h2>
     *
     * <p>{@link OperationType#PROCESS_CANCEL} 一直存在（且列在
     * {@code NOT_YET_IMPLEMENTED}），但全 repo 沒有任何程式碼發出它 ——
     * 也就是「撤案」這個操作以前根本沒有入口。本端點是唯一入口，
     * 並在成功時寫出第一筆 {@code PROCESS_CANCEL} 稽核。
     *
     * <h2>誰可以撤回：申請人本人，判定只有一份</h2>
     *
     * <p>呼叫 {@link ApplicantIdentityLookup#applicantOf}（#96 抽出的唯一實作：
     * {@code onBehalfOf} 優先、其次自然人 {@code initiator}、系統身分回
     * {@code null}）。<b>不</b>用 {@code TaskController} 催辦那條的
     * {@code ApplicantResolver.resolveApplicant}：那條的第三段是「系統案件的
     * 受理人」（{@code bpm:external:revision} 持有人），是 #3 對催辦的裁決；
     * 撤回沒有同樣的裁決，所以系統案件（{@code applicantOf} 回 null）
     * <b>不開放</b> —— 沒有自然人申請人的案件，目前沒有任何身分可以撤回。
     *
     * <h2>拒絕的分流與 {@code TaskController.urgeTask} 一致</h2>
     *
     * <p>催辦是同一種形狀的授權問題（「只有申請人可以做這個動作」），
     * 因此沿用它的分流，而不是另發明一套：
     * <ol>
     *   <li><b>非參與者 → 404</b>（{@link ProcessAccessGuard#denyNonParticipant}，
     *       留一筆 {@code DATA_ACCESS {denied:true}}）。403 會確認案件存在，
     *       對可枚舉的 id 等於把枚舉管道留著。</li>
     *   <li><b>參與者但不是申請人 → 403</b>（他本來就看得到這張單，
     *       沒有必要對他說謊），並留一筆 {@code DATA_ACCESS {denied:true,
     *       reason:"not the applicant", action:"cancel"}}。</li>
     * </ol>
     * <p>系統案件（申請人判定回 null）的呼叫者走同一條分流：參與者 403、
     * 非參與者 404。<b>刻意不</b>把系統案件開放給受理人 —— 見上。
     *
     * <h2>可撤回條件：第一關尚未處理</h2>
     *
     * <p>以「沒有任何<b>已完成</b>的歷史任務」判定（{@code finished().count() == 0}）：
     * 只要有任何一個關卡被完成過，案件就已經進入處理，撤回會讓後續關卡與
     * 已完成的事實失去意義 → 409（狀態衝突，不是權限問題）。
     *
     * <p>⚠️ <b>已聲明（claimed）但未完成仍可撤回</b>：聲明只是「我來處理」，
     * 沒有任何關卡完成，所以仍在「第一關尚未處理」的範圍內。這是刻意的，
     * 不是漏洞 —— 申請人撤回時，受理人手上的任務會隨實例一起消失。
     *
     * <h2>狀態碼一覽（所有拒絕都在刪除之前，零副作用、零通知）</h2>
     *
     * <ul>
     *   <li>未認證 → 401。</li>
     *   <li>body 的 {@code reason} 不是字串 → 400（不靜默忽略）。</li>
     *   <li>案件不存在／已結束／重複撤回 → 404。重複撤回的第二次
     *       runtime 已查不到，落在同一個 404。</li>
     *   <li>參與者非申請人 → 403；非參與者 → 404（見上）。</li>
     *   <li>已有完成任務 → 409。</li>
     * </ul>
     *
     * <p>被拒的請求<b>不得</b>寫 {@code PROCESS_CANCEL}，也<b>不得</b>發
     * 撤回通知：403／404 只留 {@code DATA_ACCESS} 拒絕痕跡
     * （{@code publishDetached}，與催辦相同理由 —— 拒絕後緊接著拋例外，
     * 掛在交易上的稽核永遠不會 commit），400／409 不留任何稽核。
     * 通知的收集與發送都在這些檢查之後（見下），案件本身在 403／404／409
     * 之後都必須仍在 runtime（測試逐條驗證）。
     *
     * <h2>執行與稽核：同一個交易，fail-closed</h2>
     *
     * <p>{@code runtimeService.deleteProcessInstance(id, reason)} 刪除執行中的
     * 實例（歷史保留），reason 成為 {@code ACT_HI_PROCINST.DELETE_REASON_} ——
     * 規格 §9.4 的 {@code cancelled} 正是「已刪除實例且 result=null」，
     * 由既有的 {@code ExternalApiController.buildStatusFromHistory} 推導，
     * 本端點<b>不</b>改動那條路。
     *
     * <p>{@code PROCESS_CANCEL} 稽核（operator = 呼叫者）用 {@code publish}
     * 寫在刪除之後：本方法有 {@code @Transactional}，稽核掛在 beforeCommit，
     * 寫不進去就整個交易回滾（刪除也不成立）→ 503。這是 repo 對寫入端點的
     * 一致政策（P1-14 fail-closed），不是本端點自己的選擇。
     *
     * <p>detail 只放 {@code reason} 與 {@code cancelledAt}：
     * 撤回原因（呼叫端提供）與發生時間。不放表單值、簽核意見或收件人。
     *
     * <h2>撤回通知現任受理人（#7 殘餘收尾）</h2>
     *
     * <p>刪除成功後，對刪除前收集到的每個「有可送對象」的待處理任務發一則
     * {@code process_cancelled}（{@link NotifyPublisher#processCancelled}）。
     * 收件人是現任受理人：assignee 優先、候選任務送候選人
     * （規則只有一份，抽在 {@link NotifyPublisher#taskRecipients}）；
     * 候選群組沒有 email、無人任務沒有收件人，兩者都略過，不送空訊息。
     *
     * <p><b>為什麼每任務一則</b>：平行關卡時各任務的受理人不同，一則合併信
     * 無法回答每個人「我手上哪個任務消失了」；與催辦的每任務一則一致
     * （{@code TaskController.urgeTask}）。
     *
     * <p><b>為什麼收件人在刪除前收集、發送在刪除後</b>：任務隨
     * {@code deleteProcessInstance} 一起消失，刪除後已無從得知受理人是誰，
     * 所以名單必須先收；發送則必須在刪除成功之後，被拒路徑
     * （400／403／404／409）才保證零通知。
     *
     * <p><b>通知 fail-open、稽核 fail-closed 的已知窗口</b>：通知用
     * {@link NotifyPublisher#publish}（吞例外），RabbitMQ 不通不影響撤回，
     * 也不影響稽核。但稽核實際寫入在 commit（beforeCommit），若它失敗，
     * 交易回滾、案件其實未被撤回，而通知已經送出 —— 先寫稽核也無法消除
     * 這個窗口（稽核同樣在 commit 才落地）。接受的取捨：多一則可容忍的
     * 噪音，勝過撤回成功卻沒有稽核。
     *
     * <h2>⚠️ 競爭窗口（已知殘餘）</h2>
     *
     * <p>「先檢查 state／已完成任務，再刪除」與「有人同時完成任務或撤回」
     * 之間存在 TOCTOU 窗口：預先檢查無法消除它。刪除當下實例已不存在時，
     * Flowable 丟 {@code FlowableObjectNotFoundException}，這裡翻成 404
     * （與 {@code startProcess} 對同型例外的處理一致）。但「完成任務」的
     * 窗口沒有引擎層的鎖可以擋（Flowable 的樂觀鎖不管這個跨指令條件），
     * 極端情況下可能刪到一個剛被完成的案件 —— 這是接受的取捨，見報告。
     */
    @PostMapping("/{id}/cancel")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> cancelProcess(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             @CallerId String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "無法確認身分，請先登入");
        }

        // ① payload 形狀先講清楚：reason 非字串 → 400（不靜默忽略）。
        //    省略或空白視同沒帶，用固定值 —— 空白不可能表達撤回原因，
        //    與 ProcessAccessGuard.requireSelf 對空白的處理同一取向。
        String reason = cancelReasonOf(body);

        // ② 只有執行中的案件可撤回。已結束與不存在都回 404：對呼叫端而言
        //    兩者該做的事相同（不要重試），而重複撤回的第二次也落在這裡。
        if (accessGuard.stateOf(id) != ProcessAccessGuard.InstanceState.RUNNING) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "案件不存在或已結束: " + id);
        }

        // ③ 授權：只有申請人本人。分流與 TaskController.urgeTask 完全相同
        //    （參與者 403 + DATA_ACCESS、非參與者 404 + DATA_ACCESS），
        //    差別只在申請人的判定不含「系統案件的受理人」那一段（見 javadoc）。
        String applicant = applicantLookup.applicantOf(id);
        if (applicant == null || !applicant.equals(callerId)) {
            if (accessGuard.isParticipant(id, callerId)) {
                // 參與者但不是申請人：不是探測（他看得到這張單），
                // 但仍是一筆被拒的授權嘗試，留痕。detached 的理由見 javadoc。
                auditPublisher.publishDetached(new AuditEvent(OperationType.DATA_ACCESS.name(),
                        callerId, id, null,
                        Map.of("denied", true, "reason", "not the applicant", "action", "cancel")));
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有申請人可以撤回");
            }
            // 非參與者：404 + DATA_ACCESS {denied:true}（不會返回）。
            accessGuard.denyNonParticipant(id, callerId);
        }

        // ④ 可撤回條件：第一關尚未處理 = 沒有任何已完成的任務。
        //    claimed 但未完成不算「處理過」（見 javadoc）。
        if (historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(id).finished().count() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "案件已進入處理，無法撤回");
        }

        // ⑤ 通知收件人必須在刪除<b>之前</b>收集：任務隨實例一起消失，
        //    刪除後查不到任何人。只收「有可送對象」的任務 —— 候選群組
        //    沒有 email、無人任務沒有收件人，都不送空訊息。
        //    收件人規則用 NotifyPublisher.taskRecipients（唯一實作；
        //    TaskController 催辦走同一條，見該方法）。
        Map<Task, List<String>> deliverable = new LinkedHashMap<>();
        for (Task t : taskService.createTaskQuery().processInstanceId(id).list()) {
            List<String> to = NotifyPublisher.taskRecipients(taskService, t);
            if (!to.isEmpty()) deliverable.put(t, to);
        }

        // ⑥ 執行：刪除執行中的實例。歷史實例保留，reason 進 DELETE_REASON_，
        //    外部 API 的 /status 因此照現行規則回 cancelled（§9.4）。
        try {
            runtimeService.deleteProcessInstance(id, reason);
        } catch (FlowableObjectNotFoundException e) {
            // race 保險：預先檢查之後、刪除之前，實例被別人撤回或完成了。
            // 沒有這一層它會變成 500；這裡與 startProcess 的處理一致。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "案件不存在或已結束: " + id, e);
        }

        // ⑦ 通知：每個「有待處理任務且收得到信」的受理人一則 process_cancelled
        //    （平行關卡一案多則，理由見 javadoc）。assignee 有值時只放
        //    assignee；候選任務才放 candidateUsers —— 與 TimeoutNotifyDelegate
        //    的 payload 形狀一致，EmailConsumer 的解析規則只有一份。
        //    fail-open：publish 吞例外，RabbitMQ 不通不影響撤回。
        for (Map.Entry<Task, List<String>> entry : deliverable.entrySet()) {
            Task t = entry.getKey();
            String assignee = t.getAssignee();
            List<String> candidates = (assignee == null || assignee.isBlank())
                    ? entry.getValue() : List.of();
            notifyPublisher.processCancelled(t.getId(), t.getName(), id,
                    NotifyPublisher.extractProcessKey(t.getProcessDefinitionId()),
                    assignee, candidates, applicant);
        }

        // ⑧ 稽核：operator = 呼叫者（不是申請人判定值 —— 兩者在放行路徑上
        //    必然相同，但記「真正做事的人」才是稽核的用途）。
        //    與刪除同一個交易：publish 掛 beforeCommit，寫不進去就回滾
        //    （刪除也不成立）→ 503。被拒路徑走不到這一行。
        auditPublisher.publish(new AuditEvent(OperationType.PROCESS_CANCEL.name(), callerId,
                id, null,
                Map.of("reason", reason, "cancelledAt", Instant.now().toString())));

        Map<String, Object> result = new HashMap<>();
        result.put("processInstanceId", id);
        result.put("status", "cancelled");
        return result;
    }

    /**
     * 撤回請求的 {@code reason}：省略／空白 → {@link #DEFAULT_CANCEL_REASON}；
     * 非字串 → 400。
     *
     * <p>非字串明確拒絕而不是靜默丟棄：呼叫端送了 {@code {"reason":123}}
     * 卻拿到 200，會以為那個值被記進了稽核 —— 與 #66 對 {@code initiator}
     * 的立場相同（送了就必須處理，不能假裝收下）。
     */
    private static String cancelReasonOf(Map<String, Object> body) {
        if (body == null) return DEFAULT_CANCEL_REASON;
        Object raw = body.get("reason");
        if (raw == null) return DEFAULT_CANCEL_REASON;
        if (!(raw instanceof String s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reason 必須是字串（可省略；省略或空白時為 " + DEFAULT_CANCEL_REASON + "）");
        }
        return s.isBlank() ? DEFAULT_CANCEL_REASON : s.trim();
    }

    /**
     * 我的申請（執行中）（#71：讀端授權）。
     *
     * <h2>改動前是什麼</h2>
     *
     * <p>{@code ?initiator=} 是 {@code required=false} 且<b>完全不檢查</b>是否
     * 等於呼叫者 —— 不帶參數時 {@code query} 上沒有任何條件，於是回傳全公司
     * <b>執行中</b>的案件（實測 107 件），包含 businessKey 與當前審核人。
     * 那等於任何登入者都能列出全公司此刻在審什麼、卡在誰手上。
     *
     * <h2>三條規則（與 #66 同一組政策，不可改）</h2>
     *
     * <ol>
     *   <li><b>帶了與自己不符的身分 → 明確 400。</b>不靜默忽略：靜默丟棄會讓
     *       呼叫端以為它查得到對方的申請，而實際拿到的是自己的。</li>
     *   <li><b>省略參數 → 預設為呼叫者</b>（不是「全部」）。</li>
     *   <li><b>空白視同省略。</b>與 {@code DocumentController.createdBy} 的
     *       判斷一致（那是 JPA entity 欄位，同樣無法分辨「沒送」與送了 null）。
     *       空白不可能指向別人，因此不需要 400。</li>
     * </ol>
     *
     * <p>⚠️ 這裡<b>不</b>套用稽核旁路：本端點回傳的永遠是「呼叫者自己的申請」，
     * 稽核人員要查<b>別人</b>的案件請走 {@code /involved}（仍限於他自己參與的）
     * 或稽核 API。附件那條旁路是因為「調查者不會是該案的參與者」，
     * 這個端點沒有同樣的需求。
     */
    @GetMapping
    public List<Map<String, Object>> getProcessInstances(
            @RequestParam(required = false) String initiator,
            @CallerId String callerId) {
        String self = accessGuard.requireSelf(initiator, callerId, "initiator");
        var query = runtimeService.createProcessInstanceQuery();
        // 代員工發起的案件（R-20）：initiator 是 system:<id>，員工記在 onBehalfOf。
        // 兩者都要比對，否則代發的單不會出現在那位員工的「我的申請」。
        // 回應以 onBehalf=true 標示，讓前端能顯示「由外部系統代為提出」——
        // 使用者看到一張自己沒送過的單，必須知道它是怎麼來的。
        query.or().variableValueEquals("initiator", self)
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, self).endOr();
        java.util.Set<String> onBehalf = runtimeService.createProcessInstanceQuery()
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, self).list()
                .stream().map(ProcessInstance::getProcessInstanceId).collect(java.util.stream.Collectors.toSet());
        final java.util.Set<String> delegated = onBehalf;
        return query.orderByProcessInstanceId().desc().list().stream()
                .map(pi -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("onBehalf", delegated.contains(pi.getProcessInstanceId()));
                    m.put("processInstanceId", pi.getProcessInstanceId());
                    m.put("processDefinitionKey", pi.getProcessDefinitionKey());
                    m.put("businessKey", pi.getBusinessKey() != null ? pi.getBusinessKey() : "");
                    m.put("startTime", pi.getStartTime());
                    m.put("status", "running");
                    // currentTask
                    //
                    // ⚠️ 這一行在 GET /api/process-instances 的 stream 之中。
                    // 用 singleResult() 的話，只要系統中「任何一個」案件有
                    // 併發任務，這個端點就對「所有使用者」整體 500 ——
                    // 而業務人員在設計器畫一個平行閘道就能觸發。
                    List<Task> tasks = taskService.createTaskQuery()
                            .processInstanceId(pi.getProcessInstanceId())
                            .orderByTaskCreateTime().asc().list();
                    if (!tasks.isEmpty()) {
                        Task task = tasks.get(0);
                        m.put("currentTask", Map.of(
                                "taskId", task.getId(),
                                "taskName", task.getName() != null ? task.getName() : "",
                                "assignee", task.getAssignee() != null ? task.getAssignee() : ""));
                        // 併發時只顯示其中一個會讓使用者以為案件只等一個人；
                        // 把數量一併帶出來，讓前端至少有能力呈現「還有其他關卡」。
                        m.put("currentTaskCount", tasks.size());
                    }
                    return m;
                }).toList();
    }

    /**
     * 我參與的案件（執行中）—— 申請人的單不算，審過／正在審的才算。
     *
     * <h2>為什麼需要這個端點</h2>
     *
     * <p>{@code GET /api/process-instances} 只回「我送出的單」，但實務上最常見的
     * 情境是「我三個月前審過這張單，現在卡在別人的關卡」—— 那張單不是我的申請，
     * 卻明確是我參與的。要求查詢端點同時回兩種視角會讓「我的申請」這個頁面
     * 混入別人送來、也已經審完的案件，於是拆成不同 API。
     *
     * <h2>授權規則</h2>
     *
     * <p>不帶任何參數，範圍就是「{@code isParticipant} 為真的案件」——
     * 與 {@link ProcessAccessGuard#isParticipant} 的三個條件刻意一致
     * （見 {@link ProcessInvolvementService}），否則會出現
     * 「列表看得到、點進去 404」或反過來。
     *
     * <h2>⚠️ 效能</h2>
     *
     * <p>查詢次數固定為 4，與公司規模無關；<b>不可</b>改成
     * 「列出所有案件再逐案跑 isParticipant」（那是 N+1 × 4，見該類別）。
     * 其中 {@code taskInvolvedUser} 本身是全表掃描，那是這個端點的主要成本來源。
     *
     * <p>currentTask 一次撈回（而非逐案查詢）：我們已經知道所有 id 了，
     * 而 {@code getProcessInstances} 的逐案查詢是既有端點的 N+1，不在本次範圍。
     */
    @GetMapping("/involved")
    public List<Map<String, Object>> getInvolvedProcessInstances(@CallerId String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "無法確認身分，請先登入");
        }
        java.util.Set<String> ids = involvementService.involvedRunningInstanceIds(callerId);
        if (ids.isEmpty()) return List.of();

        // ⚠️ 絕對不可用 singleResult()：見上面 getProcessInstances 內的註解。
        // 平行閘道或 multi-instance 會簽會產生併發任務，那會讓整個端點 500。
        java.util.Map<String, List<Task>> tasksByInstance = new java.util.HashMap<>();
        for (Task t : involvementService.findOpenTasks(ids)) {
            tasksByInstance.computeIfAbsent(t.getProcessInstanceId(), k -> new java.util.ArrayList<>()).add(t);
        }
        return involvementService.findRunning(ids).stream()
                .map(pi -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("involved", true);
                    m.put("processInstanceId", pi.getProcessInstanceId());
                    m.put("processDefinitionKey", pi.getProcessDefinitionKey());
                    m.put("businessKey", pi.getBusinessKey() != null ? pi.getBusinessKey() : "");
                    m.put("startTime", pi.getStartTime());
                    m.put("status", "running");
                    List<Task> tasks = tasksByInstance.getOrDefault(pi.getProcessInstanceId(), List.of());
                    if (!tasks.isEmpty()) {
                        Task task = tasks.get(0);
                        m.put("currentTask", Map.of(
                                "taskId", task.getId(),
                                "taskName", task.getName() != null ? task.getName() : "",
                                "assignee", task.getAssignee() != null ? task.getAssignee() : ""));
                        // 併發時只顯示其中一個會讓使用者以為案件只等一個人，
                        // 把數量一併帶出來（與 GET /api/process-instances 同一理由）。
                        m.put("currentTaskCount", tasks.size());
                    }
                    return m;
                }).toList();
    }

    /**
     * 案件變數 —— 表單資料（欄位 id == 變數名，spec §8.5）。
     *
     * <h2>改動前是什麼</h2>
     *
     * <p>{@code try { return runtimeService.getVariables(id); }
     * catch (Exception e) { return Map.of(); }} ——
     * <b>零授權檢查</b>，而變數裡放的是薪資等敏感表單資料，任何登入者都能讀
     * 任何案件的 id。而且那個 catch 把「你沒權」「案件不存在」「引擎出錯」
     * 三種語意全部塌成 {@code 200 + {}}，呼叫端無從分辨該怎麼處理。
     *
     * <h2>三種結果</h2>
     *
     * <ol>
     *   <li><b>非參與者 → 404</b>（不是 403）。403 會確認「這個案件存在」，
     *       對可枚舉的 id 等於把枚舉管道留著。理由與
     *       {@link ProcessAccessGuard#requireParticipant} 相同。</li>
     *   <li><b>不存在的實例 → 404</b>。</li>
     *   <li><b>已結束、而呼叫者是參與者 → 200 + {}</b>。
     *       這是<b>刻意維持</b>的既有行為：Flowable 結束流程時會清除 runtime
     *       變數，而前端的 {@code DocumentDetail.vue} 依賴空物件
     *       （它 catch 空、variables 保持 {}、{@code DynamicForm} 仍用 schema 渲染）。
     *       改成 404 會讓「審結的單打不開」。</li>
     * </ol>
     *
     * <h2>稽核旁路（policy：只認 audit:log:read、唯讀、每次留痕）</h2>
     *
     * <p>與附件完全相同的政策與相同的實作方式（{@link ProcessAccessGuard#requireReadAccess}），
     * 因為這裡的資料與附件是同一批（一份單的薪資欄位既在表單資料也在附件裡）。
     * 經由旁路的讀取會寫一筆 {@code DATA_ACCESS {auditBypass:true}}。
     *
     * <p>⚠️ 與 {@code AttachmentController.download} 的一處刻意差異：
     * 那裡<b>每一次</b>下載（含參與者）都留痕，這裡只記旁路。
     * 因為 variables 在每次開啟單據時都會被讀一次（熱路徑），
     * 而參與者讀到的正是他自己填的表單值。把「稽核旁路」這件事本身
     * 完整記錄下來就足以回答「誰以稽核身分調閱了哪些案件」；
     * 若連參與者讀表單都要留痕，等於要為日常操作建立另一套行為稽核。
     * 這是政策選擇，若要與附件完全對齊，把 {@code publish} 移出 {@code if} 即可。
     *
     * <p>刻意<b>不</b>加 {@code @Transactional}：這是唯讀查詢 + 一次稽核寫入，
     * 與 {@code AttachmentController.list} 同一型（稽核失敗 → 503，
     * 見 {@code AuditFailClosedTest}）。加交易反而會讓稽核掛在 beforeCommit，
     * 而回應組裝階段的例外會讓它永遠寫不進去。
     */
    @GetMapping("/{id}/variables")
    public Map<String, Object> getVariables(@PathVariable String id, @CallerId String callerId) {
        boolean auditBypass = accessGuard.requireReadAccess(id, callerId);
        if (auditBypass) {
            // 參與者讀自己的表單值不留痕（那是日常操作）；稽核旁路必須留痕。
            auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                    id, null,
                    Map.of("action", "get_variables", "auditBypass", true)));
        }

        // 區分「已結束」與「不存在」：兩者 runtime 都拿不到變數，
        // 但前者是參與者有權看的合法狀態（回 {}），後者是 404。
        // 順序上授權先於存在性檢查：沒有權限的人不該靠狀態碼分辨
        // 「這個 id 不存在」與「我不該看這個 id」。
        if (accessGuard.stateOf(id) != ProcessAccessGuard.InstanceState.RUNNING) {
            return Map.of();
        }
        return runtimeService.getVariables(id);
    }

    /**
     * 案件流程圖：流程定義的 BPMN XML ＋ <b>目前點亮的活動 id</b>（#80：補上物件層授權）。
     *
     * <h2>改動前是什麼</h2>
     *
     * <p>整個方法<b>沒有 {@code @CallerId}、沒有任何檢查</b>：
     * <pre>
     *   public Map&lt;String, Object&gt; getBpmnXml(@PathVariable String id)
     * </pre>
     * 任何登入者拿任一 pid 就拿得到流程圖，而<b>回應裡的 {@code activeIds}
     * 洩漏的是「這張單現在卡在哪一關」</b>。
     *
     * <h2>為什麼這個情報比 id 枚舉更值錢</h2>
     *
     * <p>純粹的 id 枚舉只能證明「某張單存在」，拿到之後還得逐個端點試。
     * 而 {@code activeIds} 直接回答了攻擊者最需要的那一個問題：
     * <b>「現在輪到誰審」。</b> 拿到之後，後續動作就可以針對那個人
     * （社交工程、釣魚信、猜他的待辦網址），而不是對整間公司廣撒網。
     * 換句話說它把「我該攻擊誰」從搜尋問題變成查表問題。
     *
     * <p>而且這條路徑是<b>刻意留下來的</b>：#76 關掉了
     * {@code GET /api/history/tasks} 這個 taskId 發射台，#71 關掉了
     * {@code GET /api/process-instances}，但 pid 仍然可從
     * {@code GET /api/documents}（#80 一併修）與稽核紀錄取得 ——
     * 而本端點是 pid 進去、<b>情報出來</b>的轉換器。
     *
     * <h2>守衛：{@code requireReadAccess}，與 variables／附件／表單資料同一條</h2>
     *
     * <p>資料是「一張單的內容」（流程路徑與目前位置），
     * 因此走既定的讀端政策：<b>關係人，<b>或</b>持有 {@code audit:log:read}
     * 的稽核人員，且旁路每次留痕</b>，非關係人 404。
     *
     * <p>不新造一條規則的理由不是 DRY，而是<b>規則只能有一份</b>：
     * 這份資料與 {@code .../{id}/variables}（薪資欄位）、附件、
     * {@code /api/form-data/{pid}}、簽核軌跡是同一批的同一個層級。
     * 讓本端點自己寫一份 {@code isParticipant}，就會出現
     * 「variables 拒絕、bpmn-xml 放行」那種組合型式的差異 ——
     * 而那種差異比沒有檢查更難察覺，因為兩邊單獨看起來都合理。
     *
     * <h2>⚠️ 為什麼「實例不存在」仍然是 200 + 空圖（未改）</h2>
     *
     * <p>{@code pi == null} 有兩種成因，而<b>本方法刻意不分辨它們</b>：
     * <ul>
     *   <li><b>已結案</b>：runtime 查不到，但案件確實存在。關係人有權看，
     *       而 {@code ProcessDiagram.vue:23} 依賴 {@code 200 + xml:""}
     *       來顯示「無流程圖資料」。改成 404 會讓審結的單在畫面上變成
     *       {@code el-empty} 的錯誤訊息。</li>
     *   <li><b>從未存在</b>：此時呼叫者必然不是關係人
     *       （{@code isParticipant} 三個條件都查不到任何东西），
     *       <b>已經在守衛那裡被擋成 404</b>。</li>
     * </ul>
     *
     * <p>所以剩下的 200 + 空圖只有「稽核旁路」與「已結案的關係人」兩種，
     * 兩者都沒有再洩漏任何東西。用歷史查詢把這兩種情形分開需要改變
     * 回應契約（已結案的單突然有流程圖），那是產品決定，不屬於本工項 ——
     * 已回報 PM。
     *
     * <h2>稽核旁路留痕</h2>
     *
     * <p>與 {@link #getVariables} 同一政策：<b>只記旁路</b>，不記關係人讀取。
     * 因為開一張單就會讀一次流程圖，把日常操作全部留痕等於要為它建立
     * 另一套行為稽核；而「誰以稽核身分調閱了哪些案件的流程圖」這件事本身
     * 被記錄下來就足以追查。用 {@code publish}（fail-closed）而非
     * {@code publishDetached}：稽核寫不進去的話，這次調閱就不該成功。
     *
     * <h2>刻意不加 {@code @Transactional}</h2>
     *
     * <p>唯讀查詢 ＋ 一次稽核寫入，與 {@link #getVariables}、
     * {@code AttachmentController.list}、{@code HistoryController.getHistoricTasks}
     * 同一型（稽核失敗 → 503，見 {@code AuditFailClosedTest}）。
     * 加交易反而會讓稽核掛在 {@code beforeCommit}，
     * 而回應組裝階段的例外會讓它永遠寫不進去。
     */
    @GetMapping("/{id}/bpmn-xml")
    public Map<String, Object> getBpmnXml(@PathVariable String id,
                                          @CallerId String callerId) throws Exception {
        // 授權先於存在性檢查：沒有權限的人不該靠狀態碼分辨
        // 「這個 id 不存在」與「我不該看這個 id」（兩者都必須是 404）。
        if (accessGuard.requireReadAccess(id, callerId)) {
            // 關係人讀自己案件的流程圖不留痕（那是日常操作）；稽核旁路必須留痕。
            auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                    id, null,
                    Map.of("action", "get_bpmn_xml", "auditBypass", true)));
        }

        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(id).singleResult();
        // 見上方「為什麼實例不存在仍然是 200 + 空圖」：已結案的單前端要能開。
        if (pi == null) return Map.of("xml", "", "activeIds", List.of());

        var pd = repositoryService.getProcessDefinition(pi.getProcessDefinitionId());
        org.flowable.bpmn.model.BpmnModel model = repositoryService.getBpmnModel(pi.getProcessDefinitionId());

        // Auto-layout if no DI (hand-written BPMN without diagram section)
        if (model.getLocationMap().isEmpty()) {
            new org.flowable.bpmn.BpmnAutoLayout(model).execute();
        }

        byte[] xmlBytes = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(model);
        List<String> activeIds = runtimeService.getActiveActivityIds(id);
        return Map.of("xml", new String(xmlBytes), "activeIds", activeIds);
    }
}
