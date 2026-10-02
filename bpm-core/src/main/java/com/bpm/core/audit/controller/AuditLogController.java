package com.bpm.core.audit.controller;

import com.bpm.core.security.CallerId;
import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.service.AuditCsvWriter;
import com.bpm.core.audit.service.AuditLogService;
import com.bpm.core.dto.AuditEvent;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
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
 *
 * <p>CSV 匯出（#40）也遵守同一條線：只記篩選條件與命中筆數，不記匯出的內容。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    /**
     * 匯出的分頁大小。
     *
     * <p>匯出必須能處理「比記憶體大的結果集」，所以採逐頁查詢、逐頁寫到
     * HTTP 回應，任何時刻只有一頁在記憶體裡。
     * 500 是每頁的資料庫往返與記憶體佔用的折衷；它<b>不是</b>匯出筆數上限
     * —— 沒有上限（見 #40 報告的待裁決事項）。
     */
    private static final int EXPORT_PAGE_SIZE = 500;

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
        recordAccess(OperationType.DATA_ACCESS, requesterId, "search", Map.of(
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
        recordAccess(OperationType.DATA_ACCESS, requesterId, "integrity-check", Map.of(
                "startDate", startDate,
                "endDate", endDate,
                "intact", String.valueOf(result.get("intact")),
                "checked", String.valueOf(result.get("checked")),
                "broken", String.valueOf(result.get("broken")),
                "firstBrokenId", String.valueOf(result.get("firstBrokenId"))));
        return result;
    }

    /**
     * 匯出符合篩選條件的稽核紀錄為 CSV。
     *
     * <h2>為什麼逐頁查、直接寫進 response，而不是 {@code StreamingResponseBody}</h2>
     *
     * <p>稽核表只會長大，而「匯出」的用途正是撈出大範圍的歷史資料
     * （例如年度查核）。若先 {@code findAll} 再組字串，記憶體用量與結果集
     * 成正比，一次大匯出就能把服務打掛 —— 而匯出是唯讀端點，
     * 不該有這種破壞力。因此逐頁查（{@link #EXPORT_PAGE_SIZE} 筆）逐頁寫，
     * 任何時刻只有一頁在記憶體裡。
     *
     * <p>⚠️ <b>不用 {@code StreamingResponseBody}</b>：它會讓 MVC 在完成後
     * 對同一個請求做一次 ASYNC dispatch，而該次 dispatch 會再走一遍
     * 授權鏈 —— 本服務的身分由 {@code GatewayAuthenticationFilter}
     * 逐請求設定，ASYNC dispatch 上沒有身分 → 落在
     * {@code /api/audit-logs/**} 的 {@code hasAuthority} 被拒 →
     * 因為回應已 commit，變成每次成功匯出都噴一筆
     * 「Unable to handle the Spring Security Exception because the response
     * is already committed」ERROR（2026-10-02 實測）。
     * 直接寫入 {@code HttpServletResponse} 的 {@code OutputStream} 沒有
     * 第二次 dispatch，串流效果相同（Tomcat 滿緩衝就送出），
     * 也不需要在 SecurityConfig 開 ASYNC 放行。
     *
     * <h2>⚠️ 先寫稽核、才開始送資料</h2>
     *
     * <p>與列表端點同一順序：查詢完成後、回應之前記一筆 {@code EXPORT_DATA}。
     * 稽核寫入失敗（fail-closed）→ 回 503（此時尚未取用 OutputStream，
     * 回應還能正常替換），不會出現「敏感資料已流出、但沒有任何人知道」。
     *
     * <p>代價是：若客戶端在串流途中斷線，稽核記的是「符合條件的筆數」
     * 而非「實際送達的筆數」。記的是<b>查詢命中數</b>（與列表端點的
     * {@code totalHits} 同義），不是宣稱全部送達 —— 篩選條件完整保留，
     * 調查時可以重放。
     *
     * <p>篩選參數與列表端點逐字相同，且走同一個
     * {@link AuditLogService#search}／repository 查詢 —— 沒有第二份規則。
     */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @CallerId String requesterId,
            HttpServletResponse response) throws IOException {

        OperationType opType = operationType != null ? OperationType.valueOf(operationType) : null;
        Instant start = startDate != null ? Instant.parse(startDate) : null;
        Instant end = endDate != null ? Instant.parse(endDate) : null;

        // 第一頁同時提供「命中筆數」與串流的起點 —— 不需要為了計數多查一次。
        Page<AuditLog> firstPage = auditLogService.search(processInstanceId, operatorId, opType,
                start, end, PageRequest.of(0, EXPORT_PAGE_SIZE));

        recordAccess(OperationType.EXPORT_DATA, requesterId, "export", Map.of(
                "processInstanceId", nullSafe(processInstanceId),
                "operatorId", nullSafe(operatorId),
                "operationType", nullSafe(operationType),
                "startDate", nullSafe(startDate),
                "endDate", nullSafe(endDate),
                "totalHits", firstPage.getTotalElements(),
                "format", "csv"));

        String filename = "audit-logs-" + LocalDate.now() + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"");

        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8))) {
            AuditCsvWriter.writeHeader(writer);
            Page<AuditLog> page = firstPage;
            for (int pageNumber = 0; ; pageNumber++) {
                for (AuditLog log : page.getContent()) {
                    AuditCsvWriter.writeRow(writer, log);
                }
                if (!page.hasNext()) {
                    break;
                }
                page = auditLogService.search(processInstanceId, operatorId, opType,
                        start, end, PageRequest.of(pageNumber + 1, EXPORT_PAGE_SIZE));
            }
        }
    }

    private void recordAccess(OperationType type, String requesterId, String action,
                              Map<String, Object> detail) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("action", action);
        d.putAll(detail);
        auditPublisher.publish(new AuditEvent(type.name(),
                requesterId != null && !requesterId.isBlank() ? requesterId : "unknown",
                null, null, d));
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
