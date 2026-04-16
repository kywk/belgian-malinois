package com.bpm.core.notify;

import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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
        msg.put("processDefinitionKey", task.getProcessDefinitionId());
        msg.put("timestamp", Instant.now().toString());

        // Get initiator from process variables for email context
        Object initiator = task.getVariable("initiator");
        if (initiator != null) msg.put("initiator", initiator.toString());

        rabbitTemplate.convertAndSend("bpm.exchange", "bpm.notify.task", msg);
    }
}
