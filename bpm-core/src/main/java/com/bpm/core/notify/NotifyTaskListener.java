package com.bpm.core.notify;

import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component("notifyTaskListener")
public class NotifyTaskListener implements TaskListener {

    private final RabbitTemplate rabbitTemplate;

    public NotifyTaskListener(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void notify(DelegateTask task) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("event", "task_assigned");
        msg.put("taskId", task.getId());
        msg.put("taskName", task.getName());
        msg.put("assignee", task.getAssignee());
        msg.put("processInstanceId", task.getProcessInstanceId());
        // ⚠️ 必須是 key 而不是 id。getProcessDefinitionId() 回傳
        // "leave-approval:1:2504"（含版號），而 EmailConsumer 拿它去查
        // findByProcessDefinitionKeyAndEventType...，DB 存的是 "leave-approval"
        // → 永遠查不到任何設定，整套模板機制形同虛設，一律落到硬編模板，
        // 且 ${processName} 會被渲染成那串 id（security-audit P1-13）。
        //
        // WebhookTaskListener 對同一件事本來就有正確的 extractProcessKey()
        // —— 兩處先前不一致。
        msg.put("processDefinitionKey", extractProcessKey(task.getProcessDefinitionId()));
        msg.put("timestamp", Instant.now().toString());

        // Get initiator from process variables for email context
        Object initiator = task.getVariable("initiator");
        if (initiator != null) msg.put("initiator", initiator.toString());

        // 候選人任務（candidateUsers／candidateGroups）沒有 assignee。
        // 改動前 EmailConsumer 遇到 assignee 為 null 就直接 return
        // → 群組待辦完全不發通知，候選人不知道有事情等他。
        if (task.getAssignee() == null || task.getAssignee().isBlank()) {
            List<String> candidates = task.getCandidates().stream()
                    .map(org.flowable.identitylink.api.IdentityLink::getUserId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!candidates.isEmpty()) msg.put("candidateUsers", candidates);
        }

        rabbitTemplate.convertAndSend("bpm.exchange", "bpm.notify.task", msg);
    }

    /**
     * 從 processDefinitionId 取出 key。
     *
     * <p>格式是 {@code {key}:{version}:{deploymentId}}，因此取第一個冒號之前。
     * 已經是 key 的值（不含冒號）原樣回傳。
     */
    static String extractProcessKey(String processDefinitionId) {
        if (processDefinitionId == null) return "";
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }
}
