package com.bpm.core.dlq;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工重放死信佇列（#51）。
 *
 * <h2>為什麼需要人工重放</h2>
 *
 * <p>死信代表一筆業務事件在自動重試耗盡後<b>確定沒有被處理</b>
 * （通知沒寄、webhook 沒送、稽核沒寫）。自動重試只能處理暫時性故障；
 * 當故障是「下游系統掛了半天」或「設定寫錯」時，修好之後需要有人
 * 把那些訊息重新送一次 —— 否則只能靠人工比對與手動補償，那正是
 * DLQ 告警要讓人知道、而重放要讓人能收拾的閉環。
 *
 * <h2>重放來源是 parking queue</h2>
 *
 * <p>死信在告警後由 {@code DeadLetterConsumer} 重發布到
 * {@code dlq.parking.bpm}／{@code dlq.parking.audit}（無 consumer、
 * 無 TTL），重放只讀這兩個 parking queue：訊息既然留著，任何時候
 * 都可以再放一次。
 *
 * <p>舊的 {@code dlq.bpm}／{@code dlq.audit} 不在重放範圍內 —— 它們有
 * consumer，重放去搶會與告警競爭，而且訊息被消費後就沒了。服務中斷／
 * consumer 停用期間累積在舊 DLQ 的訊息，在服務恢復後會由 consumer
 * 自動 parking，所以「只讀 parking」仍是完整解；若 consumer 被永久
 * 停用，那些訊息連告警都不會發生，也就沒有本端點要收的閉環。
 *
 * <h2>目的地的取法：origin 標頭 → x-death → queue 對照表</h2>
 *
 * <p>重放必須回到<b>原始目的地</b>，不是回到 DLX。解析順序：
 * <ol>
 *   <li><b>{@code x-bpm-origin-*} 自訂標頭</b>（主要路徑）：
 *       parking 時由 consumer 把 {@code x-death} 最舊一筆的起源抄下來。
 *       RabbitMQ 3.13 起不再把客戶端重發布的 x-death 當死信紀錄維護
 *       （4.x 將不再解讀），所以 parking 訊息不依賴它；自訂標頭是本
 *       系統自己的契約，會原樣跟著訊息走。</li>
 *   <li><b>{@code x-death} 最舊一筆</b>：舊版 broker、或繞過 consumer
 *       直接放進 parking 的訊息可能帶著它。實測 3.13.7：重發布後它仍
 *       留在訊息上（只是不再被 broker 更新），所以這條不是死路；但它的
 *       存在與內容因版本而異，只當備援。慣例上最新的一筆在最前面、
 *       起源在最後，所以取最後一筆的 {@code exchange} ＋
 *       {@code routing-keys[0]}。</li>
 *   <li><b>queue 名稱對照表</b>：都沒有時直接經 default exchange 重投到
 *       原始 queue（見 {@link #FALLBACK_QUEUE} 與 {@link #fallbackQueue}）。</li>
 * </ol>
 *
 * <p>⚠️ <b>絕對不可以重投到 {@code dlx.exchange}</b>：那會讓訊息被
 * binding 再撿回 DLQ，重放變成原地打轉，而且每一次都消耗一次 max。
 * origin 標頭與 x-death 的 {@code exchange} 記的都是<b>原始發布的
 * exchange</b>（本例是 {@code bpm.exchange}），不是 DLX —— 但兩條路徑
 * 都加同一道 {@code dlx.exchange} 防呆，因為這個欄位的語意一旦被誤解，
 * 後果就是上面那個迴圈。
 *
 * <p>queue 對照表：{@code dlq.parking.audit} → {@code audit.log.queue}
 * （audit.exchange 只有這個來源）；{@code dlq.parking.bpm} → 依 payload
 * 是否帶 {@code __webhookUrl} 分辨 {@code bpm.webhook.queue} 或
 * {@code bpm.notify.queue}。parking queue 只有一個名字，卻有兩個可能的
 * 來源，而兩者「補送」的語意完全不同（webhook 是外送 HTTP、通知是寄信）
 * —— 送錯等於沒補。
 *
 * <p>走 fallback 時記 WARN，讓維運分得出「正常重放」與「猜的」。
 *
 * <h2>⚠️ 冪等性：這是 at-least-once，不是 exactly-once</h2>
 *
 * <p>流程是「重投成功 → basicAck」。若在兩者之間斷線，訊息會重新
 * 出現在 parking queue（ack 未送達），下次重放就<b>再送一次</b>。原訊息
 * 也可能其實早已成功、只是 ack 前失敗，而 parking 裡的那筆是重複的。
 * 因此重放<b>可能造成重複投遞</b>，這是刻意的取捨：漏送比重送更難發現
 * 也更難補救。下游（{@code WebhookConsumer} 的 {@code deliveryId}、
 * {@code EmailConsumer} 的收件人去重語意）必須容忍重複。
 *
 * <h2>失敗就停，不熱迴圈</h2>
 *
 * <p>重投失敗時 {@code basicNack(requeue=true)} 把訊息放回 parking queue
 * 並<b>停止本輪</b>。若失敗原因是 broker／網路問題，繼續抓下一筆只會
 * 製造更多失敗與更混亂的狀態；讓呼叫端看到失敗、修好再重放。
 * nack 本身也失敗時，訊息會停留在 unacked，連線關閉時由 broker 自動
 * requeue —— 不會遺失。
 *
 * <p>⚠️ <b>已知邊界：重投到「已不存在的綁定」會被靜默丟棄。</b>
 * 發布沒有開 mandatory 旗標（開了也要處理 basic.return 的非同步回呼，
 * 不在本輪範圍）。若原始 exchange／binding 已被刪除，訊息不會進到任何
 * 佇列，但這裡會照樣 ack —— 佇列不會無聲長大，但那一筆也就沒了。
 * 實務上這代表「重放前先確認原流程／原佇列還在」；要讓它變成可偵測的
 * 錯誤需要 publisher confirms，是後續工項。
 *
 * <h2>重放的 webhook 會再走一次 SSRF 閘門</h2>
 *
 * <p>重投到 {@code bpm.webhook.queue} 的訊息會由 {@code WebhookConsumer}
 * 重新消費，而它會在送出 HTTP 前先過 {@code WebhookUrlPolicy}。
 * 重放<b>不繞過</b>任何安全檢查：一則被閘門拒絕的訊息不會因為
 * 「人工重放」而被打出去，它只會再被記一次 ERROR 後丟棄。
 */
@Service
public class DlqReplayService {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayService.class);

    /**
     * API 的 queue 參數 → 實際 RabbitMQ 佇列（parking queue）。
     * 只有這兩個值，其餘 400。
     */
    static final Map<String, String> QUEUES = Map.of(
            "bpm", "dlq.parking.bpm",
            "audit", "dlq.parking.audit");

    /**
     * parking 時由 {@code DeadLetterConsumer} 寫入的起源標頭。
     *
     * <p>標頭名稱是 producer／consumer 之間的契約，常數只定義在這裡
     * （讀取端），consumer 直接引用 —— 兩份字串各自漂移就會讓重放
     * 靜默退回 fallback。{@link #ORIGIN_QUEUE_HEADER} 只供維運判讀，
     * 不用於路由。
     */
    public static final String ORIGIN_EXCHANGE_HEADER = "x-bpm-origin-exchange";
    public static final String ORIGIN_ROUTING_KEY_HEADER = "x-bpm-origin-routing-key";
    public static final String ORIGIN_QUEUE_HEADER = "x-bpm-origin-queue";

    /** 單次重放的訊息數上限。防的是「誤觸把整個 DLQ 一次打回下游」。 */
    static final int MAX_MESSAGES_PER_REQUEST = 1000;

    /** 沒有 origin 標頭也沒有 x-death 時，parking 佇列 → 原始佇列。 */
    private static final Map<String, String> FALLBACK_QUEUE = Map.of(
            "dlq.parking.audit", "audit.log.queue");

    /** 死信訊息的 {@code x-death} 標頭轉成 Spring {@link MessageProperties}。 */
    private static final DefaultMessagePropertiesConverter PROPS_CONVERTER =
            new DefaultMessagePropertiesConverter();

    private final ConnectionFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;
    private final AuditEventPublisher auditPublisher;
    private final ObjectMapper objectMapper;

    public DlqReplayService(ConnectionFactory connectionFactory,
                            RabbitTemplate rabbitTemplate,
                            AuditEventPublisher auditPublisher,
                            ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.rabbitTemplate = rabbitTemplate;
        this.auditPublisher = auditPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * 把 {@code dlqQueue} 裡的訊息重投回原始目的地並 ack，最多 {@code max} 筆。
     *
     * @param queueParam API 參數（僅 {@code bpm}／{@code audit}）
     * @param max        本輪最多處理幾筆（1..{@link #MAX_MESSAGES_PER_REQUEST}）
     * @param operatorId 呼叫者（稽核用）
     * @throws ResponseStatusException queue 不在白名單或 max 超出範圍（400）；
     *                                 完全無法連上 broker、一筆都沒處理時（503）
     */
    public ReplayResult replay(String queueParam, int max, String operatorId) {
        // 白名單與上限的規則只寫在這裡一份：controller 不重複驗證，
        // 呼叫端（含未來的排程／CLI）走同一條路也得到同一組限制。
        String dlqQueue = QUEUES.get(queueParam);
        if (dlqQueue == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "queue 只接受 bpm／audit（對應 dlq.parking.bpm／dlq.parking.audit）：" + queueParam);
        }
        if (max < 1 || max > MAX_MESSAGES_PER_REQUEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "max 必須介於 1 與 " + MAX_MESSAGES_PER_REQUEST + " 之間：" + max);
        }

        int replayed = 0;
        int failed = 0;
        int fallbackUsed = 0;
        // 最後一次 basicGet 回報的剩餘量。null 代表沒抓到任何訊息也還沒問過
        // （empty queue 會是 0）。
        Long remaining = null;

        // ⚠️ basicGet(queue, false) 手動 ack：不能用 rabbitTemplate.receive()，
        // 它預設 autoAck —— 訊息會在我們有機會重投之前就從佇列消失。
        try (var connection = connectionFactory.createConnection();
             var channel = connection.createChannel(false)) {

            for (int i = 0; i < max; i++) {
                var got = channel.basicGet(dlqQueue, false);
                if (got == null) {
                    remaining = 0L;
                    break;
                }
                // 剩餘量是「這筆被取出後」的佇列長度快照。有並行消費者時
                // 本來就只是近似值，回報給呼叫端參考用。
                remaining = (long) got.getMessageCount();
                long deliveryTag = got.getEnvelope().getDeliveryTag();
                Message message = new Message(got.getBody(),
                        PROPS_CONVERTER.toMessageProperties(
                                got.getProps(), got.getEnvelope(), StandardCharsets.UTF_8.name()));

                Destination destination = resolveDestination(dlqQueue, message);

                try {
                    // ⚠️ x-death 不隨重放保證：RabbitMQ 3.13 起不再把客戶端
                    // 重發布的 x-death 當死信紀錄維護（實測 3.13.7 它會留在
                    // 訊息上、但不再被更新；4.x 不再解讀）。重放的訊息在下游
                    // 看起來與「第一次發布」相同（這正是「重放」要的語意）；
                    // origin 標頭是 parking 的契約、對下游無意義，留著無害。
                    rabbitTemplate.send(destination.exchange(), destination.routingKey(), message);
                } catch (Exception e) {
                    log.error("DLQ 重放失敗，訊息放回佇列並停止本輪。queue={} 目的地={}/{} 原因={}",
                            dlqQueue, destination.exchange(), destination.routingKey(), e.getMessage(), e);
                    failed++;
                    // 重投失敗 → 放回 DLQ。這不是「再試一次」，只是不把訊息吃掉；
                    // 下一輪由人決定（可能故障還沒修好）。
                    nackQuietly(channel, deliveryTag);
                    // 剛 nack 的這筆回到佇列，快照 +1；僅供參考。
                    remaining = remaining == null ? null : remaining + 1;
                    break;
                }

                try {
                    channel.basicAck(deliveryTag, false);
                    replayed++;
                    if (destination.source() == DestinationSource.FALLBACK) fallbackUsed++;
                    log.info("DLQ 重放成功：queue={} → exchange={} routingKey={} 來源={} messageId={}",
                            dlqQueue, destination.exchange(), destination.routingKey(),
                            destination.source(),
                            message.getMessageProperties().getMessageId());
                } catch (Exception e) {
                    // 重投已成功、ack 失敗：訊息稍後會被 broker 重投（重複），
                    // 如類別註解所述這是 at-least-once 的必然窗口。
                    log.error("DLQ 重放已投遞但 ack 失敗，訊息將被重投（可能重複）。"
                            + "queue={} messageId={} 原因={}",
                            dlqQueue, message.getMessageProperties().getMessageId(), e.getMessage(), e);
                    failed++;
                    remaining = remaining == null ? null : remaining + 1;
                    break;
                }
            }
        } catch (Exception e) {
            if (replayed == 0 && failed == 0) {
                // 連第一筆都沒碰到（多半是 broker 連不上）。不能回「0 筆」
                // 假裝佇列是空的 —— 那會讓維運以為沒事了。回 503 讓呼叫端
                // 知道「這次根本沒執行」，可以稍後重試。
                log.error("DLQ 重放無法開始（broker 連線失敗）：queue={} 原因={}",
                        dlqQueue, e.getMessage(), e);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "無法連線 RabbitMQ 重放 DLQ：" + e.getMessage(), e);
            }
            // 跑到一半才斷：已處理的訊息已各自 ack 或 nack，這裡把整體結果
            // 記下來並回報 partial。未完成的那一筆會在 channel 關閉後由
            // broker requeue，所以剩餘量 +1。
            failed++;
            remaining = remaining == null ? null : remaining + 1;
            log.error("DLQ 重放中斷：queue={} 已重放={} 已失敗={} 原因={}",
                    dlqQueue, replayed, failed, e.getMessage(), e);
        }

        ReplayResult result = new ReplayResult(dlqQueue, replayed, failed, remaining);
        auditReplay(queueParam, max, result, fallbackUsed, operatorId);
        return result;
    }

    /**
     * 解析原始目的地。
     *
     * <p>順序：origin 標頭 → {@code x-death} 最舊一筆 → queue 名稱對照表
     * （見類別註解）。缺欄位、空值、或指到 {@code dlx.exchange} 時往下退，
     * 退到 fallback 時記 WARN。
     *
     * <p>package-private：解析規則是本工項最容易錯的一段（origin 標頭的
     * 優先序、x-death 的順序、dlx 防呆、fallback 分辨），由
     * {@code DlqReplayServiceDestinationTest} 直接以組出來的訊息釘住它
     * —— 客戶端無法偽造 broker 產生的 x-death（且 3.13 起重發布的
     * x-death 不再被更新），所以這個規則無法只靠整合測試覆蓋。
     */
    Destination resolveDestination(String dlqQueue, Message message) {
        MessageProperties props = message.getMessageProperties();

        // 1) parking 時寫入的 origin 標頭：本系統自己的契約，跨 broker
        //    版本都在，是 parking 訊息的主要路徑。
        String originExchange = string(props.getHeader(ORIGIN_EXCHANGE_HEADER));
        String originRoutingKey = string(props.getHeader(ORIGIN_ROUTING_KEY_HEADER));
        if (usableDestination(originExchange, originRoutingKey)) {
            return new Destination(originExchange, originRoutingKey, DestinationSource.ORIGIN_HEADER);
        }
        if (!originExchange.isBlank() || !originRoutingKey.isBlank()) {
            log.warn("DLQ 訊息的 origin 標頭不完整或指向 dlx.exchange，往下改用 x-death／fallback。"
                            + "queue={} origin={}/{}",
                    dlqQueue, originExchange, originRoutingKey);
        }

        // 2) x-death 最舊一筆：舊版 broker、或繞過 consumer 直接放進
        //    parking 的訊息可能還帶著它。
        List<Map<String, ?>> xDeath = props.getXDeathHeader();
        if (xDeath != null && !xDeath.isEmpty()) {
            // 慣例：最新在前、起源在後。取起源。
            Map<String, ?> origin = xDeath.get(xDeath.size() - 1);
            String exchange = string(origin.get("exchange"));
            String routingKey = firstRoutingKey(origin.get("routing-keys"));
            if (usableDestination(exchange, routingKey)) {
                return new Destination(exchange, routingKey, DestinationSource.X_DEATH);
            }
            log.warn("DLQ 訊息的 x-death 缺少可用的 exchange／routing-keys（或指向 dlx.exchange），"
                            + "改用 queue 名稱對照表 fallback。queue={} x-death={}",
                    dlqQueue, origin);
        } else {
            log.warn("DLQ 訊息沒有 origin 標頭也沒有 x-death，改用 queue 名稱對照表 fallback。queue={}",
                    dlqQueue);
        }

        // fallback 直接投回原始 queue（default exchange 以 queue 名為 routing key），
        // 不需要知道原本的 routing key —— origin 資訊缺失時它也不在。
        return new Destination("", fallbackQueue(dlqQueue, message), DestinationSource.FALLBACK);
    }

    /** 目的地可用＝exchange 與 routing key 都在，且不是會原地打轉的 DLX。 */
    private static boolean usableDestination(String exchange, String routingKey) {
        return !exchange.isBlank() && !routingKey.isBlank() && !"dlx.exchange".equals(exchange);
    }

    private String fallbackQueue(String dlqQueue, Message message) {
        String fixed = FALLBACK_QUEUE.get(dlqQueue);
        if (fixed != null) return fixed;
        if ("dlq.parking.bpm".equals(dlqQueue)) {
            return isWebhookMessage(message) ? "bpm.webhook.queue" : "bpm.notify.queue";
        }
        // 白名單已擋掉其他值，正常到不了這裡。
        throw new IllegalStateException("沒有 fallback 對照的 DLQ 佇列：" + dlqQueue);
    }

    /**
     * {@code dlq.parking.bpm} 的兩個來源只能靠 payload 分辨：webhook 訊息
     * 一定帶 {@code __webhookUrl}（{@code WebhookConfigResolver} 在發布前
     * 放入），通知訊息沒有。用 JSON 欄位而不是字串包含，避免內文剛好提到
     * 這個字。
     */
    private boolean isWebhookMessage(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0) return false;
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.hasNonNull("__webhookUrl");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 重放的稽核。
     *
     * <p>用 {@code publishDetached} 而不是 fail-closed 的 {@code publish}：
     * 重放<b>已經動了 broker 狀態</b>（訊息已投遞、已 ack）。此時稽核寫入
     * 失敗若回 503，呼叫端會以為「什麼都沒做」而重呼一次 —— 那會把
     * 整批訊息再送一遍。稽核失敗只記 log，結果照實回報。
     */
    private void auditReplay(String queueParam, int max, ReplayResult result,
                             int fallbackUsed, String operatorId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "replay");
        detail.put("queue", result.queue());
        detail.put("requestedQueue", queueParam);
        detail.put("max", max);
        detail.put("replayed", result.replayed());
        detail.put("failed", result.failed());
        detail.put("remaining", result.remaining());
        detail.put("fallbackUsed", fallbackUsed);

        auditPublisher.publishDetached(new AuditEvent(
                OperationType.DLQ_REPLAY.name(),
                operatorId != null && !operatorId.isBlank() ? operatorId : "unknown",
                "user",
                null, null, null, null,
                detail, Instant.now()));
    }

    /** nack 失敗不能蓋掉原始失敗：訊息留在 unacked，連線關閉時 broker 會自動 requeue。 */
    private static void nackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.error("DLQ 重放的 basicNack 也失敗，訊息將在連線關閉後由 broker requeue：{}", e.getMessage(), e);
        }
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    /** {@code routing-keys} 是清單；取第一筆。 */
    private static String firstRoutingKey(Object routingKeys) {
        if (routingKeys instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
            return list.get(0).toString();
        }
        return "";
    }

    /** 重放的原始目的地。{@code exchange} 空字串代表 default exchange。 */
    record Destination(String exchange, String routingKey, DestinationSource source) {
    }

    /** 目的地是從哪裡解析出來的；{@code fallbackUsed} 只算 {@link #FALLBACK}。 */
    enum DestinationSource { ORIGIN_HEADER, X_DEATH, FALLBACK }

    /**
     * 重放結果。
     *
     * @param queue     實際處理的 parking 佇列（{@code dlq.parking.bpm}／{@code dlq.parking.audit}）
     * @param replayed  成功重投並 ack 的筆數
     * @param failed    重投或 ack 失敗的筆數（最多 1，失敗即停）
     * @param remaining 佇列剩餘量的快照；null 代表無法取得
     */
    public record ReplayResult(String queue, int replayed, int failed, Long remaining) {
    }
}
