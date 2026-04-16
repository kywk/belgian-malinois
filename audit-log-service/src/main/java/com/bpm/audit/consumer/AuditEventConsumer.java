package com.bpm.audit.consumer;

import com.bpm.audit.model.AuditLog;
import com.bpm.audit.model.OperationType;
import com.bpm.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public AuditEventConsumer(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "audit.log.queue")
    public void handle(Map<String, Object> event) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setOperationType(OperationType.valueOf((String) event.get("operationType")));
            auditLog.setOperatorId((String) event.get("operatorId"));
            auditLog.setOperatorSource((String) event.getOrDefault("operatorSource", "user"));
            auditLog.setProcessDefinitionKey((String) event.get("processDefinitionKey"));
            auditLog.setProcessInstanceId((String) event.get("processInstanceId"));
            auditLog.setTaskId((String) event.get("taskId"));
            auditLog.setBusinessKey((String) event.get("businessKey"));
            auditLog.setTraceId((String) event.get("traceId"));
            auditLog.setIpAddress((String) event.get("ipAddress"));
            auditLog.setUserAgent((String) event.get("userAgent"));

            Object detail = event.get("detail");
            if (detail != null) {
                auditLog.setDetail(objectMapper.writeValueAsString(detail));
            }

            Object ts = event.get("timestamp");
            if (ts instanceof String s) {
                auditLog.setCreatedAt(Instant.parse(s));
            } else if (ts instanceof Number n) {
                auditLog.setCreatedAt(Instant.ofEpochSecond(n.longValue()));
            }

            auditLogService.append(auditLog);
            log.debug("Audit log saved: {} for {}", auditLog.getOperationType(), auditLog.getProcessInstanceId());
        } catch (Exception e) {
            log.error("Failed to process audit event: {}", e.getMessage(), e);
            throw new RuntimeException(e); // triggers retry → DLQ
        }
    }
}
