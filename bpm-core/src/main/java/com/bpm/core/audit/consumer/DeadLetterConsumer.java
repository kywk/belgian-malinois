package com.bpm.core.audit.consumer;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dlq.DlqReplayService;
import com.bpm.core.dto.AuditEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 死信佇列的消費者與告警入口。
 *
 * <h2>為什麼需要它</h2>
 *
 * <p>{@code dlq.audit} 與 {@code dlq.bpm} 先前<b>沒有任何 consumer、
 * 也沒有任何告警</b>（security-audit P1-14）。一筆處理失敗的訊息會靜默沉到
 * 死信佇列，然後<b>永遠沒有人知道稽核少了一筆、或某個通知從未送出</b>。
 *
 * <p>這個 consumer 不嘗試「修復」訊息 —— 它把遺失<b>變成可收拾的</b>：
 * 以 ERROR 記錄完整內容，讓它進到日誌與告警管線，並且把訊息 parking
 * 起來保留原始 payload，供人工重放。
 *
 * <h2>#51：除了 log，再寫一筆稽核＋（選配）寄一封信</h2>
 *
 * <p>ERROR log 只有在有人看日誌時才存在。這裡補上兩條主動路徑：
 * <ul>
 *   <li><b>{@link OperationType#DLQ_MESSAGE} 稽核</b> —— 讓「什麼時候
 *       有幾筆死信」進入可查詢的軌跡（operator 是 {@code system}，
 *       來源比照引擎事件記 {@code engine}）。</li>
 *   <li><b>選配 email</b> —— 由 {@code bpm.dlq.alert-recipients} 設定；
 *       預設空＝不寄。收件人留空是安全的預設值：沒有明確指定時，
 *       系統不會把任何 DLQ 資訊寄到任何地方。</li>
 * </ul>
 *
 * <p>稽核 detail 與告警信<b>只放非敏感的中介資料</b>（queue、事件名、
 * messageId、payload 長度、x-death 摘要），<b>不放 payload 內容</b>：
 * 通知與 webhook 的 payload 可能含案件資料與簽核意見，而稽核庫與
 * 外寄郵件是兩個最不該複製這些內容的地方（security-audit P2-1 的同一條線）。
 * payload 全文仍照既有行為留在 ERROR log（截斷 2000 字）。
 *
 * <h2>#51 留存收尾：告警後 parking</h2>
 *
 * <p>告警只讓人知道訊息死了；訊息本身要留下來，人工重放才有東西可放。
 * 兩個 listener 在 {@link #alert} 之後把訊息重發布到對應的 parking queue
 * （{@code dlq.parking.bpm}／{@code dlq.parking.audit}，無 consumer、
 * 無 TTL），成功才返回（ack 原訊息）。重放（{@code DlqReplayService}）
 * 只讀 parking queue，因此任何時候都能把死信放回原始目的地，不再受限於
 * 「consumer 停用期間剛好累積的訊息」。
 *
 * <p>重發布前把 {@code x-death} 最舊一筆的起源抄成
 * {@code x-bpm-origin-exchange}／{@code x-bpm-origin-routing-key}／
 * {@code x-bpm-origin-queue} 自訂標頭：RabbitMQ 3.13 起不再把客戶端
 * 重發布的 x-death 當成死信紀錄維護（count 不再累加；4.x 將不再解讀），
 * 重放端不該依賴它的內容或存在；自訂標頭是本系統自己的契約，會原樣
 * 跟著訊息走。找不到 x-death 就不加標頭，重放會走 queue 名稱對照的
 * fallback。標頭名稱的常數定義在 {@link DlqReplayService}（讀取端），
 * 兩邊只有一份。
 *
 * <h2>⚠️ 這裡的例外處理決定成敗</h2>
 *
 * <p>{@code dlq.bpm}／{@code dlq.audit} <b>沒有設定 DLX</b>。consumer 若往外
 * 拋例外，Spring AMQP 會 requeue，於是同一筆訊息立刻被重投、再拋、
 * 再重投 —— 無限熱迴圈，同時把 listener 執行緒全部占滿。
 *
 * <p>所以告警（稽核與寄信）<b>全部</b>包在 try/catch 裡，任何失敗都只記
 * log。稽核走 {@code publishDetached}（不拋），寄信失敗也只記 log。
 * 這個 consumer 永遠正常返回 → 訊息被 ack 移除，不會迴圈。
 *
 * <p>⚠️ parking 失敗的取捨：重發布到 parking queue 失敗時，只記 ERROR
 * 後<b>照常返回</b>（訊息被 ack＝從原佇列消失）。拋例外會無限 requeue；
 * 而 parking 失敗多半是 broker／channel 層級的暫時故障，requeue 也不
 * 保證下一輪就會成功。此時 ERROR log 與 {@code DLQ_MESSAGE} 稽核都已
 * 寫入 —— 最壞情況仍可從 log 人工補救。這是「絕不熱迴圈」優先於
 * 「絕不遺失」的取捨。
 */
@Component
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    /** 稽核與寄信用的寄件者，與 {@code EmailConsumer} 一致。 */
    private static final String FROM = "bpm-noreply@company.com";

    private final AuditEventPublisher auditPublisher;
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final String alertRecipients;

    public DeadLetterConsumer(AuditEventPublisher auditPublisher,
                              JavaMailSender mailSender,
                              ObjectMapper objectMapper,
                              RabbitTemplate rabbitTemplate,
                              @Value("${bpm.dlq.alert-recipients:}") String alertRecipients) {
        this.auditPublisher = auditPublisher;
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.alertRecipients = alertRecipients;
    }

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
        alert("dlq.audit", message);
        park("dlq.parking.audit", message);
    }

    /**
     * BPM 事件死信（通知、webhook）。
     */
    @RabbitListener(queues = "dlq.bpm")
    public void handleBpmDeadLetter(Message message) {
        log.error("BPM 事件進入死信佇列 —— 該通知／webhook 未送出。payload={} headers={}",
                body(message), message.getMessageProperties().getHeaders());
        alert("dlq.bpm", message);
        park("dlq.parking.bpm", message);
    }

    /**
     * 把死信「停」進 parking queue（#51 留存收尾），供人工重放。
     *
     * <p>見類別註解：告警與 parking 分開，parking 失敗不影響告警，也不
     * 讓 listener 拋例外。重發布走 default exchange（routing key＝queue 名），
     * parking queue 不綁任何 exchange。
     */
    private void park(String parkingQueue, Message message) {
        try {
            rabbitTemplate.send("", parkingQueue, withOriginHeaders(message));
        } catch (Exception e) {
            // 見類別註解「parking 失敗的取捨」：訊息會被 ack 而從原佇列
            // 消失，但 log 與 DLQ_MESSAGE 稽核都在，仍可人工補救。
            log.error("DLQ 訊息 parking 失敗（重發布到 {}），訊息將被 ack 而從原佇列移除；"
                            + "ERROR log 與 DLQ_MESSAGE 稽核仍在，可據以人工補救。"
                            + "messageId={} 原因={}",
                    parkingQueue, message.getMessageProperties().getMessageId(), e.getMessage(), e);
        }
    }

    /**
     * 從 {@code x-death} 最舊一筆抄出起源，寫成自訂標頭。
     *
     * <p>RabbitMQ 3.13 起不再把客戶端重發布的 x-death 當死信紀錄維護
     * （4.x 不再解讀），重放端不依賴它；自訂標頭會原樣留在 parking
     * 訊息上。找不到 x-death（或欄位為空）就不加，重放會走 queue
     * 名稱對照的 fallback。
     */
    private static Message withOriginHeaders(Message message) {
        List<Map<String, ?>> xDeath = message.getMessageProperties().getXDeathHeader();
        if (xDeath == null || xDeath.isEmpty()) return message;

        // 慣例：最新在前、起源在後。取起源那一筆。
        Map<String, ?> origin = xDeath.get(xDeath.size() - 1);
        MessageProperties props = message.getMessageProperties();
        putIfPresent(props, DlqReplayService.ORIGIN_EXCHANGE_HEADER, string(origin.get("exchange")));
        putIfPresent(props, DlqReplayService.ORIGIN_ROUTING_KEY_HEADER,
                firstRoutingKey(origin.get("routing-keys")));
        putIfPresent(props, DlqReplayService.ORIGIN_QUEUE_HEADER, string(origin.get("queue")));
        return message;
    }

    private static void putIfPresent(MessageProperties props, String header, String value) {
        if (!value.isBlank()) props.getHeaders().put(header, value);
    }

    /**
     * 告警：稽核 + 選配 email。
     *
     * <p>三段各自獨立 try/catch —— 稽核失敗不該阻止寄信，寄信失敗也不該
     * 讓 listener 拋例外（見類別註解「例外處理決定成敗」）。
     */
    private void alert(String queue, Message message) {
        Map<String, Object> detail;
        try {
            detail = alertDetail(queue, message);
        } catch (Exception e) {
            log.error("DLQ 告警內容組裝失敗，改用最小內容。queue={} 原因={}", queue, e.getMessage(), e);
            detail = Map.of("queue", queue);
        }

        try {
            auditPublisher.publishDetached(new AuditEvent(
                    OperationType.DLQ_MESSAGE.name(),
                    // 由 broker 觸發，不是人類操作。與 TASK_UNREACHABLE／PROCESS_COMPLETE 相同記 "system"。
                    "system", "engine",
                    null, null, null, null,
                    detail, Instant.now()));
        } catch (Exception e) {
            // publishDetached 本身不拋；這裡是防禦性的第二道。
            log.error("DLQ 稽核寫入失敗（不影響訊息處理）。queue={} 原因={}", queue, e.getMessage(), e);
        }

        try {
            sendAlertEmail(queue, detail);
        } catch (Exception e) {
            log.error("DLQ 告警信寄送失敗（不影響訊息處理）。queue={} 原因={}", queue, e.getMessage(), e);
        }
    }

    /**
     * 稽核與告警信共用的內容。<b>只放非敏感的中介資料</b>，見類別註解。
     */
    private Map<String, Object> alertDetail(String queue, Message message) {
        MessageProperties props = message.getMessageProperties();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("queue", queue);
        detail.put("event", payloadEvent(message));
        detail.put("messageId", props.getMessageId() == null ? "" : props.getMessageId());
        detail.put("payloadChars", message.getBody() == null ? 0 : message.getBody().length);
        detail.put("xDeath", xDeathSummary(props));
        return detail;
    }

    /**
     * payload 裡的事件名（非敏感）。稽核事件的 payload 沒有 {@code event}，
     * 退回 {@code operationType} —— 讓「哪一種稽核掉了」看得出來。
     * 解析不到（非 JSON／空 body）時回空字串。
     */
    private String payloadEvent(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0) return "";
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.hasNonNull("event")) return node.get("event").asText();
            if (node.hasNonNull("operationType")) return node.get("operationType").asText();
        } catch (Exception e) {
            // 非 JSON payload：沒有可安全帶出的事件名，留空。
        }
        return "";
    }

    /**
     * x-death 摘要（broker 中介資料，非業務內容）。
     * 每一筆取 queue／reason／exchange／第一個 routing key／count。
     */
    private static List<Map<String, Object>> xDeathSummary(MessageProperties props) {
        List<Map<String, ?>> xDeath = props.getXDeathHeader();
        if (xDeath == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, ?> entry : xDeath) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("queue", string(entry.get("queue")));
            e.put("reason", string(entry.get("reason")));
            e.put("exchange", string(entry.get("exchange")));
            e.put("routingKey", firstRoutingKey(entry.get("routing-keys")));
            e.put("count", entry.get("count") == null ? 0L : entry.get("count"));
            out.add(e);
        }
        return out;
    }

    /**
     * 選配的告警信。{@code bpm.dlq.alert-recipients} 為空（預設）時完全不寄。
     */
    private void sendAlertEmail(String queue, Map<String, Object> detail) {
        List<String> recipients = parseRecipients(alertRecipients);
        if (recipients.isEmpty()) return;

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setTo(recipients.toArray(String[]::new));
        mail.setFrom(FROM);
        mail.setSubject("【BPM】DLQ 警報：" + queue);
        mail.setText(buildAlertText(queue, detail));
        mailSender.send(mail);
        log.info("DLQ 告警信已寄出：queue={} recipients={}", queue, recipients);
    }

    /** 告警信內容：與稽核同一組非敏感中介資料。 */
    private static String buildAlertText(String queue, Map<String, Object> detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("死信佇列收到訊息，需人工處理（重放：POST /api/admin/dlq/replay?queue=")
                .append("dlq.audit".equals(queue) ? "audit" : "bpm").append("）。\n\n");
        sb.append("queue: ").append(queue).append('\n');
        sb.append("event: ").append(detail.getOrDefault("event", "")).append('\n');
        sb.append("messageId: ").append(detail.getOrDefault("messageId", "")).append('\n');
        sb.append("payloadChars: ").append(detail.getOrDefault("payloadChars", 0)).append('\n');
        sb.append("x-death: ").append(detail.getOrDefault("xDeath", List.of())).append('\n');
        sb.append("\n（payload 內容不在本信與稽核中，只留在伺服器 ERROR log。）");
        return sb.toString();
    }

    private static List<String> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String firstRoutingKey(Object routingKeys) {
        if (routingKeys instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
            return list.get(0).toString();
        }
        return "";
    }

    private static String body(Message message) {
        byte[] b = message.getBody();
        if (b == null || b.length == 0) return "(empty)";
        // 截斷避免超長 payload 塞爆日誌
        String s = new String(b, StandardCharsets.UTF_8);
        return s.length() > 2000 ? s.substring(0, 2000) + "...(truncated)" : s;
    }
}
