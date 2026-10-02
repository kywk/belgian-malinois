package com.bpm.core.dlq;

import com.bpm.core.audit.AuditEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 重放目的地解析規則（#51）。
 *
 * <h2>為什麼這是單元測試而不是整合測試</h2>
 *
 * <p>真實 broker（RabbitMQ 3.13+）<b>會剝掉客戶端發布的 {@code x-death}</b>
 * —— 它是 broker 自己的標頭，應用不得偽造。所以「多筆 x-death 取最舊」
 * 這種形狀無法用「放一筆訊息進 DLQ」製造；整合測試改走真實死信路徑
 * （retry 耗盡）驗證正常情境，這裡用組出來的 {@link Message} 釘住
 * 那些真實路徑難以穩定製造的分支：多筆時的順序、{@code dlx.exchange}
 * 防呆、欄位缺漏、fallback 的來源分辨。
 *
 * <p>被測方法是 package-private 的同一份實作（不是複製品）——
 * 規則只有一份。
 */
class DlqReplayServiceDestinationTest {

    private final DlqReplayService service = new DlqReplayService(
            mock(ConnectionFactory.class),
            mock(RabbitTemplate.class),
            mock(AuditEventPublisher.class),
            new ObjectMapper());

    private static Message message(String json, List<Map<String, Object>> xDeath) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (xDeath != null) {
            props.getHeaders().put("x-death", xDeath);
        }
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }

    private static Map<String, Object> death(String queue, String exchange, String routingKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queue", queue);
        m.put("reason", "rejected");
        m.put("exchange", exchange);
        m.put("routing-keys", List.of(routingKey));
        m.put("count", 1L);
        return m;
    }

    @Test
    @DisplayName("多筆 x-death：取最舊（最後）一筆 —— 最新在前、起源在後")
    void usesOldestEntryWhenMultiple() {
        var d = service.resolveDestination("dlq.bpm", message("{}", List.of(
                death("dlq.bpm", "dlx.exchange", "bpm"),
                death("bpm.webhook.queue", "bpm.exchange", "bpm.webhook.oldest"))));

        assertThat(d.exchange()).isEqualTo("bpm.exchange");
        assertThat(d.routingKey()).isEqualTo("bpm.webhook.oldest");
        assertThat(d.fromXDeath()).isTrue();
    }

    @Test
    @DisplayName("x-death 指到 dlx.exchange：不重投 DLX，改走 fallback")
    void neverRoutesToDlx() {
        var d = service.resolveDestination("dlq.bpm", message(
                "{\"__webhookUrl\":\"http://example/hook\"}",
                List.of(death("bpm.webhook.queue", "dlx.exchange", "bpm"))));

        assertThat(d.exchange())
                .as("重投 dlx.exchange 會被 binding 撿回 DLQ，原地打轉")
                .isEmpty();
        assertThat(d.routingKey()).isEqualTo("bpm.webhook.queue");
        assertThat(d.fromXDeath()).isFalse();
    }

    @Test
    @DisplayName("x-death 欄位缺漏：fallback，不猜")
    void malformedXDeathFallsBack() {
        Map<String, Object> noExchange = death("bpm.webhook.queue", "", "bpm.webhook.task");
        var d1 = service.resolveDestination("dlq.bpm",
                message("{\"__webhookUrl\":\"http://example/hook\"}", List.of(noExchange)));
        assertThat(d1.fromXDeath()).isFalse();
        assertThat(d1.routingKey()).isEqualTo("bpm.webhook.queue");

        Map<String, Object> emptyRoutingKeys = death("bpm.webhook.queue", "bpm.exchange", "");
        var d2 = service.resolveDestination("dlq.bpm",
                message("{\"__webhookUrl\":\"http://example/hook\"}", List.of(emptyRoutingKeys)));
        assertThat(d2.fromXDeath()).isFalse();
        assertThat(d2.routingKey()).isEqualTo("bpm.webhook.queue");
    }

    @Test
    @DisplayName("無 x-death fallback：audit → audit.log.queue；bpm 依 __webhookUrl 分辨")
    void fallbackByQueueName() {
        var audit = service.resolveDestination("dlq.audit",
                message("{\"operationType\":\"TASK_APPROVE\"}", null));
        assertThat(audit.exchange()).isEmpty();
        assertThat(audit.routingKey()).isEqualTo("audit.log.queue");
        assertThat(audit.fromXDeath()).isFalse();

        var webhook = service.resolveDestination("dlq.bpm",
                message("{\"event\":\"task.create\",\"__webhookUrl\":\"http://example/hook\"}", null));
        assertThat(webhook.routingKey()).isEqualTo("bpm.webhook.queue");

        var notify = service.resolveDestination("dlq.bpm",
                message("{\"event\":\"task_assigned\",\"assignee\":\"user001\"}", null));
        assertThat(notify.routingKey()).isEqualTo("bpm.notify.queue");

        // 非 JSON／空 body 分不出 webhook，安全預設是通知佇列（不會外送 HTTP）。
        var unknown = service.resolveDestination("dlq.bpm", message("not-json", null));
        assertThat(unknown.routingKey()).isEqualTo("bpm.notify.queue");
    }
}
