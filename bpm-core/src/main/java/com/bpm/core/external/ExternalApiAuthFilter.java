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
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

@Component
public class ExternalApiAuthFilter extends OncePerRequestFilter {

    private final ExternalSystemRepository repo;
    private final AuditEventPublisher auditPublisher;
    private final ObjectMapper objectMapper;

    public ExternalApiAuthFilter(ExternalSystemRepository repo, AuditEventPublisher auditPublisher,
                                  ObjectMapper objectMapper) {
        this.repo = repo;
        this.auditPublisher = auditPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/external/");
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

        if (!Boolean.TRUE.equals(sys.getEnabled())) {
            reject(response, 403, "System is disabled", systemId, request);
            return;
        }

        // IP whitelist check
        if (sys.getIpWhitelist() != null && !sys.getIpWhitelist().isBlank()) {
            Set<String> allowed = Set.of(sys.getIpWhitelist().split(","));
            String clientIp = request.getRemoteAddr();
            if (!allowed.contains(clientIp.trim())) {
                reject(response, 403, "IP not in whitelist: " + clientIp, systemId, request);
                return;
            }
        }

        // Action check based on URI pattern
        String action = resolveAction(request);
        if (sys.getAllowedActions() != null && !sys.getAllowedActions().contains(action)) {
            reject(response, 403, "Action not allowed: " + action, systemId, request);
            return;
        }

        // Update lastUsedAt
        sys.setLastUsedAt(Instant.now());
        repo.save(sys);

        // Store system info in request for downstream use
        request.setAttribute("externalSystem", sys);
        request.setAttribute("externalSystemId", systemId);

        chain.doFilter(request, response);
    }

    private String resolveAction(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if (uri.contains("/process-instances") && "POST".equals(method)) return "start_process";
        if (uri.contains("/tasks/") && "PUT".equals(method)) return "complete_task";
        if (uri.contains("/process-instances") && "GET".equals(method)) return "query_status";
        if (uri.contains("/callback")) return "callback";
        return "query_status";
    }

    private void reject(HttpServletResponse response, int status, String reason,
                         String systemId, HttpServletRequest request) throws IOException {
        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL",
                systemId != null ? "system:" + systemId : "unknown",
                null, null,
                Map.of("status", "rejected", "reason", reason,
                        "ip", request.getRemoteAddr(), "uri", request.getRequestURI())));

        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("error", reason, "status", status)));
    }
}
