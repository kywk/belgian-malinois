package com.bpm.core.staff;

import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

public class StaffAuthzClient {
    private final RestClient client;
    public StaffAuthzClient(RestClient client) { this.client = client; }
    public boolean allowed(StaffIdentitySnapshot staff, String permission) {
        return allowedSubjects(staff.subjectIds(), permission);
    }
    public boolean allowedSubjects(List<String> subjects, String permission) {
        try {
            // AuthZ accepts subject_ids, NOT effective_subject_ids. Attributes must remain server-owned.
            var body = Map.of("subject_ids", subjects, "system_code", "bpm",
                    "permissions", List.of(permission), "mode", "ALL", "subject_attributes", Map.of());
            var response = client.post().uri("/api/v1/sdk/permissions/check").body(body)
                    .retrieve().body(tools.jackson.databind.JsonNode.class);
            if (response == null || !response.path("code").isString()
                    || !"0000".equals(response.path("code").asString())
                    || !response.path("data").path("allowed").isBoolean())
                throw StaffFailure.unavailable("AUTHZ");
            return response.path("data").path("allowed").asBoolean();
        } catch (StaffFailure e) { throw e; }
        catch (RuntimeException e) { throw StaffFailure.unavailable("AUTHZ"); }
    }
}
