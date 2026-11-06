package com.bpm.core.external.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** Stable public error vocabulary. Internal exception messages never cross this boundary. */
public enum CaseError {
    SERVICE_AUTH_INVALID(401, "服務憑證無效"),
    SERVICE_FORBIDDEN(403, "服務未獲授權"),
    WORKFLOW_NOT_ALLOWED(403, "流程不在服務授權範圍"),
    NOT_FOUND(404, "查無可存取的案件"),
    VALIDATION_ERROR(400, "請求不符合 v1 契約"),
    PAYLOAD_TOO_LARGE(413, "請求超過 1 MiB"),
    DEFINITION_VERSION_MISMATCH(409, "流程綁定版本不符或已停用"),
    FORM_VERSION_MISMATCH(409, "主表綁定版本不符"),
    FORM_VALIDATION_FAILED(422, "資料未通過鎖定表單驗證"),
    IDEMPOTENCY_CONFLICT(409, "相同請求識別已使用不同內容"),
    CASE_ALREADY_STARTED(409, "案件已保留投遞紀錄"),
    RATE_LIMITED(429, "服務呼叫達限制"),
    IDENTITY_UNAVAILABLE(503, "無法確認身分"),
    AUTHZ_UNAVAILABLE(503, "無法確認權限"),
    ROUTING_UNAVAILABLE(503, "無合格內勤受理路徑"),
    INTEGRATION_NOT_CONFIGURED(503, "尚未完成服務端設定"),
    UPSTREAM_UNAVAILABLE(503, "上游來源無法使用"),
    OPERATION_UNCERTAIN(500, "處理結果尚待對帳"),
    INTERNAL_ERROR(500, "案件查詢失敗");

    public final int status;
    final String message;
    CaseError(int status, String message) {
        this.status = status;
        this.message = message;
    }
    String code() {
        return "BPM_" + name();
    }
    Failure fail() {
        return new Failure(this);
    }
    static class Failure extends RuntimeException {
        final CaseError error;
        Failure(CaseError error) {
            super(error.code());
            this.error = error;
        }
    }
    public static String trace(HttpServletRequest req) {
        if (req.getAttribute("caseTrace") instanceof String trace)
            return trace;
        String trace = req.getHeader("X-Trace-Id");
        if (trace == null || !trace.matches("[A-Za-z0-9._-]{1,128}"))
            trace = UUID.randomUUID().toString();
        req.setAttribute("caseTrace", trace);
        return trace;
    }
    public record Envelope(
            String code, String message, String traceId, String requestId, boolean retryable, String outcome) {}
    public Envelope envelope(HttpServletRequest req) {
        boolean auth = this == SERVICE_AUTH_INVALID || this == SERVICE_FORBIDDEN;
        String outcome = this == OPERATION_UNCERTAIN    ? "UNKNOWN"
                : auth || "GET".equals(req.getMethod()) ? "NOT_APPLICABLE"
                                                        : "NOT_APPLIED";
        return new Envelope(code(), message, trace(req), (String) req.getAttribute("caseRequestId"),
                this == RATE_LIMITED || status == 503 && this != INTEGRATION_NOT_CONFIGURED, outcome);
    }
}
