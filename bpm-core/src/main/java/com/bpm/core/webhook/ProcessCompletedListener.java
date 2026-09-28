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

import org.springframework.context.annotation.Lazy;

@Component
public class ProcessCompletedListener implements FlowableEventListener {

    private final RabbitTemplate rabbitTemplate;
    private final RuntimeService runtimeService;

    /**
     * ⚠️ {@code @Lazy} 不可移除 —— Stage 4 已實測確認（2026-09-28）。
     *
     * <p>升級計畫要求「確認 @Lazy RuntimeService 的循環依賴 workaround 是否仍必要」。
     * 答案是<b>仍然必要</b>，而且它不是 Flowable 6 的遺留物，是結構性的循環：
     *
     * <pre>
     *   processCompletedListener
     *     → runtimeService
     *       → StandaloneEngineConfiguration
     *         → engineConfigurers
     *           → processEngineConfigurer（FlowableConfig）
     *             → processCompletedListener   ← 回到起點
     * </pre>
     *
     * <p>成因：{@code FlowableConfig.processEngineConfigurer} 必須注入本 listener
     * 才能呼叫 {@code setEventListeners()}，而本 listener 又需要引擎產生的
     * {@code RuntimeService}。移除 {@code @Lazy} 後啟動直接失敗：
     * {@code Requested bean is currently in creation: Is there an unresolvable
     * circular reference?}（Boot 3 預設禁止循環參照）。
     */
    public ProcessCompletedListener(RabbitTemplate rabbitTemplate, @Lazy RuntimeService runtimeService) {
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
