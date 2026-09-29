package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-09-29：{@code /api/audit-logs/**} <b>移除</b> {@code ROLE_ADMIN} 的旁路。
 *
 * <h2>為什麼這不是「限制功能」，而是「讓兩處政策一致」</h2>
 *
 * <p>{@link ProcessAccessGuard#requireReadAccess} 早就<b>明確拒絕</b>
 * {@code ROLE_ADMIN} 讀案件流程變數 —— 理由寫在該方法的註解裡：
 * 「能管理系統」與「能看全公司薪資單附件」是不同的權責。
 *
 * <p>但稽核紀錄的 {@code AuditEvent.detail} 會帶<b>整包流程變數</b>
 * （見 {@code ExternalApiController.completeTask} 的
 * {@code Map.of("action", "complete_task", "variables", vars)}），
 * 而 {@code /api/audit-logs/**} 當時<b>接受</b> {@code ROLE_ADMIN}。
 * 結果是：{@code requireReadAccess} 擋下的東西，用稽核查詢就整批撈得回來 ——
 * 那是一條側門，不是一項功能。
 *
 * <p>所以這條改動<b>收緊</b>的是一條本來就與同專案另一處政策矛盾的規則。
 * 移除之後，讀全公司稽核紀錄的權責一律落在 {@code audit:log:read} 這個
 * 可在權限中心逐人指派、看得見、可回收的權限碼上。
 *
 * <h2>與表單設計的差異是刻意的</h2>
 *
 * <p>同一份改動裡新增的 {@code bpm:form:design}（表單 schema 寫入）
 * <b>保留</b>了 {@code ROLE_ADMIN} 旁路。表單 schema 是設定資產、不是個人資料，
 * 而且那些操作全部留下 {@code FORM_UPDATE} 稽核。兩條規則對同一個問題
 * 給出不同答案，因為資料的性質不同 —— 理由寫在
 * {@code SecurityConfig} 的兩條規則旁與
 * {@code FormDesignAuthorizationTest}。
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：授權層拒絕後的 403 會被容器
 * 轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@code ErrorDispatchTest}）。用 MockMvc 寫，這些測試在缺陷存在時
 * 會照樣全綠。
 */
class AuditReadAuthorityTest extends IntegrationTestBase {

    @Value("${bpm.security.jwt.dev-secret}")
    private String devSecret;

    private final HttpClient http = HttpClient.newHttpClient();

    /** 持有 {@code audit:log:read} 的稽核職能使用者（權限中心 fixture）。 */
    private static final String AUDITOR = "dir001";

    /** 持有通配權限 {@code *} 的管理員 —— 依政策<b>不</b>等於有 audit:log:read。 */
    private static final String ADMIN = "admin001";

    /** 沒有任何權限碼的一般使用者。 */
    private static final String NO_PERM = "user001";

    private static final String RANGE = "?startDate=2026-01-01T00:00:00Z&endDate=2027-01-01T00:00:00Z";

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

    // ── 查詢 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("持有 audit:log:read 的人可以查稽核")
    void auditReadPermissionGrantsAccess() throws Exception {
        // dir001 由權限中心持有 audit:log:read（見 MockPermController）。
        assertThat(getViaGateway("/api/audit-logs?size=5", AUDITOR).statusCode())
                .as("移除 ROLE_ADMIN 旁路之後，這條必須是唯一能通過的路徑 —— "
                        + "否則就沒有任何人讀得到稽核")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("⚠️ 只有 ROLE_ADMIN（* 通配）而沒有 audit:log:read → 查稽核 403")
    void wildcardAdminIsNoLongerEnoughForAuditRead() throws Exception {
        // admin001 的權限是 ["*"]，AuthorityResolver 把它轉成 ROLE_ADMIN。
        // 它<b>不會</b>產生 audit:log:read 這個 authority
        // （見 AuthorityResolver 類別註解的通配權限說明）。
        //
        // 移除旁路之前這一格是 200 —— 而稽核紀錄的 detail 裡含
        // 全公司薪資、簽核意見與核決金額，且繞開了
        // ProcessAccessGuard.requireReadAccess 對 ROLE_ADMIN 的明確拒絕。
        assertThat(getViaGateway("/api/audit-logs?size=5", ADMIN).statusCode())
                .as("ROLE_ADMIN 不等於 audit:log:read —— 要調閱必須由權限中心明確指派")
                .isEqualTo(403);

        // 同一條規則的另一個形狀：JWT roles claim 宣告自己是 admin 也一樣。
        // 這是另一條認證路徑（claim 優先於權限中心查詢），
        // 規則若只在權限中心那一側擋，這一側就是漏的。
        assertThat(getViaJwt("/api/audit-logs?size=5", "idp-admin001", List.of("admin")).statusCode())
                .as("roles claim = [admin] 同樣不得讀稽核")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("沒有 audit:log:read 的一般使用者仍然 403（旁路移除不影響這條）")
    void plainUserIsStillForbidden() throws Exception {
        // 與上面那條必須成組存在。少了它，dir001 的 200 可以靠
        // 「整條規則壞掉、變成放行所有人」達成 —— 那正是移除旁路前的狀態。
        assertThat(getViaGateway("/api/audit-logs?size=5", NO_PERM).statusCode()).isEqualTo(403);
    }

    // ── 完整性檢查 ─────────────────────────────────────────────────

    @Test
    @DisplayName("integrity-check 同樣只接受 audit:log:read")
    void integrityCheckFollowsTheSameRule() throws Exception {
        // integrity-check 是「唯一能看出 hash chain 被動過的工具」
        // （見 AuditLogController 的類別註解）。
        // 若它與一般查詢的授權不一致，攻擊者只要查一般紀錄就好 ——
        // 那條路沒有防護。兩個端點必須同一把鑰匙。
        assertThat(getViaGateway("/api/audit-logs/integrity-check" + RANGE, AUDITOR).statusCode())
                .isEqualTo(200);

        assertThat(getViaGateway("/api/audit-logs/integrity-check" + RANGE, ADMIN).statusCode())
                .as("ROLE_ADMIN 刻意不得執行完整性檢查")
                .isEqualTo(403);

        assertThat(getViaJwt("/api/audit-logs/integrity-check" + RANGE,
                "idp-admin001", List.of("admin")).statusCode())
                .as("roles claim = [admin] 同樣不得執行完整性檢查")
                .isEqualTo(403);
    }

    // ── 未認證 ────────────────────────────────────────────────────

    @Test
    @DisplayName("完全沒有身分時仍是 401（不是 403）")
    void anonymousStillGetsUnauthorized() throws Exception {
        // 沒有閘道密鑰也沒有 Bearer token：連身分都沒有，與「身分不足」不同。
        // 這條不該因為這次改動而變成 403。
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/audit-logs?size=5"))
                .GET().build();
        assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }
}
