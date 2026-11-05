package com.bpm.core.staff;

import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.Map;

/** BPM-owned bridge contract. The existing ODS tables alone cannot prove ACTIVE status. */
public class StaffDirectoryClient {
    private final RestClient client;
    public StaffDirectoryClient(RestClient client) { this.client = client; }
    public StaffIdentitySnapshot resolve(String issuer, String subject) {
        return lookup("/resolve", Map.of("issuer", issuer, "subject", subject));
    }
    public StaffIdentitySnapshot byUserId(String userId) {
        StaffIdentitySnapshot result = lookup("/users/resolve", Map.of("userId", userId));
        if (!result.userId().equals(userId)) throw StaffFailure.unavailable("IDENTITY");
        return result;
    }
    private StaffIdentitySnapshot lookup(String path, Map<String, String> input) {
        try {
            DirectoryResult result = client.post().uri(path).body(input).retrieve().body(DirectoryResult.class);
            if (result == null) throw StaffFailure.unavailable("IDENTITY");
            if ("NOT_FOUND".equals(result.result()))
                throw new StaffFailure("BPM_STAFF_REQUIRED", 403, "無法對應有效的內勤身分");
            if (!"FOUND".equals(result.result()) || result.snapshot() == null)
                throw StaffFailure.unavailable("IDENTITY");
            return result.snapshot().validated(Instant.now());
        } catch (StaffFailure e) { throw e; }
        catch (RuntimeException e) { throw StaffFailure.unavailable("IDENTITY"); }
    }
    public record DirectoryResult(String result, StaffIdentitySnapshot snapshot) {}
}
