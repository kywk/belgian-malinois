package com.bpm.core.external.v1;

import java.time.LocalDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CaseStore {
    private final JdbcTemplate db;
    CaseStore(@Qualifier("primaryDataSource") DataSource db) {
        this.db = new JdbcTemplate(db);
    }

    record Binding(String domain, String workflowRole, String definitionKey, String definitionId, String formId,
            Integer formVersion, String routingRule, String approvalPermission, String approvedEnd,
            String rejectedEnd, String returnedNode, boolean enabled) {}

    record Delivery(String systemId, String requestId, String businessKey, long submissionRevision,
            String requestHash, int hashVersion, String workflowCode, long bindingVersion, String definitionKey,
            String definitionId, String formId, Integer formVersion, String approvedEnd, String rejectedEnd,
            String returnedNode, String state, String errorCode, String processId, String attemptId,
            String statusSignature, long sourceRevision, LocalDateTime updatedAt, LocalDateTime reservedAt) {
        CaseCommand.Ref ref() {
            return CaseCommand.Ref.from(businessKey, submissionRevision);
        }
        Receipt receipt() {
            return new Receipt(requestId, state, ref(), businessKey, processId, errorCode,
                    "PROCESSING".equals(state) ? 2 : null);
        }
    }
    record Receipt(String requestId, String state, CaseCommand.Ref caseRef, String businessKey,
            String processInstanceId, String errorCode, Integer retryAfterSeconds) {}
    private static final DataClassRowMapper<Binding> BINDING = new DataClassRowMapper<>(Binding.class);
    private static final DataClassRowMapper<Delivery> REQUEST = new DataClassRowMapper<>(Delivery.class);

    Optional<Binding> binding(String system, CaseCommand command) {
        return db
                .query("SELECT * FROM bpm_case_binding WITH (HOLDLOCK) WHERE workflow_code=? AND binding_version=? AND caller_system=?",
                        BINDING, command.workflowCode(), command.bindingVersion(), system)
                .stream()
                .findFirst();
    }
    Optional<Delivery> byRequest(String system, String id) {
        return db.query("SELECT * FROM bpm_case_request WHERE system_id=? AND request_id=?", REQUEST, system, id)
                .stream()
                .findFirst();
    }
    Optional<Delivery> byKey(String system, String key) {
        return db.query("SELECT * FROM bpm_case_request WHERE system_id=? AND business_key=?", REQUEST, system, key)
                .stream()
                .findFirst();
    }
    Optional<Delivery> byProcess(String system, String id) {
        return db.query("SELECT * FROM bpm_case_request WHERE system_id=? AND process_id=?", REQUEST, system, id)
                .stream()
                .findFirst();
    }
    Delivery locked(String system, String id) {
        return db.queryForObject(
                "SELECT * FROM bpm_case_request WITH (UPDLOCK,ROWLOCK) WHERE system_id=? AND request_id=?", REQUEST,
                system, id);
    }
    void reserve(String system, CaseCommand c, Binding b, String hash, String trace) {
        db.update("""
                INSERT INTO bpm_case_request (system_id,request_id,business_key,submission_revision,request_hash,
                   hash_version,workflow_code,binding_version,definition_key,definition_id,form_id,form_version,
                   actor_json,trace_id,approved_end,rejected_end,returned_node,state,attempt_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PROCESSING',?)
                """, system, c.requestId(), c.caseRef().key(), c.caseRef().submissionRevision(), hash,
                CaseCommand.HASH_VERSION, c.workflowCode(), c.bindingVersion(), b.definitionKey(), b.definitionId(),
                b.formId(), b.formVersion(), CaseCommand.JSON.writeValueAsString(c.actor()), trace, b.approvedEnd(),
                b.rejectedEnd(), b.returnedNode(), java.util.UUID.randomUUID().toString());
    }
    void confirm(Delivery row, String processId) {
        int n = db.update(
                "UPDATE bpm_case_request SET state='CONFIRMED', process_id=? WHERE system_id=? AND request_id=? AND state='PROCESSING'",
                processId, row.systemId(), row.requestId());
        if (n != 1)
            throw CaseError.OPERATION_UNCERTAIN.fail();
    }
    void fail(String system, String id, CaseError error) {
        db.update(
                "UPDATE bpm_case_request SET state=?,error_code=? WHERE system_id=? AND request_id=? AND state='PROCESSING'",
                error == CaseError.OPERATION_UNCERTAIN ? "UNCERTAIN" : "REJECTED", error.code(), system, id);
    }
    Delivery recover(Delivery row) {
        if (!"PROCESSING".equals(row.state()))
            return row;
        // Fresh requests can return 202 immediately without contending with the active writer.
        if (row.reservedAt().isAfter(LocalDateTime.now(java.time.Clock.systemUTC()).minusMinutes(5)))
            return row;
        // Compare-and-set under SQL Server's write lock: never take over or restart an attempt.
        db.update("""
                UPDATE bpm_case_request SET state='UNCERTAIN',error_code='BPM_OPERATION_UNCERTAIN'
                WHERE system_id=? AND request_id=? AND state='PROCESSING' AND reserved_at<DATEADD(minute,-5,SYSUTCDATETIME())
                """, row.systemId(), row.requestId());
        return byRequest(row.systemId(), row.requestId()).orElseThrow();
    }
    Delivery observe(Delivery row, String signature) {
        if (signature.equals(row.statusSignature()))
            return row;
        db.update("""
                UPDATE bpm_case_request SET status_signature=?,source_revision=source_revision+1,updated_at=SYSUTCDATETIME()
                WHERE system_id=? AND request_id=? AND source_revision<9007199254740991
                """, signature, row.systemId(), row.requestId());
        Delivery updated = byRequest(row.systemId(), row.requestId()).orElseThrow();
        if (!signature.equals(updated.statusSignature()))
            throw CaseError.INTERNAL_ERROR.fail();
        return updated;
    }
}
