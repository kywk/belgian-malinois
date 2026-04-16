package com.bpm.core.webhook;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.event.impl.FlowableEntityEventImpl;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class ProcessCompletedListener implements FlowableEventListener {

    private final RabbitTemplate rabbitTemplate;
    private final RuntimeService runtimeService;

    public ProcessCompletedListener(RabbitTemplate rabbitTemplate, RuntimeService runtimeService) {
        this.rabbitTemplate = rabbitTemplate;
        this.runtimeService = runtimeService;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() != FlowableEngineEventType.PROCESS_COMPLETED) return;

        if (event instanceof FlowableEntityEventImpl entityEvent
                && entityEvent.getEntity() instanceof ExecutionEntity exec) {

            String processInstanceId = exec.getProcessInstanceId();
            String processDefKey = extractKey(exec.getProcessDefinitionId());

            Map<String, Object> vars = new HashMap<>();
            try {
                vars = runtimeService.getVariables(processInstanceId);
            } catch (Exception ignored) {
                // Process already completed, variables may not be accessible via runtime
            }

            String result = Boolean.TRUE.equals(vars.get("rejected")) ? "rejected" : "approved";

            Map<String, Object> payload = new HashMap<>();
            payload.put("event", "process.completed");
            payload.put("timestamp", Instant.now().toString());
            payload.put("processInstanceId", processInstanceId);
            payload.put("processDefinitionKey", processDefKey);
            payload.put("businessKey", exec.getBusinessKey());
            payload.put("result", result);
            payload.put("allVariables", vars);

            rabbitTemplate.convertAndSend("bpm.exchange", "bpm.webhook." + processDefKey, payload);
        }
    }

    @Override
    public boolean isFailOnException() { return false; }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() { return false; }

    @Override
    public String getOnTransaction() { return null; }

    private String extractKey(String processDefinitionId) {
        if (processDefinitionId == null) return "";
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }
}
