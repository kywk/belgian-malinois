package com.bpm.core.audit.service;

import com.bpm.core.audit.model.AuditLog;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * 稽核紀錄 → CSV（RFC 4180）。
 *
 * <h2>為什麼是 RFC 4180 而不是「用逗號接起來」</h2>
 *
 * <p>稽核的 {@code detail} 是 JSON、{@code userAgent} 可能含逗號、
 * 簽核意見可能含換行。用 {@code String.join(",")} 產生出來的檔案，
 * 匯入端會在這些地方多切一欄或整列錯位 —— 而錯位的匯出比沒有匯出更糟：
 * 它看起來是一份完整的報表，實際上把 A 的簽核意見記到 B 的欄位裡。
 *
 * <p>因此凡是含逗號、雙引號、LF 或 CR 的欄位一律用雙引號包住，
 * 欄位內的雙引號加倍。這是 RFC 4180 的規定，也是 Excel／Google Sheets／
 * 大多數 CSV parser 的共同語言。
 *
 * <h2>UTF-8 BOM</h2>
 *
 * <p>檔案開頭寫 UTF-8 BOM（{@code EF BB BF}）。沒有它，Excel 在中文 Windows
 * 上會用 ANSI（CP950）解讀 UTF-8 位元組 —— 中文姓名與簽核意見變成亂碼。
 * 這是「匯出給人看」與「匯出給程式吃」的差別；本端點的用途是前者。
 *
 * <h2>⚠️ 刻意不做 formula injection 前置處理</h2>
 *
 * <p>有些實作會把 {@code =}、{@code +}、{@code -}、{@code @} 開頭的欄位
 * 前面加一個單引號，避免 Excel 把欄位當公式執行。本檔<b>不做</b>：
 * 那會讓匯出內容與稽核庫的原始值不同，而稽核匯出的價值就在逐字一致。
 * 若日後要防，應該是「匯入端」或另開一個明確標示的防護模式，
 * 不是靜默改寫資料。此為待裁決事項（見 #40 報告）。
 *
 * <h2>欄位順序穩定</h2>
 *
 * <p>{@link #HEADER} 的順序即資料列順序，兩者都由同一份清單驅動，
 * 不會有「表頭與資料對不上」的情況。順序一經發布就不可重排 ——
 * 匯入端與既有的自動化會依賴它。
 */
public final class AuditCsvWriter {

    /** UTF-8 BOM。Excel 需要它才會把檔案當 UTF-8 解讀。 */
    public static final String BOM = "\uFEFF";

    /** RFC 4180 規定的列分隔符（CRLF），不是 {@code \n}。 */
    public static final String CRLF = "\r\n";

    /**
     * 欄位順序：與稽核實體一一對應。
     *
     * <p>{@code eventId} 是投遞層的冪等鍵（見 AuditLog 的註解），
     * 不是稽核內容；仍然匯出，因為匯出是一份完整快照，
     * 排除欄位會讓「這筆為什麼重複」少一條線索。
     */
    public static final List<String> HEADER = List.of(
            "id",
            "createdAt",
            "operationType",
            "operatorId",
            "operatorName",
            "operatorSource",
            "processDefinitionKey",
            "processInstanceId",
            "taskId",
            "businessKey",
            "traceId",
            "eventId",
            "detail",
            "previousState",
            "newState",
            "ipAddress",
            "userAgent",
            "hashValue",
            "previousHash");

    private AuditCsvWriter() {
    }

    /** 寫出 BOM + 表頭 + CRLF。整個匯出檔只呼叫一次。 */
    public static void writeHeader(Writer writer) throws IOException {
        writer.write(BOM);
        writer.write(String.join(",", HEADER));
        writer.write(CRLF);
    }

    /** 寫出一筆資料列 + CRLF。 */
    public static void writeRow(Writer writer, AuditLog log) throws IOException {
        writer.write(toCsvLine(log));
        writer.write(CRLF);
    }

    /** 依 {@link #HEADER} 的順序把一筆紀錄轉成 CSV 列（不含列分隔符）。 */
    static String toCsvLine(AuditLog log) {
        List<String> values = List.of(
                log.getId() == null ? "" : log.getId().toString(),
                log.getCreatedAt() == null ? "" : log.getCreatedAt().toString(),
                log.getOperationType() == null ? "" : log.getOperationType().name(),
                nullSafe(log.getOperatorId()),
                nullSafe(log.getOperatorName()),
                nullSafe(log.getOperatorSource()),
                nullSafe(log.getProcessDefinitionKey()),
                nullSafe(log.getProcessInstanceId()),
                nullSafe(log.getTaskId()),
                nullSafe(log.getBusinessKey()),
                nullSafe(log.getTraceId()),
                nullSafe(log.getEventId()),
                nullSafe(log.getDetail()),
                nullSafe(log.getPreviousState()),
                nullSafe(log.getNewState()),
                nullSafe(log.getIpAddress()),
                nullSafe(log.getUserAgent()),
                nullSafe(log.getHashValue()),
                nullSafe(log.getPreviousHash()));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values.get(i)));
        }
        return sb.toString();
    }

    /**
     * RFC 4180 逃逸：需要時才加引號，引號內部的雙引號加倍。
     *
     * <p>不需要引號時<b>不加</b>：讓一般欄位保持可讀，也讓「有沒有逃逸」
     * 這件事在檔案裡看得出來（有問題的值一定是帶引號的那幾個）。
     */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append('"');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
