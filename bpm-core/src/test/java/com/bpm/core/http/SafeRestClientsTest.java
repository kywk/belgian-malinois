package com.bpm.core.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * #97 {@link SafeRestClients} 的兩條共用規則：不跟隨 3xx、逾時。
 *
 * <h2>為什麼用真的 HTTP server</h2>
 *
 * <p>「有沒有跟隨重導」只能從<b>另一端</b>數請求來證明：被允許的主機
 * （這裡是 {@code localhost}）回應 302，若 client 跟隨 {@code Location}，
 * 第二個端點就會收到請求。斷言「第二端點零請求」是唯一能區分
 * 「擋下了」與「看起來擋下了」的證據 —— 同 {@code ExternalApiDelegateTest}
 * 的作法。
 *
 * <h2>為什麼 POST 也要測</h2>
 *
 * <p>三個客戶端裡 webhook 用 POST／PUT，delegate 用 GET／POST／PUT。
 * <b>GET 是真正由這條設定守住的</b>：Spring 的
 * {@code SimpleClientHttpRequestFactory} 對非 GET 本來就設
 * {@code follow=false}（POST 即使被跟隨也會被改寫成 GET、body 丟掉）。
 * POST 這條釘的是「webhook 的形狀今天安全」，不是「helper 使它安全」。
 *
 * <p>負向控制組（2026-10-03 實測）：把
 * {@code setInstanceFollowRedirects(false)} 拿掉後，
 * {@code getDoesNotFollowRedirect} 紅（302 變 200、第二端點 0 變 1）；
 * {@code postDoesNotFollowRedirect} 仍綠 —— 原因就是上面那句
 * Spring 的預設，它<b>證明不了</b> helper 對 POST 有作用；
 * {@code readTimeoutIsApplied} 與此無關，仍綠。
 */
class SafeRestClientsTest {

    /** 回應 302 的「被允許的主機」。 */
    private HttpServer allowedHost;

    /** 302 的 Location 指向的 loopback 端點（攻擊者想讓伺服器去打的地方）。 */
    private HttpServer redirectTarget;

    /** 兩個端點各自收到的請求數（每個測試歸零）。 */
    private final AtomicInteger allowedHits = new AtomicInteger();
    private final AtomicInteger targetHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        allowedHits.set(0);
        targetHits.set(0);

        redirectTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirectTarget.createContext("/landed", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        redirectTarget.start();

        allowedHost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        allowedHost.createContext("/", exchange -> {
            allowedHits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/slow")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (path.equals("/redirect")) {
                // 被允許的主機把伺服器重導去打 loopback 的攻擊形狀。
                exchange.getResponseHeaders().set("Location",
                        "http://127.0.0.1:" + redirectTarget.getAddress().getPort() + "/landed");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        allowedHost.start();
    }

    @AfterEach
    void tearDown() {
        allowedHost.stop(0);
        redirectTarget.stop(0);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private String allowedHostUrl(String path) {
        return "http://localhost:" + allowedHost.getAddress().getPort() + path;
    }

    private String redirectLocation() {
        return "http://127.0.0.1:" + redirectTarget.getAddress().getPort() + "/landed";
    }

    // ── 不跟隨 3xx ─────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 GET：302 原樣回傳，Location 指向的端點零請求")
    void getDoesNotFollowRedirect() {
        ResponseEntity<Void> response = SafeRestClients.create(2000, 2000).get()
                .uri(allowedHostUrl("/redirect"))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value())
                .as("跟隨重導的話這裡會是第二端點的 200")
                .isEqualTo(302);
        assertThat(response.getHeaders().getLocation()).hasToString(redirectLocation());
        assertThat(targetHits.get())
                .as("Location 指向 loopback：跟隨了就等於繞過 WebhookUrlPolicy")
                .isZero();
        assertThat(allowedHits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 POST（webhook 的形狀）：302 原樣回傳，第二端點零請求")
    void postDoesNotFollowRedirect() {
        ResponseEntity<Void> response = SafeRestClients.create(2000, 2000).method(HttpMethod.POST)
                .uri(allowedHostUrl("/redirect"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"event\":\"task.create\"}")
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(targetHits.get()).isZero();
        assertThat(allowedHits.get()).isEqualTo(1);
    }

    // ── 逾時 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("read timeout 真的有設：100ms vs 睡 1s → 不等到對方回應")
    void readTimeoutIsApplied() {
        Throwable thrown = catchThrowable(() -> SafeRestClients.create(2000, 100).get()
                .uri(allowedHostUrl("/slow"))
                .retrieve().toBodilessEntity());

        assertThat(thrown)
                .as("沒有逾時的話這條會等滿 1 秒後拿到 200")
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }
}
