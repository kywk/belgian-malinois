package com.bpm.core.external;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Component
public class ExternalApiAuthFilter extends OncePerRequestFilter {

    static final String PREFIX = "/api/external/";

    private final ExternalSystemRepository repo;
    private final AuditEventPublisher auditPublisher;
    private final ObjectMapper objectMapper;
    /**
     * 停用／IP／allowedActions 的規則已抽到 {@link ExternalSystemAccessGuard}
     * （工項 #21：回呼端點需要同一份規則）。此處只負責 API key 認證、
     * action 解析與拒絕稽核 —— 順序與訊息與抽共用前完全相同。
     */
    private final ExternalSystemAccessGuard accessGuard;
    /** v2／legacy 雙讀的金鑰驗證（R-25）。見 {@link ApiKeyHasher}。 */
    private final ApiKeyHasher apiKeyHasher;
    /** 認證失敗的節流（R-25）。 */
    private final ExternalAuthThrottle authThrottle;
    /** lastUsedAt 的寫入節流（R-09 效能債，R-25 收）。 */
    private final ExternalSystemUsageTracker usageTracker;

    public ExternalApiAuthFilter(ExternalSystemRepository repo, AuditEventPublisher auditPublisher,
                                  ObjectMapper objectMapper, ExternalSystemAccessGuard accessGuard,
                                  ApiKeyHasher apiKeyHasher, ExternalAuthThrottle authThrottle,
                                  ExternalSystemUsageTracker usageTracker) {
        this.repo = repo;
        this.auditPublisher = auditPublisher;
        this.objectMapper = objectMapper;
        this.accessGuard = accessGuard;
        this.apiKeyHasher = apiKeyHasher;
        this.authThrottle = authThrottle;
        this.usageTracker = usageTracker;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 同時涵蓋不帶尾斜線的 "/api/external"，避免 prefix 檢查的常見破口
        // （目前該 URI 沒有對應 handler，但不應依賴這點）。
        return !uri.startsWith(PREFIX) && !uri.equals("/api/external");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");
        String systemId = request.getHeader("X-System-Id");

        if (apiKey == null || systemId == null) {
            reject(response, 401, "Missing X-API-Key or X-System-Id", systemId, request);
            return;
        }

        String clientIp = request.getRemoteAddr();

        // ── R-25：失敗節流先於任何 DB 查詢 ─────────────────────────────
        //
        // 429 必須比一次驗證便宜，否則節流本身就是放大攻擊的入口。
        // 桶的鍵是（systemId＋IP），見 ExternalAuthThrottle 的類別註解。
        if (authThrottle.isBlocked(systemId, clientIp)) {
            reject(response, 429, "Too many failed authentication attempts", systemId,
                    request, authThrottle.retryAfterSeconds(systemId, clientIp));
            return;
        }

        ExternalSystem sys = repo.findBySystemId(systemId).orElse(null);

        // ── R-25：金鑰驗證（v2／legacy 雙讀）與輪替寬限期 ───────────────
        //
        // 改動前是 findBySystemIdAndApiKey(systemId, sha256(apiKey))：
        // 只有單一格式、也沒有寬限期。現在明文在 Java 端比對，落庫值可能是
        // v2 HMAC 或舊版 SHA-256 —— 由 ApiKeyHasher 看前綴決定演算法。
        // 失效的舊 key 先看 current、再看寬限期內的 previous。
        boolean matchedCurrent = sys != null
                && apiKeyHasher.matches(apiKey, sys.getApiKey());
        boolean matchedPrevious = !matchedCurrent && sys != null
                && previousKeyActive(sys)
                && apiKeyHasher.matches(apiKey, sys.getPreviousApiKey());
        if (!matchedCurrent && !matchedPrevious) {
            authThrottle.recordFailure(systemId, clientIp);
            reject(response, 401, "Invalid API Key or System ID", systemId, request);
            return;
        }
        authThrottle.recordSuccess(systemId, clientIp);

        // 透明升級 legacy 雜湊（R-25 的遷移策略）：第一次成功驗證就把
        // 匹配到的那一把改寫成 v2，DB 不需要明文、運維不需要逐系統輪替。
        // 只升級具體匹配的那一把 —— previous 可能是尚未用過的舊格式，
        // 不能因為 current 驗過就順手改掉它。
        boolean upgraded = false;
        if (matchedCurrent && !ApiKeyUtil.isV2(sys.getApiKey())) {
            sys.setApiKey(apiKeyHasher.hash(apiKey));
            upgraded = true;
        } else if (matchedPrevious && !ApiKeyUtil.isV2(sys.getPreviousApiKey())) {
            sys.setPreviousApiKey(apiKeyHasher.hash(apiKey));
            upgraded = true;
        }

        // IP 白名單。改動前用 Set.of(split(",")) —— 白名單若有重複 IP 會拋
        // IllegalArgumentException 變成 500，且集合元素未 trim（trim 的是
        // clientIp），因此 "10.0.0.1, 10.0.0.2" 的第二個項目永遠比不中。
        // 停用與 IP 的規則在 ExternalSystemAccessGuard（工項 #21 抽共用）——
        // 順序（停用 → IP → 解析 action → allowedActions）與訊息與抽共用前相同。
        var denied = accessGuard.rejectSystemOrIp(sys, clientIp);
        if (denied.isPresent()) {
            reject(response, denied.get().status(), denied.get().reason(), systemId, request);
            return;
        }

        // 由路徑與方法推導 action。未知路徑一律拒絕（fail-closed）——
        // 改動前未匹配的路徑會 fall through 成 "query_status"，等於未知端點
        // 自動取得查詢權限。
        String action = resolveAction(request);
        if (action == null) {
            reject(response, 403, "Unrecognised external API endpoint", systemId, request);
            return;
        }
        denied = accessGuard.rejectAction(sys, action);
        if (denied.isPresent()) {
            reject(response, denied.get().status(), denied.get().reason(), systemId, request);
            return;
        }

        // lastUsedAt 改為分鐘級寫入（R-09 的效能債，R-25 收）；若剛才做了
        // legacy→v2 的金鑰升級，forceWrite 讓那次變更在同一筆 save 落地。
        usageTracker.touch(sys, upgraded);

        request.setAttribute("externalSystem", sys);
        request.setAttribute("externalSystemId", systemId);

        chain.doFilter(request, response);
    }

    /**
     * 以「精確路徑 + 方法」推導 action。
     *
     * <p>改動前是對整個 URI 做 {@code contains()} 子串比對，且未匹配時預設
     * 回傳 {@code "query_status"}。這裡改為列舉實際存在的 5 個端點，
     * 未知組合回傳 {@code null} 由呼叫端拒絕。
     *
     * @return action 名稱，無法辨識時回傳 {@code null}
     */
    String resolveAction(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith(PREFIX)) return null;

        String method = request.getMethod();
        String path = uri.substring(PREFIX.length());
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);

        // POST /api/external/process-instances
        // GET  /api/external/process-instances?businessKey=...
        if (path.equals("process-instances")) {
            if ("POST".equals(method)) return "start_process";
            if ("GET".equals(method)) return "query_status";
            return null;
        }

        // GET /api/external/process-instances/{id}/status
        if ("GET".equals(method)
                && path.startsWith("process-instances/") && path.endsWith("/status")) {
            return "query_status";
        }

        // PUT /api/external/tasks/{taskId}   （不含更深層路徑）
        if ("PUT".equals(method) && path.startsWith("tasks/")
                && path.indexOf('/', "tasks/".length()) < 0) {
            return "complete_task";
        }

        // GET /api/external/process-definitions/{key}/variable-spec
        if ("GET".equals(method)
                && path.startsWith("process-definitions/") && path.endsWith("/variable-spec")) {
            return "query_status";
        }

        // ── 工項 #22：External Worker Task（輪詢認領）────────────────────
        //
        // 整個 /api/external/worker/** 介面共用<b>一個</b> allowedActions 項目
        // {@code external_worker}（使用者 2026-10-03 裁決）。不拆成
        // acquire／complete／fail 三個 action 的原因：三者是同一個工作循環，
        // 只授權其中兩個等於讓 worker 認領得到任務卻永遠交不回去。
        //
        // ⚠️ 白名單項目是 {@code external_worker}，與稽核 detail 的
        // {@code external_worker_acquire} 等操作名<b>不同</b> —— 後者只描述
        // 「做了什麼」，不是授權單位。兩者若混用，管理員會以為要填
        // {@code external_worker_acquire} 才能呼叫，而實際授權比對是精確比對
        // （見 ExternalSystemPolicy），填錯就是 403。
        //
        // 這一段只列舉實際存在的端點，未知的 worker 路徑回 null → 呼叫端
        // 統一以 403 拒絕（fail-closed，與本方法其他分支相同）。
        //
        // POST /api/external/worker/tasks/acquire
        if ("POST".equals(method) && path.equals("worker/tasks/acquire")) {
            return "external_worker";
        }

        // GET /api/external/worker/tasks?topic=...
        if ("GET".equals(method) && path.equals("worker/tasks")) {
            return "external_worker";
        }

        // POST /api/external/worker/tasks/{jobId}/complete
        // POST /api/external/worker/tasks/{jobId}/fail
        // POST /api/external/worker/tasks/{jobId}/unacquire
        if ("POST".equals(method) && path.startsWith("worker/tasks/")
                && (path.endsWith("/complete") || path.endsWith("/fail")
                        || path.endsWith("/unacquire"))) {
            return "external_worker";
        }

        return null;
    }

    /**
     * {@code previousApiKey} 是否仍在寬限期內（R-25）。
     *
     * <p>到期時間或金鑰任一為 null 都不算有效 —— 缺到期時間的 previous
     * 沒有任何「可以相信它到什麼時候」的依據，不能拿來放行。
     */
    private static boolean previousKeyActive(ExternalSystem sys) {
        return sys.getPreviousApiKey() != null
                && sys.getPreviousApiKeyExpiresAt() != null
                && sys.getPreviousApiKeyExpiresAt().isAfter(Instant.now());
    }

    private void reject(HttpServletResponse response, int status, String reason,
                         String systemId, HttpServletRequest request) throws IOException {
        reject(response, status, reason, systemId, request, 0);
    }

    private void reject(HttpServletResponse response, int status, String reason,
                         String systemId, HttpServletRequest request,
                         long retryAfterSeconds) throws IOException {
        // Map.of 不接受 null value：getRemoteAddr() 依 Servlet 規範可為 null，
        // 若不處理會讓 reject() 自己拋 NPE，把原本要回的 401/403 蓋成 500。
        String ip = Objects.requireNonNullElse(request.getRemoteAddr(), "unknown");
        // 節流的拒絕也留稽核：429 的出現本身就是「有人在猜金鑰」的訊號，
        // 而失敗的猜測已經不再逐筆寫入（超過門檻後每筆都被 429 取代），
        // 少了這筆就完全看不到節流正在生效。
        auditPublisher.publishDetached(new AuditEvent("EXTERNAL_API_CALL",
                systemId != null ? "system:" + systemId : "unknown",
                null, null,
                Map.of("status", "rejected", "reason", reason,
                        "ip", ip, "uri", request.getRequestURI())));

        response.setStatus(status);
        response.setContentType("application/json");
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        }
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("error", reason, "status", status)));
    }
}
