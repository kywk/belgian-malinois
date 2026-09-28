package com.bpm.core.webhook;

import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component("webhookTaskListener")
public class WebhookTaskListener implements TaskListener {

    private final RabbitTemplate rabbitTemplate;

    public WebhookTaskListener(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void notify(DelegateTask task) {
        String event = task.getEventName(); // create, complete, delete

        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "task." + event);
        payload.put("timestamp", Instant.now().toString());
        payload.put("processInstanceId", task.getProcessInstanceId());
        payload.put("processDefinitionKey", extractProcessKey(task.getProcessDefinitionId()));
        payload.put("businessKey", task.getVariable("businessKey"));
        payload.put("taskId", task.getId());
        payload.put("taskName", task.getName());

        switch (event) {
            case "create" -> {
                payload.put("assignee", task.getAssignee());
                payload.put("dueDate", task.getDueDate());
            }
            case "complete" -> {
                payload.put("operatorId", task.getAssignee());
                Object approved = task.getVariable("approved");
                Object rejected = task.getVariable("rejected");
                if (Boolean.TRUE.equals(rejected)) {
                    payload.put("action", "rejected");
                    payload.put("rejectReason", task.getVariable("rejectReason"));
                } else if (Boolean.FALSE.equals(approved)) {
                    payload.put("action", "returned");
                } else {
                    payload.put("action", "approved");
                }
                // ⚠️ 刻意不外送流程變數（security-audit P2-1）。
                //
                // 表單欄位 id 就是流程變數名（spec §8.5），因此
                // getVariablesLocal() 等於把該關卡表單的全部內容
                // （可能含薪資、身分證號）原封不動送到外部 URL，
                // 而且沒有任何白名單。
                //
                // 簽核結果已由上面的 action／rejectReason 表達；
                // 需要明細的接收端應回頭呼叫 API（那條路徑有授權）。
            }
            case "delete" -> {
                payload.put("assignee", task.getAssignee());
            }
        }

        rabbitTemplate.convertAndSend("bpm.exchange", "bpm.webhook.task", payload);
    }

    private String extractProcessKey(String processDefinitionId) {
        if (processDefinitionId == null) return "";
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }
}
