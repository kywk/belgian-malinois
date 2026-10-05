package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R-25 的整合測試：v2 格式、legacy 遷移、輪替寬限期、失敗節流。
 *
 * <h2>為什麼走真實 HTTP</h2>
 *
 * <p>本工項的失敗模式幾乎都只在 filter 裡看得到（401／429），而
 * {@link IntegrationTestBase} 的註解記錄過 MockMvc 對 ERROR dispatch 的盲區。
 * 外部呼叫因此全部打測試自己的 Tomcat（{@link #send}），
 * 只有 admin API 用 MockMvc（它不經過外部認證鏈）。
 *
 * <h2>為什麼不整表清空</h2>
 *
 * <p>整合測試共用同一組容器與 DB；整表 deleteAll 會踩到其他測試類別的
 * fixture。這裡每個系統用唯一 systemId 建立，測試後只刪自己建的。
 *
 * <h2>節流測試為什麼不用特殊設定</h2>
 *
 * <p>門檻用 production 預設（10 次／分鐘），因為「預設值本身能運作」
 * 才是驗收點；用唯一 systemId 隔離桶，不會污染其他測試。
 */
class ApiKeyHardeningTest extends IntegrationTestBase {

    private static final String ADMIN = "admin001";
    private static final String PROCESS_KEY = "leave-approval";

    @Autowired
    private ExternalSystemRepository repo;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> createdSystems = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        createdSystems.forEach(sid -> repo.findBySystemId(sid).ifPresent(repo::delete));
        createdSystems.clear();
    }

    // ── fixture／工具 ──────────────────────────────────────────────

    private record CreatedSystem(String systemId, String apiKey) {}

    private static String uniqueSystemId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 走 admin API 建立系統，回傳只出現一次的明文金鑰。 */
    private CreatedSystem createSystem(String systemId) throws Exception {
        createdSystems.add(systemId);
        String body = mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"" + systemId + "\","
                                + "\"systemName\":\"R25 測試系統\","
                                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                + "\"allowedActions\":\"[\\\"start_process\\\"]\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String apiKey = body.replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");
        assertThat(apiKey).as("admin 建立必須回傳明文金鑰（唯一一次）").startsWith("sk-");
        return new CreatedSystem(systemId, apiKey);
    }

    /** 直接以 legacy 格式（單輪 SHA-256）落庫，模擬改動前既有的資料列。 */
    private void seedLegacySystem(String systemId, String plainKey) {
        createdSystems.add(systemId);
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId(systemId);
        sys.setSystemName("R25 legacy 系統");
        sys.setApiKey(ApiKeyUtil.hash(plainKey));
        sys.setAllowedProcessKeys("[\"leave-approval\"]");
        sys.setAllowedActions("[\"start_process\"]");
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        repo.save(sys);
    }

    private HttpResponse<String> send(String method, String path, String systemId,
                                      String apiKey, String body) throws Exception {
        var builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-API-Key", apiKey)
                .header("X-System-Id", systemId)
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String startBody(String businessKey) {
        return "{\"processDefinitionKey\":\"" + PROCESS_KEY + "\","
                + "\"businessKey\":\"" + businessKey + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}";
    }

    private String start(CreatedSystem sys) throws Exception {
        var res = send("POST", "/api/external/process-instances",
                sys.systemId(), sys.apiKey(), startBody("r25-" + UUID.randomUUID()));
        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        return res.body();
    }

    // ── ① v2 格式：admin 建立的金鑰 ─────────────────────────────────

    @Test
    @DisplayName("admin 建立的金鑰以 v2: 落庫、明文不落庫，且能通過真實認證")
    void adminCreatedKeyIsV2AndWorks() throws Exception {
        CreatedSystem sys = createSystem(uniqueSystemId("r25-v2"));

        ExternalSystem stored = repo.findBySystemId(sys.systemId()).orElseThrow();
        assertThat(stored.getApiKey())
                .as("R-25：新金鑰必須是 HMAC 格式，不是無 salt SHA-256")
                .startsWith(ApiKeyUtil.V2_PREFIX)
                .isNotEqualTo(sys.apiKey());
        assertThat(stored.getApiKey())
                .as("落庫的是 v2: 前綴＋64 字元 hex")
                .hasSize(ApiKeyUtil.V2_PREFIX.length() + 64);

        assertThat(start(sys)).contains("processInstanceId");
    }

    // ── ② legacy 雙讀與透明升級 ────────────────────────────────────

    @Test
    @DisplayName("既有 SHA-256 金鑰仍可驗證，且第一次成功後透明升級為 v2")
    void legacyKeyIsAcceptedAndUpgradedOnFirstUse() throws Exception {
        String systemId = uniqueSystemId("r25-legacy");
        String plainKey = "sk-r25-legacy-" + UUID.randomUUID().toString().substring(0, 8);
        seedLegacySystem(systemId, plainKey);

        assertThat(repo.findBySystemId(systemId).orElseThrow().getApiKey())
                .as("前置條件：模擬改動前的資料列")
                .isEqualTo(ApiKeyUtil.hash(plainKey));

        var first = send("POST", "/api/external/process-instances",
                systemId, plainKey, startBody("r25-legacy-" + UUID.randomUUID()));
        assertThat(first.statusCode())
                .as("部署當天既有金鑰不得失效：body=%s", first.body())
                .isEqualTo(200);

        ExternalSystem upgraded = repo.findBySystemId(systemId).orElseThrow();
        assertThat(upgraded.getApiKey())
                .as("第一次成功驗證後應透明升級成 v2（不需要運維逐系統輪替）")
                .startsWith(ApiKeyUtil.V2_PREFIX)
                .isNotEqualTo(ApiKeyUtil.hash(plainKey));

        var second = send("POST", "/api/external/process-instances",
                systemId, plainKey, startBody("r25-legacy-" + UUID.randomUUID()));
        assertThat(second.statusCode())
                .as("升級後同一把明文仍要能驗證（升級的是儲存格式，不是金鑰本身）")
                .isEqualTo(200);
    }

    // ── ③ 輪替寬限期 ──────────────────────────────────────────────

    @Test
    @DisplayName("rotate-key 後舊金鑰在新金鑰到期前可用；到期後舊 401、新仍可用")
    void rotatedOldKeySurvivesGracePeriodThenExpires() throws Exception {
        CreatedSystem before = createSystem(uniqueSystemId("r25-rotate"));

        String rotated = mockMvc.perform(post("/api/admin/external-systems/"
                        + before.systemId() + "/rotate-key")
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newKey = rotated.replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");
        assertThat(newKey).startsWith("sk-").isNotEqualTo(before.apiKey());
        assertThat(rotated).as("回應必須告知舊金鑰的到期時間，運維才排得動更換")
                .contains("previousKeyValidUntil");
        assertThat(rotated).doesNotContain("\"previousKeyValidUntil\":\"\"");

        CreatedSystem oldKey = new CreatedSystem(before.systemId(), before.apiKey());
        CreatedSystem newKeySys = new CreatedSystem(before.systemId(), newKey);
        assertThat(start(newKeySys)).as("新金鑰立即生效").contains("processInstanceId");
        assertThat(start(oldKey))
                .as("寬限期內舊金鑰必須還能用 —— 這正是 rotate 不再等於中斷的驗證點")
                .contains("processInstanceId");

        // 把寬限期強制撥到過去，模擬「呼叫端沒在期限內換完」。
        ExternalSystem sys = repo.findBySystemId(before.systemId()).orElseThrow();
        sys.setPreviousApiKeyExpiresAt(Instant.now().minusSeconds(5));
        repo.save(sys);

        var expiredOld = send("POST", "/api/external/process-instances",
                before.systemId(), before.apiKey(), startBody("r25-expired-" + UUID.randomUUID()));
        assertThat(expiredOld.statusCode())
                .as("到期後舊金鑰必須失效；body=%s", expiredOld.body())
                .isEqualTo(401);
        assertThat(start(newKeySys)).as("新金鑰不受舊金鑰到期影響").contains("processInstanceId");
    }

    // ── ④ 驗證失敗節流 ────────────────────────────────────────────

    @Test
    @DisplayName("同一系統連續失敗達門檻後回 429、帶 Retry-After；正確金鑰也被暫時擋下")
    void failedAttemptsAreThrottled() throws Exception {
        CreatedSystem sys = createSystem(uniqueSystemId("r25-throttle"));

        for (int i = 0; i < 10; i++) {
            var res = send("POST", "/api/external/process-instances",
                    sys.systemId(), "sk-wrong-" + i, startBody("r25-thr-" + UUID.randomUUID()));
            assertThat(res.statusCode())
                    .as("第 %d 次失敗應是 401（門檻之前不擋）", i + 1)
                    .isEqualTo(401);
        }

        var blocked = send("POST", "/api/external/process-instances",
                sys.systemId(), sys.apiKey(), startBody("r25-thr-" + UUID.randomUUID()));
        assertThat(blocked.statusCode())
                .as("達到門檻後，即使金鑰正確也先回 429（避免猜測繼續消耗驗證成本）")
                .isEqualTo(429);
        assertThat(blocked.body()).contains("Too many failed authentication attempts");
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    @DisplayName("成功驗證會重置失敗計數：偶發失敗不累積成鎖死")
    void successResetsFailureCounter() throws Exception {
        CreatedSystem sys = createSystem(uniqueSystemId("r25-reset"));

        for (int i = 0; i < 5; i++) {
            send("POST", "/api/external/process-instances",
                    sys.systemId(), "sk-bad-" + i, startBody("r25-reset-" + UUID.randomUUID()));
        }
        assertThat(start(sys)).as("5 次失敗 < 門檻，正確金鑰仍可通過").contains("processInstanceId");

        // 上一個成功已清空桶；再累積 5 次失敗也不該被推到門檻。
        for (int i = 0; i < 5; i++) {
            var res = send("POST", "/api/external/process-instances",
                    sys.systemId(), "sk-bad2-" + i, startBody("r25-reset-" + UUID.randomUUID()));
            assertThat(res.statusCode()).isEqualTo(401);
        }
        assertThat(start(sys))
                .as("重置後重新計數 —— 否則正常系統會因為歷史失敗被鎖死")
                .contains("processInstanceId");
    }
}
