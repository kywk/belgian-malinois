package com.bpm.core.form.service;

import com.bpm.core.form.service.FormOptionsService.FormOption;
import com.bpm.core.webhook.WebhookUrlPolicy;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #56 {@link FormOptionsService} 的抓取、政策、正規化、快取與錯誤規則。
 *
 * <h2>為什麼用真的 HTTP server 而不是 mock {@code RestClient}</h2>
 *
 * <p>要釘住的規則大多跟「請求有沒有真的出去」有關：政策拒絕時不得發請求、
 * 逾時不得無限等待、非 2xx 要變 502。{@code HttpServer} 可以從<b>另一端</b>
 * 數請求，這是唯一能區分「送出了」與「看起來送出了」的證據
 * （同 {@code ExternalApiDelegateTest} 的作法）。
 *
 * <p>Redis 用 Mockito 而不是 Testcontainers：本組要驗的是<b>服務對 Redis 的
 * 行為</b>（快取命中不再打上游、讀／寫故障要 fail-open），那需要控制
 * 「Redis 回什麼／拋什麼」，用真的 Redis 反而做不到。真實 Redis 的
 * 序列化往返由 {@code FormOptionsEndpointTest}（整合測試）守。
 *
 * <h2>policy 的兩種設定</h2>
 *
 * <p>允許路徑用 {@code new WebhookUrlPolicy("localhost")}（把 loopback 明列
 * 為例外，與 {@code application-test.yml} 同一條路徑）；拒絕路徑用空清單，
 * 並以 {@code 127.0.0.1} 當目標 —— 它與 {@code localhost} 是不同的字面
 * host，而政策比對的就是字面 host。兩者都是真的 {@link WebhookUrlPolicy}。
 *
 * <h2>負向控制組（2026-10-03 實測）</h2>
 *
 * <p>把 {@code load()} 裡的 policy 檢查改成 {@code String reason = null}
 * （停用閘門、其餘不動）後執行本組：<b>2 紅 15 綠</b>。紅的是
 * {@code policyRejectionDoesNotSendRequest}（403 變 200、請求數 0 變 1）與
 * {@code policyRejectionDoesNotReadCache}（403 變 200，且被拒 URL 直接由快取供應）；
 * 其餘 15 條與閘門無關，全綠 —— 這正是負向控制組的意義：證明那 2 條對
 * 「閘門在不在」敏感，而不是在測別的東西。恢復檢查後 17 條全綠。
 */
class FormOptionsServiceTest {

    private HttpServer server;
    private String baseUrl;

    /** 從另一端數到的請求數（每個測試歸零）。 */
    private final AtomicInteger hits = new AtomicInteger();

    /** path → 回應 body；沒設定的 path 回空陣列。 */
    private final Map<String, String> bodies = new ConcurrentHashMap<>();

    /** path → 回應狀態碼；沒設定的 path 回 200。 */
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked") // mock(ValueOperations.class) 的泛型由欄位型別保證
    void setUp() throws IOException {
        hits.set(0);
        bodies.clear();
        statuses.clear();
        bodies.put("/object", "[{\"label\":\"特休\",\"value\":\"annual\"},{\"label\":\"事假\",\"value\":\"personal\"}]");
        bodies.put("/strings", "[\"annual\",\"personal\"]");
        bodies.put("/mixed", "[{\"label\":\"特休\",\"value\":\"annual\"},{\"value\":\"personal\"},{\"label\":\"  \",\"value\":\"sick\"},{\"label\":\"天數\",\"value\":3}]");
        bodies.put("/empty", "[]");
        bodies.put("/not-json", "<html>not json</html>");
        bodies.put("/wrong-shape", "{\"options\":[{\"label\":\"a\",\"value\":\"a\"}]}");
        bodies.put("/bad-element", "[{\"label\":\"a\",\"value\":\"a\"},123]");
        statuses.put("/fail", 503);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if (path.contains("slow")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (path.equals("/redirect")) {
                // 被允許的主機把伺服器重導去打 loopback 的攻擊形狀。
                exchange.getResponseHeaders().set("Location",
                        "http://localhost:" + server.getAddress().getPort() + "/object");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            int status = statuses.getOrDefault(path, 200);
            byte[] response = bodies.getOrDefault(path, "[]").getBytes(StandardCharsets.UTF_8);
            // 明確帶 charset：沒有它，RestClient 端會用非 UTF-8 解碼，中文變亂碼
            // （JSON 的 RFC 8259 預設是 UTF-8，這裡照實標示）。
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // 預設 cache miss
        when(valueOps.get(anyString())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 允許 localhost（loopback 的明確例外），逾時寬鬆。 */
    private FormOptionsService serviceAllowingLocalhost() {
        return new FormOptionsService(new WebhookUrlPolicy("localhost"), redis, new ObjectMapper(), 2000, 2000);
    }

    /** 空清單：loopback／內網全拒。 */
    private FormOptionsService serviceRejectingAll() {
        return new FormOptionsService(new WebhookUrlPolicy(""), redis, new ObjectMapper(), 2000, 2000);
    }

    private ResponseStatusException loadAndCatch(FormOptionsService service, String url) {
        ResponseStatusException e =
                catchThrowableOfType(() -> service.load(url), ResponseStatusException.class);
        assertThat(e).as("應該丟 ResponseStatusException").isNotNull();
        return e;
    }

    // ── 正規化：兩種上游形狀 ────────────────────────────────────────

    @Test
    @DisplayName("上游 [{label,value}] → 原樣正規化")
    void objectShapeIsNormalized() {
        List<FormOption> options = serviceAllowingLocalhost().load(baseUrl + "/object");

        assertThat(options).containsExactly(
                new FormOption("特休", "annual"),
                new FormOption("事假", "personal"));
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("上游 [\"a\",\"b\"] → label=value=字串")
    void stringShapeIsNormalized() {
        List<FormOption> options = serviceAllowingLocalhost().load(baseUrl + "/strings");

        assertThat(options).containsExactly(
                new FormOption("annual", "annual"),
                new FormOption("personal", "personal"));
    }

    @Test
    @DisplayName("label 缺席／空白時用 value；數字 value 轉字串（spec §8.4 的 value 是字串）")
    void labelFallsBackToValueAndNumbersBecomeStrings() {
        List<FormOption> options = serviceAllowingLocalhost().load(baseUrl + "/mixed");

        assertThat(options).containsExactly(
                new FormOption("特休", "annual"),
                new FormOption("personal", "personal"),
                new FormOption("sick", "sick"),
                new FormOption("天數", "3"));
    }

    @Test
    @DisplayName("空陣列是合法的「目前沒有選項」，不是錯誤")
    void emptyArrayIsValid() {
        assertThat(serviceAllowingLocalhost().load(baseUrl + "/empty")).isEmpty();
    }

    @Test
    @DisplayName("url 空白 → 400，且不發請求")
    void blankUrlIsBadRequest() {
        ResponseStatusException e = loadAndCatch(serviceAllowingLocalhost(), "  ");

        assertThat(e.getStatusCode().value()).isEqualTo(400);
        assertThat(hits.get()).isZero();
    }

    // ── 上游失敗：一律 502 ──────────────────────────────────────────

    @Test
    @DisplayName("非 2xx（503）→ 502，請求確實有出去")
    void non2xxIsBadGateway() {
        ResponseStatusException e = loadAndCatch(serviceAllowingLocalhost(), baseUrl + "/fail");

        assertThat(e.getStatusCode().value()).isEqualTo(502);
        assertThat(e.getReason()).contains("HTTP 503");
        assertThat(hits.get())
                .as("非 2xx 是『有送出去但對方失敗』——請求必須存在")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("非 JSON（HTML）→ 502")
    void nonJsonIsBadGateway() {
        ResponseStatusException e = loadAndCatch(serviceAllowingLocalhost(), baseUrl + "/not-json");

        assertThat(e.getStatusCode().value()).isEqualTo(502);
        assertThat(e.getReason()).contains("不是合法 JSON");
    }

    @Test
    @DisplayName("JSON 但最外層不是陣列 → 502（不靜默當成空清單）")
    void wrongShapeIsBadGateway() {
        ResponseStatusException e = loadAndCatch(serviceAllowingLocalhost(), baseUrl + "/wrong-shape");

        assertThat(e.getStatusCode().value()).isEqualTo(502);
        assertThat(e.getReason()).contains("JSON 陣列");
    }

    @Test
    @DisplayName("陣列含無法正規化的元素（數字）→ 502，不靜默丟掉")
    void badElementIsBadGateway() {
        ResponseStatusException e = loadAndCatch(serviceAllowingLocalhost(), baseUrl + "/bad-element");

        assertThat(e.getStatusCode().value()).isEqualTo(502);
        assertThat(e.getReason()).contains("value");
    }

    @Test
    @DisplayName("🔴 3xx 重導不得跟隨：被允許的主機無法用 Location 把伺服器帶去打 loopback")
    void redirectIsNotFollowed() {
        // 政策只看原始 URL 的 host。若 client 跟隨重導，allowed host 就能
        // 302 到 http://127.0.0.1:8080/... —— SSRF 閘門形同虛設。
        ResponseStatusException e = loadAndCatch(serviceAllowingLocalhost(), baseUrl + "/redirect");

        assertThat(e.getStatusCode().value()).isEqualTo(502);
        assertThat(e.getReason()).contains("HTTP 302");
        assertThat(hits.get())
                .as("只有 /redirect 被請求一次；跟隨重導的話 /object 也會被請求")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("讀取逾時（100ms vs 睡 1s）→ 502，不是無限等待")
    void readTimeoutIsBadGateway() {
        FormOptionsService service =
                new FormOptionsService(new WebhookUrlPolicy("localhost"), redis, new ObjectMapper(), 2000, 100);

        ResponseStatusException e = loadAndCatch(service, baseUrl + "/slow");

        assertThat(e.getStatusCode().value()).isEqualTo(502);
        assertThat(e.getReason()).contains("連線或逾時失敗");
    }

    // ── SSRF 政策：拒絕時不得發請求、不得讀快取 ─────────────────────

    @Test
    @DisplayName("🔴 policy 拒絕（127.0.0.1 不在清單）→ 403，且請求數為 0")
    void policyRejectionDoesNotSendRequest() {
        ResponseStatusException e = loadAndCatch(serviceRejectingAll(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/object");

        assertThat(e.getStatusCode().value()).isEqualTo(403);
        assertThat(e.getReason()).contains("loopback");
        assertThat(hits.get())
                .as("政策拒絕是非暫時性的；連嘗試都不能有")
                .isZero();
    }

    @Test
    @DisplayName("🔴 快取不得掩蓋拒絕：即使 Redis 有快取值，被拒 URL 仍 403、且不讀快取")
    void policyRejectionDoesNotReadCache() {
        // 若 policy 檢查被移到快取之後，這條快取會讓被拒的 URL 照常回 200。
        when(valueOps.get(anyString()))
                .thenReturn("[{\"label\":\"快取\",\"value\":\"cached\"}]");

        ResponseStatusException e = loadAndCatch(serviceRejectingAll(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/object");

        assertThat(e.getStatusCode().value()).isEqualTo(403);
        verify(valueOps, never()).get(anyString());
        assertThat(hits.get()).isZero();
    }

    // ── 快取：命中不再打上游；Redis 故障 fail-open ──────────────────

    @Test
    @DisplayName("第二次載入由快取供應（不再打上游），且 key 含完整 URL")
    void secondLoadIsServedFromCache() {
        // 模擬一台真的 Redis：set 的值原樣從 get 讀回。
        AtomicReference<String> stored = new AtomicReference<>();
        doAnswer(inv -> {
            stored.set(inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.get(anyString())).thenAnswer(inv -> stored.get());

        FormOptionsService service = serviceAllowingLocalhost();
        String url = baseUrl + "/object";

        List<FormOption> first = service.load(url);
        List<FormOption> second = service.load(url);

        assertThat(second).isEqualTo(first);
        assertThat(hits.get())
                .as("第二次必須由快取供應，上游只該被呼叫一次")
                .isEqualTo(1);
        verify(valueOps, times(2)).get(FormOptionsService.CACHE_PREFIX + url);
        verify(valueOps).set(FormOptionsService.CACHE_PREFIX + url, stored.get(), FormOptionsService.CACHE_TTL);
    }

    @Test
    @DisplayName("Redis 讀取故障 → 直接抓上游（fail-open）")
    void redisReadFailureFallsBackToUpstream() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        List<FormOption> options = serviceAllowingLocalhost().load(baseUrl + "/object");

        assertThat(options).hasSize(2);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Redis 寫入故障 → 本次結果仍然有效（fail-open）")
    void redisWriteFailureStillReturnsOptions() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        List<FormOption> options = serviceAllowingLocalhost().load(baseUrl + "/object");

        assertThat(options).hasSize(2);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("快取值壞掉 → 視為未命中，改抓上游")
    void corruptedCacheValueIsTreatedAsMiss() {
        when(valueOps.get(anyString())).thenReturn("{not a json array");

        List<FormOption> options = serviceAllowingLocalhost().load(baseUrl + "/object");

        assertThat(options).hasSize(2);
        assertThat(hits.get()).isEqualTo(1);
    }
}
