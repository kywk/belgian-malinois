package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認證機制本身（R-01）。
 *
 * <h2>改動前的狀態</h2>
 *
 * <p>全 repo 沒有任何 {@code SecurityFilterChain}。身分就是呼叫端自帶的
 * {@code X-User-Id} 標頭 —— 填什麼就是什麼。
 *
 * <p>這使得 P0／P1／P2 修好的每一項授權檢查在真實環境都建立在假前提上：
 * 檢查邏輯正確，但輸入可以偽造。P2-4 剛讓「外部系統的授權變更」留下
 * operatorId，而那個 operatorId 也來自同一個可偽造的標頭。
 *
 * <h2>這組測試守的是兩條認證路徑的邊界</h2>
 *
 * <p>斷言「授權規則寫對了」是不夠的 —— 如果認證本身可以繞過，
 * 授權規則檢查的就是攻擊者自選的身分。所以這裡測的是：
 * 偽造、過期、錯簽的 token 會被拒；沒有閘道密鑰的標頭不構成身分。
 */
class AuthenticationTest extends IntegrationTestBase {

    @Value("${bpm.security.jwt.dev-secret}")
    private String devSecret;

    /** 用與驗證端完全相同的密鑰簽出 token —— 測到真正的驗證路徑，而不是繞過它。 */
    private String signToken(String subject, List<String> roles, Instant issuedAt, Instant expiresAt)
            throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt));
        if (roles != null) claims.claim("roles", roles);

        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(devSecret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private String validToken(String subject, List<String> roles) throws Exception {
        Instant now = Instant.now();
        return signToken(subject, roles, now, now.plusSeconds(600));
    }

    /**
     * 不帶任何預設身分的請求。
     *
     * <p>IntegrationTestBase 以 defaultRequest 附上閘道密鑰與預設使用者，
     * 所以要測「未認證」必須明確覆蓋掉它們 ——
     * 否則測試會在有身分的情況下通過，斷言完全是空的。
     */
    private static MockHttpServletRequestBuilder anonymous(String path) {
        return get(path)
                .header(GatewayAuthenticationFilter.SECRET_HEADER, "")
                .header(GatewayAuthenticationFilter.USER_HEADER, "");
    }

    // ── 未認證 ────────────────────────────────────────────────────

    @Test
    @DisplayName("完全沒有身分時業務端點必須回 401")
    void anonymousRequestsAreRejected() throws Exception {
        mockMvc.perform(anonymous("/api/tasks")).andExpect(status().isUnauthorized());
        mockMvc.perform(anonymous("/api/process-instances")).andExpect(status().isUnauthorized());
        mockMvc.perform(anonymous("/api/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("健康檢查必須公開 —— 容器的 healthcheck 沒有身分可帶")
    void healthEndpointStaysPublic() throws Exception {
        // 斷言「不是 401/403」而不是 200：測試環境刻意把 mail 指向不存在的
        // port，所以 health 的彙總狀態是 DOWN（503）。
        // 這個測試要驗的是「有沒有被認證層擋下」，不是服務健不健康 ——
        // 用 200 會讓它變成一個隨環境浮動的測試。
        int status = mockMvc.perform(anonymous("/actuator/health"))
                .andReturn().getResponse().getStatus();
        assertThat(status)
                .as("健康檢查被認證層擋下了 —— docker 的 healthcheck 會永遠失敗")
                .isNotIn(401, 403);
    }

    @Test
    @DisplayName("未定義的路徑必須拒絕，而不是預設放行")
    void unmatchedPathsAreDenied() throws Exception {
        // 授權矩陣最後一條是 denyAll() 而非 authenticated()。
        // 新增端點時漏掉規則的結果要是「不能用」而非「任何登入者都能用」。
        mockMvc.perform(get("/some/new/endpoint").header("X-User-Id", "admin001"))
                .andExpect(status().isForbidden());
    }

    // ── JWT ──────────────────────────────────────────────────────

    @Test
    @DisplayName("合法簽章的 JWT 必須被接受，sub 成為身分")
    void validJwtIsAccepted() throws Exception {
        mockMvc.perform(anonymous("/api/tasks")
                        .header("Authorization", "Bearer " + validToken("user001", null)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("簽章錯誤的 JWT 必須被拒 —— 否則任何人都能自己造身分")
    void tokenWithWrongSignatureIsRejected() throws Exception {
        // 未簽章的 token：header.payload 後面接一個空簽章。
        // 這是 alg:none 攻擊的基本形式 —— 直接手組字串，因為 SignedJWT
        // 在未簽章狀態下不允許 serialize()。
        var enc = java.util.Base64.getUrlEncoder().withoutPadding();
        String unsigned = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + enc.encodeToString(
                        ("{\"sub\":\"admin001\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}")
                                .getBytes(StandardCharsets.UTF_8))
                + ".";
        mockMvc.perform(anonymous("/api/tasks").header("Authorization", "Bearer " + unsigned))
                .andExpect(status().isUnauthorized());

        // 用錯誤密鑰簽的 token
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder().subject("admin001")
                        .expirationTime(Date.from(Instant.now().plusSeconds(600))).build());
        jwt.sign(new MACSigner("a-completely-different-secret-32-bytes!".getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(anonymous("/api/tasks").header("Authorization", "Bearer " + jwt.serialize()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("過期的 JWT 必須被拒")
    void expiredTokenIsRejected() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        String expired = signToken("admin001", List.of("admin"), past, past.plusSeconds(60));

        mockMvc.perform(anonymous("/api/audit-logs")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("roles claim 存在時優先採用（政策決策 2026-09-29）")
    void rolesClaimTakesPriorityOverPermissionCentre() throws Exception {
        // user001 在權限中心沒有任何權限，但 IdP 說他是 admin。
        // claim 經過簽章，所以它是權威來源。
        mockMvc.perform(anonymous("/api/admin/external-systems")
                        .header("Authorization", "Bearer " + validToken("user001", List.of("admin"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("roles claim 不存在時回頭查權限中心")
    void permissionCentreIsUsedWhenClaimIsAbsent() throws Exception {
        // dir001 持有 audit:log:read（權限中心指派），token 不帶 roles。
        mockMvc.perform(anonymous("/api/audit-logs")
                        .header("Authorization", "Bearer " + validToken("dir001", null))
                        .param("size", "5"))
                .andExpect(status().isOk());

        // user001 沒有該權限 —— 同一條路徑必須拒絕，否則上一個斷言是空的。
        mockMvc.perform(anonymous("/api/audit-logs")
                        .header("Authorization", "Bearer " + validToken("user001", null))
                        .param("size", "5"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("權限中心的通配 * 必須轉成 ADMIN，不可當成字面 authority")
    void wildcardPermissionBecomesAdminRole() throws Exception {
        // admin001 的權限是 ["*"]。照字面當 authority 會產生一個名叫 * 的
        // authority，而 hasRole('ADMIN') 不會命中 —— 結果是「擁有全部權限的
        // 管理員被拒絕存取」。這種 bug 只影響通配持有者，
        // 而開發時通常就是用管理員在測，所以特別容易漏。
        mockMvc.perform(anonymous("/api/admin/external-systems")
                        .header("Authorization", "Bearer " + validToken("admin001", null)))
                .andExpect(status().isOk());
    }

    // ── 信任閘道 ──────────────────────────────────────────────────

    @Test
    @DisplayName("沒有閘道密鑰的 X-User-Id 不構成身分")
    void userHeaderWithoutGatewaySecretIsNotAnIdentity() throws Exception {
        // 這是本次修復的核心：改動前這個請求會被當成 admin001。
        mockMvc.perform(get("/api/audit-logs")
                        .header(GatewayAuthenticationFilter.SECRET_HEADER, "")
                        .header("X-User-Id", "admin001")
                        .param("size", "5"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("閘道密鑰錯誤時 X-User-Id 也不構成身分")
    void wrongGatewaySecretIsNotAnIdentity() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header(GatewayAuthenticationFilter.SECRET_HEADER, "wrong-secret")
                        .header("X-User-Id", "admin001")
                        .param("size", "5"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("閘道認證的呼叫者必須取得該身分在權限中心的權限")
    void gatewayCallerGetsPermissionCentreAuthorities() throws Exception {
        // 只給 ROLE_GATEWAY 是不夠的：閘道說「這是 dir001」時，
        // dir001 就該擁有 dir001 的權限，否則 server 之間的路徑
        // 對需要權限的端點一律 403。
        mockMvc.perform(get("/api/audit-logs")
                        .header("X-User-Id", "dir001")
                        .param("size", "5"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/api/internal/** 只接受閘道認證，JWT 使用者不得存取")
    void internalEndpointsRequireGatewayAuthentication() throws Exception {
        // 快取失效由組織／權限系統推送異動時觸發，呼叫方是機器不是人。
        // 即使是管理員的 JWT 也不該走這條路 —— 它不是給人用的介面。
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/internal/cache-invalidate/org")
                        .header(GatewayAuthenticationFilter.SECRET_HEADER, "")
                        .header(GatewayAuthenticationFilter.USER_HEADER, "")
                        .header("Authorization", "Bearer " + validToken("admin001", List.of("admin")))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"all\",\"userIds\":[\"user001\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("閘道認證的呼叫可以用 /api/internal/**")
    void gatewayCallersCanUseInternalEndpoints() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/internal/cache-invalidate/org")
                        .header("X-User-Id", "org-sync-service")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"all\",\"userIds\":[\"user001\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("JWT 身分優先於閘道注入的標頭")
    void jwtIdentityWinsOverGatewayHeader() throws Exception {
        // 一個能碰到閘道密鑰的人不該能冒用任何人 —— 帶了 Bearer token 時，
        // token 的 sub 才是身分，X-User-Id 被忽略。
        // JWT 的 sub（user001，無稽核權）必須勝過閘道標頭（dir001，有稽核權）。
        // 若閘道標頭贏了，這裡會是 200。
        mockMvc.perform(get("/api/audit-logs")
                        .header("X-User-Id", "dir001")   // 閘道說是 dir001
                        .header("Authorization", "Bearer " + validToken("user001", null))
                        .param("size", "5"))
                .andExpect(status().isForbidden());
    }

    // ── 稽核的 operatorId 不再可偽造 ──────────────────────────────

    @Test
    @DisplayName("稽核的 operatorId 來自已認證身分，不是請求標頭")
    void auditOperatorComesFromAuthenticatedIdentity() throws Exception {
        truncateAuditLog();

        // 帶 JWT（user001）同時帶一個矛盾的 X-User-Id（admin001）。
        // 稽核必須記 user001 —— 否則 P2-4 補上的 operatorId 仍是可偽造的。
        mockMvc.perform(get("/api/audit-logs")
                        .header("X-User-Id", "admin001")
                        .header("Authorization", "Bearer " + validToken("dir001", null))
                        .param("size", "5"))
                .andExpect(status().isOk());

        long deadline = System.currentTimeMillis() + 10_000;
        String operator = null;
        while (System.currentTimeMillis() < deadline && operator == null) {
            operator = firstAuditOperator();
            if (operator == null) Thread.sleep(100);
        }
        assertThat(operator)
                .as("稽核記下的是請求標頭而非已認證身分 —— operatorId 仍可偽造")
                .isEqualTo("dir001");
    }

    private static String firstAuditOperator() {
        var out = new java.util.concurrent.atomic.AtomicReference<String>();
        withAuditConnection(c -> {
            try (var st = c.createStatement();
                 var rs = st.executeQuery(
                         "SELECT TOP 1 operator_id FROM bpm_audit_log "
                                 + "WHERE operation_type = 'DATA_ACCESS' ORDER BY id DESC")) {
                if (rs.next()) out.set(rs.getString(1));
            }
        });
        return out.get();
    }
}
