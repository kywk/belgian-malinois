package com.bpm.core.audit.service;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class AuditLogService {

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public synchronized AuditLog append(AuditLog log) {
        String previousHash = repository.findLastRecord()
                .map(AuditLog::getHashValue).orElse("GENESIS");
        log.setPreviousHash(previousHash);
        log.setHashValue(computeHash(log, previousHash));
        return repository.save(log);
    }

    public Page<AuditLog> search(String processInstanceId, String operatorId,
                                  OperationType operationType, Instant startDate,
                                  Instant endDate, Pageable pageable) {
        return repository.search(processInstanceId, operatorId, operationType,
                startDate, endDate, pageable);
    }

    public Map<String, Object> integrityCheck(Instant startDate, Instant endDate) {
        List<AuditLog> logs = repository.findByDateRange(startDate, endDate);
        int checked = 0;
        int broken = 0;
        Long firstBrokenId = null;

        for (AuditLog log : logs) {
            String expected = computeHash(log, log.getPreviousHash());
            if (!expected.equals(log.getHashValue())) {
                broken++;
                if (firstBrokenId == null) firstBrokenId = log.getId();
            }
            checked++;
        }

        return Map.of(
                "checked", checked,
                "broken", broken,
                "intact", broken == 0,
                "firstBrokenId", firstBrokenId != null ? firstBrokenId : "none",
                "startDate", startDate.toString(),
                "endDate", endDate.toString());
    }

    static String computeHash(AuditLog log, String previousHash) {
        try {
            String content = log.getOperationType() + "|"
                    + nullSafe(log.getOperatorId()) + "|"
                    + nullSafe(log.getProcessInstanceId()) + "|"
                    + nullSafe(log.getTaskId()) + "|"
                    + nullSafe(log.getDetail()) + "|"
                    + log.getCreatedAt() + "|"
                    + previousHash;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Hash computation failed", e);
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
