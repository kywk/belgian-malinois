package com.bpm.core.engine;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.external.ExternalActorIdentity;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.impl.cfg.TransactionState;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任務建立後<b>沒有任何人看得到</b>時發出告警：沒有 assignee，也沒有候選人／候選群組。
 *
 * <h2>⚠️ 2026-09-30（#83）：「沒有 assignee」不等於「沒有人看得到」</h2>
 *
 * <p>本類別原本的條件是 {@code assignee != null && !isBlank() → return}。
 * 而外部系統發起的案件 {@code initiator} 是 {@code system:<id>}（R-20），
 * 補件關卡若用 {@code ${initiator}} 指派，assignee 就會是 {@code system:erp} ——
 * 非空白，於是這裡<b>直接 return，不告警</b>。
 *
 * <p>但 {@code system:erp} <b>沒有人能登入</b>，所以
 * {@link com.bpm.core.security.TaskHolderGuard} 的四個條件全部不命中：
 * 沒有任何人看得到那個任務、沒有任何人能簽它，而<b>沒有任何錯誤訊息</b>。
 * 這是簽核系統裡最難察覺的失敗型態 —— 沒有例外、沒有告警，
 * 使用者只會說「我的單子怎麼還沒過」。
 *
 * <p>因此判準改成「有沒有人能動它」，而 assignee 是不是人由
 * {@link com.bpm.core.external.ExternalActorIdentity} 單一判定
 * （規則只有一份，見該類別）。
 *
 * <h2>⚠️ 已知的誤報情境，以及為什麼仍然值得報</h2>
 *
 * <p>{@code ExternalApiController.completeTask} 是 server-to-server 路徑，
 * 它用 {@code verifyRunningOwnership} 而<b>不是</b> TaskHolderGuard ——
 * 所以理論上「assignee 是 {@code system:erp}、由外部系統回呼完成」
 * 是一個可以成立的設計，那種情況下本告警是誤報。
 *
 * <p>仍然報的理由是兩個方向的代價完全不對稱：誤報的代價是運維多看一則
 * 記錄（而且 {@code reason} 會寫明 {@code assignee-is-system-identity}，
 * 一看就知道該怎麼處理）；漏報的代價是一張永久卡死的案件，
 * 而且沒有任何人會知道它卡住。
 *
 * <p>而且本專案<b>沒有任何一支 BPMN</b>是那樣設計的。若日後真的需要，
 * 正確做法是讓該關卡改用候選群組或指派給具體的人 ——
 * 那時候選人查得到它，這個告警也就自然不會出現。
 *
 * <h2>⚠️ 2026-09-30（#89）：「有候選人」不等於「候選人看得到」</h2>
 *
 * <p>#83 的判準是「有沒有人能動它」，而「有候選人」被當成「有人能動」的理由。
 * 但 Flowable 的候選人查詢帶著 {@code RES.ASSIGNEE_ IS NULL}，
 * 所以<b>只要 ASSIGNEE_ 不是 null，候選人就看不到那個任務</b>。
 *
 * <p>而 {@code setAssignee(taskId, "")} 產生的是<b>空字串不是 null</b>
 * （2026-09-30 實測確認）。於是出現這個形狀：有候選人、assignee 是空字串 ——
 * <b>候選人查不到它，listener 也不告警</b>。這比 {@code system:} 更難察覺，
 * 因為稽核與資料看起來都「有人負責」。
 *
 * <p>因此判準改成<b>逐字對應那條 SQL 的條件</b>：{@code hasCandidate}
 * 只有在 {@code assignee == null} 時才有意義。
 *
 * <h3>⚠️ 實測修正了工項描述中的一個前提（2026-09-30）</h3>
 *
 * <p>工項描述稱「BPMN 的 {@code flowable:assignee=""} 字面值仍能產生它」。
 * <b>實測不成立</b>：{@code UserTaskActivityBehavior.handleAssignments} 對
 * assignee 有 {@code StringUtils.isNotEmpty} 前置判斷，空字串<b>不會被寫入</b>
 * —— 實測 {@code flowable:assignee=""} 與 {@code flowable:assignee=" "}
 * 產生的 assignee 都是 <b>null</b>（那不是缺陷，null 對候選查詢是好的），
 * 候選群組查得到、listener 也不告警，行為正確。
 *
 * <p><b>但缺陷本身是真的</b>，且有<b>兩條真的能產生它的路徑</b>（皆為實測）：
 * <ol>
 *   <li>{@code setAssignee(taskId, "")} / {@code setAssignee(taskId, " ")} ——
 *       空字串<b>原樣寫入</b>（{@code TaskService.setAssignee} 沒有 isNotEmpty 判斷），
 *       候選群組查詢 count=0。</li>
 *   <li>{@code flowable:assignee="${var}"} 而 {@code var} 為空白字串 ——
 *       運算式求值<b>繞過</b>了那道 isNotEmpty（判斷的是運算式字串本身非空），
 *       求值結果是 {@code "  "}<b>照樣寫入</b>，候選群組查詢 count=0。
 *       這一條<b>是管理員部署的流程就能觸發的</b>，不經任何 API。</li>
 * </ol>
 *
 * <p>⚠️ 本 listener 是<b>最後一道</b>，不是唯一一道：#88 已在外部 API 的
 * <b>寫入端</b>擋掉空白 assignee。但那個入口只管 {@code firstTaskAssignee}，
 * 管不到 BPMN 運算式與其他寫入端 —— 這正是為什麼這個 listener 必須修：
 * <b>寫入端漏掉時，仍然要看得見</b>。兩者互補，不可互相取代
 * （與 {@code BpmnLintService} 規則 h 對本 listener 的註解同一個道理）。
 *
 * <h2>為什麼需要</h2>
 *
 * <p>這種任務在 Flowable 裡完全合法：建立成功、流程停在那裡、不報錯。但沒有任何
 * 使用者的待辦清單會出現它，所以<b>案件永遠卡住，而且沒有人知道</b>。
 * 典型成因是指派運算式求值為 null —— JUEL 對 List 越界不拋例外而是回 null
 * （P2-6 的 {@code getManagerChain(...)[1]} 就是這樣），或是業務人員在設計器
 * 漏設受理人。
 *
 * <h2>只告警，不硬擋（2026-09-29 決策）</h2>
 *
 * <p>硬擋會讓整個流程推進失敗，而且會弄壞合法的「先建立、再補候選人」模式
 * （{@code ExternalApiController.startProcess} 在啟動後才 {@code addCandidateGroup}）。
 * 告警讓問題可見，由人決定怎麼處理。#83 的修法是讓根因（補件關卡派給系統身分）
 * 不再發生，而不是靠這個 listener 去擋 —— 擋只會讓「退回」這個動作失敗，
 * 主管會看到一個與他無關的 500。
 *
 * <h2>為什麼在 commit 之後才檢查</h2>
 *
 * <p>若在 {@code TASK_CREATED} 當下檢查，上面那個外部 API 路徑<b>每一次</b>都會誤報
 * —— 候選群組是同一個交易稍後才加上的。誤報會訓練大家忽略這個告警，
 * 等真的出事時就沒人看了。所以用 Flowable 的交易生命週期事件
 * （{@code onTransaction = committed}），在 commit 後重新查一次任務的最終狀態。
 * 交易回滾時不觸發 —— 任務根本不存在，也就沒有問題。
 *
 * <h2>告警寫到哪</h2>
 *
 * <p>ERROR log（給監控）＋一筆 {@link OperationType#TASK_UNREACHABLE} 稽核
 * （給事後查詢，也讓測試能斷言）。稽核用 {@code publishDetached}：
 * 業務已經 commit，這裡拋例外只會讓使用者拿到錯誤，卻回滾不了任何東西。
 */
@Component
public class UnreachableTaskListener implements FlowableEventListener {

    private static final Logger log = LoggerFactory.getLogger(UnreachableTaskListener.class);

    /** {@code @Lazy} 的理由與 {@code ProcessCompletedListener} 相同：引擎設定需要本 listener。 */
    private final TaskService taskService;
    private final AuditEventPublisher auditPublisher;

    public UnreachableTaskListener(@Lazy TaskService taskService, AuditEventPublisher auditPublisher) {
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() != FlowableEngineEventType.TASK_CREATED) return;
        if (!(event instanceof FlowableEntityEvent entityEvent)
                || !(entityEvent.getEntity() instanceof Task created)) return;

        // 重新查詢：事件帶的是建立當下的快照，而這裡要的是 commit 後的最終狀態。
        Task task = taskService.createTaskQuery().taskId(created.getId()).singleResult();
        if (task == null) return;  // 同一個交易內已完成或刪除

        // ⚠️ 這一段的條件就是本項的判準：「有沒有人能動它」。
        //
        // 改動前只問「assignee 是不是 null／空白」，而 assignee="system:erp"
        // 非空白 → 直接 return → 不告警。但那是**伺服器鑄造的系統身分，不是人**：
        // 沒有人能以它登入，TaskHolderGuard 的四個條件（assignee／owner／
        // candidateUser／候選群組）全部不命中，於是**沒有任何人能簽**，
        // 案件停在這裡而沒有任何錯誤訊息 —— #83 的靜默卡死。
        //
        // 也就是說：這個 listener 的判準寫錯了問題。它問的是「有沒有受理人」，
        // 真正要問的是「有沒有**人**能受理」。
        String assignee = task.getAssignee();
        boolean assigneeIsSystemActor = ExternalActorIdentity.isSystemActor(assignee);
        if (assignee != null && !assignee.isBlank() && !assigneeIsSystemActor) return;

        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        boolean hasCandidate = links.stream().anyMatch(l -> "candidate".equals(l.getType())
                && (l.getUserId() != null || l.getGroupId() != null));

        // ⚠️ 候選人<b>只在 assignee 為 null 時才救得了</b>這個任務。
        //
        // Flowable 的 taskCandidateUser／taskCandidateGroup 查詢帶著
        // ASSIGNEE_ IS NULL（Task.xml 的 selectTaskByCandidateGroup*，
        // 條件 3 的同一件事也記載於 TaskHolderGuard.isHolder），
        // 所以<b>只要 ASSIGNEE_ 不是 null</b>，候選人就看不到這個任務 ——
        // 兩個條件互斥，不是互補。
        //
        // ⚠️ #89：判準必須<b>逐字對應那條 SQL 的條件</b>，而不是「assignee
        // 是不是某種特別的身分」。缺陷期間這裡寫的是
        //     if (hasCandidate && !assigneeIsSystemActor) return;
        // 那只把「系統身分」從候選人的救援範圍裡挖掉，於是<b>非 null 但不是
        // 系統身分的 assignee 一律讓候選人「救」成功</b> —— 而 Flowable 對那些值
        // 同樣不讓候選人看到。實測（2026-09-30，真實 MSSQL ＋ Flowable 7.2.0）：
        // assignee 是空字串或純空白時，taskCandidateGroup 的 count 是 0，
        // 而 TASK_UNREACHABLE 也是 0 筆 —— 群組成員看不到任務，listener 也不告警。
        //
        // 為什麼不用「把空白 assignee 正規化成 null」：那會讓本 listener
        // <b>改動別人寫入的資料</b>，而它的職責是觀察與告警（見類別註解
        // 「只告警，不硬擋」——硬擋會讓流程推進失敗）。要擋空白 assignee
        // 應該在<b>寫入端</b>擋（#88 已在外部 API 做了），listener 這一層的
        // 職責是<b>寫入端漏掉時仍然看得見</b>，兩者不可互相取代 ——
        // 與 BpmnLintService 規則 h 對這個 listener 的註解是同一個道理。
        //
        // ⚠️ 2026-10-01（#91 方向 B）：BPMN 運算式求值為空白那條路徑已在
        // 引擎的寫入端根治 —— BlankAssigneeNormalizingInterceptor（全域
        // CreateUserTaskInterceptor）於 handleAssignments 之後把空白 assignee
        // 收斂成 null。本 listener 的判準與職責都不變：它仍是最後一道，
        // 負責在寫入端漏掉時（例如 setAssignee 等其他寫入端）告警。
        // 兩者互補，不可互相取代。
        if (hasCandidate && assignee == null) return;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskName", task.getName() == null ? "" : task.getName());
        detail.put("taskDefinitionKey", task.getTaskDefinitionKey() == null ? "" : task.getTaskDefinitionKey());
        detail.put("processDefinitionId", task.getProcessDefinitionId() == null ? "" : task.getProcessDefinitionId());
        // reason 讓查稽核的人分辨成因。三者都是「沒有人能簽」，但處理的起點不同：
        //   no-assignee-no-candidate    → 沒指定受理人，也沒留給任何人
        //   assignee-is-system-identity → 指定了，但那是沒有人能登入的身分
        //   assignee-blocks-candidates  → ⚠️ #89：指定了空白字串。**看起來**
        //     「有候選人就有人能處理」，但候選人查不到它（ASSIGNEE_ IS NULL
        //     不命中）—— 前兩者一眼看得出該做什麼，這一項不會。
        //
        // ⚠️ 既有兩個字串**不得**改名或合併（監控與稽核查詢已依它分流）。
        // 告警本身不因 reason 而改變：**新增的 reason 不代表多報**，
        // 只是讓同一個「沒有人能簽」的三種成因在稽核裡可分辨。
        detail.put("reason", reasonOf(assignee, assigneeIsSystemActor));
        // assignee 原樣帶上（空字串就是空字串）。
        //
        // ⚠️ 刻意不寫成 `assignee == null ? "" : assignee`：那會讓「null」與
        // 「空字串」在稽核裡長得一模一樣，而這兩者的成因與修法都不同 ——
        // 正是本工項要分辨的東西。既有查詢以「無此 key 或空字串」當 null 處理，
        // 行為不變（見 UnreachableSystemAssigneeAlertTest 的 null 案例）。
        detail.put("assignee", assignee);

        log.error("任務沒有任何人看得到，案件將卡住。"
                        + "taskId={} processInstanceId={} detail={}",
                task.getId(), task.getProcessInstanceId(), detail);

        auditPublisher.publishDetached(new AuditEvent(
                OperationType.TASK_UNREACHABLE.name(),
                // 由引擎偵測，不是人類操作。與 PROCESS_COMPLETE 相同記 "system"。
                "system", "engine", null,
                task.getProcessInstanceId(), task.getId(), null,
                detail, java.time.Instant.now()));
    }

    /**
     * 告警的成因標記。抽成方法而不是留在三元運算子裡，是因為 #89 之後
     * 已經有<b>三種</b>成因（原本兩種），而且新增的那一種與另外兩種
     * 判斷的是<b>不同的事</b>：前兩種看「有沒有人能動它」，第三種看
     * 「看起來有人、實際上沒有」。塞回一個三元運算子會讓這個區別看不出來。
     *
     * <p>⚠️ 這裡的分支必須與上面兩道 return 的條件保持一致 ——
     * 判準與標記分家正是本缺陷的成因（規則只能有一份）。
     */
    private static String reasonOf(String assignee, boolean assigneeIsSystemActor) {
        if (assigneeIsSystemActor) return "assignee-is-system-identity";
        // ⚠️ 這裡是 assignee != null 就標成 assignee-blocks-candidates，
        // **不可**寫成 !assignee.isBlank() —— 空字串與純空白本身就是
        // isBlank() == true，寫成 !isBlank() 會讓空字串掉回
        // no-assignee-no-candidate，而那正是本工項要修的形狀被標錯成因。
        //
        // 為什麼不需要再排除非空白：能走到這裡的 assignee 必然是
        // 「null／空白／系統身分」三者之一（第一道 return 已擋掉其餘），
        // 所以 assignee != null 與 assignee 是空白的在這裡是同一個集合。
        if (assignee != null) return "assignee-blocks-candidates";
        return "no-assignee-no-candidate";
    }

    /** 告警失敗不可影響流程（而且此時業務早已 commit）。 */
    @Override
    public boolean isFailOnException() { return false; }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() { return true; }

    @Override
    public String getOnTransaction() { return TransactionState.COMMITTED.name(); }
}
