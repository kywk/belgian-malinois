package com.bpm.core.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

public record AuditEvent(
        String operationType,
        String operatorId,
        String operatorSource,
        String processDefinitionKey,
        String processInstanceId,
        String taskId,
        String businessKey,
        Map<String, Object> detail,
        Instant timestamp
) implements Serializable {

    public AuditEvent(String operationType, String operatorId, String processInstanceId, String taskId, Map<String, Object> detail) {
        this(operationType, operatorId, "user", null, processInstanceId, taskId, null, detail, Instant.now());
    }
}
