package com.bpm.core.external;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public ExternalApiAuthFilter(ExternalSystemRepository repo, AuditEventPublisher auditPublisher,
                                  ObjectMapper objectMapper, ExternalSystemAccessGuard accessGuard) {
        this.repo = repo;
        this.auditPublisher = auditPublisher;
        this.objectMapper = objectMapper;
        this.accessGuard = accessGuard;
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

        String keyHash = ApiKeyUtil.hash(apiKey);
        var optSys = repo.findBySystemIdAndApiKey(systemId, keyHash);
        if (optSys.isEmpty()) {
            reject(response, 401, "Invalid API Key or System ID", systemId, request);
            return;
        }

        ExternalSystem sys = optSys.get();

        // IP 白名單。改動前用 Set.of(split(",")) —— 白名單若有重複 IP 會拋
        // IllegalArgumentException 變成 500，且集合元素未 trim（trim 的是
        // clientIp），因此 "10.0.0.1, 10.0.0.2" 的第二個項目永遠比不中。
        String clientIp = request.getRemoteAddr();
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

        // TODO(R-09): lastUsedAt 目前每個請求寫一次 DB。應改為非同步或以分鐘
        // 為粒度節流，見 docs/plan/2026-09-28-remediation-backlog.md。
        sys.setLastUsedAt(Instant.now());
        repo.save(sys);

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

        return null;
    }

    private void reject(HttpServletResponse response, int status, String reason,
                         String systemId, HttpServletRequest request) throws IOException {
        // Map.of 不接受 null value：getRemoteAddr() 依 Servlet 規範可為 null，
        // 若不處理會讓 reject() 自己拋 NPE，把原本要回的 401/403 蓋成 500。
        String ip = Objects.requireNonNullElse(request.getRemoteAddr(), "unknown");
        auditPublisher.publishDetached(new AuditEvent("EXTERNAL_API_CALL",
                systemId != null ? "system:" + systemId : "unknown",
                null, null,
                Map.of("status", "rejected", "reason", reason,
                        "ip", ip, "uri", request.getRequestURI())));

        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("error", reason, "status", status)));
    }
}
