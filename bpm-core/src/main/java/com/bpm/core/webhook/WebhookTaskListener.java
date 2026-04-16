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
                // Include variables modified in this task
                Map<String, Object> vars = new HashMap<>(task.getVariablesLocal());
                payload.put("variables", vars);
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
