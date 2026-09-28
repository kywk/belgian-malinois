package com.bpm.core.audit.consumer;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Consumes audit events from RabbitMQ (sent by form-service and other external producers).
 */
@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public AuditEventConsumer(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * 消費外部（form-service 等）發來的稽核事件。
     *
     * <p>⚠️ 幂等是必要的（security-audit P1-14）。RabbitMQ 的投遞保證是
     * at-least-once：consumer ack 之前連線中斷、或 retry 之後又成功，
     * 都會讓同一筆稽核被 append 兩次。
     *
     * <p>後果比「多一列」嚴重：hash chain 會多出一個重複節點，而
     * {@code integrityCheck} <b>完全察覺不到</b> —— 它驗的是鏈是否連續，
     * 而重複 append 產生的鏈在數學上完全合法。稽核紀錄被污染，
     * 完整性檢查卻回報 intact。
     *
     * <p>幂等鍵取自訊息的 {@code eventId}，沒有則退回 {@code traceId}。
     * 兩者皆無時無法去重 —— 此時記錄警告，因為那代表發送端沒有帶鍵。
     */
    @RabbitListener(queues = "audit.log.queue")
    public void handle(Map<String, Object> event) {
        try {
            String eventId = (String) event.getOrDefault("eventId", event.get("traceId"));
            if (eventId != null && !eventId.isBlank()) {
                if (auditLogService.existsByEventId(eventId)) {
                    log.debug("稽核事件 {} 已處理過，略過（broker 重投）", eventId);
                    return;
                }
            } else {
                log.warn("稽核事件缺少 eventId／traceId，無法去重 —— "
                        + "broker 重投時會產生重複的 hash chain 節點");
            }

            AuditLog auditLog = new AuditLog();
            auditLog.setEventId(eventId);
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
            throw new RuntimeException(e);
        }
    }
}
