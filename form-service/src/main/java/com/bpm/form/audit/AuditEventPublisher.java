package com.bpm.form.audit;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class AuditEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public AuditEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String operationType, String operatorId, String processInstanceId, Map<String, Object> detail) {
        rabbitTemplate.convertAndSend("audit.exchange", "audit.log", Map.of(
                "operationType", operationType,
                "operatorId", operatorId != null ? operatorId : "",
                "operatorSource", "user",
                "processInstanceId", processInstanceId != null ? processInstanceId : "",
                "detail", detail,
                "timestamp", Instant.now().toString()));
    }
}
