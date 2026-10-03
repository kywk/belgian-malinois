package com.bpm.core.security;

import jakarta.servlet.DispatcherType;
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
 *
 * <p>⚠️ <b>但那條 {@code denyAll()} 本身不是行為的承重結構</b>
 * （2026-09-29 實測發現，先前版本的這段註解把因果講得比實際稍強）：
 * 把它<b>整行刪掉</b>之後，{@code AuthenticationTest} 的 16 個測試
 * 仍然全綠，包含 {@code unmatchedPathsAreDenied}。
 *
 * <p>原因是 Spring Security 6 的 {@code RequestMatcherDelegatingAuthorizationManager}
 * 在<b>沒有任何規則匹配時本來就 abstain → deny</b>。所以：
 * <ul>
 *   <li><b>安全結論不變</b> —— 漏掉規則仍然是「不能用」而非「都能用」，
 *       上面那段設計原則依然是這個類別存在的理由。</li>
 *   <li>但真正提供這個保險的是框架的 abstain 行為，<b>不是這一行</b>。
 *       這行是<b>意圖標記</b>：讓讀程式碼的人一眼看到「這裡預設是拒絕」。</li>
 *   <li>⚠️ <b>沒有任何測試能區分這兩種情況</b> —— 寫一個斷言
 *       「授權矩陣最後一條是 denyAll」的測試只會鎖定實作細節，
 *       而且在框架行為改變時會變成假綠。所以這個意圖只能靠文件維持；
 *       動到這段時請一併評估。</li>
 * </ul>
 *
 * <h2>BPM 目前使用的權限碼（新增權限碼時記在這裡）</h2>
 *
 * <table border="1">
 *   <caption>權限碼與它的規則</caption>
 *   <tr><th>權限碼</th><th>保護的端點</th><th>接受 ROLE_ADMIN？</th></tr>
 *   <tr><td>{@code audit:log:read}</td>
 *       <td>{@code /api/audit-logs/**}，以及 ProcessAccessGuard 的讀取旁路
 *           （流程變數、表單資料、附件）</td>
 *       <td><b>否</b> —— 含全公司薪資與簽核意見，見該規則的註解</td></tr>
 *   <tr><td>{@code bpm:form:design}</td>
 *       <td>{@code POST/PUT/DELETE /api/forms/**}（讀維持登入即可）</td>
 *       <td><b>是</b> —— 表單 schema 是設定資產，見該規則的註解</td></tr>
 * </table>
 *
 * <p>兩列刻意不同。相同的字面問題（「管理員可不可以？」）在不同的資料上
 * 有不同的答案，把答案寫在規則旁而不是寫成一個全域的「管理員萬能」，
 * 是為了讓下一次新增端點的人<b>必須重新回答這個問題</b>，而不是繼承預設值。
 *
 * <p>其餘規則用角色表達（{@code hasRole(ADMIN)}）是因為它們本來就該是
 * 「能管系統的人才能做」：部署 BPMN、管理外部系統授權與 API key。
 * 那些操作不讀任何個人資料。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 可檢視稽核紀錄的權限碼。政策決策：由權限中心指派（2026-09-29）。 */
    private static final String AUDIT_READ = AuthorityResolver.PERM_AUDIT_READ;

    /** 可設計表單的權限碼。政策決策：業務人員自行設計流程與表單（2026-09-29）。 */
    private static final String FORM_DESIGN = AuthorityResolver.PERM_FORM_DESIGN;

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

                    // ── 錯誤頁的 ERROR dispatch ─────────────────────
                    // controller 拋出 ResponseStatusException（404／409／400）或
                    // AuditWriteException（503）時，容器會以 ERROR dispatch 轉到 /error
                    // 組回應內容。改動前沒有這條規則 → 那次 dispatch 落到最後的
                    // denyAll() → <b>所有錯誤在線上都變成 403</b>：前端分不出
                    // 「不存在」「衝突」「稽核中斷請重試」與「沒權限」。
                    //
                    // MockMvc 不做 error dispatch，所以整合測試全綠也看不出來 ——
                    // 是對執行中的服務 curl 才發現的（見 ErrorDispatchTest）。
                    //
                    // 放行是安全的：狀態碼在原本那次請求（已經過授權）就決定了，
                    // ERROR dispatch 只負責寫回應內容。直接 GET /error 是 REQUEST
                    // dispatch，不受這條規則影響，仍落到 denyAll()。
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();

                    // ── 公開：容器健康檢查 ──────────────────────────
                    // docker compose 的 healthcheck 沒有身分可帶。
                    // 只開放 health 與 info，metrics 等端點未開放（見 P2-3 的觀察）。
                    auth.requestMatchers("/actuator/health", "/actuator/health/**",
                            "/actuator/info").permitAll();

                    // ── 公開：API 文件（dev/test 用） ───────────────
                    // /swagger-ui.html 是 springdoc 的入口（會轉到
                    // /swagger-ui/index.html），/swagger-ui/** 是它的靜態資源；
                    // /v3/api-docs/** 是 OpenAPI JSON，/v3/api-docs.yaml 是同一份的 YAML。
                    //
                    // 為什麼可以 permitAll：prod 由 application.yml 的 prod 文件
                    // 把 springdoc.api-docs 與 springdoc.swagger-ui 設為
                    // enabled: false —— 那時<b>根本不會註冊這些端點</b>，
                    // permitAll 沒有東西可放行。這是刻意的雙層防護：
                    // 設定層決定 prod 不註冊，授權層只讓 dev/test 進得去。
                    //
                    // ⚠️ 反過來說，若 prod 的 enabled: false 被拿掉，這條規則
                    // 會直接讓 API 文件在 prod 公開。OpenApiProdDisabledTest
                    // 就是那個開關的守門人 —— 動這裡時請一起看它。
                    //
                    // ⚠️ 只放行文件本身，不連帶放寬任何業務路徑。
                    auth.requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml",
                            "/swagger-ui/**", "/swagger-ui.html").permitAll();

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

                    // ── 外部系統回呼：由 CallbackAuthFilter 驗 HMAC 簽章 ──
                    // 與上一條同一個模式：外部系統沒有 JWT，認證發生在專屬
                    // 過濾器裡（X-System-Id + X-Callback-Signature + 時間戳窗），
                    // 授權（allowedActions 的 callback）也在那裡判定。
                    auth.requestMatchers("/api/callback/**").permitAll();

                    // ── Server 之間：只接受閘道認證過的呼叫 ─────────
                    // 快取失效是由組織／權限系統推送異動時觸發的，
                    // 呼叫方是機器不是人，所以要求 ROLE_GATEWAY 而非使用者身分。
                    auth.requestMatchers("/api/internal/**").hasRole("GATEWAY");

                    // ── 稽核查詢：政策決策的落點 ────────────────────
                    //
                    // ⚠️ 這條規則<b>刻意不接受 ROLE_ADMIN</b>，而下面的表單設計
                    // 刻意接受。兩者不同是刻意的，理由如下（延伸自
                    // ProcessAccessGuard.requireReadAccess:120-124 的同一條線）：
                    //
                    //   稽核紀錄含<b>全公司</b>的請假、採購、核決金額與簽核意見，
                    //   而 AuditEvent.detail 會帶整包流程變數
                    //   （見 ExternalApiController.completeTask 的
                    //   Map.of("action", "complete_task", "variables", vars)）。
                    //   「能管理系統」與「能看全公司薪資」是<b>不同權責</b> ——
                    //   ProcessAccessGuard 早就明確拒絕 ROLE_ADMIN 讀案件流程變數，
                    //   若這裡放行，等於留下一條側門：
                    //   用稽核查詢把 requireReadAccess 擋下的東西整批撈出來。
                    //   所以這條不是「限制功能」，而是<b>讓兩處政策一致</b>。
                    //
                    //   反過來說，權限中心的通配持有者（* → ROLE_ADMIN）
                    //   要調閱稽核，權限中心就得明確指派 audit:log:read。
                    //   這是有意的摩擦：它讓「誰看得到全公司資料」永遠是
                    //   權限中心裡一筆看得見的指派，而不是隱含在管理員身分裡。
                    auth.requestMatchers("/api/audit-logs/**").hasAuthority(AUDIT_READ);

                    // ── 表單設計：建立／改／發布／封存／刪除 ──────
                    //
                    // ⚠️ FormDefinitionController 的 @RequestMapping 是
                    // **/api/forms**（不是 /api/admin/forms），所以改動前它落在
                    // 最後的 /api/** → authenticated()：**任何登入者**都能
                    //   POST   /api/forms/{formKey}/revisions  建立 draft
                    //   PUT    /api/forms/{id}                  改 schemaJson
                    //   POST   /api/forms/{id}/publish          發布
                    //   POST   /api/forms/{id}/archive
                    //   DELETE /api/forms/{id}
                    // 而且 GET /api/forms 直接回傳所有 draft 的 id，不用猜 UUID。
                    //
                    // 危害是流程完整性而非資料外洩：spec §8.5 訂明表單欄位 id
                    // 就是流程變數名，所以能改 schema 就能加一個欄位 id 叫
                    // approved —— 送件人在填表時就決定了簽核結果。
                    //
                    // 為什麼是權限碼而不是 ROLE_ADMIN：見
                    // AuthorityResolver.PERM_FORM_DESIGN 的註解 ——
                    // 綁 ADMIN 會擋掉「業務人員自行設計流程」這個產品目標本身。
                    //
                    // 為什麼這裡<b>接受</b> ROLE_ADMIN（與稽核那條相反）：
                    //   1. 表單 schema 是<b>設定資產</b>，不是個人資料。把
                    //      「能不能改系統的設定行為」納入系統管理的職責是合理的，
                    //      而稽核紀錄裡是別人的薪資與簽核意見 —— 兩者性質不同。
                    //   2. 通配持有者（* → ROLE_ADMIN）本來就是超級使用者。
                    //      剝奪他的表單設計權換不到任何安全收益（他仍可部署 BPMN、
                    //      改外部系統授權），只會造成實際困擾。
                    //   3. 反向的風險不存在：ROLE_ADMIN 不含任何個人資料的讀取權，
                    //      而這三條放行的操作<b>全部留下稽核</b>
                    //      （FormDefinitionController.audit → FORM_UPDATE，
                    //      見 security-audit P1-15）。
                    //
                    // 讀端刻意<b>不</b>放進來：GET 維持「登入即可」，理由見下方
                    // /api/** 那條規則。讀別人做的表單 schema 不是敏感操作 ——
                    // 業務人員需要看到同事做了哪些表單才知道自己要建哪一個。
                    // 這與「能改 schema」是兩件事，不因為一邊寬鬆就放寬另一邊。
                    auth.requestMatchers(HttpMethod.POST, "/api/forms/**")
                            .access((authentication, ctx) ->
                                    new org.springframework.security.authorization.AuthorizationDecision(
                                            hasAuthority(authentication.get(), FORM_DESIGN)
                                                    || hasRole(authentication.get(), ADMIN)));
                    auth.requestMatchers(HttpMethod.PUT, "/api/forms/**")
                            .access((authentication, ctx) ->
                                    new org.springframework.security.authorization.AuthorizationDecision(
                                            hasAuthority(authentication.get(), FORM_DESIGN)
                                                    || hasRole(authentication.get(), ADMIN)));
                    auth.requestMatchers(HttpMethod.DELETE, "/api/forms/**")
                            .access((authentication, ctx) ->
                                    new org.springframework.security.authorization.AuthorizationDecision(
                                            hasAuthority(authentication.get(), FORM_DESIGN)
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
                    //
                    // ⚠️ GET /api/forms 與 GET /api/forms/{formKey} 刻意落在這裡
                    // （登入即可），而同一個路徑的 POST/PUT/DELETE 落在上面的
                    // 表單設計規則。**比對是 method-aware 的**，所以「讀開放、
                    // 寫要權限」不會互相蓋掉 —— 這也是上面必須列三個 method、
                    // 不能用單一 requestMatchers("/api/forms/**") 的原因：
                    // 那會連 GET 一起攔下，業務人員就看不到別人做的表單了。
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
