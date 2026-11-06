package com.bpm.core.dlq;

import com.bpm.core.audit.AuditEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class DlqReplayPublishFailureTest {
    @Test
    void missingBindingKeepsParkedMessageUntilRouteReturns() throws Exception {
        try (var broker = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"))) {
            broker.start();
            var factory = new CachingConnectionFactory(broker.getHost(), broker.getAmqpPort());
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
            factory.setUsername(broker.getAdminUsername());
            factory.setPassword(broker.getAdminPassword());
            try {
                var admin = new RabbitAdmin(factory);
                String parking = "dlq.parking.bpm";
                var target = new Queue("wave.target");
                var exchange = new org.springframework.amqp.core.DirectExchange("wave.origin");
                admin.declareQueue(new Queue(parking));
                admin.declareQueue(target);
                admin.declareExchange(exchange);
                var client = new RabbitTemplate(factory);
                String id = java.util.UUID.randomUUID().toString();
                byte[] body = ("wave-" + id).getBytes(StandardCharsets.UTF_8);
                client.send("", parking, MessageBuilder.withBody(body).setMessageId(id)
                        .setHeader(DlqReplayService.ORIGIN_EXCHANGE_HEADER, exchange.getName())
                        .setHeader(DlqReplayService.ORIGIN_ROUTING_KEY_HEADER, "restore").build());
                var service = new DlqReplayService(factory, client, mock(AuditEventPublisher.class), new ObjectMapper());
                var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                        .standaloneSetup(new DlqAdminController(service)).build();
                var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/dlq/replay").param("queue", "bpm").param("max", "1");
                var first = mvc.perform(request).andReturn().getResponse();
                long depth = admin.getQueueInfo(parking).getMessageCount();
                assertThat(first.getStatus()).isEqualTo(200);
                assertThat(first.getContentAsString()).as("parking=%s", depth).contains("\"failed\":1", "\"replayed\":0");
                assertThat(depth).isOne();
                assertThat(client.receive(target.getName())).isNull();
                admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(target).to(exchange).with("restore"));
                var second = mvc.perform(request).andReturn().getResponse();
                assertThat(second.getStatus()).isEqualTo(200);
                assertThat(second.getContentAsString()).contains("\"replayed\":1", "\"failed\":0");
                var got = client.receive(target.getName(), 2000);
                assertThat(got).isNotNull();
                assertThat(got.getBody()).isEqualTo(body);
                assertThat(got.getMessageProperties().getMessageId()).isEqualTo(id);
                assertThat(client.receive(parking)).isNull();
                assertThat(client.receive(target.getName())).isNull();
            } finally {
                factory.destroy();
            }
        }
    }

    @Test
    void failedPublishLeavesOriginalAvailableForTheNextReplay() {
        try (var broker = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"))) {
            broker.start();
            var factory = new CachingConnectionFactory(broker.getHost(), broker.getAmqpPort());
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
            factory.setUsername(broker.getAdminUsername());
            factory.setPassword(broker.getAdminPassword());
            try {
                var admin = new RabbitAdmin(factory);
                String parking = "dlq.parking.bpm";
                String target = "bpm.notify.queue";
                admin.declareQueue(new Queue(parking));
                admin.declareQueue(new Queue(target));
                var client = new RabbitTemplate(factory);
                byte[] body = "{\"event\":\"task_assigned\"}".getBytes(StandardCharsets.UTF_8);
                client.send("", parking, MessageBuilder.withBody(body).setMessageId("publish-failure-case").build());

                var publisher = spy(client);
                doThrow(new AmqpException("injected publish failure")).doCallRealMethod()
                        .when(publisher).send(eq(""), eq(target), any(Message.class), any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
                var service = new DlqReplayService(factory, publisher, mock(AuditEventPublisher.class), new ObjectMapper());

                assertThat(service.replay("bpm", 2, "operator"))
                        .isEqualTo(new DlqReplayService.ReplayResult(parking, 0, 1, 1L));
                assertThat(client.receive(target)).isNull();
                assertThat(service.replay("bpm", 1, "operator"))
                        .isEqualTo(new DlqReplayService.ReplayResult(parking, 1, 0, 0L));
                var delivered = client.receive(target, 2000);
                assertThat(delivered).isNotNull();
                assertThat(delivered.getBody()).isEqualTo(body);
                assertThat(delivered.getMessageProperties().getMessageId()).isEqualTo("publish-failure-case");
                assertThat(client.receive(target)).isNull();
                assertThat(client.receive(parking)).isNull();
            } finally {
                factory.destroy();
            }
        }
    }
}
