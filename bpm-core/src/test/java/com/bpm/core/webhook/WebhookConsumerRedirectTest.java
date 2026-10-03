package com.bpm.core.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * #97 webhook 投遞不得跟隨 3xx。
 *
 * <h2>攻擊形狀</h2>
 *
 * <p>{@link WebhookUrlPolicy} 只檢查 {@code __webhookUrl} 的原始 host。
 * 若 client 跟隨 3xx，一個被允許的主機（這裡是清單裡的 {@code localhost}）
 * 只要回 {@code 302 Location: http://127.0.0.1:<port>/landed}，就能讓
 * bpm-core 自己走去打 loopback／內網 —— 閘門形同虛設。
 *
 * <p>斷言「第二端點零請求」是唯一能區分「擋下了」與「看起來擋下了」的證據：
 * 從另一端數請求。對照組（2xx 直接成功）證明 consumer 真的會送、真的會走完，
 * 否則零請求可能只是「整條鏈路根本沒動」。
 *
 * <h2>為什麼關掉重導之後 webhook 會失敗</h2>
 *
 * <p>3xx 現在會原樣抵達 consumer（{@code RestClient} 預設只對 4xx／5xx
 * 拋例外），因此 {@code handle()} 明確把非 2xx 當成投遞失敗 —— 否則
 * 302 會被記成 delivered，接收端其實什麼都沒收到。失敗走既有的
 * 重試 → DLQ 路徑。
 *
 * <p>⚠️ 負向控制組（2026-10-03 實測）：把 {@code SafeRestClients} 的
 * {@code setInstanceFollowRedirects(false)} 拿掉後，本組<b>兩條都仍綠</b>
 * —— Spring 的 {@code SimpleClientHttpRequestFactory} 對非 GET 本來就
 * 不跟隨（見 {@code SafeRestClients} 註解）。本組因此證明的是「webhook
 * 的 POST 投遞不會被 302 帶走，且 3xx 會被當成投遞失敗」，<b>證明不了</b>
 * helper 是使它安全的原因；真正由 helper 守住的是 GET（delegate／動態選項）。
 * 反過來，把 {@code handle()} 裡的「非 2xx」檢查拿掉，
 * {@code redirectToLoopbackIsNotFollowed} 會紅（consumer 把 302 記成
 * delivered、不再拋出）。
 */
class WebhookConsumerRedirectTest {

    /** 被允許的主機（WebhookUrlPolicy 的清單裡有 localhost）。 */
    private HttpServer allowedHost;

    /** 302 的 Location 指向的 loopback 端點。 */
    private HttpServer redirectTarget;

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
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().set("Location",
                        "http://127.0.0.1:" + redirectTarget.getAddress().getPort() + "/landed");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        allowedHost.start();
    }

    @AfterEach
    void tearDown() {
        allowedHost.stop(0);
        redirectTarget.stop(0);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 允許 localhost（loopback 的明確例外），逾時寬鬆。 */
    private WebhookConsumer consumerAllowingLocalhost() {
        return new WebhookConsumer(new ObjectMapper(), new WebhookUrlPolicy("localhost"),
                "unit-test-only-secret", 2000, 2000);
    }

    private Map<String, Object> payload(String path) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "task.create");
        payload.put("__webhookUrl", "http://localhost:" + allowedHost.getAddress().getPort() + path);
        payload.put("__webhookMethod", "POST");
        return payload;
    }

    // ── 測試 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 被允許的主機 302 到 loopback → 投遞失敗，第二端點零請求")
    void redirectToLoopbackIsNotFollowed() {
        Throwable thrown = catchThrowable(() -> consumerAllowingLocalhost().handle(payload("/redirect")));

        assertThat(thrown)
                .as("3xx 不是 2xx：必須走投遞失敗（重試 → DLQ），不得記成 delivered")
                .isInstanceOf(RuntimeException.class);
        assertThat(thrown).hasMessageContaining("302");
        assertThat(targetHits.get())
                .as("跟隨重導的話 127.0.0.1 的端點會被請求 —— 那正是 SSRF 繞過")
                .isZero();
        assertThat(allowedHits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("對照組：2xx 直接回應仍然投遞成功（證明上一條不是『什麼都沒送』）")
    void directDeliveryStillWorks() {
        consumerAllowingLocalhost().handle(payload("/ok"));

        assertThat(allowedHits.get()).isEqualTo(1);
        assertThat(targetHits.get()).isZero();
    }
}
