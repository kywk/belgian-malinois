package com.bpm.core.audit.controller;

import com.bpm.core.security.CallerId;
import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.service.AuditLogService;
import com.bpm.core.dto.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 稽核查詢。
 *
 * <h2>查稽核這件事本身也要留下紀錄（security-audit P2-4）</h2>
 *
 * <p>改動前這兩個端點完全沒有稽核。但「誰查了誰的簽核紀錄」是敏感資訊 ——
 * 稽核庫裡有全公司的請假、採購、核決金額與簽核意見。可以無痕跡地翻閱它，
 * 等於這份資料沒有存取控制的事實層面。
 *
 * <p>這在稽核領域有個現成的名字：<b>稽核稽核者</b>。理由不是不信任稽核人員，
 * 而是「有人在看」這件事本身就會改變行為 —— 而且當真的發生濫用時，
 * 那是唯一能發現它的途徑。
 *
 * <p>{@code integrityCheck} 特別重要：它是唯一能看出 hash chain 被動過的
 * 工具。如果有人正在調查竄改，而那個人就是竄改者，
 * 這筆紀錄是唯一的痕跡（而且它寫在鏈上，改不掉 —— 見 AuditLogService）。
 *
 * <h2>⚠️ 這裡刻意不記查詢結果，只記查詢條件</h2>
 *
 * <p>記結果會讓稽核庫自我膨脹（查 1000 筆就多寫 1000 筆內容），
 * 而且會把同一份敏感資料複製一次。查詢條件已足夠回答「誰翻了什麼範圍」。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final AuditEventPublisher auditPublisher;

    public AuditLogController(AuditLogService auditLogService,
                              AuditEventPublisher auditPublisher) {
        this.auditLogService = auditLogService;
        this.auditPublisher = auditPublisher;
    }

    @GetMapping({"", "/"})
    public Page<AuditLog> search(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CallerId String requesterId) {

        OperationType opType = operationType != null ? OperationType.valueOf(operationType) : null;
        Instant start = startDate != null ? Instant.parse(startDate) : null;
        Instant end = endDate != null ? Instant.parse(endDate) : null;

        Page<AuditLog> result = auditLogService.search(processInstanceId, operatorId, opType,
                start, end, PageRequest.of(page, size));

        // 查詢完成後才記錄，且帶上命中筆數 —— 「查了 5000 筆」與「查了 1 筆」
        // 在調查時是完全不同的訊號。
        recordAccess(requesterId, "search", Map.of(
                "processInstanceId", nullSafe(processInstanceId),
                "operatorId", nullSafe(operatorId),
                "operationType", nullSafe(operationType),
                "startDate", nullSafe(startDate),
                "endDate", nullSafe(endDate),
                "page", page,
                "size", size,
                "totalHits", result.getTotalElements()));
        return result;
    }

    @GetMapping("/integrity-check")
    public Map<String, Object> integrityCheck(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @CallerId String requesterId) {
        Map<String, Object> result =
                auditLogService.integrityCheck(Instant.parse(startDate), Instant.parse(endDate));

        // 連結果一起記：完整性檢查的結論（有沒有斷鏈、第一筆壞在哪）本身就是
        // 需要留存的事實。它很短，不會膨脹稽核庫。
        recordAccess(requesterId, "integrity-check", Map.of(
                "startDate", startDate,
                "endDate", endDate,
                "intact", String.valueOf(result.get("intact")),
                "checked", String.valueOf(result.get("checked")),
                "broken", String.valueOf(result.get("broken")),
                "firstBrokenId", String.valueOf(result.get("firstBrokenId"))));
        return result;
    }

    private void recordAccess(String requesterId, String action, Map<String, Object> detail) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("action", action);
        d.putAll(detail);
        auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(),
                requesterId != null && !requesterId.isBlank() ? requesterId : "unknown",
                null, null, d));
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
