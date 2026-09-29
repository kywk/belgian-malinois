package com.bpm.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 授權矩陣與認證鏈。
 *
 * <h2>改動前的狀態</h2>
 *
 * <p>全 repo 沒有任何 {@code SecurityFilterChain} 或 {@code @EnableWebSecurity}。
 * 身分就是呼叫端自帶的 {@code X-User-Id} 標頭 —— 填什麼就是什麼。
 *
 * <p>這使得 P0／P1／P2 修好的每一項授權檢查在真實環境都建立在假前提上：
 * 檢查邏輯正確，但輸入可以偽造。舉例：P2-4 剛修好「外部系統的授權變更要稽核」，
 * 但稽核裡的 operatorId 也來自同一個可偽造的標頭。
 *
 * <h2>兩條認證路徑</h2>
 *
 * <ul>
 *   <li>使用者 → JWT（{@code Authorization: Bearer}）。本服務<b>只驗證</b>，
 *       不簽發、不處理 OIDC 流程 —— 那由另一個服務負責。</li>
 *   <li>Server 之間 → {@link GatewayAuthenticationFilter}。</li>
 *   <li>外部系統的 {@code /api/external/**} 另有 API key 過濾器
 *       （{@code ExternalApiAuthFilter}）。那一層管的是「哪個系統、可做什麼」，
 *       與閘道的網路層信任<b>並存</b>而非互斥。</li>
 * </ul>
 *
 * <h2>授權矩陣的設計原則</h2>
 *
 * <p><b>預設拒絕。</b>最後一條規則是 {@code anyRequest().denyAll()}，
 * 不是 {@code authenticated()}。原因：新增端點時，漏掉規則的結果是
 * 「這個端點不能用」（開發時立刻發現）而不是「任何登入者都能用」
 * （上線後才發現）。這比方便重要。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 可檢視稽核紀錄的權限碼。政策決策：由權限中心指派（2026-09-29）。 */
    private static final String AUDIT_READ = AuthorityResolver.PERM_AUDIT_READ;
    private static final String ADMIN = "ADMIN";

    private final AuthorityResolver authorityResolver;

    public SecurityConfig(AuthorityResolver authorityResolver) {
        this.authorityResolver = authorityResolver;
    }

    @Bean
    GatewayAuthenticationFilter gatewayAuthenticationFilter(
            @Value("${bpm.security.gateway.enabled:false}") boolean enabled,
            @Value("${bpm.security.gateway.shared-secret:}") String sharedSecret,
            @Value("${bpm.security.gateway.trusted-proxies:}") List<String> trustedProxies) {
        return new GatewayAuthenticationFilter(enabled, sharedSecret, trustedProxies, authorityResolver);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    GatewayAuthenticationFilter gatewayFilter,
                                    @Value("${bpm.external.mock-enabled:false}") boolean mockEnabled)
            throws Exception {

        http
                // REST API 不用 session，身分每次請求自帶。
                // STATELESS 同時讓 CSRF 失去攻擊面（沒有 cookie 可被瀏覽器自動附帶）。
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF 保護針對的是「瀏覽器自動附帶憑證」的模型。Bearer token 與
                // 閘道密鑰都必須由呼叫端明確附上，第三方網站無法代為附加，
                // 所以 CSRF token 在此沒有作用，只會讓所有呼叫端多一次往返。
                .csrf(csrf -> csrf.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterBefore(gatewayFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> {

                    // ── 公開：容器健康檢查 ──────────────────────────
                    // docker compose 的 healthcheck 沒有身分可帶。
                    // 只開放 health 與 info，metrics 等端點未開放（見 P2-3 的觀察）。
                    auth.requestMatchers("/actuator/health", "/actuator/health/**",
                            "/actuator/info").permitAll();

                    // ── 開發用 mock 組織／權限系統 ──────────────────
                    // OrgRestClient 會對「自己」發 HTTP 取組織資料，那條呼叫
                    // 沒有身分可帶（它發生在 BPMN 運算式求值中）。
                    //
                    // 只在 mock 開啟時放行。prod profile 的 mock-enabled=false，
                    // 且 ExternalSystemUrlValidator 會拒絕「關閉 mock 卻仍指向
                    // /mock/」的設定組合 —— 所以 prod 不存在這個放行。
                    if (mockEnabled) {
                        auth.requestMatchers("/mock/**").permitAll();
                    }

                    // ── 外部系統 API：由 ExternalApiAuthFilter 驗 API key ──
                    // 這裡放行是因為認證發生在那個過濾器裡，而它管的是
                    // 「哪個系統、可做什麼」（allowedActions/allowedProcessKeys）。
                    auth.requestMatchers("/api/external/**").permitAll();

                    // ── Server 之間：只接受閘道認證過的呼叫 ─────────
                    // 快取失效是由組織／權限系統推送異動時觸發的，
                    // 呼叫方是機器不是人，所以要求 ROLE_GATEWAY 而非使用者身分。
                    auth.requestMatchers("/api/internal/**").hasRole("GATEWAY");

                    // ── 稽核查詢：政策決策的落點 ────────────────────
                    // 稽核庫有全公司的請假、採購、核決金額與簽核意見。
                    // 權責由權限中心以 audit:log:read 指派（2026-09-29 決策）。
                    // ADMIN（權限中心的通配持有者）一併接受。
                    auth.requestMatchers("/api/audit-logs/**")
                            .access((authentication, ctx) ->
                                    new org.springframework.security.authorization.AuthorizationDecision(
                                            hasAuthority(authentication.get(), AUDIT_READ)
                                                    || hasRole(authentication.get(), ADMIN)));

                    // ── 部署 BPMN：平台上最有後果的單一操作 ──────────
                    // 它決定所有後續案件的簽核路徑要送給誰，而且是覆寫式的。
                    auth.requestMatchers(HttpMethod.POST, "/api/deployments/**").hasRole(ADMIN);

                    // ── 管理端點 ───────────────────────────────────
                    // /api/admin/external-systems 管的是外部系統的 API key 與
                    // allowedProcessKeys —— 誰能從外部發起哪些流程。
                    // 通知設定是一條隱藏簽核活動的路徑（見 P2-4）。
                    auth.requestMatchers("/api/admin/**").hasRole(ADMIN);

                    // ── BPMN lint：設計器的即時檢查 ─────────────────
                    // 需要登入。它是任意 XML 的解析入口（見 P2-5），
                    // 不該對未認證的呼叫開放。
                    auth.requestMatchers("/api/bpmn/**").authenticated();

                    // ── 其餘業務 API：需要登入 ─────────────────────
                    // 個案層級的權限（誰能簽這張單）由 controller 內的
                    // requireParticipant 等檢查負責 —— 那是資料層的授權，
                    // 無法只靠 URL 表達。
                    auth.requestMatchers("/api/**").authenticated();

                    // ── 預設拒絕 ───────────────────────────────────
                    // 見類別註解：漏掉規則要變成「不能用」而非「都能用」。
                    auth.anyRequest().denyAll();
                });

        return http.build();
    }

    /**
     * JWT → authorities。
     *
     * <p>{@code roles} claim 存在時優先採用（IdP 已知道角色，且經過簽章）；
     * 否則回頭查權限中心。這是 2026-09-29 的政策決策。
     *
     * <p>principal 名稱用 {@code sub}，那是 JWT 規範的主體識別 ——
     * 不用自訂 claim，避免不同 IdP 的差異滲進授權邏輯。
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(this::authoritiesFromJwt);
        return converter;
    }

    private Collection<GrantedAuthority> authoritiesFromJwt(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        var fromClaim = authorityResolver.fromJwtRoles(roles);
        if (!fromClaim.isEmpty()) return fromClaim;

        // claim 沒帶角色 → 權限中心是授權的事實來源。
        var out = new ArrayList<>(authorityResolver.fromPermissionCentre(jwt.getSubject()));
        return out;
    }

    private static boolean hasAuthority(
            org.springframework.security.core.Authentication auth, String authority) {
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    private static boolean hasRole(
            org.springframework.security.core.Authentication auth, String role) {
        return hasAuthority(auth, "ROLE_" + role);
    }
}
