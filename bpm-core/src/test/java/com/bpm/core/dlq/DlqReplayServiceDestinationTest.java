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
 * <p>客戶端無法偽造 broker 產生的 {@code x-death}；RabbitMQ 3.13 起也不再
 * 把客戶端重發布的 x-death 當死信紀錄維護（實測 3.13.7 仍留在訊息上、
 * 但不再更新；4.x 不再解讀）。所以「多筆 x-death 取最舊」這種形狀無法用
 * 「放一筆訊息進 DLQ」製造；整合測試改走真實死信路徑（retry 耗盡 →
 * consumer parking）驗證正常情境，這裡用組出來的 {@link Message} 釘住那些
 * 真實路徑難以穩定製造的分支：origin 標頭的優先序、多筆 x-death 的順序、
 * {@code dlx.exchange} 防呆、欄位缺漏、fallback 的來源分辨。
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

    /** 帶 parking origin 標頭的訊息（DeadLetterConsumer 在重發布前寫入）。 */
    private static Message parked(String json, String exchange, String routingKey, String queue) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (exchange != null) props.getHeaders().put(DlqReplayService.ORIGIN_EXCHANGE_HEADER, exchange);
        if (routingKey != null) props.getHeaders().put(DlqReplayService.ORIGIN_ROUTING_KEY_HEADER, routingKey);
        if (queue != null) props.getHeaders().put(DlqReplayService.ORIGIN_QUEUE_HEADER, queue);
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
    @DisplayName("origin 標頭優先於 x-death —— parking 的主要路徑")
    void originHeadersWinOverXDeath() {
        Message message = parked("{\"__webhookUrl\":\"http://example/hook\"}",
                "bpm.exchange", "bpm.webhook.origin", "bpm.webhook.queue");
        // x-death 就算還在（舊版 broker），也不能蓋掉 origin 標頭。
        message.getMessageProperties().getHeaders().put("x-death",
                List.of(death("bpm.webhook.queue", "bpm.exchange", "bpm.webhook.xdeath")));

        var d = service.resolveDestination("dlq.parking.bpm", message);

        assertThat(d.exchange()).isEqualTo("bpm.exchange");
        assertThat(d.routingKey()).isEqualTo("bpm.webhook.origin");
        assertThat(d.source()).isEqualTo(DlqReplayService.DestinationSource.ORIGIN_HEADER);
    }

    @Test
    @DisplayName("origin 標頭指到 dlx.exchange：不重投 DLX，改用 x-death")
    void originHeadersNeverRouteToDlx() {
        Message message = parked("{\"__webhookUrl\":\"http://example/hook\"}",
                "dlx.exchange", "bpm.webhook.origin", "bpm.webhook.queue");
        message.getMessageProperties().getHeaders().put("x-death",
                List.of(death("bpm.webhook.queue", "bpm.exchange", "bpm.webhook.real")));

        var d = service.resolveDestination("dlq.parking.bpm", message);

        assertThat(d.exchange())
                .as("重投 dlx.exchange 會被 binding 撿回 DLQ，原地打轉")
                .isEqualTo("bpm.exchange");
        assertThat(d.routingKey()).isEqualTo("bpm.webhook.real");
        assertThat(d.source()).isEqualTo(DlqReplayService.DestinationSource.X_DEATH);
    }

    @Test
    @DisplayName("origin 標頭缺欄位：往下退到 x-death，最後才是 fallback")
    void incompleteOriginHeadersFallThrough() {
        // 只有 exchange、沒有 routing key → x-death 可用時用 x-death。
        Message withXDeath = parked("{\"__webhookUrl\":\"http://example/hook\"}",
                "bpm.exchange", null, "bpm.webhook.queue");
        withXDeath.getMessageProperties().getHeaders().put("x-death",
                List.of(death("bpm.webhook.queue", "bpm.exchange", "bpm.webhook.xdeath")));
        var viaXDeath = service.resolveDestination("dlq.parking.bpm", withXDeath);
        assertThat(viaXDeath.routingKey()).isEqualTo("bpm.webhook.xdeath");
        assertThat(viaXDeath.source()).isEqualTo(DlqReplayService.DestinationSource.X_DEATH);

        // 只有 routing key、沒有 exchange，且沒有 x-death → fallback。
        var viaFallback = service.resolveDestination("dlq.parking.bpm",
                parked("{\"__webhookUrl\":\"http://example/hook\"}", null, "bpm.webhook.origin", null));
        assertThat(viaFallback.exchange()).isEmpty();
        assertThat(viaFallback.routingKey()).isEqualTo("bpm.webhook.queue");
        assertThat(viaFallback.source()).isEqualTo(DlqReplayService.DestinationSource.FALLBACK);
    }

    @Test
    @DisplayName("多筆 x-death：取最舊（最後）一筆 —— 最新在前、起源在後")
    void usesOldestEntryWhenMultiple() {
        var d = service.resolveDestination("dlq.parking.bpm", message("{}", List.of(
                death("dlq.bpm", "dlx.exchange", "bpm"),
                death("bpm.webhook.queue", "bpm.exchange", "bpm.webhook.oldest"))));

        assertThat(d.exchange()).isEqualTo("bpm.exchange");
        assertThat(d.routingKey()).isEqualTo("bpm.webhook.oldest");
        assertThat(d.source()).isEqualTo(DlqReplayService.DestinationSource.X_DEATH);
    }

    @Test
    @DisplayName("x-death 指到 dlx.exchange：不重投 DLX，改走 fallback")
    void neverRoutesToDlx() {
        var d = service.resolveDestination("dlq.parking.bpm", message(
                "{\"__webhookUrl\":\"http://example/hook\"}",
                List.of(death("bpm.webhook.queue", "dlx.exchange", "bpm"))));

        assertThat(d.exchange())
                .as("重投 dlx.exchange 會被 binding 撿回 DLQ，原地打轉")
                .isEmpty();
        assertThat(d.routingKey()).isEqualTo("bpm.webhook.queue");
        assertThat(d.source()).isEqualTo(DlqReplayService.DestinationSource.FALLBACK);
    }

    @Test
    @DisplayName("x-death 欄位缺漏：fallback，不猜")
    void malformedXDeathFallsBack() {
        Map<String, Object> noExchange = death("bpm.webhook.queue", "", "bpm.webhook.task");
        var d1 = service.resolveDestination("dlq.parking.bpm",
                message("{\"__webhookUrl\":\"http://example/hook\"}", List.of(noExchange)));
        assertThat(d1.source()).isEqualTo(DlqReplayService.DestinationSource.FALLBACK);
        assertThat(d1.routingKey()).isEqualTo("bpm.webhook.queue");

        Map<String, Object> emptyRoutingKeys = death("bpm.webhook.queue", "bpm.exchange", "");
        var d2 = service.resolveDestination("dlq.parking.bpm",
                message("{\"__webhookUrl\":\"http://example/hook\"}", List.of(emptyRoutingKeys)));
        assertThat(d2.source()).isEqualTo(DlqReplayService.DestinationSource.FALLBACK);
        assertThat(d2.routingKey()).isEqualTo("bpm.webhook.queue");
    }

    @Test
    @DisplayName("無 origin 也無 x-death fallback：audit → audit.log.queue；bpm 依 __webhookUrl 分辨")
    void fallbackByQueueName() {
        var audit = service.resolveDestination("dlq.parking.audit",
                message("{\"operationType\":\"TASK_APPROVE\"}", null));
        assertThat(audit.exchange()).isEmpty();
        assertThat(audit.routingKey()).isEqualTo("audit.log.queue");
        assertThat(audit.source()).isEqualTo(DlqReplayService.DestinationSource.FALLBACK);

        var webhook = service.resolveDestination("dlq.parking.bpm",
                message("{\"event\":\"task.create\",\"__webhookUrl\":\"http://example/hook\"}", null));
        assertThat(webhook.routingKey()).isEqualTo("bpm.webhook.queue");

        var notify = service.resolveDestination("dlq.parking.bpm",
                message("{\"event\":\"task_assigned\",\"assignee\":\"user001\"}", null));
        assertThat(notify.routingKey()).isEqualTo("bpm.notify.queue");

        // 非 JSON／空 body 分不出 webhook，安全預設是通知佇列（不會外送 HTTP）。
        var unknown = service.resolveDestination("dlq.parking.bpm", message("not-json", null));
        assertThat(unknown.routingKey()).isEqualTo("bpm.notify.queue");
    }
}
