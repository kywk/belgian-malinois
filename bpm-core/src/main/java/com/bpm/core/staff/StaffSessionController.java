package com.bpm.core.staff;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "bpm.staff.enabled", havingValue = "true")
public class StaffSessionController {
    private final StaffIntegrationProperties properties;
    private final RestClient saml;
    private final JwtDecoder decoder;
    public StaffSessionController(StaffIntegrationProperties properties, JwtDecoder decoder) {
        this.properties = properties;
        this.saml = StaffIntegrationConfig.client(properties.samlUrl(), null);
        this.decoder = decoder;
    }
    @GetMapping("/api/me/session")
    public ResponseEntity<Session> session() {
        var auth = StaffAuthentication.current();
        if (auth == null) throw new StaffFailure("BPM_AUTH_INVALID", 401, "請使用內勤 SSO 登入");
        var staff = auth.staff();
        return noStore(new Session(staff.userId(), staff.displayName(), "STAFF", staff.deptId(),
                auth.permissions(), auth.getToken().getExpiresAt(), auth.checkedAt()));
    }
    @GetMapping("/api/auth/staff/config")
    public ResponseEntity<Map<String, String>> config() {
        return noStore(Map.of("loginUrl", properties.samlUrl() + "/saml/v1/get-jwt",
                "callbackUrl", properties.callbackUrl()));
    }
    @PostMapping("/api/auth/staff/token")
    public ResponseEntity<Map<String, String>> exchange(@RequestBody Exchange request) {
        if (request.code() == null || !request.code().matches("[A-Za-z0-9_-]{16,256}"))
            throw new StaffFailure("BPM_AUTH_INVALID", 401, "SSO 交換碼無效，請重新登入");
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("code", request.code());
            var response = saml.post().uri("/saml/v1/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(tools.jackson.databind.JsonNode.class);
            if (response == null || !"0000".equals(response.path("code").asString())
                    || !response.path("data").path("token").isString())
                throw new StaffFailure("BPM_AUTH_INVALID", 401, "SSO 交換碼無效或已使用，請重新登入");
            String token = response.path("data").path("token").asString();
            decoder.decode(token);
            return noStore(Map.of("token", token));
        } catch (StaffFailure e) { throw e; }
        catch (org.springframework.security.oauth2.jwt.JwtException e) {
            throw new StaffFailure("BPM_AUTH_INVALID", 401, "SSO 憑證不符合內勤登入設定");
        } catch (RuntimeException e) {
            throw new StaffFailure("BPM_UPSTREAM_UNAVAILABLE", 503, "SSO 服務暫時無法連線，請重新登入");
        }
    }
    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
    public record Exchange(String code) {}
    public record Session(String userId, String displayName, String userType, String deptId,
            List<String> permissions, Instant expiresAt, Instant authorizationCheckedAt) {}
}
