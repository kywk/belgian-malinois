package com.bpm.core.audit.consumer;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code DeadLetterConsumer} 的告警：內容邊界與「絕不拋例外」（#51）。
 *
 * <h2>為什麼這個測試非有不可</h2>
 *
 * <p>{@code dlq.bpm}／{@code dlq.audit} 沒有 DLX。consumer 往外拋例外時，
 * Spring AMQP 會 requeue → 同一筆訊息立刻重投 → 再拋 → <b>無限熱迴圈</b>，
 * 把 listener 執行緒占滿。所以「稽核掛掉」與「寄信掛掉」這兩條路徑
 * 必須證明<b>不會</b>把例外漏出去。
 *
 * <p>用 mock 而不是整合測試：要讓真實稽核 DB 在 listener 執行緒裡壞掉
 * 很難安排（停容器會影響整個 context），而這裡要驗的是 consumer 的
 * 邊界行為，不是 DB 的行為。負控組：把 consumer 裡的 try/catch 拿掉，
 * {@link #alertFailuresDoNotEscape()} 立刻紅。
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

    private DeadLetterConsumer consumer(String recipients) {
        return new DeadLetterConsumer(auditPublisher, mailSender, objectMapper, recipients);
    }

    private static Message message(String json) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId("msg-1");
        Map<String, Object> death = new LinkedHashMap<>();
        death.put("queue", "bpm.webhook.queue");
        death.put("reason", "rejected");
        death.put("exchange", "bpm.exchange");
        death.put("routing-keys", List.of("bpm.webhook.task"));
        death.put("count", 3L);
        props.getHeaders().put("x-death", List.of(death));
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
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
    @DisplayName("告警只帶非敏感中介資料；secret 不得進稽核與信件")
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
    }

    @Test
    @DisplayName("收件人預設空＝完全不寄信，但稽核照寫")
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
    }
}
