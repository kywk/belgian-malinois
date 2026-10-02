package com.bpm.core.audit.consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dlq.DlqReplayService;
import com.bpm.core.dto.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code DeadLetterConsumer} 的告警與 parking：內容邊界與「絕不拋例外」（#51）。
 *
 * <h2>為什麼這個測試非有不可</h2>
 *
 * <p>{@code dlq.bpm}／{@code dlq.audit} 沒有 DLX。consumer 往外拋例外時，
 * Spring AMQP 會 requeue → 同一筆訊息立刻重投 → 再拋 → <b>無限熱迴圈</b>，
 * 把 listener 執行緒占滿。所以「稽核掛掉」「寄信掛掉」「parking 掛掉」
 * 這三條路徑必須證明<b>不會</b>把例外漏出去。
 *
 * <p>用 mock 而不是整合測試：要讓真實稽核 DB 或 broker 在 listener
 * 執行緒裡壞掉很難安排（停容器會影響整個 context），而這裡要驗的是
 * consumer 的邊界行為，不是外部系統的行為。負控組：把 consumer 裡的
 * try/catch 拿掉，{@link #alertFailuresDoNotEscape()} 或
 * {@link #parkingFailureDoesNotEscape()} 立刻紅。
 *
 * <h2>內容邊界</h2>
 *
 * <p>稽核與告警信都不得複製 payload 內容（可能有案件資料與簽核意見）。
 * {@link #alertCarriesOnlyNonSensitiveMetadata()} 用一個獨特的 secret
 * 字串釘住這件事 —— 它必須出現在伺服器 log，但不得出現在稽核或信件裡。
 */
class DeadLetterConsumerAlertTest {

    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    private DeadLetterConsumer consumer(String recipients) {
        return new DeadLetterConsumer(auditPublisher, mailSender, objectMapper, rabbitTemplate, recipients);
    }

    private static MessageProperties jsonProps() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId("msg-1");
        return props;
    }

    private static Message message(String json) {
        MessageProperties props = jsonProps();
        props.getHeaders().put("x-death", List.of(death("bpm.webhook.queue", "bpm.exchange",
                "bpm.webhook.task", 3L)));
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }

    /** 沒有 x-death 的訊息（人工塞進 DLQ、或來自不支援 x-death 的路徑）。 */
    private static Message messageWithoutXDeath(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), jsonProps());
    }

    private static Map<String, Object> death(String queue, String exchange, String routingKey, long count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queue", queue);
        m.put("reason", "rejected");
        m.put("exchange", exchange);
        m.put("routing-keys", List.of(routingKey));
        m.put("count", count);
        return m;
    }

    /** 取出 consumer 實際 parking 的訊息。 */
    private Message parked(String parkingQueue) {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(parkingQueue), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("稽核與寄信同時失敗，listener 仍不得拋例外（DLQ 沒有 DLX，拋了就熱迴圈）")
    void alertFailuresDoNotEscape() {
        doThrow(new RuntimeException("audit db down"))
                .when(auditPublisher).publishDetached(any());
        doThrow(new RuntimeException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        var c = consumer("ops@company.com");

        assertThatCode(() -> c.handleBpmDeadLetter(message("{\"event\":\"task.create\"}")))
                .as("稽核失敗漏出例外 → dlq.bpm 無限 requeue")
                .doesNotThrowAnyException();
        assertThatCode(() -> c.handleAuditDeadLetter(message("{\"operationType\":\"TASK_APPROVE\"}")))
                .as("寄信失敗漏出例外 → dlq.audit 無限 requeue")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("parking 失敗：只記 ERROR，listener 不拋（訊息被 ack 遺失，但稽核仍在）")
    void parkingFailureDoesNotEscape() {
        doThrow(new RuntimeException("channel closed"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        Logger consumerLogger = (Logger) LoggerFactory.getLogger(DeadLetterConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        consumerLogger.addAppender(appender);
        try {
            var c = consumer("");

            assertThatCode(() -> c.handleBpmDeadLetter(message("{\"event\":\"task.create\"}")))
                    .as("parking 失敗漏出例外 → dlq.bpm 無限 requeue")
                    .doesNotThrowAnyException();

            assertThat(appender.list)
                    .as("parking 失敗必須留下 ERROR log —— 訊息已被 ack，log 是唯一補救線索")
                    .anyMatch(e -> e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("parking 失敗"));

            // 訊息沒了，但告警的稽核必須已經寫入（可據以人工補救）。
            verify(auditPublisher).publishDetached(any());
        } finally {
            consumerLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("告警只帶非敏感中介資料；secret 不得進稽核與信件；parking 帶 origin 標頭")
    void alertCarriesOnlyNonSensitiveMetadata() {
        String secret = "SECRET-PAYLOAD-DO-NOT-LEAK";
        var c = consumer("ops@company.com");

        c.handleBpmDeadLetter(message(
                "{\"event\":\"task.create\",\"secret\":\"" + secret + "\"}"));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publishDetached(event.capture());
        assertThat(event.getValue().operationType()).isEqualTo("DLQ_MESSAGE");
        assertThat(event.getValue().operatorId()).isEqualTo("system");
        assertThat(event.getValue().operatorSource()).isEqualTo("engine");

        Map<String, Object> detail = event.getValue().detail();
        assertThat(detail)
                .containsEntry("queue", "dlq.bpm")
                .containsEntry("event", "task.create")
                .containsEntry("messageId", "msg-1")
                .containsEntry("payloadChars", ("{\"event\":\"task.create\",\"secret\":\""
                        + secret + "\"}").length());
        assertThat(detail.get("xDeath").toString())
                .as("x-death 摘要讓維運知道訊息從哪來")
                .contains("bpm.webhook.queue")
                .contains("rejected")
                .contains("bpm.exchange")
                .contains("bpm.webhook.task");
        assertThat(detail.toString())
                .as("稽核不得複製 payload 內容")
                .doesNotContain(secret);

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("ops@company.com");
        assertThat(mail.getValue().getSubject()).contains("dlq.bpm");
        assertThat(mail.getValue().getText())
                .as("外寄郵件不得複製 payload 內容")
                .doesNotContain(secret)
                .contains("bpm.webhook.queue");

        Message parked = parked("dlq.parking.bpm");
        assertThat(new String(parked.getBody(), StandardCharsets.UTF_8))
                .as("parking 必須保留完整 payload 才能重放")
                .contains(secret);
        assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_EXCHANGE_HEADER))
                .isEqualTo("bpm.exchange");
        assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_ROUTING_KEY_HEADER))
                .isEqualTo("bpm.webhook.task");
        assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_QUEUE_HEADER))
                .isEqualTo("bpm.webhook.queue");
    }

    @Test
    @DisplayName("origin 標頭取 x-death 最舊一筆（最新在前、起源在後）")
    void originHeadersComeFromOldestDeath() {
        MessageProperties props = jsonProps();
        props.getHeaders().put("x-death", List.of(
                death("dlq.bpm", "dlx.exchange", "bpm", 1L),
                death("bpm.webhook.queue", "bpm.exchange", "bpm.webhook.oldest", 3L)));
        Message message = new Message("{\"event\":\"task.create\"}".getBytes(StandardCharsets.UTF_8), props);

        var c = consumer("");
        c.handleBpmDeadLetter(message);

        Message parked = parked("dlq.parking.bpm");
        assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_EXCHANGE_HEADER))
                .as("起源是最後一筆，不是最新一筆")
                .isEqualTo("bpm.exchange");
        assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_ROUTING_KEY_HEADER))
                .isEqualTo("bpm.webhook.oldest");
        assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_QUEUE_HEADER))
                .isEqualTo("bpm.webhook.queue");
    }

    @Test
    @DisplayName("沒有 x-death：照常 parking 但不加 origin 標頭（重放走 fallback）")
    void parkingWithoutXDeathAddsNoOriginHeaders() {
        var c = consumer("");
        c.handleBpmDeadLetter(messageWithoutXDeath("{\"event\":\"task_assigned\"}"));

        Message parked = parked("dlq.parking.bpm");
        assertThat(parked.getMessageProperties().getHeaders())
                .doesNotContainKeys(
                        DlqReplayService.ORIGIN_EXCHANGE_HEADER,
                        DlqReplayService.ORIGIN_ROUTING_KEY_HEADER,
                        DlqReplayService.ORIGIN_QUEUE_HEADER);
    }

    @Test
    @DisplayName("收件人預設空＝完全不寄信，但稽核照寫、訊息照 parking")
    void emptyRecipientsMeansNoMail() {
        var c = consumer("");

        c.handleAuditDeadLetter(message("{\"operationType\":\"TASK_APPROVE\"}"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publishDetached(event.capture());
        assertThat(event.getValue().detail())
                .containsEntry("queue", "dlq.audit")
                .as("稽核 payload 沒有 event 時退回 operationType")
                .containsEntry("event", "TASK_APPROVE");
        verify(rabbitTemplate).send(eq(""), eq("dlq.parking.audit"), any(Message.class));
    }
}
