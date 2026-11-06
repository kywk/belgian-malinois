package com.bpm.core.external.v1;

import static com.bpm.core.external.v1.CaseError.*;

import com.bpm.core.external.ExternalSystemPolicy;
import com.bpm.core.model.ExternalSystem;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ExternalCases {
    private static final Logger log = LoggerFactory.getLogger(ExternalCases.class);
    private final CaseStore store;
    private final CaseLauncher launcher;
    private final CaseRouting routing;
    private final ExternalSystemPolicy policy;
    private final TransactionTemplate transaction;
    private final HistoryService history;
    private final TaskService tasks;
    ExternalCases(CaseStore store, CaseLauncher launcher, CaseRouting routing, ExternalSystemPolicy policy,
            @Qualifier("primaryTransactionManager") PlatformTransactionManager tm, HistoryService history,
            TaskService tasks) {
        this.store = store;
        this.launcher = launcher;
        this.routing = routing;
        this.policy = policy;
        this.transaction = new TransactionTemplate(tm);
        this.history = history;
        this.tasks = tasks;
    }
    record Start(CaseStore.Delivery row, boolean replay) {}
    Start start(ExternalSystem sys, CaseCommand c, String trace) {
        String system = sys.getSystemId();
        if (!"agent-bff".equals(system))
            throw SERVICE_FORBIDDEN.fail();
        routing.checkActor(c.actor());
        String hash = c.hash();
        var old = store.byRequest(system, c.requestId());
        if (old.isPresent())
            return replay(sys, old.get(), hash);
        var b = store.binding(system, c).orElseThrow(DEFINITION_VERSION_MISMATCH::fail);
        if (!permitted(sys, b.definitionKey()) || !b.domain().equals(c.caseRef().domain())
                || !b.workflowRole().equals(c.caseRef().workflowRole()))
            throw WORKFLOW_NOT_ALLOWED.fail();
        if (store.byKey(system, c.caseRef().key()).isPresent()) {
            // Another thread may have won between the two reads.
            return collided(sys, c, hash);
        }
        CaseLauncher.Prepared prepared;
        CaseError rejection = null;
        try {
            prepared = launcher.prepare(b, c);
        } catch (Failure failure) {
            if (failure.error.status != 409 && failure.error != FORM_VALIDATION_FAILED)
                throw failure;
            prepared = null;
            rejection = failure.error;
        }
        var input = prepared;
        String reviewer = rejection == null ? routing.reviewer(b) : null;
        try {
            store.reserve(system, c, b, hash, trace);
        } catch (DuplicateKeyException ex) {
            return collided(sys, c, hash);
        }
        if (rejection != null) {
            store.fail(system, c.requestId(), rejection);
            throw rejection.fail();
        }
        try {
            return transaction.execute(tx -> {
                var row = store.locked(system, c.requestId());
                if (!"PROCESSING".equals(row.state()))
                    throw OPERATION_UNCERTAIN.fail();
                var current = store.binding(system, c).orElseThrow(DEFINITION_VERSION_MISMATCH::fail);
                if (!current.equals(b))
                    throw DEFINITION_VERSION_MISMATCH.fail();
                // An older transport may have started a process without a v1 receipt.
                // A business key alone cannot authenticate that old command's actor, body or binding.
                boolean unlinked = history.createHistoricProcessInstanceQuery()
                        .processInstanceBusinessKey(row.businessKey()).variableValueEquals("_externalSystemId", system)
                        .list().stream().anyMatch(instance -> row.businessKey().equals(instance.getBusinessKey()));
                if (unlinked) {
                    log.warn("Unlinked process requires reconciliation: requestId={} trace={}", c.requestId(), trace);
                    throw OPERATION_UNCERTAIN.fail();
                }
                String process = launcher.launch(row, b, c, input, reviewer, trace);
                store.confirm(row, process);
                var confirmed = store.byRequest(system, c.requestId()).orElseThrow();
                // At this point the form DB may already have committed. Any subsequent
                // failure needs reconciliation even when the primary transaction rolls back.
                try {
                    store.observe(confirmed, result(confirmed).signature());
                } catch (Failure failure) {
                    throw OPERATION_UNCERTAIN.fail();
                }
                return new Start(confirmed, false);
            });
        } catch (Failure ex) {
            store.fail(system, c.requestId(), ex.error);
            throw ex;
        } catch (RuntimeException ex) {
            // Includes ambiguous primary/form commit. Preserve the attempt ID even if compensation fails.
            log.error("External case requires reconciliation: requestId={} trace={} failure={}", c.requestId(), trace,
                    ex.getClass().getSimpleName());
            store.fail(system, c.requestId(), OPERATION_UNCERTAIN);
            throw OPERATION_UNCERTAIN.fail();
        }
    }
    private Start collided(ExternalSystem sys, CaseCommand c, String hash) {
        var winner = store.byRequest(sys.getSystemId(), c.requestId()).orElseThrow(CASE_ALREADY_STARTED::fail);
        return replay(sys, winner, hash);
    }
    private Start replay(ExternalSystem sys, CaseStore.Delivery row, String hash) {
        visible(sys, row);
        if (row.hashVersion() != CaseCommand.HASH_VERSION)
            throw OPERATION_UNCERTAIN.fail();
        if (!hash.equals(row.requestHash()))
            throw IDEMPOTENCY_CONFLICT.fail();
        return new Start(store.recover(row), true);
    }
    private boolean permitted(ExternalSystem sys, String key) {
        return sys.getAllowedProcessKeys() != null && !sys.getAllowedProcessKeys().isBlank()
                && policy.isProcessKeyAllowed(sys, key);
    }
    private CaseStore.Delivery visible(ExternalSystem sys, CaseStore.Delivery row) {
        if (!permitted(sys, row.definitionKey()))
            throw NOT_FOUND.fail();
        return row;
    }
    CaseStore.Receipt receipt(ExternalSystem sys, String id) {
        return store.recover(visible(sys, store.byRequest(sys.getSystemId(), id).orElseThrow(NOT_FOUND::fail)))
                .receipt();
    }
    Status byProcess(ExternalSystem sys, String id) {
        return status(visible(sys, store.byProcess(sys.getSystemId(), id).orElseThrow(NOT_FOUND::fail)));
    }
    List<Status> byKey(ExternalSystem sys, String key) {
        if (!"agent-bff".equals(sys.getSystemId()))
            throw NOT_FOUND.fail();
        return store.byKey(sys.getSystemId(), key)
                .map(r -> visible(sys, r))
                .map(store::recover)
                .filter(r -> {
                    if ("UNCERTAIN".equals(r.state())) throw OPERATION_UNCERTAIN.fail();
                    return "CONFIRMED".equals(r.state());
                })
                .map(this::status)
                .stream()
                .toList();
    }
    record Result(String engineState, String reviewStatus, String outcome, String reasonCode) {
        String signature() {
            return String.join("/", engineState, reviewStatus, outcome, Objects.toString(reasonCode, ""));
        }
    }
    record Status(String processInstanceId, String processDefinitionId, String processDefinitionKey,
            String workflowCode, long bindingVersion, CaseCommand.Ref caseRef, String businessKey,
            String formDefinitionId, Integer formVersion, String engineState, String reviewStatus, String outcome,
            String reasonCode, long sourceRevision, String updatedAt) {}

    private Status status(CaseStore.Delivery delivery) {
        return transaction.execute(tx -> {
            var row = store.locked(delivery.systemId(), delivery.requestId());
            Result result = result(row);
            var observed = store.observe(row, result.signature());
            return new Status(row.processId(), row.definitionId(), row.definitionKey(), row.workflowCode(),
                    row.bindingVersion(), row.ref(), row.businessKey(), row.formId(), row.formVersion(),
                    result.engineState(), result.reviewStatus(), result.outcome(), result.reasonCode(),
                    observed.sourceRevision(), observed.updatedAt().toInstant(ZoneOffset.UTC).toString());
        });
    }
    private Result result(CaseStore.Delivery row) {
        var instance = history.createHistoricProcessInstanceQuery().processInstanceId(row.processId()).singleResult();
        if (instance == null || !row.definitionId().equals(instance.getProcessDefinitionId())
                || !row.businessKey().equals(instance.getBusinessKey()))
            throw UPSTREAM_UNAVAILABLE.fail();
        if (instance.getDeleteReason() != null)
            return new Result("CANCELLED", "COMPLETED", "UNKNOWN", "PROCESS_CANCELLED");
        if (instance.getEndTime() == null) {
            boolean returned = row.returnedNode() != null
                    && tasks.createTaskQuery()
                                    .processInstanceId(row.processId())
                                    .taskDefinitionKey(row.returnedNode())
                                    .count()
                            > 0;
            return new Result("RUNNING", returned ? "RETURNED" : "RUNNING", "UNKNOWN", null);
        }
        String end = instance.getEndActivityId();
        if (end != null) {
            if (end.equals(row.approvedEnd()))
                return new Result("COMPLETED", "COMPLETED", "APPROVED", null);
            if (end.equals(row.rejectedEnd()))
                return new Result("COMPLETED", "COMPLETED", "REJECTED", null);
            if (end.equals(row.returnedNode()))
                return new Result("COMPLETED", "RETURNED", "UNKNOWN", null);
        }
        return new Result("COMPLETED", "UNKNOWN", "UNKNOWN", "RESULT_MAPPING_UNAVAILABLE");
    }
}
