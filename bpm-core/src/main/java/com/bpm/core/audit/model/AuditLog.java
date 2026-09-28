package com.bpm.core.audit.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bpm_audit_log", indexes = {
        @Index(name = "idx_audit_process", columnList = "processInstanceId"),
        @Index(name = "idx_audit_operator", columnList = "operatorId"),
        @Index(name = "idx_audit_created", columnList = "createdAt")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OperationType operationType;

    private String operatorId;

    // ⚠️ NVARCHAR 不可省略：預設的 String 對映是 VARCHAR，而 DB 定序為
    // SQL_Latin1_General_CP1_CI_AS —— 中文姓名會被靜默換成問號。
    // 這個欄位已被 v2 的 hash 涵蓋，一旦寫入值與讀回值不同，
    // integrityCheck 會把每一筆都誤報為遭篡改。
    // 見 db/migration/audit/V4__nvarchar_for_audit_text_columns.sql。
    @Column(columnDefinition = "NVARCHAR(255)")
    private String operatorName;

    @Column(length = 20)
    private String operatorSource;

    private String processDefinitionKey;
    private String processInstanceId;
    private String taskId;

    // 同 operatorName：businessKey 可能含中文（案件標題型的業務鍵）。
    @Column(columnDefinition = "NVARCHAR(255)")
    private String businessKey;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String detail;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String previousState;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String newState;

    private String ipAddress;
    private String userAgent;

    // 80 而非 64：v2 的 hash 帶 'v2:' 版本前綴（見 AuditLogService.V2）。
    // 對應 migration audit/V3__widen_hash_value_for_version_prefix.sql。
    @Column(nullable = false, length = 80)
    private String hashValue;

    // 與 hashValue 同寬：previousHash 存放的就是前一筆的 hashValue。
    @Column(length = 80)
    private String previousHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public OperationType getOperationType() { return operationType; }
    public void setOperationType(OperationType operationType) { this.operationType = operationType; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public String getOperatorSource() { return operatorSource; }
    public void setOperatorSource(String operatorSource) { this.operatorSource = operatorSource; }
    public String getProcessDefinitionKey() { return processDefinitionKey; }
    public void setProcessDefinitionKey(String processDefinitionKey) { this.processDefinitionKey = processDefinitionKey; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getPreviousState() { return previousState; }
    public void setPreviousState(String previousState) { this.previousState = previousState; }
    public String getNewState() { return newState; }
    public void setNewState(String newState) { this.newState = newState; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getHashValue() { return hashValue; }
    public void setHashValue(String hashValue) { this.hashValue = hashValue; }
    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
