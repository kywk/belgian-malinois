package com.bpm.core.audit;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.service.AuditLogService;
import com.bpm.core.config.AuditAsyncConfig;
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

    /**
     * 寫入稽核事件（非同步）。
     *
     * <p>⚠️ 必須指定 {@code auditTaskExecutor}。不指定的話會用 Boot 預設的
     * {@code applicationTaskExecutor}，而它的佇列容量是
     * {@code Integer.MAX_VALUE} —— 稽核 DB 變慢時事件會無上限堆在 heap，
     * 重啟或 OOM 就<b>全部消失</b>，而對應的業務操作早已回 200
     * （security-audit P1-14）。見 {@link com.bpm.core.config.AuditAsyncConfig}。
     *
     * <p>⚠️ <b>仍然是 fail-open</b>：寫入失敗時只記錄 ERROR，業務操作照樣成功。
     * 要改成 fail-closed（稽核寫不進去就讓簽核失敗）是業務政策決策 ——
     * 對 ISO 27001 而言 fail-closed 才正確，但它會讓稽核 DB 的任何抖動
     * 直接變成簽核中斷。此處保留 fail-open，但把失敗的完整事件內容一起
     * 記錄下來以便人工重放。
     */
    @Async(AuditAsyncConfig.AUDIT_EXECUTOR)
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
            // 把完整事件內容一起記錄：這是唯一還能人工重放的線索。
            log.error("稽核寫入失敗，該筆稽核已遺失（業務操作仍然成功）。event={} 原因={}",
                    event, e.getMessage(), e);
        }
    }
}
