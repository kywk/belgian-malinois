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

        // ⚠️ 候選人<b>救不了</b> assignee 是系統身分的任務。
        //
        // Flowable 的 taskCandidateUser 查詢帶著 ASSIGNEE_ IS NULL
        // （TaskHolderGuard.isHolder 的條件 3 註解記載了同一件事），
        // 所以一旦有 assignee，候選人就<b>看不到</b>這個任務 ——
        // 兩個條件互斥，不是互補。
        if (hasCandidate && !assigneeIsSystemActor) return;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskName", task.getName() == null ? "" : task.getName());
        detail.put("taskDefinitionKey", task.getTaskDefinitionKey() == null ? "" : task.getTaskDefinitionKey());
        detail.put("processDefinitionId", task.getProcessDefinitionId() == null ? "" : task.getProcessDefinitionId());
        // reason 讓查稽核的人分辨兩種成因：兩者都是「沒有人能簽」，
        // 但一種是沒有受理人，另一種是受理人是一個沒有人能持有的身分。
        detail.put("reason", assigneeIsSystemActor ? "assignee-is-system-identity" : "no-assignee-no-candidate");
        detail.put("assignee", assignee == null ? "" : assignee);

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

    /** 告警失敗不可影響流程（而且此時業務早已 commit）。 */
    @Override
    public boolean isFailOnException() { return false; }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() { return true; }

    @Override
    public String getOnTransaction() { return TransactionState.COMMITTED.name(); }
}
