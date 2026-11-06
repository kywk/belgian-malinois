package com.bpm.core.dlq;

import com.bpm.core.audit.AuditEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DlqReplayLostConfirmTest {
    @Test
    void lostConfirmStopsBatchAndRetryDuplicatesOnlyUnconfirmedMessage() throws Exception {
        try (var broker = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"))) {
            broker.start();
            try (var proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                var confirms = new AtomicInteger();
                var forwarding = Thread.ofVirtual().start(() -> forwardPublisher(proxy, broker, confirms));
                var direct = factory(broker, broker.getHost(), broker.getAmqpPort());
                var publisher = factory(broker, proxy.getInetAddress().getHostAddress(), proxy.getLocalPort());
                try {
                    String parking = "dlq.parking.bpm";
                    String target = "bpm.notify.queue";
                    var admin = new RabbitAdmin(direct);
                    admin.declareQueue(new Queue(parking));
                    admin.declareQueue(new Queue(target));
                    var client = new RabbitTemplate(direct);
                    String first = UUID.randomUUID().toString();
                    String second = UUID.randomUUID().toString();
                    for (String id : new String[]{first, second}) {
                        client.send("", parking, MessageBuilder.withBody(body(id)).setMessageId(id).build());
                    }

                    var service = new DlqReplayService(direct, new RabbitTemplate(publisher),
                            mock(AuditEventPublisher.class), new ObjectMapper());
                    var mvc = MockMvcBuilders.standaloneSetup(new DlqAdminController(service)).build();
                    var replay = post("/api/admin/dlq/replay").param("queue", "bpm").param("max", "2");
                    mvc.perform(replay).andExpect(status().isOk()).andExpect(jsonPath("$.failed").value(1))
                            .andExpect(jsonPath("$.replayed").value(0)).andExpect(jsonPath("$.remaining").value(2));
                    assertThat(confirms.get()).isOne();
                    assertThat(admin.getQueueInfo(parking).getMessageCount()).isEqualTo(2);
                    receive(client, target, first);
                    assertThat(client.receive(target)).isNull();

                    var single = post("/api/admin/dlq/replay").param("queue", "bpm").param("max", "1");
                    mvc.perform(single).andExpect(status().isOk()).andExpect(jsonPath("$.failed").value(0))
                            .andExpect(jsonPath("$.replayed").value(1)).andExpect(jsonPath("$.remaining").value(1));
                    receive(client, target, first);
                    mvc.perform(single).andExpect(status().isOk()).andExpect(jsonPath("$.failed").value(0))
                            .andExpect(jsonPath("$.replayed").value(1)).andExpect(jsonPath("$.remaining").value(0));
                    receive(client, target, second);
                    assertThat(confirms.get()).isEqualTo(3);
                    assertThat(client.receive(target)).isNull();
                    assertThat(client.receive(parking)).isNull();
                } finally {
                    publisher.destroy();
                    direct.destroy();
                    proxy.close();
                    forwarding.join(5000);
                }
            }
        }
    }

    private static byte[] body(String id) {
        return ("{\"event\":\"task_assigned\",\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static void receive(RabbitTemplate client, String queue, String id) {
        var message = client.receive(queue, 2000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat(message.getBody()).isEqualTo(body(id));
    }

    private static CachingConnectionFactory factory(RabbitMQContainer broker, String host, int port) {
        var factory = new CachingConnectionFactory(host, port);
        factory.setUsername(broker.getAdminUsername());
        factory.setPassword(broker.getAdminPassword());
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    // Only the publishing connection crosses this proxy. Consume, nack and parking ack use direct.
    private static void forwardPublisher(ServerSocket listener, RabbitMQContainer broker, AtomicInteger confirms) {
        try (var client = listener.accept(); var upstream = new Socket(broker.getHost(), broker.getAmqpPort())) {
            Thread.ofVirtual().start(() -> {
                try {
                    client.getInputStream().transferTo(upstream.getOutputStream());
                    upstream.shutdownOutput();
                } catch (IOException closed) {
                    if (!client.isClosed()) throw new java.io.UncheckedIOException(closed);
                }
            });
            var from = new DataInputStream(upstream.getInputStream());
            var to = new DataOutputStream(client.getOutputStream());
            int type;
            while ((type = from.read()) != -1) {
                int channel = from.readUnsignedShort();
                byte[] payload = new byte[from.readInt()];
                from.readFully(payload);
                int end = from.readUnsignedByte();
                // AMQP 0-9-1 method frame, class basic (60), method ack (80).
                if (type == 1 && payload.length >= 4 && payload[0] == 0 && payload[1] == 60
                        && payload[2] == 0 && payload[3] == 80 && confirms.incrementAndGet() == 1) continue;
                to.writeByte(type);
                to.writeShort(channel);
                to.writeInt(payload.length);
                to.write(payload);
                to.writeByte(end);
                to.flush();
            }
        } catch (IOException closed) {
            if (!listener.isClosed()) throw new java.io.UncheckedIOException(closed);
        }
    }
}
