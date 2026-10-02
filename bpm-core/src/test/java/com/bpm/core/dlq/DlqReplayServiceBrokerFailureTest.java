package com.bpm.core.dlq;

import com.bpm.core.audit.AuditEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 重放的失敗語意（#51）：broker 連不上時必須是 503，不是「0 筆」。
 *
 * <p>回 {@code {replayed:0}} 會讓維運以為 DLQ 是空的（「沒事」），
 * 而實際上這次重放根本沒有執行。兩者的後續處置完全不同。
 */
class DlqReplayServiceBrokerFailureTest {

    @Test
    @DisplayName("broker 連不上：回 503，不得回 0 筆假裝佇列是空的")
    void brokerDownReturns503() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.createConnection())
                .thenThrow(new AmqpConnectException(new RuntimeException("connection refused")));

        var service = new DlqReplayService(
                connectionFactory,
                mock(RabbitTemplate.class),
                mock(AuditEventPublisher.class),
                new ObjectMapper());

        assertThatThrownBy(() -> service.replay("bpm", 100, "admin001"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(503);
    }
}
