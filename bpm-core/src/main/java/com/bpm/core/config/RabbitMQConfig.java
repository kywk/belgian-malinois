package com.bpm.core.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Dead Letter Exchange
    @Bean
    public TopicExchange dlxExchange() {
        return new TopicExchange("dlx.exchange");
    }

    @Bean
    public Queue dlqBpm() {
        return QueueBuilder.durable("dlq.bpm").build();
    }

    @Bean
    public Binding dlqBpmBinding() {
        return BindingBuilder.bind(dlqBpm()).to(dlxExchange()).with("bpm.#");
    }

    // BPM Exchange
    @Bean
    public TopicExchange bpmExchange() {
        return new TopicExchange("bpm.exchange");
    }

    @Bean
    public Queue bpmNotifyQueue() {
        return QueueBuilder.durable("bpm.notify.queue")
                .withArgument("x-dead-letter-exchange", "dlx.exchange")
                .withArgument("x-dead-letter-routing-key", "bpm.notify")
                .build();
    }

    @Bean
    public Queue bpmWebhookQueue() {
        return QueueBuilder.durable("bpm.webhook.queue")
                .withArgument("x-dead-letter-exchange", "dlx.exchange")
                .withArgument("x-dead-letter-routing-key", "bpm.webhook")
                .build();
    }

    @Bean
    public Binding bpmNotifyBinding() {
        return BindingBuilder.bind(bpmNotifyQueue()).to(bpmExchange()).with("bpm.notify.#");
    }

    @Bean
    public Binding bpmWebhookBinding() {
        return BindingBuilder.bind(bpmWebhookQueue()).to(bpmExchange()).with("bpm.webhook.#");
    }

    // Audit Exchange
    @Bean
    public TopicExchange auditExchange() {
        return new TopicExchange("audit.exchange");
    }

    @Bean
    public Queue dlqAudit() {
        return QueueBuilder.durable("dlq.audit").build();
    }

    @Bean
    public Binding dlqAuditBinding() {
        return BindingBuilder.bind(dlqAudit()).to(dlxExchange()).with("audit.#");
    }

    // ── DLQ Parking（#51 留存收尾） ─────────────────────────────────
    //
    // 名字取「停車場」：DeadLetterConsumer 告警完把死信「停」進來，等人工
    // 重放（POST /api/admin/dlq/replay）再開走。它和 dlq.* 的差別是
    // dlq.* 有 consumer、訊息一進來就被消費；parking 刻意「無 consumer」，
    // 所以訊息只進不出（除了重放端點），不再依賴「consumer 剛好停用」。
    //
    // 為什麼獨立成兩個 queue 而不是擴充 dlq.*：重放的來源必須與告警的
    // 消費來源分開，否則 consumer 與重放會搶同一筆訊息；且 dlq.* 之後
    // 若加 TTL／DLX 也不會波及留存的死信。
    //
    // ⚠️ 不設 TTL、不設 DLX：
    // - 不設 TTL —— parking 的語意是「保留到人工處理」。TTL 到期會把
    //   還沒人看過的死信靜默刪掉，那正是本功能要消除的風險。
    // - 不設 DLX —— 這裡的訊息不該再被自動搬走；設了只會製造第二層
    //   死信迴圈（而且 parking 無 consumer，也永遠不會 reject）。
    @Bean
    public Queue dlqParkingBpm() {
        return QueueBuilder.durable("dlq.parking.bpm").build();
    }

    @Bean
    public Queue dlqParkingAudit() {
        return QueueBuilder.durable("dlq.parking.audit").build();
    }

    @Bean
    public Queue auditLogQueue() {
        return QueueBuilder.durable("audit.log.queue")
                .withArgument("x-dead-letter-exchange", "dlx.exchange")
                .withArgument("x-dead-letter-routing-key", "audit.log")
                .build();
    }

    @Bean
    public Binding auditLogBinding() {
        return BindingBuilder.bind(auditLogQueue()).to(auditExchange()).with("audit.#");
    }
}
