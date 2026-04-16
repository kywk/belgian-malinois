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

    public WebhookConsumer(ObjectMapper objectMapper,
                           @Value("${bpm.webhook.hmac-secret:bpm-webhook-secret}") String hmacSecret) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
        this.hmacSecret = hmacSecret;
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

        try {
            String json = objectMapper.writeValueAsString(payload);
            String signature = computeHmac(json);
            payload.put("hmacSignature", "sha256=" + signature);
            String signedJson = objectMapper.writeValueAsString(payload);

            var request = restClient.method(
                    "PUT".equalsIgnoreCase(method) ? org.springframework.http.HttpMethod.PUT : org.springframework.http.HttpMethod.POST
            ).uri(url)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-BPM-Signature", "sha256=" + signature)
                    .body(signedJson);

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
