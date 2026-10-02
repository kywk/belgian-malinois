package com.bpm.core.audit.service;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditCsvWriter} 的 RFC 4180 邊界。
 *
 * <p>這裡刻意不碰資料庫：CSV 逃逸是純函式，用單元測試把每個邊界字元
 * 逐一釘死，整合測試則負責證明這條路徑真的接到端點上。
 */
class AuditCsvWriterTest {

    @Test
    @DisplayName("escape：只有逗號／雙引號／LF／CR 觸發引號，引號加倍")
    void escapeFollowsRfc4180() {
        assertThat(AuditCsvWriter.escape(null)).isEmpty();
        assertThat(AuditCsvWriter.escape("")).isEmpty();
        assertThat(AuditCsvWriter.escape("plain")).isEqualTo("plain");
        assertThat(AuditCsvWriter.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(AuditCsvWriter.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AuditCsvWriter.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(AuditCsvWriter.escape("line1\r\nline2")).isEqualTo("\"line1\r\nline2\"");
        assertThat(AuditCsvWriter.escape("中文,「引號」")).isEqualTo("\"中文,「引號」\"");

        // 不觸發引號的字元必須原樣輸出 —— 過度逃逸雖不違反 RFC，
        // 但會讓「哪些欄位有問題」在檔案裡看不出來，也讓 diff 變吵。
        assertThat(AuditCsvWriter.escape(" a b ")).isEqualTo(" a b ");
        assertThat(AuditCsvWriter.escape("a'b")).isEqualTo("a'b");
        assertThat(AuditCsvWriter.escape("a;b")).isEqualTo("a;b");
    }

    @Test
    @DisplayName("表頭：UTF-8 BOM + 固定欄位順序 + CRLF")
    void headerIsStable() throws Exception {
        StringWriter writer = new StringWriter();
        AuditCsvWriter.writeHeader(writer);
        String text = writer.toString();

        assertThat(text)
                .as("Excel 需要 BOM 才不會把 UTF-8 中文當 ANSI")
                .startsWith("\uFEFF");
        assertThat(text)
                .as("欄位順序是匯出契約的一部分，不可重排")
                .isEqualTo("\uFEFF" + String.join(",", AuditCsvWriter.HEADER) + "\r\n");
        assertThat(AuditCsvWriter.HEADER)
                .containsExactly(
                        "id", "createdAt", "operationType", "operatorId", "operatorName",
                        "operatorSource", "processDefinitionKey", "processInstanceId",
                        "taskId", "businessKey", "traceId", "eventId", "detail",
                        "previousState", "newState", "ipAddress", "userAgent",
                        "hashValue", "previousHash");
    }

    @Test
    @DisplayName("資料列：null 成空欄、危險值被逃逸、欄數與表頭一致")
    void rowEscapesAndKeepsColumnCount() throws Exception {
        AuditLog log = new AuditLog();
        log.setId(7L);
        log.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        log.setOperationType(OperationType.TASK_APPROVE);
        log.setOperatorId("dir001");
        log.setOperatorName("王\"小明");
        log.setDetail("line1,\"quoted\"\r\nline2");

        StringWriter writer = new StringWriter();
        AuditCsvWriter.writeRow(writer, log);
        String line = writer.toString();

        // 19 欄逐字釘死：欄 5 的引號加倍、欄 13 的逗號／引號／CRLF 都在引號內，
        // 其餘欄位為空字串。
        String expected = String.join(",",
                "7",
                "2026-03-01T10:00:00Z",
                "TASK_APPROVE",
                "dir001",
                "\"王\"\"小明\"",
                "", "", "", "", "", "", "",          // operatorSource … eventId（7 欄）
                "\"line1,\"\"quoted\"\"\r\nline2\"",
                "", "", "", "", "", "")              // previousState … previousHash（6 欄）
                + "\r\n";

        assertThat(line).isEqualTo(expected);

        String withoutCrlf = line.substring(0, line.length() - 2);
        assertThat(countFields(withoutCrlf))
                .as("資料列欄數必須與表頭一致，否則匯入端整列錯位")
                .isEqualTo(AuditCsvWriter.HEADER.size());
    }

    /** 以 RFC 4180 的規則數欄位（引號內的逗號不算分隔符）。 */
    private static int countFields(String line) {
        int fields = 1;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields++;
            }
        }
        return fields;
    }
}
