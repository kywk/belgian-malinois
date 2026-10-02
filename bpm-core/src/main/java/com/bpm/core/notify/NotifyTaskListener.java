package com.bpm.core.notify;

import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * BPMN 節點層的 {@code task_assigned} 發送端。
 *
 * <p>payload 與發送的實作已收斂到 {@link NotifyPublisher}（#33）——
 * 加簽子任務、認領、催辦、退回／拒絕／結案與這裡共用同一份。
 * 本類別只負責從 {@link DelegateTask} 取出該帶的欄位。
 *
 * <h2>為什麼 {@code processDefinitionKey} 必須是 key 而不是 id</h2>
 *
 * <p>{@code getProcessDefinitionId()} 回傳 {@code "leave-approval:1:2504"}
 * （含版號），而 {@code EmailConsumer} 拿它去查
 * {@code findByProcessDefinitionKeyAndEventType...}，DB 存的是
 * {@code "leave-approval"} → 永遠查不到任何設定，整套模板機制形同虛設，
 * 且 {@code ${processName}} 會被渲染成那串 id（security-audit P1-13）。
 * 裁切規則現在在 {@link NotifyPublisher#extractProcessKey}。
 *
 * <h2>候選任務的通知</h2>
 *
 * <p>候選人任務（candidateUsers／candidateGroups）沒有 assignee。
 * 改動前 {@code EmailConsumer} 遇到 assignee 為 null 就直接 return
 * → 群組待辦完全不發通知，候選人不知道有事情等他。
 * 這裡把候選人清單一起帶上（{@code EmailConsumer.resolveRecipients} 處理）。
 */
@Component("notifyTaskListener")
public class NotifyTaskListener implements TaskListener {

    private final NotifyPublisher publisher;

    public NotifyTaskListener(NotifyPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void notify(DelegateTask task) {
        Object initiator = task.getVariable("initiator");

        // 候選人任務（candidateUsers／candidateGroups）沒有 assignee。
        // 見類別註解。
        List<String> candidates = List.of();
        if (task.getAssignee() == null || task.getAssignee().isBlank()) {
            candidates = task.getCandidates().stream()
                    .map(org.flowable.identitylink.api.IdentityLink::getUserId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }

        publisher.taskAssigned(task.getId(), task.getName(), task.getAssignee(),
                candidates, task.getProcessInstanceId(), task.getProcessDefinitionId(),
                initiator != null ? initiator.toString() : null);
    }
}
