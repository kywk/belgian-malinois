package com.bpm.core.audit;

import com.bpm.core.dto.AuditEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public AuditEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(AuditEvent event) {
        rabbitTemplate.convertAndSend("audit.exchange", "audit.log", event);
    }
}
