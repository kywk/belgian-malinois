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
    /** A Flowable assignee alone cannot prove a time-limited mandate is still in force. */
    public void requireDelegation(String owner, String delegate) {
        if (owner == null || delegate == null) throw StaffFailure.unavailable("ROUTING");
        try {
            var result = client.post().uri("/delegations/check").body(Map.of("owner", owner, "delegate", delegate))
                    .retrieve().body(Delegation.class);
            Instant now = Instant.now();
            if (result == null || !owner.equals(result.owner()) || !delegate.equals(result.delegate())
                    || result.observedAt() == null || result.observedAt().isAfter(now)
                    || !result.observedAt().isAfter(now.minusSeconds(60)) || result.validUntil() == null
                    || !result.validUntil().isAfter(now)) throw StaffFailure.unavailable("ROUTING");
            if ("INACTIVE".equals(result.status())) throw StaffFailure.denied();
            if (!"ACTIVE".equals(result.status()) || result.startsAt() == null || result.endsAt() == null
                    || !result.endsAt().isAfter(result.startsAt())) throw StaffFailure.unavailable("ROUTING");
            if (now.isBefore(result.startsAt()) || !now.isBefore(result.endsAt())) throw StaffFailure.denied();
        } catch (StaffFailure e) { throw e; }
        catch (RuntimeException e) { throw StaffFailure.unavailable("ROUTING"); }
    }
    public record Delegation(String owner, String delegate, String status, Instant observedAt,
            Instant validUntil, Instant startsAt, Instant endsAt) {}
    public record DirectoryResult(String result, StaffIdentitySnapshot snapshot) {}
}
