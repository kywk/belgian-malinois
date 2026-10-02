package com.bpm.core.audit;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.service.AuditCsvWriter;
import com.bpm.core.audit.service.AuditLogService;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稽核紀錄 CSV 匯出（#40）。
 *
 * <h2>為什麼走真實 HTTP 而不是 MockMvc</h2>
 *
 * <p>匯出直接在 controller 內把分頁結果寫進 {@code HttpServletResponse}
 * 的 {@code OutputStream}（見 AuditLogController 對「為何不用
 * StreamingResponseBody」的說明）。MockMvc 能驗證邏輯，但這裡要驗的是
 * 「使用者實際拿到的位元組」與「授權的真實狀態碼」—— 而 401／403 的
 * 狀態碼在 MockMvc 下不經過 ERROR dispatch（見 {@code AuditReadAuthorityTest}
 * 的說明）。所以直接對測試自己起的 Tomcat 發 HTTP。
 *
 * <h2>這裡的斷言刻意貼著兩份規格</h2>
 *
 * <ul>
 *   <li><b>RFC 4180</b>：CSV 以極簡 parser 讀回，欄位值必須逐字元等於
 *       寫入稽核庫的值（含逗號、雙引號、CRLF）。</li>
 *   <li><b>安全政策</b>：匯出與列表共用 {@code audit:log:read}，
 *       {@code ROLE_ADMIN} 刻意不通過 —— 與 {@code AuditReadAuthorityTest}
 *       同一條線。</li>
 * </ul>
 */
class AuditExportTest extends IntegrationTestBase {

    private static final String AUDITOR = "dir001";
    private static final String ADMIN = "admin001";

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    // ── 授權 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("匯出只接受 audit:log:read：dir001 200、admin001 403、未登入 401")
    void exportRequiresAuditReadAuthority() throws Exception {
        assertThat(export("", AUDITOR).statusCode())
                .as("持有 audit:log:read 的稽核職能必須能匯出")
                .isEqualTo(200);

        assertThat(export("", ADMIN).statusCode())
                .as("ROLE_ADMIN（* 通配）刻意不等於 audit:log:read —— "
                        + "匯出是列表的同一把鑰匙，不能是一條側門")
                .isEqualTo(403);

        assertThat(export("", "user001").statusCode())
                .as("沒有 audit:log:read 的一般使用者")
                .isEqualTo(403);

        assertThat(export("", null).statusCode())
                .as("完全沒有身分時是 401（不是 403）")
                .isEqualTo(401);
    }

    // ── CSV 正確性 ─────────────────────────────────────────────────

    @Test
    @DisplayName("CSV：UTF-8 BOM、固定表頭、RFC4180 逃逸逐字元正確")
    void csvIsUtf8BomAndRfc4180Escaped() throws Exception {
        // detail 含逗號／雙引號／CRLF，operatorName 含雙引號與中文 ——
        // 這四種字元正好覆蓋 RFC 4180 需要逃逸的全部情況。
        AuditLog inserted = append("dir001", "王\"小明", OperationType.TASK_APPROVE,
                "line1,\"quoted\"\r\nline2", Instant.parse("2026-03-01T10:00:00Z"));

        HttpResponse<byte[]> response = export("", AUDITOR);
        assertThat(response.statusCode()).isEqualTo(200);

        // 回應標頭：型別、檔名（含日期）
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("Excel 與瀏覽器要能辨識為 CSV 且是 UTF-8")
                .contains("text/csv")
                .contains("UTF-8");
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .as("下載檔名必須帶日期，否則多次匯出會互相覆蓋")
                .matches("attachment; filename=\"audit-logs-\\d{4}-\\d{2}-\\d{2}\\.csv\"");

        // UTF-8 BOM：EF BB BF
        assertThat(response.body()).hasSizeGreaterThan(3);
        assertThat(response.body()[0] & 0xFF).isEqualTo(0xEF);
        assertThat(response.body()[1] & 0xFF).isEqualTo(0xBB);
        assertThat(response.body()[2] & 0xFF).isEqualTo(0xBF);

        List<List<String>> rows = csvRows(response);
        List<String> header = rows.get(0);
        assertThat(header).containsExactlyElementsOf(AuditCsvWriter.HEADER);
        assertThat(rows).hasSize(2);

        Map<String, String> expected = new LinkedHashMap<>();
        for (String column : AuditCsvWriter.HEADER) {
            expected.put(column, "");
        }
        expected.put("id", String.valueOf(inserted.getId()));
        expected.put("createdAt", "2026-03-01T10:00:00Z");
        expected.put("operationType", "TASK_APPROVE");
        expected.put("operatorId", "dir001");
        expected.put("operatorName", "王\"小明");
        expected.put("detail", "line1,\"quoted\"\r\nline2");
        // append() 會填 hash chain —— export 是完整快照，這兩個欄位必須也在
        expected.put("hashValue", inserted.getHashValue());
        expected.put("previousHash", inserted.getPreviousHash());

        assertThat(rowAsMap(header, rows.get(1)))
                .as("parser 讀回的值必須逐字元等於寫入值；"
                        + "任何漏掉的逗號／引號／換行都會在這裡現形")
                .isEqualTo(expected);

        // 額外的原始位元組檢查：證明「逃逸真的寫在檔案裡」，
        // 而不是 parser 剛好容忍了錯誤格式。
        String raw = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(raw).contains("\"王\"\"小明\"");
        assertThat(raw).contains("\"line1,\"\"quoted\"\"\r\nline2\"");
    }

    @Test
    @DisplayName("零筆結果：仍回表頭，且匯出照樣留稽核（totalHits=0）")
    void emptyResultStillReturnsHeaderAndAudit() throws Exception {
        HttpResponse<byte[]> response = export("operatorId=nobody", AUDITOR);
        assertThat(response.statusCode()).isEqualTo(200);

        List<List<String>> rows = csvRows(response);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactlyElementsOf(AuditCsvWriter.HEADER);

        assertThat(exportAuditRow())
                .as("查無資料也是「查了」—— 沒有紀錄就分不出空匯出與沒匯出")
                .contains("\"totalHits\":0");
    }

    // ── 匯出本身被稽核 ─────────────────────────────────────────────

    @Test
    @DisplayName("匯出本身要記 EXPORT_DATA，內容含篩選條件與筆數")
    void exportIsItselfAudited() throws Exception {
        append("mgr001", "張三", OperationType.PROCESS_START, "{}",
                Instant.parse("2026-04-01T00:00:00Z"));

        HttpResponse<byte[]> response = export(
                "operatorId=mgr001&operationType=PROCESS_START"
                        + "&startDate=2026-04-01T00:00:00Z&endDate=2026-04-30T00:00:00Z",
                AUDITOR);
        assertThat(response.statusCode()).isEqualTo(200);

        String audit = exportAuditRow();
        assertThat(audit)
                .as("operator 必須是匯出者（dir001），不是被查的人")
                .contains("dir001");
        assertThat(audit)
                .as("篩選條件與命中筆數是調查「誰把什麼撈出去」的全部線索")
                .contains("\"action\":\"export\"")
                .contains("mgr001")
                .contains("PROCESS_START")
                .contains("2026-04-01T00:00:00Z")
                .contains("2026-04-30T00:00:00Z")
                .contains("\"totalHits\":1")
                .contains("\"format\":\"csv\"");
    }

    // ── 篩選與列表一致 ─────────────────────────────────────────────

    @Test
    @DisplayName("匯出與列表對同一組篩選參數回同一批 id、同一個順序")
    void exportAndListAgreeOnFilters() throws Exception {
        long a = append("mgr001", "甲", OperationType.PROCESS_START, "a",
                Instant.parse("2026-04-01T00:00:00Z")).getId();
        long b = append("mgr001", "乙", OperationType.TASK_APPROVE, "b",
                Instant.parse("2026-04-02T00:00:00Z")).getId();
        long c = append("mgr002", "丙", OperationType.PROCESS_START, "c",
                Instant.parse("2026-04-03T00:00:00Z")).getId();

        // 順序是 createdAt DESC（與列表相同）：4/2 在 4/1 之前、4/3 在 4/1 之前。
        assertExportMatchesList("operatorId=mgr001", b, a);
        assertExportMatchesList("operationType=PROCESS_START", c, a);
        assertExportMatchesList(
                "startDate=2026-04-02T00:00:00Z&endDate=2026-04-02T23:59:59Z", b);
    }

    // ── 串流分頁 ───────────────────────────────────────────────────

    @Test
    @DisplayName("1200 筆跨頁匯出：不重不漏，順序穩定")
    void largeExportStreamsAcrossPages() throws Exception {
        // 1200 > EXPORT_PAGE_SIZE(500) × 2，逼出「三頁」的邊界。
        // 直接 JDBC 批次寫入：這裡要測的是匯出的分頁邏輯，
        // 不是 hash chain 的寫入路徑（後者由 AuditWritePathTest 覆蓋）。
        insertRawRows(1200);

        HttpResponse<byte[]> response = export("operatorId=bulk", AUDITOR);
        assertThat(response.statusCode()).isEqualTo(200);

        List<List<String>> rows = csvRows(response);
        int idIndex = rows.get(0).indexOf("id");
        List<Long> ids = rows.subList(1, rows.size()).stream()
                .map(row -> Long.valueOf(row.get(idIndex)))
                .toList();

        assertThat(ids)
                .as("分頁漏掉或重複任何一筆，這裡的數字就會不對")
                .hasSize(1200)
                .doesNotHaveDuplicates();
        assertThat(ids)
                .as("順序必須與列表一致：createdAt DESC, id DESC")
                .isSortedAccordingTo(Comparator.reverseOrder());

        assertThat(exportAuditRow()).contains("\"totalHits\":1200");
    }

    // ── HTTP 小工具 ────────────────────────────────────────────────

    /** {@code userId} 為 null 時完全不帶身分，用來驗 401。 */
    private HttpResponse<byte[]> send(String path, String userId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + SERVLET_PORT + path));
        if (userId != null) {
            builder.header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                    .header("X-User-Id", userId);
        }
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> export(String params, String userId) throws Exception {
        return send("/api/audit-logs/export" + (params.isEmpty() ? "" : "?" + params), userId);
    }

    private List<String> exportIds(String params) throws Exception {
        HttpResponse<byte[]> response = export(params, AUDITOR);
        assertThat(response.statusCode()).isEqualTo(200);
        List<List<String>> rows = csvRows(response);
        int idIndex = rows.get(0).indexOf("id");
        List<String> ids = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            ids.add(row.get(idIndex));
        }
        return ids;
    }

    private List<String> listIds(String params) throws Exception {
        HttpResponse<byte[]> response = send(
                "/api/audit-logs?size=100" + (params.isEmpty() ? "" : "&" + params), AUDITOR);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode content = objectMapper.readTree(response.body()).path("content");
        List<String> ids = new ArrayList<>();
        content.forEach(node -> ids.add(node.path("id").asText()));
        return ids;
    }

    private void assertExportMatchesList(String params, Long... expectedIds) throws Exception {
        List<String> expected = new ArrayList<>();
        for (Long id : expectedIds) {
            expected.add(String.valueOf(id));
        }
        List<String> fromExport = exportIds(params);
        List<String> fromList = listIds(params);
        assertThat(fromExport)
                .as("匯出（%s）必須命中預期的 id", params)
                .containsExactlyElementsOf(expected);
        assertThat(fromExport)
                .as("匯出與列表對同一組參數必須是同一批、同一個順序", params)
                .containsExactlyElementsOf(fromList);
    }

    // ── 資料庫小工具 ───────────────────────────────────────────────

    private AuditLog append(String operatorId, String operatorName, OperationType type,
                            String detail, Instant createdAt) {
        AuditLog log = new AuditLog();
        log.setOperationType(type);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setDetail(detail);
        log.setCreatedAt(createdAt);
        return auditLogService.append(log);
    }

    /** 直接批次寫入，繞過 hash chain —— 只用於「大量資料」的分頁測試。 */
    private static void insertRawRows(int count) {
        withAuditConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO bpm_audit_log "
                            + "(operation_type, operator_id, detail, created_at, hash_value) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                Instant base = Instant.parse("2026-05-01T00:00:00Z");
                for (int i = 0; i < count; i++) {
                    ps.setString(1, "TASK_APPROVE");
                    ps.setString(2, "bulk");
                    ps.setString(3, "row-" + i);
                    ps.setObject(4, OffsetDateTime.ofInstant(
                            base.plusSeconds(i), ZoneOffset.UTC));
                    ps.setString(5, "v2:bulk");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /** 回傳唯一一筆 EXPORT_DATA 稽核的 {@code operator_id|detail}。 */
    private static String exportAuditRow() {
        List<String> rows = new ArrayList<>();
        withAuditConnection(connection -> {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT operator_id, detail FROM bpm_audit_log "
                                 + "WHERE operation_type = 'EXPORT_DATA' ORDER BY id")) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + "|" + rs.getString(2));
                }
            }
        });
        assertThat(rows).as("匯出必須留下一筆 EXPORT_DATA 稽核").hasSize(1);
        return rows.get(0);
    }

    // ── CSV 讀回（測試自己的極簡 RFC4180 parser）────────────────────

    private static List<List<String>> csvRows(HttpResponse<byte[]> response) {
        String text = new String(response.body(), StandardCharsets.UTF_8);
        if (text.startsWith(AuditCsvWriter.BOM)) {
            text = text.substring(1);
        }
        return parseCsv(text);
    }

    private static Map<String, String> rowAsMap(List<String> header, List<String> row) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            map.put(header.get(i), i < row.size() ? row.get(i) : "");
        }
        return map;
    }

    /**
     * 極簡 RFC 4180 parser：支援引號內的逗號／CRLF／雙引號（加倍）。
     *
     * <p>刻意寬鬆（未加引號的欄位也接受 LF），因為它的角色是「讀回」——
     * 嚴格度由 writer 的輸出與 {@code rowAsMap} 的逐字比較提供。
     */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
