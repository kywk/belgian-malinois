package com.bpm.core.audit;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.service.AuditLogService;
import com.bpm.core.dto.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class AuditEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditEventPublisher.class);
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public AuditEventPublisher(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Async
    public void publish(AuditEvent event) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setOperationType(OperationType.valueOf(event.operationType()));
            auditLog.setOperatorId(event.operatorId());
            auditLog.setOperatorSource(event.operatorSource());
            auditLog.setProcessDefinitionKey(event.processDefinitionKey());
            auditLog.setProcessInstanceId(event.processInstanceId());
            auditLog.setTaskId(event.taskId());
            auditLog.setBusinessKey(event.businessKey());
            if (event.detail() != null) {
                auditLog.setDetail(objectMapper.writeValueAsString(event.detail()));
            }
            auditLog.setCreatedAt(event.timestamp());
            auditLogService.append(auditLog);
        } catch (Exception e) {
            log.error("Failed to save audit log: {}", e.getMessage(), e);
        }
    }
}
