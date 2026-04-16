package com.bpm.core.external;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/external")
public class ExternalApiController {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final ProcessVariableSpecRepository specRepo;
    private final AuditEventPublisher auditPublisher;

    public ExternalApiController(RuntimeService runtimeService, TaskService taskService,
                                  HistoryService historyService, ProcessVariableSpecRepository specRepo,
                                  AuditEventPublisher auditPublisher) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.specRepo = specRepo;
        this.auditPublisher = auditPublisher;
    }

    // ── 1. Start Process ──

    @PostMapping("/process-instances")
    public Map<String, Object> startProcess(@RequestBody Map<String, Object> body,
                                             @RequestAttribute("externalSystemId") String systemId) {
        String processDefKey = (String) body.get("processDefinitionKey");
        String businessKey = (String) body.get("businessKey");
        String initiator = (String) body.getOrDefault("initiator", "system:" + systemId);
        String firstAssignee = (String) body.get("firstTaskAssignee");
        String firstGroups = (String) body.get("firstTaskCandidateGroups");
        String callbackUrl = (String) body.get("callbackUrl");

        @SuppressWarnings("unchecked")
        Map<String, Object> variables = body.get("variables") instanceof Map
                ? new HashMap<>((Map<String, Object>) body.get("variables")) : new HashMap<>();

        // Validate: system initiator needs firstTaskAssignee or groups
        if (initiator.startsWith("system:") && firstAssignee == null && firstGroups == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "外部系統發起流程必須指定 firstTaskAssignee 或 firstTaskCandidateGroups");
        }

        // Validate variables against ProcessVariableSpec
        validateVariables(processDefKey, variables);

        // Prepare variables
        variables.put("initiator", initiator);
        if (initiator.startsWith("system:") && firstAssignee != null) {
            variables.put("effectiveInitiator", firstAssignee);
        }
        if (callbackUrl != null) variables.put("_callbackUrl", callbackUrl);

        // Start process
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(processDefKey, businessKey, variables);

        // Set first task assignee/candidates
        Task firstTask = taskService.createTaskQuery()
                .processInstanceId(pi.getProcessInstanceId()).singleResult();
        if (firstTask != null) {
            if (firstAssignee != null) taskService.setAssignee(firstTask.getId(), firstAssignee);
            if (firstGroups != null) {
                for (String g : firstGroups.split(",")) {
                    taskService.addCandidateGroup(firstTask.getId(), g.trim());
                }
            }
        }

        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL", "system:" + systemId,
                "external_api", processDefKey, pi.getProcessInstanceId(), null, businessKey,
                Map.of("action", "start_process", "processDefinitionKey", processDefKey),
                java.time.Instant.now()));

        Map<String, Object> result = new HashMap<>();
        result.put("processInstanceId", pi.getProcessInstanceId());
        result.put("businessKey", businessKey != null ? businessKey : "");
        result.put("status", "running");
        if (firstTask != null) {
            result.put("currentTask", Map.of(
                    "taskId", firstTask.getId(),
                    "taskName", firstTask.getName() != null ? firstTask.getName() : "",
                    "assignee", firstAssignee != null ? firstAssignee : ""));
        }
        return result;
    }

    // ── 2. Query Status ──

    @GetMapping("/process-instances/{processInstanceId}/status")
    public Map<String, Object> getStatus(@PathVariable String processInstanceId,
                                          @RequestAttribute("externalSystemId") String systemId) {
        return buildStatusResponse(processInstanceId, systemId);
    }

    @GetMapping("/process-instances")
    public List<Map<String, Object>> queryByBusinessKey(@RequestParam String businessKey,
                                                         @RequestAttribute("externalSystemId") String systemId) {
        // Search in history (covers both running and completed)
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey)
                .variableValueEquals("initiator", "system:" + systemId)
                .orderByProcessInstanceStartTime().desc().list().stream()
                .map(hp -> buildStatusFromHistory(hp))
                .toList();
    }

    // ── 3. Complete Task ──

    @PutMapping("/tasks/{taskId}")
    public Map<String, Object> completeTask(@PathVariable String taskId,
                                             @RequestBody Map<String, Object> body,
                                             @RequestAttribute("externalSystemId") String systemId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");

        @SuppressWarnings("unchecked")
        Map<String, Object> vars = body.get("variables") instanceof Map
                ? new HashMap<>((Map<String, Object>) body.get("variables")) : new HashMap<>();

        taskService.complete(taskId, vars);

        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL", "system:" + systemId,
                "external_api", null, task.getProcessInstanceId(), taskId, null,
                Map.of("action", "complete_task", "variables", vars),
                java.time.Instant.now()));

        return Map.of("taskId", taskId, "status", "completed");
    }

    // ── Helpers ──

    private Map<String, Object> buildStatusResponse(String processInstanceId, String systemId) {
        // Try running first
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (pi != null) {
            // Verify ownership
            Object initiator = runtimeService.getVariable(processInstanceId, "initiator");
            verifyOwnership(initiator, systemId);

            Map<String, Object> result = new HashMap<>();
            result.put("processInstanceId", processInstanceId);
            result.put("businessKey", pi.getBusinessKey());
            result.put("status", "running");
            result.put("startedAt", pi.getStartTime());
            result.put("result", null);
            result.put("completedAt", null);
            result.put("currentTasks", taskService.createTaskQuery()
                    .processInstanceId(processInstanceId).list().stream()
                    .map(t -> Map.of(
                            "taskId", t.getId(),
                            "taskName", t.getName() != null ? t.getName() : "",
                            "assignee", t.getAssignee() != null ? t.getAssignee() : "",
                            "createdAt", t.getCreateTime()))
                    .toList());
            return result;
        }

        // Check history
        HistoricProcessInstance hp = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hp == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        return buildStatusFromHistory(hp);
    }

    private Map<String, Object> buildStatusFromHistory(HistoricProcessInstance hp) {
        Map<String, Object> result = new HashMap<>();
        result.put("processInstanceId", hp.getId());
        result.put("businessKey", hp.getBusinessKey());
        result.put("startedAt", hp.getStartTime());
        result.put("completedAt", hp.getEndTime());
        result.put("currentTasks", List.of());

        if (hp.getEndTime() == null) {
            result.put("status", "running");
            result.put("result", null);
        } else if (hp.getDeleteReason() != null) {
            result.put("status", "cancelled");
            result.put("result", null);
        } else {
            result.put("status", "completed");
            // Try to determine result from historic variables
            var vars = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(hp.getId()).variableName("rejected").singleResult();
            boolean rejected = vars != null && Boolean.TRUE.equals(vars.getValue());
            result.put("result", rejected ? "rejected" : "approved");
        }
        return result;
    }

    private void verifyOwnership(Object initiator, String systemId) {
        if (initiator == null || !initiator.toString().contains(systemId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "無權查詢此流程");
        }
    }

    private void validateVariables(String processDefKey, Map<String, Object> variables) {
        List<ProcessVariableSpec> specs = specRepo.findByProcessDefinitionKeyOrderByVariableName(processDefKey);
        for (ProcessVariableSpec spec : specs) {
            if (Boolean.TRUE.equals(spec.getRequired()) && !variables.containsKey(spec.getVariableName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "缺少必填變數: " + spec.getVariableName());
            }
        }
    }
}
