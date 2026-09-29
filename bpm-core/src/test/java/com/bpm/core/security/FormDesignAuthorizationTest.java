package com.bpm.core.security;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-09-29：{@code /api/forms/**} 的寫入端需要 {@code bpm:form:design}。
 *
 * <h2>缺陷</h2>
 *
 * <p>{@code FormDefinitionController} 的 {@code @RequestMapping} 是
 * <b>{@code /api/forms}</b>（不是 {@code /api/admin/forms}），所以它落在
 * {@code SecurityConfig} 最後的 {@code /api/** → authenticated()}：
 * <b>任何登入者</b>都能建立 draft、改 schemaJson、發布、封存、刪除。
 * 而且 {@code GET /api/forms} 直接回傳所有 draft 的 {@code id}，不用猜 UUID。
 *
 * <p>危害不是「表單被弄亂」，而是<b>流程本身的完整性</b>：spec §8.5 訂明
 * 表單欄位 id 就是流程變數名，所以能改 schema 就能加一個欄位 id 叫
 * {@code approved} —— 送件人在填表時就決定了簽核結果，
 * 而流程定義與稽核都不會留下這條路徑被使用的痕跡。
 *
 * <h2>為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：{@code ResponseStatusException} 會被
 * 容器轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@code ErrorDispatchTest}）。授權擋下的 403 同樣會被 {@code /error}
 * 改寫，所以這裡用 {@link HttpClient} 打真的 servlet port。
 *
 * <h2>每一條拒絕都驗「資料真的沒變」</h2>
 *
 * <p>被拒絕的寫入如果回 403 卻仍然改了資料，這個修補只是把攻擊從
 * 「做得到」變成「看不到錯誤訊息」。因此每條拒絕都直接回
 * {@link FormDefinitionRepository} 驗證後果 —— 只有狀態碼的守衛測試
 * 可以被「先放行、之後回捲」之類的實作矇混過去。
 *
 * <h2>三個身分的分工</h2>
 *
 * <ul>
 *   <li>{@link #NO_PERM}：user001，權限中心 fixture 裡沒有任何權限碼。</li>
 *   <li>{@link #DESIGNER}：mgr001，持有 {@code bpm:form:design}，
 *       <b>不是</b>管理員 —— 這一列證明新增的權限碼真的有分離出作用。</li>
 *   <li>{@link #ADMIN}：admin001，權限是 {@code *} → {@code ROLE_ADMIN}。</li>
 * </ul>
 */
class FormDesignAuthorizationTest extends IntegrationTestBase {

    @Autowired
    private FormDefinitionRepository defRepo;

    @Value("${bpm.security.jwt.dev-secret}")
    private String devSecret;

    private final HttpClient http = HttpClient.newHttpClient();

    /** 沒有任何權限碼的一般使用者。 */
    private static final String NO_PERM = "user001";

    /** 持有 {@code bpm:form:design} 的部門主管（非管理員）。 */
    private static final String DESIGNER = "mgr001";

    /** 通配權限 {@code *} → {@code ROLE_ADMIN}。 */
    private static final String ADMIN = "admin001";

    // ── HTTP 小工具（走真實 HTTP，見類別註解）───────────────────────

    private HttpResponse<String> send(String method, String path, String userId, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        return send("GET", path, userId, null);
    }

    private HttpResponse<String> post(String path, String userId, String body) throws Exception {
        return send("POST", path, userId, body);
    }

    private HttpResponse<String> put(String path, String userId, String body) throws Exception {
        return send("PUT", path, userId, body);
    }

    private HttpResponse<String> delete(String path, String userId) throws Exception {
        return send("DELETE", path, userId, null);
    }

    /** 以真實 JWT 發請求（第二條認證路徑），roles 為 null 表示交給權限中心決定。 */
    private HttpResponse<String> sendWithJwt(String method, String path, String subject,
                                            List<String> roles) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)));
        if (roles != null) claims.claim("roles", roles);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(devSecret.getBytes(StandardCharsets.UTF_8)));

        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("Authorization", "Bearer " + jwt.serialize())
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ── fixture ────────────────────────────────────────────────────

    private String uniqueKey() {
        return "authz-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private FormDefinition saveForm(String formKey, String status, int version) {
        FormDefinition d = new FormDefinition();
        d.setFormKey(formKey);
        d.setName("測試表單");
        d.setVersion(version);
        d.setStatus(status);
        d.setSchemaJson("{\"fields\":[{\"id\":\"original\"}]}");
        return defRepo.save(d);
    }

    private List<FormDefinition> versionsOf(String formKey) {
        return defRepo.findAll().stream()
                .filter(d -> formKey.equals(d.getFormKey()))
                .sorted(java.util.Comparator.comparing(FormDefinition::getVersion))
                .toList();
    }

    // ── 建立 draft：POST /api/forms/{formKey}/revisions ─────────────

    @Test
    @DisplayName("沒有 bpm:form:design 的登入者不得建立表單 draft（且不得真的建立）")
    void revisionRequiresTheFormDesignPermission() throws Exception {
        String key = uniqueKey();
        saveForm(key, "published", 1);

        var res = post("/api/forms/" + key + "/revisions", NO_PERM, null);

        assertThat(res.statusCode())
                .as("任何登入者都能建立 draft → 可以把自己的審核表單塞進流程")
                .isEqualTo(403);
        assertThat(versionsOf(key))
                .as("403 之後資料庫必須完全沒變 —— 只看狀態碼的守衛測試會被"
                        + "「先放行再回捲」之類的實作矇混過去")
                .hasSize(1);
    }

    @Test
    @DisplayName("持有 bpm:form:design 的非管理員可以建立 draft")
    void designerCanCreateRevision() throws Exception {
        String key = uniqueKey();
        saveForm(key, "published", 1);

        var res = post("/api/forms/" + key + "/revisions", DESIGNER, null);

        assertThat(res.statusCode())
                .as("業務人員設計表單是這個專案的產品目標，權限碼不能把路堵死")
                .isEqualTo(200);
        assertThat(versionsOf(key))
                .as("必須真的建立出第二版 draft —— 否則 200 是空的")
                .hasSize(2);
        assertThat(versionsOf(key).get(1).getStatus()).isEqualTo("draft");
    }

    // ── 改 schemaJson：PUT /api/forms/{id} ─────────────────────────

    @Test
    @DisplayName("沒有 bpm:form:design 的人不得改 schemaJson（且不得真的改掉）")
    void updateRequiresTheFormDesignPermission() throws Exception {
        String key = uniqueKey();
        FormDefinition draft = saveForm(key, "draft", 1);

        var res = put("/api/forms/" + draft.getId(), NO_PERM,
                "{\"name\":\"被改掉的表單\",\"schemaJson\":\"{\\\"fields\\\":[{\\\"id\\\":\\\"approved\\\"}]}\"}");

        assertThat(res.statusCode()).isEqualTo(403);
        FormDefinition after = defRepo.findById(draft.getId()).orElseThrow();
        assertThat(after.getSchemaJson())
                .as("注入 approved 欄位就等於讓送件人自己決定簽核結果（spec §8.5），"
                        + "403 之後一個字都不能變")
                .doesNotContain("approved");
        assertThat(after.getName()).isEqualTo("測試表單");
    }

    @Test
    @DisplayName("持有 bpm:form:design 的人可以改 schemaJson")
    void designerCanUpdateSchema() throws Exception {
        String key = uniqueKey();
        FormDefinition draft = saveForm(key, "draft", 1);

        var res = put("/api/forms/" + draft.getId(), DESIGNER,
                "{\"name\":\"改版後的表單\",\"schemaJson\":\"{\\\"fields\\\":[{\\\"id\\\":\\\"leaveType\\\"}]}\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        FormDefinition after = defRepo.findById(draft.getId()).orElseThrow();
        assertThat(after.getSchemaJson())
                .as("必須真的換掉 schemaJson —— 200 本身不證明任何事")
                .contains("leaveType").doesNotContain("original");
        assertThat(after.getName()).isEqualTo("改版後的表單");
    }

    // ── 發布：POST /api/forms/{id}/publish ─────────────────────────

    @Test
    @DisplayName("沒有 bpm:form:design 的人不得發布（且 draft 不得變成 published）")
    void publishRequiresTheFormDesignPermission() throws Exception {
        String key = uniqueKey();
        FormDefinition draft = saveForm(key, "draft", 1);

        var res = post("/api/forms/" + draft.getId() + "/publish", NO_PERM, null);

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(defRepo.findById(draft.getId()).orElseThrow().getStatus())
                .as("發布是不可逆的行為：一旦 published，進行中案件的版本鎖定就換不掉了")
                .isEqualTo("draft");
    }

    @Test
    @DisplayName("持有 bpm:form:design 的人可以發布")
    void designerCanPublish() throws Exception {
        String key = uniqueKey();
        FormDefinition draft = saveForm(key, "draft", 1);

        var res = post("/api/forms/" + draft.getId() + "/publish", DESIGNER, null);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(defRepo.findById(draft.getId()).orElseThrow().getStatus())
                .as("必須真的發布出去")
                .isEqualTo("published");
    }

    // ── 其餘寫入端：archive / delete / create ──────────────────────

    @Test
    @DisplayName("封存與刪除同樣需要 bpm:form:design")
    void archiveAndDeleteRequireTheFormDesignPermission() throws Exception {
        String publishedKey = uniqueKey();
        FormDefinition published = saveForm(publishedKey, "published", 1);
        String draftKey = uniqueKey();
        FormDefinition draft = saveForm(draftKey, "draft", 1);

        assertThat(post("/api/forms/" + published.getId() + "/archive", NO_PERM, null).statusCode())
                .as("封存會讓所有進行中案件的表單消失 —— 不能讓一般登入者做")
                .isEqualTo(403);
        assertThat(defRepo.findById(published.getId()).orElseThrow().getStatus())
                .as("403 之後 published 不得被改成 archived").isEqualTo("published");

        assertThat(delete("/api/forms/" + draft.getId(), NO_PERM).statusCode()).isEqualTo(403);
        assertThat(defRepo.findById(draft.getId()))
                .as("403 之後 draft 不得被刪掉").isPresent();

        // 同一組操作，具備權限者就必須做得到 —— 否則 403 可能是「功能壞掉」
        assertThat(post("/api/forms/" + published.getId() + "/archive", DESIGNER, null).statusCode())
                .isEqualTo(200);
        assertThat(delete("/api/forms/" + draft.getId(), DESIGNER).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("POST /api/forms（全新 formKey）同樣需要 bpm:form:design")
    void createRequiresTheFormDesignPermission() throws Exception {
        String key = uniqueKey();
        String body = "{\"formKey\":\"" + key + "\",\"name\":\"新表單\","
                + "\"schemaJson\":\"{\\\"fields\\\":[{\\\"id\\\":\\\"approved\\\"}]}\"}";

        assertThat(post("/api/forms", NO_PERM, body).statusCode()).isEqualTo(403);
        assertThat(versionsOf(key)).as("403 之後不得建立任何版本").isEmpty();

        assertThat(post("/api/forms", DESIGNER, body).statusCode()).isEqualTo(200);
        assertThat(versionsOf(key)).as("必須真的建立出來").hasSize(1);
    }

    // ── 讀端維持登入即可 ───────────────────────────────────────────

    @Test
    @DisplayName("讀表單 schema 對任何登入者都是 200（讀維持開放）")
    void readsStayOpenToAnyAuthenticatedUser() throws Exception {
        saveForm(uniqueKey(), "published", 1);

        for (String user : List.of(NO_PERM, DESIGNER, ADMIN, "outsider001")) {
            assertThat(get("/api/forms", user).statusCode())
                    .as("讀表單 schema 對「任何登入者」開放（" + user + "）："
                            + "業務人員要能看到同事做了哪些表單才知道自己要建哪一個")
                    .isEqualTo(200);
        }
    }

    // ── ROLE_ADMIN 的旁路：這條規則刻意保留 ────────────────────────

    @Test
    @DisplayName("ROLE_ADMIN 仍可設計表單（通配持有者是超級使用者）")
    void adminKeepsFormDesignAccess() throws Exception {
        String key = uniqueKey();
        FormDefinition draft = saveForm(key, "draft", 1);

        // admin001 的權限是 ["*"] → AuthorityResolver 轉成 ROLE_ADMIN，
        // **不會**產生 bpm:form:design 這個 authority。所以這個 200
        // 只可能來自規則裡刻意保留的 hasRole(ADMIN)。
        //
        // ⚠️ 同一個 ROLE_ADMIN 對 /api/audit-logs/** 是<b>不</b>放行的 ——
        // 那半邊由 AuditReadAuthorityTest 驗。兩者刻意不同，
        // 理由寫在 SecurityConfig 的兩條規則旁。
        var res = put("/api/forms/" + draft.getId(), ADMIN,
                "{\"name\":\"管理員改的\",\"schemaJson\":\"{\\\"fields\\\":[{\\\"id\\\":\\\"byAdmin\\\"}]}\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(defRepo.findById(draft.getId()).orElseThrow().getSchemaJson())
                .as("必須真的改到 —— 否則這條 200 只是路由通而已")
                .contains("byAdmin");
    }

    // ── 第二條認證路徑：JWT 的 roles claim ────────────────────────

    @Test
    @DisplayName("JWT roles claim 帶 bpm:form:design 時同樣放行（IdP 直接指派的情況）")
    void rolesClaimCarryingThePermissionCodeIsAccepted() throws Exception {
        // AuthorityResolver.fromJwtRoles 除了加 ROLE_* 之外也保留原字串，
        // 所以 IdP 若直接以權限碼作為角色指派，hasAuthority 也能命中。
        // 這條路徑與「查權限中心」是兩條不同的認證路徑，兩者都必須通。
        String key = uniqueKey();
        saveForm(key, "published", 1);

        var res = sendWithJwt("POST", "/api/forms/" + key + "/revisions", "idp-user001",
                List.of("bpm:form:design"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(versionsOf(key)).as("必須真的建立出 draft").hasSize(2);
    }
}
