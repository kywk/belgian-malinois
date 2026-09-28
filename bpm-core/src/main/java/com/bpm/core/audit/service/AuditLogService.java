package com.bpm.core.audit.service;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 附加一筆稽核記錄。
     *
     * <p>⚠️ {@code @Transactional} 必須限定 {@code auditTransactionManager}。
     * 未限定的 {@code @Transactional} 會開在 {@code bpm_core_db} 上（primary 是
     * {@code @Primary}），稽核寫入就失去原子性 —— 見 CLAUDE.md 已知事實 #7。
     *
     * <p>把 read-modify-write（讀前一筆的 hash → 算新 hash → 寫入）包在<b>同一個</b>
     * 交易裡也是必要的：{@code synchronized} 只在單一 JVM 內有效，多實例部署時
     * 兩個節點可以同時讀到同一個 previousHash，產生分叉的 hash chain。
     * 交易本身不解決跨實例競爭（那需要序列化隔離或 DB 端序號），
     * 但至少讓單一節點內的鏈結是一致的。
     */
    @Transactional("auditTransactionManager")
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
