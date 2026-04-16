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
