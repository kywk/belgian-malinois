package com.bpm.core.engine;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
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
 * 告警讓問題可見，由人決定怎麼處理。
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
        if (task.getAssignee() != null && !task.getAssignee().isBlank()) return;

        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        boolean hasCandidate = links.stream().anyMatch(l -> "candidate".equals(l.getType())
                && (l.getUserId() != null || l.getGroupId() != null));
        if (hasCandidate) return;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskName", task.getName() == null ? "" : task.getName());
        detail.put("taskDefinitionKey", task.getTaskDefinitionKey() == null ? "" : task.getTaskDefinitionKey());
        detail.put("processDefinitionId", task.getProcessDefinitionId() == null ? "" : task.getProcessDefinitionId());

        log.error("任務沒有任何人看得到（無 assignee、無候選人／群組），案件將卡住。"
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
