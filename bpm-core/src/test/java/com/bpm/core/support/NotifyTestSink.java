package com.bpm.core.support;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知訊息（{@code bpm.notify.*}）的測試觀察點（#33／#6）。
 *
 * <h2>為什麼不能只看「流程有沒有推進」</h2>
 *
 * <p>通知的失敗型態是「什麼都不發生」：沒有例外、沒有 5xx、流程照跑。
 * 所以測試必須真的看到訊息。作法是宣告一個測試專用 queue 綁到
 * {@code bpm.exchange}（topic），routing key 與正式通知相同
 * （{@code bpm.notify.#}）—— topic exchange 會把訊息複製給<b>每個</b>
 * 相符的 queue，因此 {@code EmailConsumer} 的正式 queue 照常運作，
 * 測試拿到的是同一則訊息的副本。
 *
 * <h2>⚠️ 消費是破壞性的</h2>
 *
 * <p>{@code drain()} 會把 queue 上的訊息取走。同一條測試裡若要斷言
 * 「有 A 且沒有 B」，必須取一次、在記憶體裡篩選，不要連續呼叫兩次
 * 不同事件的 await（第二次會看不到第一次已經取走的訊息）。
 * 每個測試開頭呼叫 {@link #reset()} 清空，避免前一條測試的殘留。
 *
 * <h2>為什麼不需要等待非同步</h2>
 *
 * <p>{@code RabbitTemplate.convertAndSend} 是同步的（送到 broker 才返回），
 * 而通知都在 HTTP 請求內發送完畢，所以 request 返回時訊息已在 queue 上。
 * {@code drain} 仍留一個短 timeout，吸收 broker 端的延遲。
 */
public final class NotifyTestSink {

    /** 測試專用 queue 名稱。所有使用本 sink 的測試共用（每個測試自己 purge）。 */
    private static final String QUEUE = "test.notify.sink";

    /** 與 RabbitMQConfig 的 exchange 契約。 */
    private static final String EXCHANGE = "bpm.exchange";

    private static RabbitTemplate template;
    private static AmqpAdmin admin;

    private NotifyTestSink() {
    }

    /** 由測試在 {@code @BeforeEach} 注入容器裡的 Rabbit 元件（只需一次）。 */
    public static synchronized void install(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        if (template != null) return;
        template = rabbitTemplate;
        admin = amqpAdmin;
        admin.declareQueue(QueueBuilder.nonDurable(QUEUE).build());
        admin.declareBinding(BindingBuilder.bind(new Queue(QUEUE))
                .to(new TopicExchange(EXCHANGE))
                .with("bpm.notify.#"));
    }

    /** 清空 queue（每個測試的起點）。 */
    public static void reset() {
        installIfPossible();
        admin.purgeQueue(QUEUE);
    }

    /**
     * 取出目前 queue 上的所有訊息。
     *
     * <p>持續取到連續一次 300ms 內沒有新訊息為止 ——
     * 同步發送之下這通常是一次往返，timeout 只是雨天保險。
     */
    public static List<Map<String, Object>> drain() {
        installIfPossible();
        List<Map<String, Object>> out = new ArrayList<>();
        for (;;) {
            Object o = template.receiveAndConvert(QUEUE, 300);
            if (o == null) break;
            if (o instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                out.add(copy);
            }
        }
        return out;
    }

    /** 從 {@link #drain()} 的結果篩出某個事件。 */
    public static List<Map<String, Object>> events(List<Map<String, Object>> messages, String event) {
        return messages.stream().filter(m -> event.equals(m.get("event"))).toList();
    }

    /** 等待某個事件出現（少數非同步情境用；逾時回空清單）。 */
    public static List<Map<String, Object>> awaitEvent(String event, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<Map<String, Object>> all = new ArrayList<>();
        while (Instant.now().isBefore(deadline)) {
            all.addAll(drain());
            if (!events(all, event).isEmpty()) return all;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return all;
    }

    private static void installIfPossible() {
        if (template == null) {
            throw new IllegalStateException(
                    "NotifyTestSink.install(RabbitTemplate, AmqpAdmin) 必須先在 @BeforeEach 呼叫");
        }
    }
}
