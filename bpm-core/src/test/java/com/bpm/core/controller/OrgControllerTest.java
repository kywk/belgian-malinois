package com.bpm.core.controller;

import com.bpm.core.client.OrgRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgControllerTest {
    @Test
    void searchTrimsBoundsAndOnlyExposesPickerFields() {
        OrgRestClient client = mock(OrgRestClient.class);
        when(client.searchUsers("user")).thenReturn(IntStream.range(0, 70)
                .mapToObj(i -> Map.<String, Object>of("userId", "user" + i, "name", "測試", "deptId", "dept001",
                        "email", "private-marker", "token", "secret-marker")).toList());
        var result = new OrgController(client).searchUsers("  user  ");
        assertThat(result).hasSize(50);
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("private-marker", "secret-marker", "email", "token");
        verify(client).searchUsers("user");
    }

    @Test
    void blankAndOversizedQueriesNeverReachUpstream() {
        OrgRestClient client = mock(OrgRestClient.class);
        OrgController controller = new OrgController(client);
        assertThat(controller.searchUsers("  ")).isEmpty();
        assertThatThrownBy(() -> controller.searchUsers("x".repeat(101)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(client);
    }

    @Test
    void nullUpstreamResultIsNotReportedAsNoMatches() {
        OrgRestClient client = mock(OrgRestClient.class);
        when(client.searchUsers("user")).thenReturn(null);
        assertThatThrownBy(() -> new OrgController(client).searchUsers("user"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
