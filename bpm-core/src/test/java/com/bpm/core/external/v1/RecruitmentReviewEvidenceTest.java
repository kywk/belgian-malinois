package com.bpm.core.external.v1;

import com.bpm.core.staff.StaffFailure;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RecruitmentReviewEvidenceTest {
    @ParameterizedTest @NullSource @ValueSource(strings = {"ECM"})
    void uncontractedSourceCannotBeReviewed(String source) throws Exception {
        var row = mock(CaseStore.Delivery.class);
        when(row.ref()).thenReturn(new CaseCommand.Ref("recruitment", "a", "s", "review", 1));
        when(row.workflowCode()).thenReturn("recruitment.interview");
        when(row.processId()).thenReturn("process");
        var document = new HashMap<String,Object>(Map.of("documentId", UUID.randomUUID().toString(),
                "version", "1", "contentHash", "a".repeat(64), "purpose", "INTERVIEW_PHOTO",
                "fileName", "fixture.png", "contentType", "image/png", "sizeBytes", 67));
        document.put("source", source);
        byte[] response = CaseCommand.JSON.writeValueAsBytes(Map.of("applicationId", "a", "applicationType", "INTERVIEW",
                "submissionId", "s", "revision", 1, "documents", List.of(document)));
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        http.start();
        try {
            var evidence = new RecruitmentReviewEvidence("http://127.0.0.1:" + http.getAddress().getPort(), "fixture-key");
            assertThatThrownBy(() -> evidence.submission(row)).isInstanceOfSatisfying(StaffFailure.class,
                    failure -> org.assertj.core.api.Assertions.assertThat(failure.status()).isEqualTo(503));
        } finally { http.stop(0); }
    }
}
