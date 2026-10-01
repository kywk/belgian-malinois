package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #82：{@code GET /api/me/permissions} —— 呼叫者查自己的權限。
 *
 * <h2>缺陷</h2>
 *
 * <p>後端授權用權限碼（{@code bpm:form:design}、{@code audit:log:read}），
 * 前端卻只看 JWT 的 {@code roles} claim。於是同一個人在後端與前端得到
 * 互相矛盾的答案，且<b>方向相反</b>：業務人員拿著權限碼卻進不了表單編輯器，
 * 管理員看得見稽核頁卻吃 403。
 *
 * <h2>🔴 本組測試最重要的那一條：不能有「要看誰」的參數</h2>
 *
 * <p>{@link #lookupParametersAreIgnoredAndNeverLeakOtherUsersPermissions} 是
 * 整組測試的防護門。加一個 {@code ?userId=} 聽起來完全無害 —— 它只是把
 * 「呼叫者自己的權限」變成「任何人的權限」，而權限碼清單恰好能畫出組織的
 * 職能配置（誰是稽核、誰有表單設計權、誰是管理員）。本專案過去每一輪安全
 * 修補關掉的都是這類「順手加個參數」的路。
 *
 * <h2>非回歸：動了權限碼不代表放寬了授權</h2>
 *
 * <p>本工項改了前端「看得到什麼」，很容易順手把後端規則也放寬。所以這裡
 * 明確守住三條既有政策：
 * {@code /api/audit-logs} 仍然拒絕 {@code ROLE_ADMIN}、
 * {@code POST /api/forms} 仍然只接受 {@code bpm:form:design} 或
 * {@code ROLE_ADMIN}、前端 {@code /admin/forms} 要求的權限沒有被降級。
 *
 * <h2>⚠️ 負向控制組結果（2026-10-01，必須連同程式碼一起看）</h2>
 *
 * <p>把 {@code MeController} 換成刻意有缺陷的版本
 * （<b>接受 {@code ?userId=} 並回傳那個人的權限碼</b>、
 * {@code admin} 恆為 false）後重跑：
 *
 * <table border="1">
 *   <caption>10 條測試的負向控制組</caption>
 *   <tr><th>測試</th><th>結果</th></tr>
 *   <tr><td>{@code lookupParametersAreIgnoredAndNeverLeakOtherUsersPermissions}</td>
 *       <td>🔴 紅（前提斷言就失敗：DESIGNER 自己的清單已經不對）</td></tr>
 *   <tr><td>{@code jwtIdentityWinsAndParametersCannotOverrideIt}</td><td>🔴 紅</td></tr>
 *   <tr><td>{@code returnsOnlyTheCallersOwnPermissions}</td><td>🔴 紅</td></tr>
 *   <tr><td>{@code wildcardAdminIsNotAnAuditor}</td><td>🔴 紅</td></tr>
 *   <tr><td>{@code reflectsTheRolesClaimWhenPresent}</td><td>🔴 紅</td></tr>
 *   <tr><td>{@code rolesClaimAdminIsReported}</td><td>🔴 紅</td></tr>
 *   <tr><td>{@code reportedPermissionsMatchActualAuthorization}</td>
 *       <td>🔴 紅（訊息直接指出 mgr001 回報無權限碼但 POST /api/forms 是 200）</td></tr>
 *   <tr><td>{@code anonymousGetsUnauthorized}</td><td>✅ 綠（刻意：與本端點無關）</td></tr>
 *   <tr><td>{@code gatewayHeaderWithoutSecretIsNotAnIdentity}</td><td>✅ 綠（刻意）</td></tr>
 *   <tr><td>{@code auditLogsStillRejectWildcardAdmin}</td><td>✅ 綠（刻意）</td></tr>
 * </table>
 *
 * <p><b>7 紅 3 綠。</b>那 3 條綠的不是漏網，而是它們守的是<b>本工項沒有動過</b>的
 * 授權政策（未認證 401、閘道密鑰、稽核拒絕 ROLE_ADMIN）。
 * 它們留在同一個類別裡當對照組：日後若有人真的加了 {@code ?userId=}，
 * 前 7 條會先紅，而這 3 條會提醒「你動的東西不只影響本人」。
 *
 * <p>⚠️ 整組枚舉測試（15 個參數名 × 5 個人 = 75 次請求）能紅的原因是
 * 實作<b>真的</b>讀了那個參數。若日後的缺陷變成「參數存在但被忽略」
 * （也就是正確的修法），這個測試會<b>綠</b> —— 這是刻意的：
 * 端點的正確形態就是「參數完全不存在」，沒有任何實作可以讓它變紅。
 * 它能擋的是「有人加了參數」，那正是本工項要防的那件事。
 *
 * <h2>為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：授權層拒絕後的 403 會被容器轉成
 * ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@code ErrorDispatchTest}）。用 MockMvc 寫，非回歸那三條在缺陷存在時
 * 會照樣全綠。
 *
 * <h2>沒有加 @TestPropertySource / @Import / @DynamicPropertySource</h2>
 *
 * <p>{@code IntegrationTestBase.SERVLET_PORT} 是 static final 的單一 port，
 * 任何測試類別自行加那些註解都會讓其他測試整組紅掉。這裡只用基底提供的機制。
 */
class MePermissionsEndpointTest extends IntegrationTestBase {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${bpm.security.jwt.dev-secret}")
    private String devSecret;

    /** 持有 {@code bpm:form:design} 的部門主管，<b>不是</b>管理員。 */
    private static final String DESIGNER = "mgr001";

    /** 持有 {@code audit:log:read} 的稽核職能使用者。 */
    private static final String AUDITOR = "dir001";

    /** 通配權限 {@code *} → {@code ROLE_ADMIN}。 */
    private static final String ADMIN = "admin001";

    /** 沒有任何權限碼的一般使用者。 */
    private static final String NO_PERM = "user001";

    private static final String PATH = "/api/me/permissions";

    // ── HTTP 小工具（走真實 HTTP，見類別註解）───────────────────────

    private HttpResponse<String> getViaGateway(String path, String userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getViaJwt(String path, String subject, List<String> roles)
            throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)));
        if (roles != null) claims.claim("roles", roles);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(devSecret.getBytes(StandardCharsets.UTF_8)));

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("Authorization", "Bearer " + jwt.serialize())
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> res) throws Exception {
        return mapper.readTree(res.body());
    }

    private List<String> permissionCodesOf(HttpResponse<String> res) throws Exception {
        List<String> out = new ArrayList<>();
        JsonNode perms = body(res).get("permissions");
        assertThat(perms).as("回應必須有 permissions 欄位，實際=%s", res.body()).isNotNull();
        for (JsonNode n : perms) out.add(n.asText());
        return out;
    }

    // ── 只回呼叫者自己的 ────────────────────────────────────────────

    @Test
    @DisplayName("端點只回呼叫者自己的權限碼")
    void returnsOnlyTheCallersOwnPermissions() throws Exception {
        var designer = getViaGateway(PATH, DESIGNER);
        assertThat(designer.statusCode()).isEqualTo(200);
        assertThat(body(designer).get("userId").asText())
                .as("回應必須自報是哪一位 —— 前端要靠它對齊 session.js 的身分")
                .isEqualTo(DESIGNER);
        assertThat(permissionCodesOf(designer))
                .as("mgr001 在權限中心的 fixture 裡持有 bpm:form:design")
                .contains("bpm:form:design")
                .doesNotContain("audit:log:read");

        var auditor = getViaGateway(PATH, AUDITOR);
        assertThat(permissionCodesOf(auditor))
                .as("dir001 持有 audit:log:read，兩人的清單必須不同 —— "
                        + "如果兩者相同，這個端點就沒有真的在回答「我」")
                .contains("audit:log:read")
                .doesNotContain("bpm:form:design");

        var nobody = getViaGateway(PATH, NO_PERM);
        assertThat(permissionCodesOf(nobody))
                .as("沒有任何權限碼的人回空陣列，不是 403 —— 他仍然登入了，"
                        + "而「查自己的權限」不可能洩漏任何東西")
                .isEmpty();
    }

    @Test
    @DisplayName("admin001 的 admin=true，但權限碼清單不含 audit:log:read")
    void wildcardAdminIsNotAnAuditor() throws Exception {
        // 這條是前端最容易做錯的地方：admin001 的權限是 ["*"]，
        // AuthorityResolver 把它轉成 ROLE_ADMIN，**不會**產生
        // audit:log:read 這個 authority。若端點在這裡「貼心地」把 *
        // 展開成全部權限碼，前端就會顯示稽核入口，而後端一律 403。
        var res = getViaGateway(PATH, ADMIN);

        assertThat(body(res).get("admin").asBoolean())
                .as("ROLE_ADMIN 要如實回報，否則前端沒有任何依據判斷 /admin/** 能不能進")
                .isTrue();
        assertThat(permissionCodesOf(res))
                .as("* 不等於具名權限碼 —— 見 MockPermController 的 fixture 註解")
                .doesNotContain("audit:log:read")
                .doesNotContain("bpm:form:design");
    }

    // ── 🔴 防護門：不得有「要看誰」的參數 ─────────────────────────

    @Test
    @DisplayName("🔴 端點不接受任何「要看誰」的參數，送了也不會回別人的權限碼")
    void lookupParametersAreIgnoredAndNeverLeakOtherUsersPermissions() throws Exception {
        // 逐一試常見的參數名。任何一個被接受，攻擊者就能列出
        // 「誰有 audit:log:read」「誰有 bpm:form:design」，畫出組織的
        // 職能配置 —— 而枚舉本身不需要付出被拒絕的代價。
        //
        // 斷言用「與乾淨請求完全相同」而不是「沒有某某權限碼」：
        // 前者一旦實作改成部分支援參數就會紅，後者可能因為
        // 剛好不重疊而假綠。
        var own = permissionCodesOf(getViaGateway(PATH, DESIGNER));
        assertThat(own).as("前提：DESIGNER 自己確實有權限碼").isNotEmpty();

        List<String> otherPeople = List.of(AUDITOR, ADMIN, "mgr002", NO_PERM, "outsider001");
        List<String> paramNames = List.of(
                "userId", "userid", "user", "id", "uid", "sub", "subject",
                "principal", "loginName", "account", "as", "asUser", "for",
                "targetUserId", "ownerId");

        for (String param : paramNames) {
            for (String other : otherPeople) {
                String path = PATH + "?" + param + "="
                        + URLEncoder.encode(other, StandardCharsets.UTF_8);
                var res = getViaGateway(path, DESIGNER);

                assertThat(res.statusCode())
                        .as("送 %s=%s 時必須仍是 200（回呼叫者自己），不是 400/404",
                                param, other)
                        .isEqualTo(200);
                assertThat(body(res).get("userId").asText())
                        .as("送 %s=%s 時 userId 必須仍是呼叫者本人", param, other)
                        .isEqualTo(DESIGNER);
                assertThat(permissionCodesOf(res))
                        .as("🔴 送 %s=%s 回傳了 %s 的權限碼 —— 這是一條組織結構的枚舉通道。"
                                + "端點必須永遠只回答「我是誰」", param, other, other)
                        .isEqualTo(own);
            }
        }

        // 刻意挑兩個「別人獨有」的權限碼明示出來：萬一實作真的支援了參數，
        // 這兩條會是第一批壞掉的。
        assertThat(own).doesNotContain("audit:log:read");
    }

    @Test
    @DisplayName("🔴 雙重身分（JWT sub 與閘道標頭矛盾）時只認 JWT，且參數改不了它")
    void jwtIdentityWinsAndParametersCannotOverrideIt() throws Exception {
        // 最壞的情況組合：呼叫者帶著 Bearer token（sub=NO_PERM），
        // 同時帶閘道標頭說自己是 ADMIN，query string 說自己是 AUDITOR。
        // 三個「身分」只能有一個贏，而且必須是已認證的那個。
        String path = PATH + "?userId=" + AUDITOR;
        var req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("Authorization", "Bearer " + signJwt(NO_PERM, null))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", ADMIN)
                .GET()
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(body(res).get("userId").asText())
                .as("JWT 的 sub 必須勝過閘道標頭（見 GatewayAuthenticationFilter）")
                .isEqualTo(NO_PERM);
        assertThat(body(res).get("admin").asBoolean())
                .as("閘道標頭說的 admin001 不得讓這個請求變成管理員")
                .isFalse();
        assertThat(permissionCodesOf(res))
                .as("user001 在權限中心沒有任何權限碼 —— 三個身分的聯集也不該出現")
                .isEmpty();
    }

    // ── 未認證 ────────────────────────────────────────────────────

    @Test
    @DisplayName("完全沒有身分時是 401（落在 /api/** → authenticated() 上）")
    void anonymousGetsUnauthorized() throws Exception {
        // 沒有閘道密鑰、沒有 Bearer token。
        //
        // ⚠️ 這條同時是「SecurityConfig 不需要為本端點加任何規則」的證據：
        // 它被擋下是因為既有那條 /api/** → authenticated()，
        // 不是因為有專屬規則。
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + PATH))
                .GET().build();
        assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
                .as("未認證與權限不足是兩件事，前者是 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("閘道密鑰錯誤時也不算身分（X-User-Id 不構成身分）")
    void gatewayHeaderWithoutSecretIsNotAnIdentity() throws Exception {
        // 沿用 AuthenticationTest 的前提：裸 X-User-Id 不是身分。
        // 若這裡是 200，就代表端點有某條路徑讀了標頭。
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + PATH))
                .header("X-Gateway-Secret", "wrong-secret")
                .header("X-User-Id", ADMIN)
                .GET()
                .build();
        assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    // ── 兩條認證路徑 ──────────────────────────────────────────────

    @Test
    @DisplayName("JWT 帶 roles claim 時，回應反映 claim（那才是實際用來授權的）")
    void reflectsTheRolesClaimWhenPresent() throws Exception {
        // authoritiesFromJwt 的順序是「claim 優先，不去查權限中心」。
        // 所以端點回權限中心會回一個「權限中心自己沒被問到」的清單，
        // 前端會據此顯示與後端行為不一致的選單。
        var res = getViaJwt(PATH, "idp-user001", List.of("bpm:form:design"));

        assertThat(permissionCodesOf(res))
                .as("AuthorityResolver 會保留原字串，所以 claim 帶的權限碼要看得見")
                .contains("bpm:form:design");
        assertThat(body(res).get("admin").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("JWT 的 roles claim 帶 admin 時 admin=true")
    void rolesClaimAdminIsReported() throws Exception {
        var res = getViaJwt(PATH, "idp-user001", List.of("admin"));

        assertThat(body(res).get("admin").asBoolean())
                .as("『*』與『admin』在 AuthorityResolver 都轉成 ROLE_ADMIN")
                .isTrue();
    }

    private String signJwt(String subject, List<String> roles) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)));
        if (roles != null) claims.claim("roles", roles);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(devSecret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    // ── 非回歸：授權政策沒有被放寬 ─────────────────────────────────

    @Test
    @DisplayName("非回歸：/api/audit-logs 仍然拒絕 ROLE_ADMIN")
    void auditLogsStillRejectWildcardAdmin() throws Exception {
        // 本工項新增了一個「回傳 admin 欄位」的端點，最順手的副作用就是
        // 把「admin 看得見稽核頁」順成真的。SecurityConfig 那條規則刻意
        // 不接受 ROLE_ADMIN（稽核含全公司薪資與簽核意見）。
        assertThat(getViaGateway("/api/audit-logs?size=5", AUDITOR).statusCode())
                .as("持有 audit:log:read 的 dir001 必須仍然讀得到 —— 否則端點只是把路堵死")
                .isEqualTo(200);
        assertThat(getViaGateway("/api/audit-logs?size=5", ADMIN).statusCode())
                .as("ROLE_ADMIN 不等於 audit:log:read")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("非回歸：端點回報的權限與實際授權一致（不會給出「看得到卻點不動」的組合）")
    void reportedPermissionsMatchActualAuthorization() throws Exception {
        // 這條是整個端點存在的理由，所以必須直接驗「回報 == 實際」：
        // 前端完全信任這份清單來決定選單，若它與實際授權不一致，
        // 端點就只是把「前端在猜」換成「後端在猜」。
        for (String user : List.of(DESIGNER, AUDITOR, ADMIN, NO_PERM, "mgr002", "outsider001")) {
            var res = getViaGateway(PATH, user);
            var me = permissionCodesOf(res);
            boolean reportedFormDesign = me.contains("bpm:form:design");
            boolean reportedAuditRead = me.contains("audit:log:read");
            // 用端點自己回報的 admin 欄位，不用測試裡寫死的身分身分表 ——
            // 前端就是靠這個欄位判斷「/admin/** 能不能進」，所以它必須一致。
            boolean reportedAdmin = body(res).get("admin").asBoolean();

            String formKey = "perm-consistency-" + user + "-" + System.nanoTime();
            int asDesigner = postForm(formKey, user);
            int asAuditor = getViaGateway("/api/audit-logs?size=5", user).statusCode();

            assertThat(asDesigner == 200)
                    .as("user=%s 回報 bpm:form:design=%s admin=%s，但 POST /api/forms 是 %d —— "
                            + "回報必須等於實際授權，否則前端一定會出現點不動的項目",
                            user, reportedFormDesign, reportedAdmin, asDesigner)
                    .isEqualTo(reportedFormDesign || reportedAdmin);
            assertThat(asAuditor == 200)
                    .as("user=%s 回報 audit:log:read=%s，但 /api/audit-logs 是 %d —— "
                            + "這一條刻意不接受 ROLE_ADMIN，所以不能帶上 reportedAdmin",
                            user, reportedAuditRead, asAuditor)
                    .isEqualTo(reportedAuditRead);
        }
    }

    private int postForm(String formKey, String userId) throws Exception {
        String body = "{\"formKey\":\"" + formKey + "\",\"name\":\"一致性測試表單\","
                + "\"schemaJson\":\"{\\\"fields\\\":[{\\\"id\\\":\\\"x\\\"}]}\"}";
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/forms"))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}