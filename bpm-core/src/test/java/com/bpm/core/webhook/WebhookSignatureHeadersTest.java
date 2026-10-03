package com.bpm.core.webhook;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.WebhookTestSink;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * HMAC 簽章與重放偵測標頭（backlog #26 收尾，2026-10-02）。
 *
 * <h2>既有測試已證明的部分（這裡不重複）</h2>
 *
 * <p>{@code WebhookDeliveryWiringTest.createEventIsActuallyDelivered} 已經
 * 用「接收端以同一個 secret 對<b>收到的原始 body</b> 重算 HMAC，必須等於
 * {@code X-BPM-Signature} 標頭」釘住簽章本身，而且走的是真實的
 * BPMN → listener → queue → HTTP 路徑。這是 #26 的核心，已覆蓋。
 *
 * <h2>這裡補的是重放偵測那一半</h2>
 *
 * <p>簽章只證明「內容沒被改」。接收端還需要 {@code X-BPM-Timestamp} 與
 * {@code X-BPM-Delivery-Id} 才能做重放偵測，而 consumer 的註解明確說這兩個
 * 標頭存在的理由是「讓接收端不必先解析 body」。既有測試只斷言兩者非空，
 * 以下三件事因此沒有被證明：
 *
 * <ol>
 *   <li><b>標頭與 body 一致</b>：若 {@code X-BPM-Timestamp} 與 body 的
 *       {@code deliveryTimestamp} 不一致，接收端信標頭或信 body 會得到
 *       不同答案 —— 兩條路徑都聲稱是權威，但沒有測試說它們相同。</li>
 *   <li><b>時間戳是投遞當下</b>：若寫死或快取，接收端的重放窗會誤殺
 *       （永遠過期）或放行（永遠新鮮）。</li>
 *   <li><b>delivery id 每筆不同</b>：接收端用它去重；若重用同一組 id，
 *       第二筆正常通知會被當成重放丟掉。</li>
 * </ol>
 *
 * <p>直接送 queue（不繞 BPMN）是刻意的：這裡被測的是 consumer 產生的標頭，
 * 而 producer 端（listener 真的把訊息排入佇列）已由 #67 的兩支端到端測試
 * 證明。把 producer 再拉進來只會讓這個測試的紅燈更難歸因。
 */
class WebhookSignatureHeadersTest extends IntegrationTestBase {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private ObjectMapper objectMapper;

    /** 與 application.yml 同一個值；從設定讀，避免與設定漂移。 */
    @Value("${bpm.webhook.hmac-secret}") private String hmacSecret;

    @BeforeEach
    void resetSink() {
        WebhookTestSink.reset();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private void publish(String name, String businessKey) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "task.create");
        payload.put("businessKey", businessKey);
        payload.put("__webhookUrl",
                "http://localhost:" + SERVLET_PORT + "/mock/test-webhook-sink/" + name);
        payload.put("__webhookMethod", "POST");
        rabbitTemplate.convertAndSend("bpm.exchange", "bpm.webhook." + name, payload);
    }

    private void await(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean())
                .as("等待 15 秒後仍未發生：%s%n實際收到的投遞：%s", what, WebhookTestSink.received())
                .isTrue();
    }

    private Map<String, Object> body(WebhookTestSink.Received r) {
        try {
            return objectMapper.readValue(r.body(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new AssertionError("投遞 body 不是 JSON：" + r.body(), e);
        }
    }

    private String expectedHmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 測試 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("重放偵測標頭與 body 一致、時間戳是當下、delivery id 每筆不同")
    void replayHeadersAreConsistentAndUnique() {
        publish("sig", "SIG-1");
        publish("sig", "SIG-2");

        await(() -> WebhookTestSink.receivedTo("sig").size() == 2, "兩筆投遞");
        List<WebhookTestSink.Received> received = WebhookTestSink.receivedTo("sig");
        assertThat(received).hasSize(2);

        for (WebhookTestSink.Received r : received) {
            Map<String, Object> payload = body(r);

            // 簽章本身（既有測試已釘住的核心；這裡一併驗證讓本測試自我完整，
            // 也讓「改壞 HMAC」的負向控制組能在這個類別觀察到紅燈）。
            assertThat(r.signature())
                    .as("X-BPM-Signature 必須等於對收到的原始 body 重算的 HMAC")
                    .isEqualTo(expectedHmac(r.body()));

            // 舊缺陷的迴歸守門：簽章一度被塞進 body 後重新序列化，
            // 導致接收端無法重現位元相同的 JSON。現在只能出現在標頭。
            assertThat(payload)
                    .as("簽章只在標頭，不得塞回 body（舊缺陷的迴歸守門）")
                    .doesNotContainKey("hmacSignature");

            assertThat(r.timestamp())
                    .as("X-BPM-Timestamp 必須與 body 的 deliveryTimestamp 一致"
                            + "（consumer 註解聲稱接收端不必解析 body 就能用）")
                    .isEqualTo(payload.get("deliveryTimestamp"));
            Instant ts = Instant.parse(r.timestamp());
            assertThat(Duration.between(ts, Instant.now()).abs())
                    .as("時間戳必須是投遞當下，否則接收端的重放窗判斷會誤殺或放行")
                    .isLessThan(Duration.ofMinutes(1));

            assertThat(r.deliveryId())
                    .as("X-BPM-Delivery-Id 必須與 body 的 deliveryId 一致")
                    .isEqualTo(payload.get("deliveryId"));
            assertThatCode(() -> UUID.fromString(r.deliveryId()))
                    .as("delivery id 必須是可解析的 UUID，否則接收端無法用它去重")
                    .doesNotThrowAnyException();
        }

        assertThat(received)
                .extracting(WebhookTestSink.Received::deliveryId)
                .as("兩筆投遞不得共用 delivery id —— 共用會讓接收端把正常通知當重放丟掉")
                .doesNotHaveDuplicates();
    }
}
