package com.bpm.audit.controller;

import com.bpm.audit.model.AuditLog;
import com.bpm.audit.model.OperationType;
import com.bpm.audit.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping({"", "/"})
    public Page<AuditLog> search(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        OperationType opType = operationType != null ? OperationType.valueOf(operationType) : null;
        Instant start = startDate != null ? Instant.parse(startDate) : null;
        Instant end = endDate != null ? Instant.parse(endDate) : null;

        return auditLogService.search(processInstanceId, operatorId, opType, start, end,
                PageRequest.of(page, size));
    }

    @GetMapping("/integrity-check")
    public Map<String, Object> integrityCheck(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return auditLogService.integrityCheck(Instant.parse(startDate), Instant.parse(endDate));
    }
}
