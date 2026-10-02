package com.bpm.core.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

@Component
public class WebhookConsumer {

    private static final Logger log = LoggerFactory.getLogger(WebhookConsumer.class);
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String hmacSecret;

    private final WebhookUrlPolicy urlPolicy;

    public WebhookConsumer(ObjectMapper objectMapper,
                           WebhookUrlPolicy urlPolicy,
                           // ⚠️ 不得在此加回 @Value fallback（2026-10-02 裁決）：
                           // 程式碼內的預設值會讓屬性缺席時靜默用公開在 repo 的開發密鑰簽章。
                           // 缺值就讓 placeholder 解析失敗（fail-fast）。dev 預設值由
                           // application.yml base 文件提供；prod 另由 WebhookHmacSecretValidator
                           // 拒絕空白或開發預設值。
                           @Value("${bpm.webhook.hmac-secret}") String hmacSecret,
                           @Value("${bpm.webhook.connect-timeout-ms:2000}") long connectTimeoutMs,
                           @Value("${bpm.webhook.read-timeout-ms:5000}") long readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.urlPolicy = urlPolicy;
        this.hmacSecret = hmacSecret;
        // ⚠️ 逾時不可省略（security-audit P2-1）。RestClient.create() 沒有逾時，
        // 掛住的 endpoint 會佔住 listener 執行緒直到 TCP 超時 ——
        // 而 concurrency 預設是 1，整條 webhook 佇列會被一個壞掉的接收端阻塞。
        var rf = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(java.time.Duration.ofMillis(connectTimeoutMs));
        rf.setReadTimeout(java.time.Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(rf).build();
    }

    @RabbitListener(queues = "bpm.webhook.queue")
    public void handle(Map<String, Object> payload) {
        // For now, log the webhook payload. In production, webhook URLs come from
        // BPMN extensionElements or a webhook config table.
        // This consumer handles the actual HTTP delivery.
        String url = (String) payload.remove("__webhookUrl");
        String method = (String) payload.remove("__webhookMethod");
        if (url == null || url.isBlank()) {
            log.debug("Webhook event received (no URL configured): {}", payload.get("event"));
            return;
        }

        // ── SSRF 閘門（security-audit P2-1）─────────────────────────
        String reason = urlPolicy.rejectionReason(url);
        if (reason != null) {
            // 不重新拋出：這不是暫時性失敗，重試只會重複同一次攻擊嘗試。
            // 記 ERROR 讓它可見（該事件會因此不投遞，那是刻意的）。
            log.error("Webhook 目標位址被拒絕，不投遞。url={} 原因={} 允許清單={}",
                    url, reason, urlPolicy.allowedHosts());
            return;
        }

        try {
            // ⚠️ 簽章必須對「實際送出的 body」計算。
            //
            // 改動前：signature = HMAC(json)，其中 json 尚未含 hmacSignature；
            // 但送出的 body 是塞入該欄位後「重新序列化」的 signedJson。
            // 接收端要驗章就得移除欄位並重現位元完全相同的 JSON，
            // 而 payload 是 HashMap、Jackson 鍵序不保證 →
            // 有 X-BPM-Signature 標頭卻沒有任何可用的完整性保護。
            //
            // 現在簽章只放標頭、不塞進 body（GitHub／Stripe 的做法），
            // 接收端直接對收到的原始 body 計算 HMAC 即可驗證。
            payload.put("deliveryTimestamp", java.time.Instant.now().toString());
            payload.put("deliveryId", java.util.UUID.randomUUID().toString());
            String body = objectMapper.writeValueAsString(payload);
            String signature = computeHmac(body);

            var request = restClient.method(
                    "PUT".equalsIgnoreCase(method) ? org.springframework.http.HttpMethod.PUT : org.springframework.http.HttpMethod.POST
            ).uri(url)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-BPM-Signature", "sha256=" + signature)
                    // 時間戳與 delivery id 也放標頭，讓接收端能做重放偵測
                    // 而不必先解析 body。
                    .header("X-BPM-Timestamp", String.valueOf(payload.get("deliveryTimestamp")))
                    .header("X-BPM-Delivery-Id", String.valueOf(payload.get("deliveryId")))
                    .body(body);

            request.retrieve().toBodilessEntity();
            log.info("Webhook delivered to {}: {}", url, payload.get("event"));
        } catch (Exception e) {
            log.error("Webhook delivery failed to {}: {}", url, e.getMessage());
            throw new RuntimeException(e); // triggers retry → DLQ
        }
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
