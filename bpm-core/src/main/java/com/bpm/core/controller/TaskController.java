package com.bpm.core.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.security.CallerId;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.CommentRequest;
import com.bpm.core.notify.NotifyPublisher;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.flowable.common.engine.impl.identity.Authentication;
import com.bpm.core.dto.TaskActionRequest;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    /**
     * 不可由呼叫端以任務變數改寫的變數名。
     *
     * <p>{@code initiator} 是關鍵：兩支已部署的 BPMN 解析第一關的主管時
     * 會讀它（{@code assigneeResolver} 內部），申請人在完成自己的補件任務時
     * 附帶一個偽造的 initiator，下一輪主管審核就會派給他指定的人的主管
     * —— 等於簽核人自選審核者（security-audit P0-5）。
     *
     * <p>{@code effectiveInitiator} 是伺服器由外部系統請求推導出來的身分，
     * 同理不可由呼叫端指定。
     *
     * <p>{@code returnTo}（#1）也是伺服器決定路由的變數：complete 分支由
     * body 的 {@code returnTo} 欄位推導並自行寫入，呼叫端不得用 variables
     * 改寫它。否則可以繞過 API 的語意檢查（approved=true／rejected=true
     * 與 returnTo 互斥）與稽核判定 —— 用
     * {@code variables:[{name:"returnTo",value:"initiator"}]} 讓 BPMN 走
     * 退回申請人分支，稽核卻只留下 TASK_RETURN。正式入口是 body 的
     * {@code returnTo} 欄位（見 complete 分支）。
     *
     * <p>{@code _} 前綴的一律拒絕（R-23）：那是伺服器的內部狀態，
     * 包含 {@code _formVersions}（表單版本鎖定）與
     * {@code _externalSystemId}（外部系統擁有權判定的依據）。
     *
     * <p>⚠️ 這是保護名單（deny-list）而非完整的白名單。真正的白名單應該來自
     * formKey 的 schema（spec §8.5：欄位 id == 變數名），但那需要在此查詢
     * form-service 並處理它不可用時的行為，範圍更大。目前的做法擋住了所有
     * 「改寫引擎與身分語意」的變數，而一般業務欄位照常放行。
     */
    private static final java.util.Set<String> PROTECTED_VARIABLES =
            java.util.Set.of("initiator", "effectiveInitiator", "onBehalfOf", "returnTo");

    private static boolean isProtectedVariable(String name) {
        if (name == null || name.isBlank()) return true;
        return name.startsWith("_") || PROTECTED_VARIABLES.contains(name);
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TaskController.class);

    /**
     * 催辦的冷卻時間（#6）。
     *
     * <p><b>這是產品參數，不是技術常數</b>：30 分鐘由 PM 指定為預設值，
     * 待使用者確認。改動它只需改這一行；{@code /urge} 的回應會把
     * 這個值帶給前端（{@code cooldownMinutes}），所以前端不會有第二份。
     */
    static final Duration URGE_COOLDOWN = Duration.ofMinutes(30);

    /** 催辦冷卻的 Redis key 前綴。key 是案件（processInstanceId），見 {@link #urgeTask}。 */
    static final String URGE_KEY_PREFIX = "bpm:urge:";

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final AuditEventPublisher auditPublisher;
    private final com.bpm.core.security.ProcessAccessGuard accessGuard;
    private final com.bpm.core.security.TaskHolderGuard holderGuard;
    // #92：改派對象必須是組織系統認識的人。與 #88 的 firstTaskAssignee /
    // onBehalfOf 共用同一份規則（ExternalActorGuard）—— 「規則只能有一份」
    // 是本專案的硬規則，同一條規則有兩套形狀正是 #84／#86 的成因。
    private final com.bpm.core.external.ExternalActorGuard actorGuard;
    // #3：催辦權與補件關卡（#83）共用同一條申請人規則。系統案件
    // （initiator=system:* 且無 onBehalfOf）的答案來自權限碼
    // bpm:external:revision 的持有人 —— 兩處各自實作就會出現
    // 「催得到的人簽不掉」這種組合型式的差異。
    private final com.bpm.core.service.ApplicantResolver applicantResolver;
    // #68b：待辦清單的「代某某發起」標示。
    private final com.bpm.core.service.OnBehalfOfLookup onBehalfOfLookup;
    // #33／#6：通知的唯一發送端（退回／拒絕／結案／認領／催辦）。
    private final NotifyPublisher notifyPublisher;
    // #6：催辦頻率限制。既有 Redis（OrgService／BpmPermissionService 已在使用），
    // 不新增基礎設施。
    private final StringRedisTemplate redis;

    public TaskController(TaskService taskService, RuntimeService runtimeService,
                          RepositoryService repositoryService,
                          AuditEventPublisher auditPublisher,
                          com.bpm.core.security.ProcessAccessGuard accessGuard,
                          // #77：讀寫兩端共用的持有者判斷。待辦清單與任務動作
                          // 必須是同一條規則 —— 兩處各自維護時，只要有人改了
                          // 其中一處，就會出現「看得到、點進去被拒」那種組合
                          // 型式的差異，而那種差異比沒有檢查更難察覺。
                          com.bpm.core.security.TaskHolderGuard holderGuard,
                          com.bpm.core.external.ExternalActorGuard actorGuard,
                          com.bpm.core.service.ApplicantResolver applicantResolver,
                          com.bpm.core.service.OnBehalfOfLookup onBehalfOfLookup,
                          NotifyPublisher notifyPublisher,
                          StringRedisTemplate redis) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.auditPublisher = auditPublisher;
        this.accessGuard = accessGuard;
        this.holderGuard = holderGuard;
        this.actorGuard = actorGuard;
        this.applicantResolver = applicantResolver;
        this.onBehalfOfLookup = onBehalfOfLookup;
        this.notifyPublisher = notifyPublisher;
        this.redis = redis;
    }

    /**
     * 我的待辦（指派給我 ＋ 我是候選人 ＋ 我所屬的候選群組），依 taskId 去重。
     *
     * <h2>改動前是什麼（#71）</h2>
     *
     * <p>{@code assignee}／{@code candidateUser}／{@code candidateGroups}
     * 三個參數全是 {@code required=false} 且<b>完全不檢查是否等於呼叫者</b>；
     * 三個都不帶時還有一條「return all」—— 也就是回傳全公司待辦，
     * 洩漏「誰在審什麼」。那是收件匣資訊，不是公開資訊。
     *
     * <h2>三個參數三種處理</h2>
     *
     * <ol>
     *   <li><b>{@code assignee}／{@code candidateUser}</b>：省略 → 用呼叫者；
     *       帶了別人的 id → 明確 400（與 #66 同一政策）。
     *       這兩個參數保留是為了讓呼叫端能明確表達意圖，不是為了授權 ——
     *       它們的結果永遠與「省略」相同。</li>
     *   <li><b>{@code candidateGroups}：帶了任何值 → 一律 400。</b>
     *       這是本方法最需要解釋的一條。assignee／candidateUser 至少還能
     *       解讀成「查自己」，而群組是一個<b>集合</b>的自稱 —— 等於要求
     *       伺服器相信「我屬於這個組織」。放行它的後果與 #66 的 initiator
     *       冒用完全同型，而且更隱蔽（拿到的結果看起來完全正常）。
     *       即使帶的值剛好等於呼叫端自己的群組也拒絕：呼叫端送出這個參數
     *       本身就表示它期待該值被採信。</li>
     * </ol>
     *
     * <h2>⚠️ 省略 candidateGroups 時<b>不能</b>完全不查群組</h2>
     *
     * <p>設計器的「發起人所屬單位」會產生
     * {@code flowable:candidateGroups="${orgService.getDeptId(initiator)}"}
     * —— 一個<b>沒有受理人</b>的任務（見 {@code InitialAssigneeResolver}
     * 對「只給候選群組」的說明）。若完全不查群組，這類任務會從<b>所有人</b>
     * 的收件匣消失，而且沒有任何錯誤訊息：案件就那樣卡住。
     * 「查不到就不查」正是 {@code DuplicateApprovalFilterTest} 記錄過的
     * 教訓（候選任務被靜默隱藏 → 案件卡死）。
     *
     * <p>所以省略時由 {@link com.bpm.core.service.CandidateGroupMembership}
     * 計算呼叫端實際所屬的群組（部門代碼 ∪ 權限碼 ∪ authorities）。
     *
     * <p>⚠️ 已部署的 {@code purchase-approval} 的 {@code financeReview} 用的是
     * {@code candidateUsers} 不是 candidateGroups，所以這段不影響它 ——
     * 由 {@code InvolvedInstancesTest.candidateUserFlowIsUnaffected} 證明。
     *
     * <h2>⚠️ 不加稽核</h2>
     *
     * <p>本端點只回傳呼叫者自己的待辦，沒有稽核旁路。被拒的參數是 400，
     * 且已由 {@link com.bpm.core.security.ProcessAccessGuard} 留下
     * {@code DATA_ACCESS {denied:true}}。
     */
    @GetMapping
    public List<Map<String, Object>> getTasks(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String candidateUser,
            @RequestParam(required = false) String candidateGroups,
            @CallerId String callerId) {

        String self = accessGuard.requireSelf(assignee, callerId, "assignee");
        accessGuard.requireSelf(candidateUser, callerId, "candidateUser");
        accessGuard.rejectCallerSuppliedGroups(candidateGroups, callerId);

        // ⚠️ 三個查詢條件由 TaskHolderGuard 產生，而不是在這裡各寫一份
        // （#77）。PUT /api/tasks/{id} 的授權檢查走的是同一組建構子，
        // 見 TaskHolderGuard.inboxQueries 為什麼讀端保留批次查詢的形狀。
        Map<String, Task> taskMap = new LinkedHashMap<>();
        for (TaskQuery q : holderGuard.inboxQueries(self)) {
            q.list().forEach(t -> taskMap.putIfAbsent(t.getId(), t));
        }

        // ── 「同一人不得重複簽核」的過濾已移除（2026-09-28 決策）───────
        //
        // 移除的原因（security-audit P1-5，該項是審查中唯一沒有給出修法的）：
        //
        //  1. **它從未真正強制任何規則**。過濾只發生在待辦查詢，
        //     PUT /api/tasks/{id} 沒有對應檢查 —— 知道 taskId 就能簽第二次。
        //     一個被當成業務規則展示、實際上只是隱藏的機制，
        //     比沒有這個機制更糟：它讓人以為規則已經生效。
        //
        //  2. **它會讓案件靜默卡死**。過濾範圍是整個流程實例而非節點：
        //     只要該使用者在此 instance 完成過任何任務，所有未指派的候選任務
        //     都被移除。實際情境 —— 財務退回 → 主管再審通過 → 案子回到
        //     financeReview（candidateUsers），但當初退件的財務人員已有
        //     finished 歷史 → 該任務對他永久隱藏。若該權限只有一人，
        //     案件就此卡住且沒有任何錯誤訊息。
        //     （改用 taskDefinitionKey 也解不掉：退回後回到的就是同一個節點。
        //       真正要區分的是「同一輪」，而系統目前沒有輪次的概念。）
        //
        // 若日後確實需要「不得重複簽核」，正確做法是：先定義「一輪」的界線，
        // 然後在 complete() 加上真正的檢查（拒絕而非隱藏），
        // 而不是在查詢端過濾。

        // ── #68b：代發標示 ─────────────────────────────────────────
        //
        // 主管在待辦清單看到的是「主管審核」，而這張單的 initiator 是
        // system:erp —— 畫面上沒有任何東西告訴他這是代誰發起的，
        // 也就無從判斷該問誰補件。
        //
        // ⚠️ 授權面：呼叫端此刻已經是這個任務的持有者／候選人
        // （上面那三個查詢），而同一個人讀這個案件的
        // GET /api/process-instances/{id}/variables 早已拿到整包流程變數
        // （含 onBehalfOf 與 initiator）。所以這不是新的揭露，
        // 是把已經在瀏覽器裡的值放到它該出現的位置。
        // 完整的政策說明見 OnBehalfOfLookup 的類別註解。
        //
        // ⚠️ 一次查詢：逐個任務查變數是 N+1（收件匣可有數十筆）。
        final Map<String, String> onBehalfOf = onBehalfOfLookup.byProcessInstances(
                taskMap.values().stream()
                        .map(Task::getProcessInstanceId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()));

        return taskMap.values().stream()
                .sorted(Comparator.comparing(Task::getCreateTime).reversed())
                .map(t -> toMap(t, onBehalfOf)).toList();
    }

    /**
     * 任務動作。
     *
     * <p>改動前這裡有三個問題（security-audit P1-1／P1-3／P1-4），
     * 三者互相牽動因此一併處理：
     *
     * <p><b>P1-1 稽核查不出是誰核准的。</b>operatorId 一律取
     * {@code req.assignee()}，但前端主要簽核入口的 payload 只有 action 與
     * variables → TASK_APPROVE／TASK_RETURN／TASK_REJECT 的 operatorId 全是
 * null。改為已認證的呼叫者（{@code @CallerId}）。R-01 完成後第一順位
 * 不再是可偽造的標頭；而「fallback 到任務的 assignee」那一層在 #77
 * 加上守衛之後已經是死碼，理由見下面 operatorId 那段。
     *
     * <p><b>P1-3 守門回 HTTP 200。</b>有未完成加簽時回
     * {@code {"status":"error"}} 卻是 200，前端只看 axios 是否 throw →
     * 顯示「操作成功」並導航離開，而 comment 在 complete 之前就已寫入 →
     * DB 留下一筆「核准意見」而核准從未發生。改為 409 CONFLICT，
     * 與本檔其他錯誤路徑一致。
     *
     * <p><b>P1-4 未知 action 靜默改派。</b>改為顯式 switch：未知或缺少
     * action 一律 400。改派必須明確指定 {@code action=reassign}，
     * 不再是「有 assignee 就改派」—— 後者讓 typo 把核准變成改派且回報成功。
     *
     * <h2>#77：這裡原本<b>沒有任何持有者檢查</b>（最嚴重的授權缺陷）</h2>
     *
     * <p>{@code @CallerId} 解析出來的 {@code callerId} 改動前<b>只用於稽核</b>，
     * 從未拿去與 {@code task.getAssignee()} 比對。取得任務之後就直接
     * {@code complete}／{@code delegateTask}／{@code setAssignee}／
     * {@code resolveTask}，於是任何登入者只要知道 taskId 就能動任何任務。
     *
     * <p>實測（真實 JWT，線上服務）：user001 送出 leave-approval，
     * 該案主管審核任務的 assignee 是 mgr001；讓與該案無關的 user002 去簽
     * {@code PUT /api/tasks/{id} {"action":"complete","variables":[approved=true]}}
     * 回 <b>200 {@code {"status":"ok"}}</b>，稽核留下
     * {@code TASK_APPROVE | operatorId = user002}，流程直接走完。
     * <b>任何登入者都能批准或拒絕系統裡的任意請假單、任意採購單。</b>
     *
     * <p>而且這條路徑正是前端實際在用的表單寫入路徑
     * （{@code DocumentDetail.vue} → {@code PUT /api/tasks/{taskId}} 帶 variables），
     * 也就是說 #72 剛修好的 {@code FormDataController} 保護力<b>低於</b>
     * 前端真正在走的那條路 —— 拿表單資料的權限比簽核的權限還大。
     *
     * <h2>⚠️ 為什麼守衛排在「action 形狀檢查」<b>之後</b></h2>
     *
     * <p>順序是「形狀 → 授權 → 身分欄位」，與 {@code FormDataController.submit}
     * 相同。請求本身不完整（沒有 action）與「你沒權」是兩件事：
     * 先把形狀講清楚，呼叫端才知道要改 payload 還是改流程選擇。
     *
     * <p>反過來說，404 與 400 在此處本來就無法完全不可區分 ——
     * 「任務不存在」是 404、「任務存在但 body 壞掉」是 400，
     * 這個落差在改動前就存在，守衛插在前面並不會新開一條枚舉管道
     * （見 {@code TaskActionHardeningTest.cannotHijackByBareAssignee}：
     * 攻擊者送出 {@code {"assignee":"自己"}} 時斷言的就是 400）。
     */
    @PutMapping("/{id}")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> updateTask(@PathVariable String id,
                                          @RequestBody TaskActionRequest req,
                                          @CallerId
                                          String callerId) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任務不存在: " + id);
        }
        String processInstanceId = task.getProcessInstanceId();
        String action = req.action();

        OperationType auditType;

        if (action == null || action.isBlank()) {
            // 改動前：沒有 action 但有 assignee 就直接改派 ——
            // 任何人都能用 {"assignee":"自己"} 無條件奪取他人任務。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺少 action（可用：claim, complete, delegate, resolve, reassign）");
        }

        // ── #77：持有者守衛 ───────────────────────────────────────────
        //
        // 非持有者（含完全無關的使用者）→ 404，且**在碰到任務之前**就擋下，
        // 因此 complete／reassign／delegate 都不會改動任何資料。
        // 未認證 → 401。判斷規則見 TaskHolderGuard（與待辦清單同一份）。
        holderGuard.requireHolder(task, callerId);

        // ── #1：returnTo 只對 complete 有意義 ─────────────────────────
        //
        // 其他 action 帶了它一律 400，不靜默忽略 —— 靜默忽略會讓呼叫端
        // 以為「退回申請人」生效了（與 complete 分支對 variables 的
        // 「拒絕而非靜默丟棄」同一政策）。排在持有者守衛<b>之後</b>：
        // 對非持有者仍回 404，不新增「任務存在」的枚舉管道。
        if (req.returnTo() != null && !"complete".equals(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "returnTo 僅適用於 action=complete（支援的值：initiator）");
        }

        // 操作者就是呼叫者，一層 fallback 都不留。
        //
        // 改動前是 firstNonBlank(callerId, firstNonBlank(task.getAssignee(), req.assignee()))。
        // 後面那兩層在守衛加入後<b>永遠不會生效</b>：requireHolder 保證 callerId
        // 非空白（未認證已先被 401 擋下），firstNonBlank 的第一個參數就不會是
        // null／空白。而它們描述的心智模型 —— 「操作者可能不是呼叫者」——
        // 正是這個缺陷的成因：operatorId 只拿去稽核，從不與 task.getAssignee()
        // 比對，於是稽核看起來健全，實際上任何人都能簽任何人的單。
        //
        // 稽核要記的是「誰按的按鈕」，不是「誰持有這個任務」——那是兩個不同
        // 的事實，混用只會在出事時指錯方向。
        String operatorId = callerId;

        switch (action) {
            case "claim" -> {
                // 認領者就是呼叫者，不接受 body 指定。
                //
                // 改動前是 firstNonBlank(callerId, req.assignee())，而
                // "claim 必須指定認領者" 的 400 與「空 body 可強制釋放他人任務」
                // 那段說明都建立在 assignee 可能是 null 之上。守衛加入後
                // callerId 必定非空白，那個 fallback 與那個 400 都是死碼。
                //
                // 順帶關掉一個洞：改動前 {"action":"claim","assignee":"其他人"}
                // 會<b>代別人認領</b>。那不是 claim 的語意（claim 是「這是我的」），
                // 而且等於讓持有者指定任意受理人 —— 那是 reassign 的工作，
                // 而 reassign 的新受理人自 #92 起已由 ExternalActorGuard
                // 驗過「是不是組織系統認識的人」。
                //
                // ⚠️ claim 自己的身分不需要外部查詢：認領者就是呼叫者
                // （callerId），而呼叫端能通過閘道就代表他是個真實登入者。
                // 這與 reassign 必須打網路是兩種不同的情形，不要合併。
                String claimant = callerId;
                // ── #33：認領通知的收件人 ──────────────────────────────
                //
                // spec §16.1：認領 → 候選人群組，「通知已被認領（可選）」。
                // 群組沒有 email，實際能收到信的只有候選「人」；認領者本人
                // 排除（信是給其他還在候選清單裡的人，告訴他們不用再處理）。
                //
                // ⚠️ 必須在 claim 之前收集：claim 之後 assignee 有值，
                // 候選人 identity link 是否保留取決於引擎版本與後續操作
                // （reassign 等會改寫），先收集才是穩定的。
                List<String> otherCandidates = taskService.getIdentityLinksForTask(id).stream()
                        .filter(l -> IdentityLinkType.CANDIDATE.equals(l.getType()))
                        .map(IdentityLink::getUserId)
                        .filter(u -> u != null && !u.isBlank() && !u.equals(claimant))
                        .distinct()
                        .toList();
                try {
                    taskService.claim(id, claimant);
                } catch (FlowableTaskAlreadyClaimedException e) {
                    // 改動前這個例外變成裸 500；語意上它是衝突。
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "任務已被他人認領", e);
                }
                auditType = OperationType.TASK_CLAIM;
                // 通知失敗不影響流程（NotifyPublisher.publish 吞例外）；
                // 沒有其他候選人時 taskClaimed 直接略過，不發空訊息。
                notifyPublisher.taskClaimed(id, task.getName(), processInstanceId,
                        task.getProcessDefinitionId(), claimant, otherCandidates);
            }
            case "complete" -> {
                // 語意檢查（#77）：完成是持有者的權力，與其他 action 同一條守衛。
                // leave-approval／purchase-approval 的補件關卡 assignee 由
                // ${applicantResolver.resolve(execution)} 決定（#83 之前是 ${initiator}），
                // 所以申請人能簽自己的補件任務；外部系統發起、沒有自然人申請人時
                // 會派給權限碼 bpm:external:revision 指定的受理人。
                //
                // ⚠️ 引擎層的既有行為（非本次引入）：被 delegate 出去、尚處於
                // PENDING 的任務不能被 complete —— TaskHelper.completeTask 會
                // 拋 FlowableException（"should be resolved instead"）→ 裸 500。
                // 守衛不得（也沒有）把它變成 404：那會對 delegatee 謊稱任務不存在，
                // 把一個可診斷的規則藏起來。委派要能走完，正確的動作是 resolve
                // （見下面的 resolve 分支與 TaskHolderGuard 的生命週期分析）。
                // 把這個 500 變成明確的 409 屬於另一件事，本次不做。
                if (!taskService.getSubTasks(id).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "有未完成的加簽子任務");
                }
                // ── #1 退回申請人（returnTo=initiator）──────────────────
                //
                // 語意檢查全部排在建立 vars 與 taskService.complete 之前：
                // 任何 400 都是零副作用（任務不完成、不寫變數、不發稽核／
                // 通知）。
                //
                // 只認小寫 "initiator"：它是 BPMN 條件（purchase-approval
                // 的 gw2）直接比對的字面值，也是伺服器寫入流程變數的值。
                // 寬容大小寫會多出一層「正規化」規則，而那個規則除了這裡
                // 之外沒有任何地方需要 —— 不做。其餘值一律 400 並指名
                // 支援的值，不靜默忽略。
                boolean returnToInitiator = req.returnTo() != null;
                if (returnToInitiator && !"initiator".equals(req.returnTo())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "returnTo 只支援 initiator（收到: " + req.returnTo() + "）");
                }

                Map<String, Object> vars = new HashMap<>();
                if (req.variables() != null) {
                    // 拒絕而非靜默丟棄：靜默丟棄會讓攻擊嘗試無跡可循，
                    // 也會讓正常使用者以為自己送出的值生效了。
                    req.variables().stream()
                            .filter(v -> isProtectedVariable(v.name()))
                            .findFirst()
                            .ifPresent(v -> {
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "不允許以任務變數改寫受保護的變數: " + v.name());
                            });
                    req.variables().forEach(v -> vars.put(v.name(), v.value()));
                }

                if (returnToInitiator) {
                    // 語意衝突：退回申請人與「拒絕」「核准」不可能同時成立。
                    // 兩個 400 都必須在 complete 之前，不得靠下面的覆寫把
                    // 衝突靜默吞掉 —— 呼叫端以為拒絕了，實際卻只是退回。
                    if (Boolean.TRUE.equals(vars.get("rejected"))) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "returnTo=initiator 與 rejected=true 語意衝突");
                    }
                    if (Boolean.TRUE.equals(vars.get("approved"))) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "returnTo=initiator 與 approved=true 語意衝突");
                    }
                    // 伺服器決定值：退回申請人＝不核准、不拒絕，並帶路由
                    // 變數。用 put（覆寫）而不是 putIfAbsent：這三個值是本次
                    // 操作的語意本身，不是「缺席時的預設值」；上面的衝突
                    // 檢查已擋掉所有相衝的 client 值，因此覆寫不可能蓋掉
                    // 呼叫端的真實意圖，只保證伺服器決定不受 variables
                    // 的內容影響。
                    vars.put("approved", false);
                    vars.put("rejected", false);
                    vars.put("returnTo", "initiator");
                } else {
                    // Ensure gateway variables are always set to avoid EL PropertyNotFoundException
                    vars.putIfAbsent("rejected", false);
                    vars.putIfAbsent("approved", false);
                    // ⚠️ returnTo 必須跟著<b>每一次</b> complete 重寫：BPMN
                    // 閘道條件讀的是流程變數，若只在「這次是退回申請人」時
                    // 寫入，它會一直留在流程上 —— 下一輪的一般退回
                    // （approved=false、不帶 returnTo）就會被 gw2 的條件
                    // 誤判成退回申請人，而稽核卻是 TASK_RETURN。
                    // 空字串＝這一輪不是退回申請人。
                    vars.put("returnTo", "");
                }
                taskService.complete(id, vars);

                // ── #33／#96：完成後通知申請人（退回／拒絕／結案）────────
                //
                // ⚠️ 通知已經不在這裡發。改動前只有這條 HTTP 路徑直接呼叫
                // notifyPublisher，而 ExternalApiController.completeTask 完成
                // 任務時完全不發 —— 同一個「任務完成」事實有兩條路徑、規則
                // 只有一套。現在兩條路徑都經過 FlowableConfig 註冊的全域
                // listener（com.bpm.core.notify.CompletionNotifyListener），
                // 事件判定仍只有 NotifyPublisher.applicantEventFor 一份。
                //
                // 這裡若保留直接呼叫，listener 會與它各發一則（雙發），
                // 而「恰好一則」正是 #96 的防線。稽核（下方 auditType）留在
                // 本地：它是「誰完成了哪個動作」的 HTTP 端事實，不是通知。
                auditType = NotifyPublisher.isRevisionTask(task.getName())
                        ? OperationType.TASK_RESUBMIT : resolveCompleteAuditType(vars);
            }
            case "delegate" -> {
                // 語意檢查（#77）：被 delegate 出去之後 delegatee 必須能簽。
                // 這是本次修改最大的迴歸風險 —— Flowable 的 delegateTask 會把
                // assignee 換成 delegatee、把原本的 assignee 寫進 owner，
                // 守衛若不放行 delegatee 就等於把委派功能打死。
                // 由 TaskHolderGuard 的條件 1（assignee）涵蓋，
                // 見 TaskHolderGuard 類別註解的 delegate／resolve 生命週期分析。
                //
                // 形狀檢查（必須指定 delegateUser）：刻意保留在本地，
                // 與上面 reassign 分支的理由相同 —— actorGuard 對空白的訊息
                // 會叫人「改用 firstTaskCandidateGroups」，而 delegate 的 body
                // 裡沒有那個欄位。「沒給 delegateUser」在這裡只有一種意義。
                if (req.delegateUser() == null || req.delegateUser().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "delegate 必須指定 delegateUser");
                }

                // ── #93a：委派對象必須是組織系統認識的人 ────────────────
                //
                // 這是 #92 已在 reassign 分支修掉的<b>同一個形狀的缺陷</b>，
                // 而且在 PM 裁決之前就以註解形式回報過（「已知缺口，刻意不在
                // 本工項修」）。#93a 就是那個裁決的執行：併入同一條規則。
                //
                // 缺陷（#93a 之前這裡只有上面的空白檢查）：
                //   holder = mgr001 送出 {"action":"delegate","delegateUser":"nobody-xyz"}
                //   → delegateTask(id, "nobody-xyz") 成功 → 200 {"status":"ok"}
                //   → assignee = nobody-xyz，非 null → 候選人查詢帶著
                //     ASSIGNEE_ IS NULL，候選人救不了它（互斥）
                //   → UnreachableTaskListener 的判準是「有沒有人能動它」，
                //     nobody-xyz 非空白、非 system: 前綴 → **不告警**
                //
                // 與 reassign 的差別只有一個，所以不要誤判成兩種問題：
                // delegate 之後 owner 仍是原指派人（TaskHolderGuard 條件 2），
                // owner 還能 resolve 收回來 —— 後果較輕、可恢復，
                // 但<b>缺陷本質完全相同</b>，而且「可以 resolve 收回來」不是
                // 緩解理由：owner 必須自己察覺到不對，而沒有任何人收到告警。
                // #92 的註解裡「沒有順手修的理由」是當時的範圍界定，
                // 不是對這個缺陷的技術判斷。
                //
                // 為什麼重用 actorGuard 而不寫一份：見上面 reassign 分支的
                // 「為什麼重用 ExternalActorGuard.requireKnownPerson」。
                // 「指派給誰」只有一份實作 —— 這正是 #92 那段註解主張的。
                //
                // 驗的是「是不是人」而不是「是不是候選人」：委派給一位
                // 不在候選清單裡、但確實該處理的人（出差、代班、跨部門支援）
                // 是正常業務行為。若拿候選清單當白名單，唯一合法結果是把
                // 「委派」功能整個打死。
                //
                // 排序：requireHolder（本方法的第 1 段）之後、形狀檢查之後、
                // delegateTask 之前。
                //   * 必須在 requireHolder 之後：委派是把工作轉給別人的權力，
                //     屬於持有者。對非持有者應該是 404（授權）而不是 400
                //     （payload 形狀）—— 狀態碼的差異就是枚舉管道。
                //     這一條由 DelegateKnownPersonTest 的 GuardsNotWeakened 釘住。
                //   * 必須在形狀檢查之後：與 reassign 一致，且空白訊息才指得準。
                //   * 必須在 delegateTask 之前：擋在寫入之後就留下一個已經
                //     委派給沒有人能認得的人、而沒有任何人收到告警的任務。
                //
                // 狀態碼沿用 actorGuard 既有行為（不自創第四組政策）：
                // 組織系統「查無此人」→ 400；「故障」→ 503（此時尚未寫入任何東西，
                // 重試安全 —— 委派不會產生重複的子流程之類的東西）。
                // action 傳「委派任務」：這個端點不發起流程（見
                // ExternalActorGuard 對 action 參數的說明）。
                actorGuard.requireKnownPerson("delegateUser", req.delegateUser(), "委派任務");
                taskService.delegateTask(id, req.delegateUser());
                auditType = OperationType.TASK_DELEGATE;
            }
            case "resolve" -> {
                // 語意檢查（#77）：resolve 是「原指派人把被委派的任務收回來」，
                // Flowable 的 resolveTask 把 assignee 還給 owner ——
                // 呼叫者只可能是 owner（delegate 之前 owner 為 null 的話，
                // 引擎會在 delegateTask 時把它設成原本的 assignee）。
                // 由 TaskHolderGuard 的條件 2（owner）涵蓋。
                //
                // ⚠️ 未被 delegate 的任務上呼叫 resolve，引擎會拋例外 →
                // 裸 500。這是既有行為（改動前任何人都能觸發），本次不處理；
                // 真正該做的是「不是委派中的任務就回 409」，留待後續。
                taskService.resolveTask(id);
                auditType = OperationType.TASK_RESOLVE;
            }
            case "reassign" -> {
                // 改派現在必須明確指定 action。
                //
                // 語意檢查（#77）：改派是持有者的權力，所以與其他 action
                // 套用同一條守衛 —— 非持有者不得改派任何人的任務。
                //
                // ── #92：形狀檢查（必須指定 assignee）────────────────
                //
                // ⚠️ 這一格是**形狀**檢查，與下面的**身分**檢查是兩件事：
                // 「有沒有給一個值」與「給的那個值是不是人」不可互相取代。
                // 刻意保留這個明確的 400（而不是直接交給 actorGuard）：
                // actorGuard 對空白的訊息是為 firstTaskAssignee 寫的
                // （會提到改用候選群組），對 reassign 而言是誤導 ——
                // 「沒給 assignee」在這裡只有一種意義，就是 payload 少了一欄。
                if (req.assignee() == null || req.assignee().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "reassign 必須指定 assignee");
                }

                // ── #92：改派對象必須是組織系統認識的人 ────────────────
                //
                // 缺陷（改動前這裡只有上面的空白檢查）：
                //   holder = mgr001 送出 {"action":"reassign","assignee":"nobody-xyz"}
                //   → setAssignee(taskId, "nobody-xyz") 成功 → 200 {"status":"ok"}
                //   → assignee 非 null，Flowable 的候選人查詢帶著 ASSIGNEE_ IS NULL，
                //     所以**候選人救不了它**（兩個條件互斥）
                //   → UnreachableTaskListener 的判準是「有沒有人能動它」，
                //     而 nobody-xyz 非空白、非 system: 前綴 → **不告警**
                //   → 結果：一個沒有人看得到、沒有人能簽、也沒有任何告警的任務。
                //
                // 為什麼重用 ExternalActorGuard.requireKnownPerson 而不在此寫一份：
                // 「指派給誰」這條規則在 #88 已經有了唯一一份實作
                // （firstTaskAssignee 與 onBehalfOf 共用），本專案反覆記載的
                // 缺陷成因就是同一條規則有兩套形狀（#84／#86／#87）——
                // 兩處各自維護時，只要有人改了其中一處，就會出現
                // 「外部入口擋掉、加派擋掉，改派卻放行」那種組合型式的差異，
                // 而那種差異比沒有檢查更難察覺。
                //
                // 為什麼驗的是「**是不是人**」而不是「**是不是候選人**」：
                // 後者比本規則嚴格得多，而且會打斷合法功能。主管把任務改派給
                // 一位不在啟動時產生的候選清單裡、但確實該處理的人，是正常業務
                // 行為（出差、代班、跨部門支援）；候選清單是流程啟動時的**建議**，
                // 不是改派的白名單。若拿它當白名單，唯一合法結果是把
                // 「改派」這個功能整個打死。使用者要擋的是「指給一個沒有人
                // 認得、也沒有人能登入的字串」，那正是「是不是人」，
                // 與 ExternalActorGuard 回答的問題完全相同。
                //
                // ⚠️ 改派給「候選清單以外的人」是**刻意允許**的，
                // 由 ReassignKnownPersonTest.reassignToKnownPersonOutsideCandidateListIsAllowed
                // 釘住 —— 那條測試是本決定的唯一防護門。
                // 「只能指派給候選人」若日後被視為需求，那是**另一個工項**
                // （需要裁決，且必須連同例外路徑一起設計），不該順手加在這裡。
                //
                // 排序：排在 requireHolder 之後、排在空白檢查之後、排在寫入之前。
                //   * 必須在 requireHolder 之後：持有者是唯一有權改派的人，
                //     對非持有者應該是 404（授權），而不是 400（payload 形狀）。
                //     這也守住 #83 agent 拒絕過的誘惑 —— 新檢查不得放寬持有者守衛。
                //   * 必須在形狀檢查之後：與本方法既有的「形狀 → 授權 → 身分欄位」
                //     一致（見本方法 javadoc 末段：MockMvc 不做 error dispatch，
                //     狀態碼差異會被既有測試綁死）。
                //   * 必須在 setAssignee 之前：擋在寫入之後就留下一個已經
                //     指派、卻沒有人能簽的任務 —— 那正是要修的缺陷本身。
                //
                // 狀態碼沿用 ExternalActorGuard 既有行為（不自創第四組政策）：
                // 組織系統「查無此人」→ 400（呼叫端該改 payload）；
                // 組織系統「故障」→ 503（可安全重試，此時尚未寫入任何東西）。
                // action 傳「改派任務」：這個端點不發起流程（見
                // ExternalActorGuard 對 action 參數的說明）。
                actorGuard.requireKnownPerson("assignee", req.assignee(), "改派任務");
                taskService.setAssignee(id, req.assignee());
                auditType = OperationType.TASK_REASSIGN;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知的 action: " + action
                            + "（可用：claim, complete, delegate, resolve, reassign）");
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("action", action);
        if (req.variables() != null) {
            req.variables().forEach(v -> detail.put(v.name(), v.value()));
        }

        // operationType 改傳 enum 的 name()：編譯期就綁定，
        // 不會再出現「字串不在 enum 裡 → valueOf 拋例外 → 稽核靜默遺失」。
        auditPublisher.publish(new AuditEvent(auditType.name(), operatorId,
                processInstanceId, id, detail));
        return Map.of("taskId", id, "status", "ok");
    }

    /**
     * 催辦（#6 催辦功能／#33 通知觸發事件完整化）。
     *
     * <h2>介面為什麼是 {@code ?processInstanceId=} 而不是 PM 建議的
     * {@code /api/tasks/{taskId}/urge}</h2>
     *
     * <p>「以能接上為準」的實查結果：前端「我的申請」的 row 只有
     * {@code currentTask.taskName}／{@code assignee}，
     * <b>沒有 taskId</b>（{@code ProcessController.getProcessInstances} 的
     * currentTask map 沒放它，而 ProcessController 不在本工項的檔案邊界內）。
     * 申請人的收件匣也查不到審核人的任務（{@code TaskHolderGuard} 正確地
     * 不讓非持有者列出）。因此以 taskId 為路徑參數的端點<b>現在接不上</b>；
     * 案件 id 才是前端手上有的東西。
     *
     * <p>語意上也更貼近使用者：申請人催的是「這張單」，不是某個 taskId
     * ——平行關卡時一張單可能同時有多個待處理任務，全部一起催才對。
     * 若產品偏好 taskId 端點，前置工作已完成（2026-10-03：
     * {@code GET /api/process-instances}／{@code /involved}／歷史 involved 的
     * currentTask 已補上 taskId）；把這裡換成路徑參數時，本方法的授權與
     * 頻率邏輯可原樣沿用。目前維持案件 id 端點。
     *
     * <h2>授權：申請人本人，系統案件則是受理人（最小授權）</h2>
     *
     * <p>候選方案有兩個：
     * <ol>
     *   <li><b>案件參與者</b>（{@code requireParticipant}）—— 包含歷任審核人。
     *       這會出現「審核人催辦自己」與「已退場的審核人催辦現任審核人」
     *       兩種沒有業務意義、又會消耗收件人注意力的情境。</li>
     *   <li><b>申請人本人</b>（{@code initiator} 或 R-20 的
     *       {@code onBehalfOf}）← <b>採用</b>。催辦的業務意義是「申請人
     *       請承辦人加快」，而 {@code MyApplications.vue} 的催辦按鈕也只
     *       出現在申請人自己的清單上。</li>
     * </ol>
     *
     * <h2>#3（2026-10-02 裁決）：系統案件開放給受理人</h2>
     *
     * <p>外部系統發起且沒有 {@code onBehalfOf} 的案件（{@code initiator}
     * 是 {@code system:<id>}）沒有自然人申請人，改動前<b>沒有任何人</b>
     * 能催辦。裁決是這一類案件的催辦權開放給補件關卡的同一批受理人
     * （{@code bpm:external:revision} 的持有人）。
     *
     * <p>判定<b>不是</b>在這裡再寫一次：呼叫
     * {@link com.bpm.core.service.ApplicantResolver#resolveApplicant}
     * —— 三段順序（{@code onBehalfOf} → {@code initiator} → 系統受理人）
     * 只有那一份實作。催辦與補件如果分岔，會出現「催得到的人簽不掉」
     * 這種組合型式的差異。
     *
     * <p>⚠️ <b>自然人案件不受影響</b>：{@code initiator} 是人時答案就是
     * 那個人，即使身兼受理人的 {@code dir001} 也不會因此取得催辦權。
     * 這是負向對照，不是遺漏。
     *
     * <h2>狀態碼與邊界情形</h2>
     *
     * <p>非參與者 → 404（沿用 {@code denyNonParticipant} 的
     * 不留枚舉管道政策，並留 DATA_ACCESS 稽核）；參與者但不是申請人／
     * 受理人 → 403（他本來就看得到這張單，沒有必要對他說謊，而且 403
     * 讓前端能給出「只有申請人可以催辦」而不是「找不到資料」）。
     * 系統案件呼叫者不是受理人時沿用同一分流（參與者 403、否則 404）。
     *
     * <p>權限中心邊界（見 {@code ApplicantResolver} —— 它不對外回 null）：
     * <ul>
     *   <li><b>查無受理人</b>（持有人清單空／回系統身分 →
     *       {@link IllegalStateException}）：等於沒有任何人能催辦，
     *       沿用上面的拒絕分流，<b>不放行</b>。</li>
     *   <li><b>權限中心呼叫失敗</b>（{@link RestClientException}）：
     *       無法判定 → 503。fail-closed 是重點：不確定時不得變成 200。</li>
     * </ul>
     * 兩者都發生在取得頻率許可與發送通知<b>之前</b>，因此被拒零副作用
     * —— 沒有通知、沒有 TASK_URGE、不消耗 30 分鐘冷卻。
     *
     * <h2>收件人：目前所有待處理任務的受理人</h2>
     *
     * <p>assignee 優先；候選任務（沒有 assignee）送候選「人」。
     * 候選<b>群組</b>沒有 email，本系統的寄信端無法把信寄給一個群組
     * —— 因此群組任務在「沒有候選人」時會被視為無法催辦（409），
     * 這是既有寄信能力的天花板，不是額外遺漏。
     *
     * <h2>頻率限制</h2>
     *
     * <p>同一個<b>案件</b> 30 分鐘一次（{@link #URGE_COOLDOWN}，產品參數，
     * 待使用者確認）。key 用 processInstanceId 而不是 taskId：
     * 使用者感知的是「我催了這張單」，而且平行關卡時一鍵會催多個任務，
     * 用 taskId 會變成可以對同一張單連續觸發多次。
     *
     * <p>Redis 故障時 fail-open（視為取得許可）：催辦只是提醒，
     * 不該因為快取層故障而不能用 —— 與 {@code OrgService}／
     * {@code BpmPermissionService} 既有的 Redis 容錯取向一致。
     *
     * <h2>被拒絕時零副作用</h2>
     *
     * <p>授權失敗（403／404）與頻率限制（429）都在<b>取得許可、發送通知
     * 之前</b>就返回；沒有通知、沒有 Redis 以外的任何寫入。
     * 成功催辦則在發送之後寫一筆 {@code TASK_URGE} 稽核
     * （operator = 呼叫者、案件 id、冷卻分鐘數與任務／收件人數；
     * 不含簽核意見與表單值）—— 見方法尾端的 publish。
     */
    @PostMapping("/urge")
    public Map<String, Object> urgeTask(@RequestParam String processInstanceId,
                                        @CallerId String callerId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 processInstanceId");
        }
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "無法確認身分，請先登入");
        }
        if (accessGuard.stateOf(processInstanceId)
                == com.bpm.core.security.ProcessAccessGuard.InstanceState.ABSENT) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "案件不存在: " + processInstanceId);
        }

        // #3：申請人／受理人的判定與補件關卡共用同一條規則（見 urgeApplicantOf）。
        String applicant = urgeApplicantOf(processInstanceId);

        if (applicant == null || !applicant.equals(callerId)) {
            if (accessGuard.isParticipant(processInstanceId, callerId)) {
                // 參與者但不是申請人：不是探測（他看得到這張單），
                // 但仍是一筆被拒的授權嘗試，留痕。
                auditPublisher.publishDetached(new AuditEvent(OperationType.DATA_ACCESS.name(),
                        callerId, processInstanceId, null,
                        Map.of("denied", true, "reason", "not the applicant", "action", "urge")));
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有申請人可以催辦");
            }
            // 非參與者：404 + DATA_ACCESS {denied:true}（不會返回）。
            accessGuard.denyNonParticipant(processInstanceId, callerId);
        }

        // 收件人先解析：沒有可催辦的對象時回 409，且不消耗頻率限制。
        List<Task> currentTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).list();
        Map<Task, List<String>> deliverable = new LinkedHashMap<>();
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        for (Task t : currentTasks) {
            // 收件人規則只有一份：NotifyPublisher.taskRecipients（#7n 收斂）。
            List<String> to = NotifyPublisher.taskRecipients(taskService, t);
            if (!to.isEmpty()) {
                deliverable.put(t, to);
                recipients.addAll(to);
            }
        }
        if (deliverable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "案件目前沒有可催辦的受理人");
        }

        if (!acquireUrgePermit(processInstanceId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "已於 " + URGE_COOLDOWN.toMinutes() + " 分鐘內催辦過，請稍後再試");
        }

        deliverable.forEach((t, to) ->
                notifyPublisher.taskUrged(t.getId(), t.getName(), processInstanceId,
                        t.getProcessDefinitionId(), t.getAssignee(), to, applicant));

        // ── #6：成功催辦的稽核（operator = 呼叫者）────────────────────
        //
        // 位置在發送之後。本端點沒有 @Transactional（與 getComments 同型，
        // 刻意不為了稽核改變既有交易結構）：publish 會直接同步寫入，
        // 失敗拋 AuditWriteException（fail-closed）→ 503，
        // 不會出現「通知已送出、卻沒有軌跡」。
        //
        // ⚠️ 授權失敗（403／404）與頻率限制（429）都在上面就拋出，
        // 走不到這一行 —— 被拒的請求不得寫出 TASK_URGE。
        //
        // detail 只放非敏感資訊：冷卻分鐘數、實際送出的任務數與收件人數。
        // 刻意不放 recipients 名單（誰被催辦屬於個資，且稽核的用途是回答
        // 「誰在何時催了哪張單、催了幾個對象」，不是複製案件內容），
        // 也不放任務名稱、表單值或簽核意見。
        auditPublisher.publish(new AuditEvent(OperationType.TASK_URGE.name(), callerId,
                processInstanceId, null,
                Map.of("cooldownMinutes", URGE_COOLDOWN.toMinutes(),
                        "taskCount", deliverable.size(),
                        "recipientCount", recipients.size())));

        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("recipients", List.copyOf(recipients));
        result.put("cooldownMinutes", URGE_COOLDOWN.toMinutes());
        return result;
    }

    /**
     * 催辦的申請人／受理人（#3）：三段規則的唯一呼叫點。
     *
     * <p>{@code onBehalfOf} 與 {@code initiator} 由這裡自己取，再交給
     * {@link com.bpm.core.service.ApplicantResolver#resolveApplicant} ——
     * 三段順序只有那一份實作；第三段（系統受理人）的答案
     * （{@code bpm:external:revision} 的持有人）只有權限中心知道，
     * 因此這一段可能打一次 self HTTP（有 Redis 快取兜住頻率，
     * 見 {@code BpmPermissionService}）。
     *
     * <p>⚠️ 兩種失敗都不得 fail-open，而且都在取得頻率許可與發送通知
     * <b>之前</b>返回，因此被拒零副作用：
     * <ul>
     *   <li>{@link IllegalStateException}（查無受理人／回傳系統身分）＝
     *       沒有任何人能催辦 → 回 {@code null}，呼叫端走既有的
     *       參與者 403／非參與者 404 分流。</li>
     *   <li>{@link RestClientException}（權限中心故障）＝無法判定 → 503。
     *       不確定時不得變成 200。</li>
     * </ul>
     *
     * @return 保證不是 {@code system:*} 的 userId；查無受理人時 {@code null}
     */
    private String urgeApplicantOf(String processInstanceId) {
        try {
            return applicantResolver.resolveApplicant(
                    onBehalfOfLookup.byProcessInstances(List.of(processInstanceId))
                            .get(processInstanceId),
                    accessGuard.initiatorOf(processInstanceId));
        } catch (IllegalStateException e) {
            log.warn("催辦無法判定受理人（{}）: {}", processInstanceId, e.getMessage());
            return null;
        } catch (RestClientException e) {
            log.warn("權限中心查詢催辦受理人失敗（{}）: {}", processInstanceId, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "無法確認催辦權限，請稍後再試");
        }
    }

    /**
     * 取得催辦許可（同案件 30 分鐘一次）。Redis 故障時 fail-open。
     *
     * @return {@code false} 代表冷卻中，呼叫端回 429
     */
    private boolean acquireUrgePermit(String processInstanceId) {
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(URGE_KEY_PREFIX + processInstanceId, "1", URGE_COOLDOWN);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("催辦頻率限制無法取得 Redis 許可，本次放行（key={}）: {}",
                    URGE_KEY_PREFIX + processInstanceId, e.toString());
            return true;
        }
    }

    /**
     * 留言（#79：這裡原本<b>完全沒有</b>物件層授權）。
     *
     * <h2>缺陷（真實 JWT 線上實測）</h2>
     *
     * <p>{@code callerId} 從 R-01 起只用於稽核，<b>從未拿去與案件或任務比對</b>。
     * 任何登入者只要知道 taskId 就能在別人的單上留言，稽核留下
     * {@code TASK_COMMENT | operatorId = user002}。改動前 taskId 還能從
     * {@code GET /api/history/tasks} 大量取得（那是 #76 修掉的發射台）。
     *
     * <h2>守衛：{@code requireTaskParticipant}（寫個案，不是動作任務）</h2>
     *
     * <p>批註不影響流程走向（spec §4.5），所以適用「寫個案」政策 ——
     * {@code requireParticipant}，<b>不開稽核旁路</b>（稽核人員的職責是查閱，
     * 不是替別人的案子補簽核意見）。規則與理由見 {@code ProcessAccessGuard}。
     *
     * <p>守衛<b>排在最前面</b>：與 {@link #updateTask} 不同，這個方法沒有
     * 「請求形狀」檢查可排，而訊息為空不會改變任何東西（{@code message} 可為 null），
     * 因此沒有理由讓未授權的呼叫端先走到資料層。
     *
     * <h2>連帶修掉：對不存在的 taskId 留言是<b>裸 500</b></h2>
     *
     * <p>負向控制組實測（把守衛整段拿掉之後）：
     * {@code POST /api/tasks/{unknownId}/comments} →
     * {@code FlowableObjectNotFoundException: Cannot find task with id …} → <b>500</b>。
     * 引擎的 {@code AddCommentCmd} <b>確實會</b>驗任務存在，所以不會產生
     * 孤兒批註（這點與「Flowable 不驗外鍵」的直覺相反，是實測確認的），
     * 但那個例外沒有被翻成 {@code ResponseStatusException}，於是呼叫端拿到 500。
     *
     * <p>500 的後果與 #85 記錄過的同一個：修好之後呼叫端才會停止重試。
     * 而更根本的問題是改動前<b>連「這個任務存不存在」都沒人問</b> ——
     * 守衛先確認任務真的存在，這條路徑於是回 404，
     * 與 {@link #updateTask} 對不存在任務的處理一致。
     *
     * <p>順帶一提，{@code processInstanceId} 現在由守衛回傳，
     * 這也讓「pid 一定是真實的」變成結構性保證，而不是 {@code task != null} 的副產品。
     *
     * <h2>#79-2：對<b>已完成</b>的關卡留言由裸 500 改為 404</h2>
     *
     * <p>症狀：{@code POST} 到一個已結束的 taskId，授權會通過
     * （守衛看得到歷史，所以 pid 找得到、關係人也成立），接著
     * {@code AddCommentCmd} 因為 runtime 裡沒有這個任務而拋
     * {@code FlowableObjectNotFoundException} → <b>500</b>。
     *
     * <p>這與 #79 改動前完全相同（改動前 {@code task == null} → pid 傳 null
     * → 同一個例外），所以<b>不是</b> #79 引入的迴歸。但守衛放行之後才 500
     * 對呼叫端更誤導：「你有權，但這件事做不成」被講成「伺服器壞了」，
     * 而 500 的直覺是「再試一次」，重試<b>永遠不會成功</b>。
     * 已由使用者裁決為 404（與其他「找不到東西」的回應一致，見
     * {@code ProcessAccessGuard} 類別註解的枚舉政策）。
     *
     * <h3>⚠️ 為什麼 catch 這個例外不會把真實的引擎故障藏起來</h3>
     *
     * <p>這是本工項唯一的風險點：{@code AddCommentCmd} 若也會對
     * <b>真正存在</b>的任務拋同一個例外，那麼「任務存在但引擎有問題」
     * 就會被講成 404。已用 javap 逐一檢查 Flowable 7.2.0 的
     * {@code AddCommentCmd.execute} 位元碼確認：它<b>只</b>在兩個地方丟
     * {@code FlowableObjectNotFoundException}，兩者都是「runtime 裡查不到」——
     * <ol>
     *   <li>{@code taskId != null && taskService.getTask(taskId) == null}
     *       → {@code "Cannot find task with id …"}</li>
     *   <li>{@code processInstanceId != null && findById(pid) == null}
     *       → {@code "Cannot find process instance with id …"}</li>
     * </ol>
     * 該方法沒有第三個拋出點。因此對一個<b>存在</b>的執行中任務，(1) 不可能觸發；
     * (2) 要觸發必須是「runtime 的任務列還在、但流程實例列已經不見」——
     * 而 Flowable 結束一個流程實例時是在<b>同一個交易</b>裡刪掉兩者，
     * 這個狀態不可達。換句話說，這個例外在這條路徑上<b>只</b>代表
     * 「目標不在 runtime」。
     *
     * <p>其他引擎故障<b>不會</b>被這個 catch 吃掉，刻意保留 500：
     * 暫停中的任務／流程實例 → {@code FlowableException}
     * （{@code AddCommentCmd} 對 {@code isSuspended()} 明確拋這個，不是本類別）；
     * 資料庫／約束問題 → {@code DataIntegrityViolationException}。
     *
     * <h3>⚠️ 為什麼用 catch 而不是先查再留言</h3>
     *
     * <p>「先 {@code createTaskQuery().taskId(id).count() == 0} 就 404」
     * 看起來更直觀，但它會在 controller 裡<b>再寫一份</b>「這個 taskId
     * 有沒有在 runtime」的規則 —— 而
     * {@link com.bpm.core.security.ProcessAccessGuard#processInstanceIdOfTask}
     * 已經是那條規則（runtime 優先、歷史次之）。#84（create/update 對同一個
     * 參數兩套規則）與 #86（刪除規則與「記得 flush」分在兩處）都是這麼長出來的，
     * 所以本 repo 的硬規則是<b>規則只能有一份</b>。
     *
     * <p>而且預先檢查<b>消除不了</b>競態窗口：查完到真的留言之間，
     * 關卡仍可能被完成。要關掉那個窗口終究還是需要同一層 catch。
     * 引擎自己就是「在不在 runtime」的權威，把它翻譯成狀態碼既不會多一份規則，
     * 也不會漏掉競態。同一個模式已用在
     * {@code ProcessController.startProcess} 與 {@code ExternalApiController.startProcess}
     * 的 {@code FlowableObjectNotFoundException} 轉譯上。
     *
     * <h3>⚠️ 為什麼不順手擋掉<b>讀</b>端</h3>
     *
     * <p>{@link #getComments} 與 {@code HistoryController} 的歷史讀端點讀的是
     * {@code ACT_HI_COMMENT}，與任務是否還在 runtime <b>無關</b> ——
     * 已結束關卡的簽核意見正是簽核軌跡的一部分，必須讀得到，
     * 否則 {@code ApprovalTimeline.vue} 會在審結的案件上整段空白。
     * 本工項刻意不動讀端，並以 {@code CommentAuthorizationTest} 的
     * 「已完成任務的留言讀取仍然成功」把它釘死。
     *
     * <h3>⚠️ 為什麼這裡<b>不</b>補稽核紀錄</h3>
     *
     * <p>不變的是 {@code TASK_COMMENT} —— 沒有留言發生就不該宣稱有
     * （與「被拒的請求不得寫出稽核」同一條原則）。刻意<b>不</b>補一筆
     * {@code DATA_ACCESS denied}：那條紀錄的語意是「有人探測了他無權的案件」
     * （{@code denyNonParticipant}），而呼叫端<b>確實</b>是關係人、
     * 也<b>沒有</b>任何授權規則被違反。塞進去會污染「誰在試探別人的單」這個
     * 訊號。未留痕並不難診斷：404 的訊息會說明是「已結束」。
     * 而且兩種情況回的都是 404，不會因此多開一條枚舉管道。
     *
     * <h3>前端相容性（實查，非假設）</h3>
     *
     * <p>前端走不到這條路徑：{@code ApprovalTimeline.vue:44} 只對
     * {@code t.endTime} 為真的 taskId 呼叫<b>歷史讀</b>端點
     * （{@code /api/history/tasks/{id}/comments}）；兩個寫入端
     * （{@code CommentPanel.vue:35}／{@code ActionDialog.vue:61}）的 taskId
     * 都來自 {@code DocumentDetail.vue} 的 {@code route.params.taskId}，
     * 而 {@code /tasks/:taskId} 只由 {@code TaskInbox.vue:43} 與
     * {@code Dashboard.vue:33} 導向 —— 兩者都出自 {@code GET /api/tasks}
     * 這個 runtime 待辦清單。也就是說寫入端的 taskId 必然還在執行中。
     */
    @PostMapping("/{id}/comments")
    @Transactional("primaryTransactionManager")
    public Map<String, String> addComment(@PathVariable String id,
                                          @RequestBody CommentRequest req,
                                          @CallerId
                                          String callerId) {
        // 非關係人 → 404（不是 403），且留痕；任務不存在 → 404。理由見
        // ProcessAccessGuard.requireTaskParticipant 與 denyNonParticipant。
        String processInstanceId = accessGuard.requireTaskParticipant(id, callerId);

        // ⚠️ 這裡的 fallback 現在是<b>死碼</b>，而且它「看起來安全」是巧合：
        // 安全性完全建立在 firstNonBlank 的<b>參數順序</b>上 ——
        // callerId 在前，所以 req.userId() 永遠輪不到（requireTaskParticipant
        // 已保證 callerId 非空白，未認證會先被 404 擋下）。
        //
        // 刻意<b>不</b>在這裡改成 requireSelf(req.userId(), callerId, "userId")：
        // 「身分欄位帶了別人的值 → 明確 400」是 #66／#72／#81 那一組規則，
        // 套用在這裡會讓 body 帶 userId 的既有呼叫端（spec §4.5 的範例、
        // 前端 ActionDialog.vue:61 與 CommentPanel.vue:35 都送
        // {@code userId:'current_user'}）整個變成 400。那是政策性決定，
        // 另案處理；這裡只把「順序是承重結構」這件事寫下來。
        //
        // 2026-09-30 線上實測（#79）：送 {"userId":"dir001"} 時，批註作者與
        // 稽核 operatorId 都是實際的呼叫者 —— 冒用沒有成功。
        String author = firstNonBlank(callerId, req.userId());

        // 用 try/finally 還原原值：Authentication 存放在 ThreadLocal，
        // 而 servlet 容器的執行緒是重複使用的 —— 不還原會讓下一個請求
        // 沿用上一個使用者的身分。
        String previous = Authentication.getAuthenticatedUserId();
        try {
            Authentication.setAuthenticatedUserId(author);
            // #79-2：把「runtime 裡沒有這個任務」翻譯成 404。
            //
            // ⚠️ catch 的範圍刻意<b>只包住 addComment 這一行</b>：
            // 稽核的 publish 留在外面。若把它包進去，fail-closed 的稽核失敗
            // 會被翻成 404 —— 那是把「稽核寫不進去所以這筆調閱不該成功」
            // （AuditFailClosedTest）講成「東西不存在」，把真實故障藏起來。
            //
            // 守衛查得到歷史、所以已結束的關卡會走到這裡；引擎只認 runtime。
            // 完整理由（含「這個例外會不會在任務存在時也觸發」的位元碼查證）
            // 見本方法的 javadoc。
            try {
                taskService.addComment(id, processInstanceId, req.message());
            } catch (FlowableObjectNotFoundException e) {
                // ⚠️ 本方法是 @Transactional，而 Flowable 命令在外層交易中
                // 拋例外會把交易標成 rollback-only —— 所以這裡只能「翻譯」
                // 例外，不能在同一個交易裡繼續做別的事（與
                // ExternalApiController.startProcess 的同型註解）。
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "任務不存在或已結束，無法留言: " + id, e);
            }
        } finally {
            Authentication.setAuthenticatedUserId(previous);
        }

        auditPublisher.publish(new AuditEvent("TASK_COMMENT", author,
                processInstanceId, id, Map.of("message", req.message())));
        return Map.of("status", "ok");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /**
     * 讀取批註（#79：這裡原本<b>完全沒有</b>任何檢查）。
     *
     * <h2>缺陷（真實 JWT 線上實測）</h2>
     *
     * <p>taskId {@code ba0454b0-…}（user001 的請假單，持有者 mgr001）上
     * 掛著 mgr001 寫的「薪資調幅尚未報帳，請補附件後再簽」。
     * 實測：{@code user001}／{@code user002}／{@code mgr002} 三個身分
     * 全部 {@code GET 200}，也就是<b>完全無關的人讀得到簽核意見全文</b>。
     * 這是整條鏈上<b>最後一扇還開著的門</b>：taskId 的發射台已由 #76 關掉，
     * 但 taskId 仍可從稽核紀錄（{@code audit:log:read}）取得。
     *
     * <h2>守衛：{@code requireTaskReadAccess}（讀個案內容）</h2>
     *
     * <p>與 variables／form-data／附件／簽核軌跡<b>同一條</b>
     * {@code requireReadAccess}：關係人 ∪ {@code audit:log:read}（旁路每次留痕），
     * 非關係人 404。規則與「為什麼旁路留痕寫在守衛裡」見
     * {@code ProcessAccessGuard.requireTaskReadAccess}。
     *
     * <p>⚠️ <b>刻意不新增 {@code @Transactional}</b>：唯讀查詢 ＋ 一次稽核寫入，
     * 與 {@code ProcessController.getVariables}／{@code HistoryController.getHistoricTasks}
     * 同一型（稽核失敗 → 503，見 {@code AuditFailClosedTest}）。
     * 加交易反而會讓稽核掛在 beforeCommit，響應組裝階段的例外會讓它永遠寫不進去。
     *
     * <p>⚠️ <b>前端的相容性由構造保證</b>：{@code CommentPanel.vue:31} 呼叫本端點，
     * 而它綁的 taskId 來自 {@code DocumentDetail.vue} 的待辦清單 ——
     * 也就是呼叫者<b>持有或可認領</b>的任務，而 assignee／owner／candidateUser
     * 全部落在 {@code isParticipant} 條件 2（{@code taskInvolvedUser}）的比對範圍內。
     * 由 {@code CommentAuthorizationTest} 的兩條正向測試固定住：
     * {@code taskHolderCanStillReadIt}、
     * {@code reviewerCanStillTraverseTheWholeApprovalTimeline}。
     */
    @GetMapping("/{id}/comments")
    public List<Map<String, Object>> getComments(@PathVariable String id,
                                                 @CallerId
                                                 String callerId) {
        accessGuard.requireTaskReadAccess(id, callerId);
        return mapComments(taskService.getTaskComments(id));
    }

    /**
     * complete 的稽核型別（#1 起包含退回申請人）。
     *
     * <p>條件順序即語意：{@code rejected} 先判 —— 與 BPMN gw2 的
     * rejected 分支優先於 returnTo 一致（{@code rejected=true + returnTo}
     * 在 API 層已是 400，這裡的順序是給非 HTTP 完成路徑的縱深防禦）；
     * {@code returnTo=initiator} 次之；最後才是 {@code approved} 的一般
     * 核准／退回。
     *
     * <p>⚠️ {@code TASK_RETURN}（退回上一站）與
     * {@code TASK_RETURN_INITIATOR}（退回申請人）的差別是<b>語意</b>，
     * 不是狀態：兩者都是 {@code approved=false}。BPMN 的路由由
     * {@code returnTo} 變數決定，稽核因此也必須看它，否則
     * 「退到起點」在軌跡上會長得跟「退回上一站」一模一樣。
     *
     * <p>呼叫端（complete 分支）的補件任務判定（{@code TASK_RESUBMIT}）
     * 仍優先於本方法，順序不變。
     */
    private OperationType resolveCompleteAuditType(Map<String, Object> vars) {
        if (Boolean.TRUE.equals(vars.get("rejected"))) return OperationType.TASK_REJECT;
        if ("initiator".equals(vars.get("returnTo"))) return OperationType.TASK_RETURN_INITIATOR;
        if (Boolean.FALSE.equals(vars.get("approved"))) return OperationType.TASK_RETURN;
        if (Boolean.TRUE.equals(vars.get("approved"))) return OperationType.TASK_APPROVE;
        return OperationType.TASK_APPROVE;
    }

    /**
     * 一筆任務的對外表示法。
     *
     * @param onBehalfOf 案件 id → 代發員工（見 {@code OnBehalfOfLookup}）。
     *                   由呼叫端一次查好傳入，<b>不可</b>在這裡逐筆查 ——
     *                   那是 N+1，而且會讓「一次查詢」的保證只存在於註解裡。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Task t, Map<String, String> onBehalfOf) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", t.getId());
        m.put("taskName", t.getName());
        m.put("assignee", t.getAssignee());
        m.put("processInstanceId", t.getProcessInstanceId());
        m.put("createTime", t.getCreateTime());
        m.put("dueDate", t.getDueDate());
        m.put("formKey", t.getFormKey());
        // #68b：審核人端原本看不到的代發標示。
        // 缺席（null）= 這不是代發的案件；呼叫端因此可用「有沒有這個鍵」
        // 判斷，不需要另外一個布林欄位。
        m.put("onBehalfOf", t.getProcessInstanceId() != null
                ? onBehalfOf.get(t.getProcessInstanceId()) : null);
        // Resolve locked formVersion from process variable
        try {
            Map<String, Integer> versions = (Map<String, Integer>)
                    runtimeService.getVariable(t.getProcessInstanceId(), "_formVersions");
            if (versions != null && t.getFormKey() != null) {
                m.put("formVersion", versions.get(t.getFormKey()));
            }
        } catch (Exception ignored) {}
        // Enrich with processDefinitionKey and businessKey
        try {
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(t.getProcessInstanceId()).singleResult();
            if (pi != null) {
                m.put("processDefinitionKey", pi.getProcessDefinitionKey());
                m.put("businessKey", pi.getBusinessKey());
            }
        } catch (Exception ignored) {}
        return m;
    }

    static List<Map<String, Object>> mapComments(List<org.flowable.engine.task.Comment> comments) {
        return comments.stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("message", c.getFullMessage());
                    m.put("userId", c.getUserId() != null ? c.getUserId() : "");
                    m.put("time", c.getTime().toString());
                    return m;
                }).toList();
    }
}
