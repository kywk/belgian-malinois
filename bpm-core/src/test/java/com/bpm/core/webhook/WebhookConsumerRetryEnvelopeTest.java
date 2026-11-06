package com.bpm.core.webhook;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpStatus.NO_CONTENT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

class WebhookConsumerRetryEnvelopeTest {
    private static final String TARGET = "http://localhost:29384/internal/bpm/v1/webhook";

    @Test
    void retryingSameMutableEnvelopeRetainsPutDestinationAndBusinessIdentity() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var consumer = new WebhookConsumer(new ObjectMapper(), new WebhookUrlPolicy("localhost"),
                "isolated-wave-secret", builder.build());
        var seen = new ArrayList<Seen>();
        RequestMatcher capture = request -> {
            String id = request.getHeaders().getFirst("X-BPM-Delivery-Id");
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            seen.add(new Seen(request.getMethod(), request.getURI().toString(), id, body));
        };
        server.expect(requestTo(TARGET)).andExpect(method(HttpMethod.PUT)).andExpect(capture)
                .andRespond(withStatus(SERVICE_UNAVAILABLE));
        server.expect(requestTo(TARGET)).andExpect(method(HttpMethod.PUT)).andExpect(capture)
                .andRespond(withStatus(NO_CONTENT));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", "workflow.completed");
        envelope.put("businessKey", "application-a/submission-v1");
        envelope.put("processInstanceId", "process-v1");
        envelope.put("__webhookUrl", TARGET);
        envelope.put("__webhookMethod", "PUT");

        assertThatThrownBy(() -> consumer.handle(envelope)).isInstanceOf(RuntimeException.class);
        consumer.handle(envelope);
        server.verify();

        assertThat(envelope).containsEntry("__webhookUrl", TARGET).containsEntry("__webhookMethod", "PUT");
        assertThat(seen).hasSize(2);
        assertThat(seen).extracting(Seen::method).containsExactly(HttpMethod.PUT, HttpMethod.PUT);
        assertThat(seen).extracting(Seen::uri).containsExactly(TARGET, TARGET);
        assertThat(seen).extracting(Seen::deliveryId).doesNotHaveDuplicates();
        assertThat(seen).allSatisfy(s -> {
            assertThat(s.deliveryId()).isNotBlank();
            assertThat(s.body()).contains("application-a/submission-v1", "process-v1");
        });
    }

    private record Seen(HttpMethod method, String uri, String deliveryId, String body) {}
}
