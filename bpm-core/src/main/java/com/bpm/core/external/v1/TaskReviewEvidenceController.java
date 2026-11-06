package com.bpm.core.external.v1;

import com.bpm.core.staff.StaffAuthentication;
import com.bpm.core.staff.StaffFailure;
import com.bpm.core.staff.StaffTaskAuthorization;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.TaskService;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "bpm.staff.enabled", havingValue = "true")
@RequestMapping("/api/tasks/{taskId}/review-evidence")
class TaskReviewEvidenceController {
    private final TaskService tasks;
    private final StaffTaskAuthorization permission;
    private final CaseStore cases;
    private final RecruitmentReviewEvidence owner;

    TaskReviewEvidenceController(TaskService tasks, StaffTaskAuthorization permission, CaseStore cases,
            RecruitmentReviewEvidence owner) {
        this.tasks = tasks;
        this.permission = permission;
        this.cases = cases;
        this.owner = owner;
    }

    @GetMapping
    ResponseEntity<RecruitmentReviewEvidence.Submission> metadata(@PathVariable String taskId,
            @RequestParam MultiValueMap<String, String> params) {
        var receipt = receipt(taskId, params);
        var result = owner.submission(receipt);
        assigned(taskId); // Task completion or reassignment while the owner was responding.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/documents/{documentId}")
    ResponseEntity<byte[]> document(@PathVariable String taskId, @PathVariable String documentId,
            @RequestParam MultiValueMap<String, String> params) {
        var receipt = receipt(taskId, params);
        var doc = owner.submission(receipt).documents().stream().filter(d -> d.documentId().equals(documentId))
                .findFirst().orElseThrow(TaskReviewEvidenceController::missing);
        byte[] bytes = owner.document(receipt, doc);
        assigned(taskId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment").contentType(MediaType.parseMediaType(doc.contentType()))
                .body(bytes);
    }

    private CaseStore.Delivery receipt(String taskId, MultiValueMap<String, String> params) {
        var task = assigned(taskId);
        var receipt = cases.byProcess("agent-bff", task.getProcessInstanceId()).orElseThrow(TaskReviewEvidenceController::missing);
        var ref = receipt.ref();
        if (!"CONFIRMED".equals(receipt.state()) || !task.getProcessDefinitionId().equals(receipt.definitionId()) ||
                !Set.of("recruitment.interview", "recruitment.credit").contains(receipt.workflowCode()) ||
                !"recruitment".equals(ref.domain()) || !"review".equals(ref.workflowRole())) throw missing();
        // A supplied case tuple is only a precondition. The owner path always comes from the delivery receipt.
        var expected = Map.of("applicationId", ref.applicationId(), "submissionId", ref.submissionId(),
                "revision", Long.toString(ref.submissionRevision()));
        for (var param : params.entrySet()) {
            if (param.getValue().size() != 1 || !param.getValue().getFirst().equals(expected.get(param.getKey())))
                throw missing();
        }
        return receipt;
    }

    private Task assigned(String taskId) {
        var staff = StaffAuthentication.current();
        var task = tasks.createTaskQuery().taskId(taskId).active().singleResult();
        if (staff == null || task == null || task.getProcessDefinitionId() == null || task.getParentTaskId() != null ||
                task.getDelegationState() == DelegationState.PENDING || !staff.getName().equals(task.getAssignee())) throw missing();
        permission.require(task, null);
        return task;
    }

    static StaffFailure missing() {
        return new StaffFailure("BPM_NOT_FOUND", 404, "查無可核閱的送件附件");
    }
}
