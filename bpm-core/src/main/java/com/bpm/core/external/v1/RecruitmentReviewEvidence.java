package com.bpm.core.external.v1;

import com.bpm.core.staff.StaffFailure;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Service-authenticated reads of frozen evidence. Only the explicit summary DTO is forwarded to the browser. */
@Component
class RecruitmentReviewEvidence {
    private final String origin;
    private final String key;

    RecruitmentReviewEvidence(@Value("${bpm.staff.evidence.owner-url:}") String origin,
            @Value("${bpm.staff.evidence.owner-key:}") String key) {
        this.origin = origin;
        this.key = key;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Submission(String applicationId, String applicationType, String submissionId, long revision, List<Document> documents) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(String documentId, String version, String contentHash, String purpose, String fileName,
            String contentType, int sizeBytes, String source) {}

    Submission submission(CaseStore.Delivery row) {
        byte[] body = fetch(row, "", "application/json", 65536);
        Submission result;
        try {
            result = CaseCommand.JSON.readValue(body, Submission.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw unavailable();
        }
        var ref = row.ref();
        boolean interview = "recruitment.interview".equals(row.workflowCode());
        String type = interview ? "INTERVIEW" : "CREDIT_CHECK";
        if (result == null || !ref.applicationId().equals(result.applicationId()) ||
                !ref.submissionId().equals(result.submissionId()) || ref.submissionRevision() != result.revision() ||
                !type.equals(result.applicationType()) || result.documents() == null) throw unavailable();
        for (var doc : result.documents()) {
            if (doc == null || !"MOCK".equals(doc.source()) || doc.documentId() == null || !doc.documentId().matches(CaseCommand.UUID_PATTERN) ||
                    doc.fileName() == null || doc.fileName().isBlank() || doc.version() == null || doc.version().isBlank() ||
                    doc.contentHash() == null || !doc.contentHash().matches("[a-f0-9]{64}") || doc.sizeBytes() <= 0 ||
                    doc.sizeBytes() > (interview ? 1024 * 1024 : 5 * 1024 * 1024) || doc.contentType() == null ||
                    !(interview ? "INTERVIEW_PHOTO".equals(doc.purpose()) && Set.of("image/png","image/jpeg").contains(doc.contentType())
                            : "CREDIT_REPORT".equals(doc.purpose()) && "application/pdf".equals(doc.contentType()))) throw unavailable();
        }
        return result;
    }

    byte[] document(CaseStore.Delivery row, Document doc) {
        byte[] content = fetch(row, "/documents/" + doc.documentId(), doc.contentType(), doc.sizeBytes());
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            if (content.length != doc.sizeBytes() || !hash.equals(doc.contentHash()) || !hasSignature(content, doc.contentType()))
                throw unavailable();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return content;
    }

    private boolean hasSignature(byte[] bytes, String type) {
        byte[] prefix = switch (type) {
            case "image/png" -> new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
            case "image/jpeg" -> new byte[] {(byte) 255, (byte) 216, (byte) 255};
            case "application/pdf" -> "%PDF-".getBytes(StandardCharsets.US_ASCII);
            default -> throw unavailable();
        };
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private byte[] fetch(CaseStore.Delivery row, String suffix, String contentType, int maxBytes) {
        if (origin.isBlank() || key.isBlank()) throw unavailable();
        URI base;
        try {
            base = URI.create(origin);
        } catch (IllegalArgumentException e) {
            throw unavailable();
        }
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null ||
                !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) && Set.of("localhost","127.0.0.1").contains(base.getHost())))
            throw unavailable();
        var ref = row.ref();
        URI uri = base.resolve("/internal/staff-review/v1/applications/" + ref.applicationId() + "/submissions/"
                + ref.submissionId() + "/revisions/" + ref.submissionRevision() + "/instances/" + row.processId() + suffix);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("X-Service-Id", "bpm-staff-review");
            connection.setRequestProperty("X-Service-Key", key);
            connection.setRequestProperty("Accept", contentType);
            int status = connection.getResponseCode();
            if (status == 404) throw TaskReviewEvidenceController.missing();
            String responseType = connection.getContentType();
            if (status != 200 || responseType == null || !contentType.equalsIgnoreCase(responseType.split(";",2)[0].trim()) ||
                    connection.getContentLengthLong() > maxBytes) throw unavailable();
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) throw unavailable();
                return bytes;
            }
        } catch (IOException e) {
            throw unavailable();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static StaffFailure unavailable() {
        return new StaffFailure("BPM_EVIDENCE_UNAVAILABLE", 503, "送件附件來源目前無法確認");
    }
}
