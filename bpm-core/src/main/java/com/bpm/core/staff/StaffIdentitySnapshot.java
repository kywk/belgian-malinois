package com.bpm.core.staff;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public record StaffIdentitySnapshot(String userId, String displayName, String userType,
        String status, String deptId, List<String> orgObjectIds, String sourceRevision,
        Instant observedAt, Instant validUntil) {
    public StaffIdentitySnapshot validated(Instant now) {
        if (!text(userId, 100) || !text(displayName, 200) || !text(deptId, 64)
                || !text(sourceRevision, 128) || orgObjectIds == null || orgObjectIds.isEmpty()
                || orgObjectIds.stream().anyMatch(id -> !text(id, 64))
                || observedAt == null || validUntil == null || observedAt.isAfter(now)
                || !observedAt.isAfter(now.minusSeconds(60)) || !validUntil.isAfter(now)
                || !validUntil.isAfter(observedAt)) throw StaffFailure.unavailable("IDENTITY");
        if (!"STAFF".equals(userType))
            throw new StaffFailure("BPM_STAFF_REQUIRED", 403, "BPM 後台僅供內勤人員使用");
        if ("INACTIVE".equals(status))
            throw new StaffFailure("BPM_ACCOUNT_INACTIVE", 403, "內勤帳號已停用");
        if (!"ACTIVE".equals(status)) throw StaffFailure.unavailable("IDENTITY");
        return this;
    }
    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.strip());
    }
    public List<String> subjectIds() {
        return Stream.concat(Stream.of("staff-" + userId.toLowerCase(Locale.ROOT)),
                orgObjectIds.stream().map(id -> "org-" + id.toLowerCase(Locale.ROOT))).distinct().toList();
    }
}
