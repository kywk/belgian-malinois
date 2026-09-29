package com.bpm.core.audit.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 死信佇列的消費者。
 *
 * <h2>為什麼需要它</h2>
 *
 * <p>{@code dlq.audit} 與 {@code dlq.bpm} 先前<b>沒有任何 consumer、
 * 也沒有任何告警</b>（security-audit P1-14）。一筆處理失敗的訊息會靜默沉到
 * 死信佇列，然後<b>永遠沒有人知道稽核少了一筆、或某個通知從未送出</b>。
 *
 * <p>這個 consumer 不嘗試「修復」訊息 —— 它的唯一職責是讓遺失<b>可被看見</b>：
 * 以 ERROR 記錄完整內容，讓它進到日誌與告警管線，並且保留原始 payload
 * 以便人工重放。
 *
 * <p>⚠️ 這不是完整的解法。真正的告警需要把這些 ERROR 接到監控系統
 * （以及決定誰負責處理），那屬於維運設計。但「先讓它可見」是任何後續處理
 * 的前提 —— 目前連可見性都沒有。
 */
@Component
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    /**
     * 稽核事件死信。
     *
     * <p>這一條特別嚴重：代表一筆稽核記錄確定沒有寫進 DB，
     * 而它對應的業務操作早已回 200 完成。
     */
    @RabbitListener(queues = "dlq.audit")
    public void handleAuditDeadLetter(Message message) {
        log.error("稽核事件進入死信佇列 —— 這筆稽核確定沒有寫入，"
                        + "對應的業務操作已經完成。需人工重放。payload={} headers={}",
                body(message), message.getMessageProperties().getHeaders());
    }

    /**
     * BPM 事件死信（通知、webhook）。
     */
    @RabbitListener(queues = "dlq.bpm")
    public void handleBpmDeadLetter(Message message) {
        log.error("BPM 事件進入死信佇列 —— 該通知／webhook 未送出。payload={} headers={}",
                body(message), message.getMessageProperties().getHeaders());
    }

    private static String body(Message message) {
        byte[] b = message.getBody();
        if (b == null || b.length == 0) return "(empty)";
        // 截斷避免超長 payload 塞爆日誌
        String s = new String(b, StandardCharsets.UTF_8);
        return s.length() > 2000 ? s.substring(0, 2000) + "...(truncated)" : s;
    }
}
