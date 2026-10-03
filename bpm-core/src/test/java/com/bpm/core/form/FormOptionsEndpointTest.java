package com.bpm.core.form;

import com.bpm.core.security.GatewayAuthenticationFilter;
import com.bpm.core.support.ExternalApiTestSink;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #56 動態選項代理端點的端到端：路由、授權、真實 Redis 快取與上游失敗轉譯。
 *
 * <h2>分工：規則在 {@code FormOptionsServiceTest}，這裡只驗「有接上」</h2>
 *
 * <p>抓取、政策、正規化、逾時的規則由單元測試釘住（用真的 HttpServer 與
 * 可控的 Redis mock）。本檔補的是單元測試結構性看不到的三件事：
 * <ol>
 *   <li><b>路由真的存在</b> —— {@code /api/forms/options} 與
 *       {@code FormDefinitionController} 的 {@code /api/forms/{formKey}}
 *       並存，字面路徑必須贏。</li>
 *   <li><b>授權矩陣真的蓋到它</b> —— 未登入要 401（落在
 *       {@code /api/**} → {@code authenticated()}）。</li>
 *   <li><b>快取在真 Redis 上的序列化往返</b> —— 單元測試的 Redis 是 mock，
 *       寫進去再讀出來是同一個字串；這裡用真的 Redis，證明
 *       {@code [{label,value}]} 的 JSON 能寫入、讀回、且第二次真的不打上游。</li>
 * </ol>
 *
 * <h2>allowed-hosts 沿用 #67 的測試例外</h2>
 *
 * <p>{@code application-test.yml} 把 {@code localhost} 列進
 * {@code bpm.webhook.allowed-hosts}，所以 sink URL 過得了
 * {@code WebhookUrlPolicy}；被擋的路徑用 {@code 127.0.0.1}
 * （不同的字面 host）驗證，與 {@code ExternalApiDelegateIntegrationTest} 同一條界線。
 *
 * <h2>sink 名稱每個測試都不同</h2>
 *
 * <p>Redis 容器在整個測試 JVM 內共用，而選項快取的 TTL 是 60 秒 ——
 * 兩個測試若用同一個 sink 名稱與 URL，後跑的那個會讀到前一個的快取，
 * 「上游收到幾次」的斷言就會變成假象。名稱帶測試語意並保持唯一。
 */
class FormOptionsEndpointTest extends IntegrationTestBase {

    @BeforeEach
    void resetSink() {
        ExternalApiTestSink.reset();
    }

    /** ⚠️ 必須是 localhost：allowed-hosts 比對的是 URL 裡的字面 host。 */
    private String sinkUrl(String name) {
        return "http://localhost:" + SERVLET_PORT + "/mock/test-external-api/" + name;
    }

    private static String optionsPath() {
        return "/api/forms/options";
    }

    // ── 正向：真的經過後端代理 ──────────────────────────────────────

    @Test
    @DisplayName("#56 GET /api/forms/options 代理上游 [{label,value}] 並回 {options:[...]}")
    void proxiesObjectShapeOptions() throws Exception {
        ExternalApiTestSink.respondWith("options-ok",
                "[{\"label\":\"特休\",\"value\":\"annual\"},{\"label\":\"事假\",\"value\":\"personal\"}]");

        mockMvc.perform(get(optionsPath()).param("url", sinkUrl("options-ok")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options.length()").value(2))
                .andExpect(jsonPath("$.options[0].label").value("特休"))
                .andExpect(jsonPath("$.options[0].value").value("annual"))
                .andExpect(jsonPath("$.options[1].value").value("personal"));

        assertThat(ExternalApiTestSink.receivedTo("options-ok"))
                .as("前端直連的問題就是這裡：請求必須由後端代理出去")
                .hasSize(1);
        assertThat(ExternalApiTestSink.receivedTo("options-ok").get(0).method()).isEqualTo("GET");
    }

    @Test
    @DisplayName("#56 上游 [\"a\",\"b\"] 也正規化成 {label,value}")
    void proxiesStringShapeOptions() throws Exception {
        ExternalApiTestSink.respondWith("options-strings", "[\"annual\",\"personal\"]");

        mockMvc.perform(get(optionsPath()).param("url", sinkUrl("options-strings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].label").value("annual"))
                .andExpect(jsonPath("$.options[0].value").value("annual"));
    }

    // ── 快取：真 Redis 往返 ─────────────────────────────────────────

    @Test
    @DisplayName("#56 第二次載入由 Redis 快取供應：上游只被呼叫一次")
    void secondLoadHitsCacheNotUpstream() throws Exception {
        ExternalApiTestSink.respondWith("options-cache",
                "[{\"label\":\"特休\",\"value\":\"annual\"}]");
        String url = sinkUrl("options-cache");

        mockMvc.perform(get(optionsPath()).param("url", url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].value").value("annual"));
        mockMvc.perform(get(optionsPath()).param("url", url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].value").value("annual"));

        assertThat(ExternalApiTestSink.receivedTo("options-cache"))
                .as("第二次必須由快取供應 —— 上游是外部系統，不該被表單開啟頻率牽動")
                .hasSize(1);
    }

    // ── SSRF 政策與上游失敗 ─────────────────────────────────────────

    @Test
    @DisplayName("#56 🔴 policy 拒絕（127.0.0.1 不在清單）→ 403，且上游零請求")
    void policyRejectionIsForbiddenWithoutUpstreamRequest() throws Exception {
        String blocked = "http://127.0.0.1:" + SERVLET_PORT + "/mock/test-external-api/options-blocked";

        mockMvc.perform(get(optionsPath()).param("url", blocked))
                .andExpect(status().isForbidden());

        assertThat(ExternalApiTestSink.receivedTo("options-blocked"))
                .as("政策拒絕時連請求都不能送出")
                .isEmpty();
    }

    @Test
    @DisplayName("#56 上游非 2xx（503）→ 502，且請求確實有出去")
    void upstreamFailureIsBadGateway() throws Exception {
        ExternalApiTestSink.fail("options-fail", 503);

        mockMvc.perform(get(optionsPath()).param("url", sinkUrl("options-fail")))
                .andExpect(status().isBadGateway());

        assertThat(ExternalApiTestSink.receivedTo("options-fail"))
                .as("非 2xx 是『有送出去但對方失敗』——請求必須存在，才能與政策拒絕區分")
                .hasSize(1);
    }

    // ── 授權 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("#56 未登入 → 401（落在 /api/** → authenticated()）")
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get(optionsPath()).param("url", sinkUrl("options-anon"))
                        // 覆蓋 IntegrationTestBase 的預設閘道身分，見 AuthenticationTest。
                        .header(GatewayAuthenticationFilter.SECRET_HEADER, "")
                        .header(GatewayAuthenticationFilter.USER_HEADER, ""))
                .andExpect(status().isUnauthorized());

        assertThat(ExternalApiTestSink.receivedTo("options-anon"))
                .as("未認證的請求不得觸發任何上游抓取")
                .isEmpty();
    }
}
