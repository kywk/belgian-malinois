package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.dto.CommentRequest;
import com.bpm.core.dto.TaskActionRequest;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final AuditEventPublisher auditPublisher;

    public TaskController(TaskService taskService, RuntimeService runtimeService,
                          RepositoryService repositoryService, HistoryService historyService,
                          AuditEventPublisher auditPublisher) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.auditPublisher = auditPublisher;
    }

    /**
     * Merged pending tasks: assignee + candidateUser + candidateGroups, deduplicated.
     */
    @GetMapping
    public List<Map<String, Object>> getTasks(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String candidateUser,
            @RequestParam(required = false) String candidateGroups) {

        Map<String, Task> taskMap = new LinkedHashMap<>();

        if (assignee != null) {
            taskService.createTaskQuery().taskAssignee(assignee).list()
                    .forEach(t -> taskMap.put(t.getId(), t));
        }
        if (candidateUser != null) {
            taskService.createTaskQuery().taskCandidateUser(candidateUser).list()
                    .forEach(t -> taskMap.putIfAbsent(t.getId(), t));
        }
        if (candidateGroups != null) {
            taskService.createTaskQuery()
                    .taskCandidateGroupIn(List.of(candidateGroups.split(",")))
                    .list().forEach(t -> taskMap.putIfAbsent(t.getId(), t));
        }

        // If no filter params, return all
        if (assignee == null && candidateUser == null && candidateGroups == null) {
            taskService.createTaskQuery().orderByTaskCreateTime().desc().list()
                    .forEach(t -> taskMap.put(t.getId(), t));
        }

        return taskMap.values().stream()
                .sorted(Comparator.comparing(Task::getCreateTime).reversed())
                .map(this::toMap).toList();
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateTask(@PathVariable String id, @RequestBody TaskActionRequest req) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        String processInstanceId = task != null ? task.getProcessInstanceId() : null;
        String action = req.action();
        String auditType;

        if ("claim".equals(action)) {
            taskService.claim(id, req.assignee());
            auditType = "TASK_CLAIM";
        } else if ("complete".equals(action)) {
            // Block complete if there are pending subtasks (countersign)
            if (!taskService.getSubTasks(id).isEmpty()) {
                return Map.of("taskId", id, "status", "error", "message", "有未完成的加簽子任務");
            }
            Map<String, Object> vars = new HashMap<>();
            if (req.variables() != null) {
                req.variables().forEach(v -> vars.put(v.name(), v.value()));
            }
            // Ensure 'rejected' is always set to avoid EL PropertyNotFoundException
            vars.putIfAbsent("rejected", false);
            taskService.complete(id, vars);
            auditType = resolveCompleteAuditType(vars);
        } else if ("delegate".equals(action)) {
            taskService.delegateTask(id, req.delegateUser());
            auditType = "TASK_DELEGATE";
        } else if ("resolve".equals(action)) {
            taskService.resolveTask(id);
            auditType = "TASK_RESOLVE";
        } else if (req.assignee() != null) {
            taskService.setAssignee(id, req.assignee());
            auditType = "TASK_REASSIGN";
        } else {
            auditType = "TASK_UPDATE";
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("action", action != null ? action : "reassign");
        if (req.variables() != null) {
            req.variables().forEach(v -> detail.put(v.name(), v.value()));
        }

        auditPublisher.publish(new AuditEvent(auditType, req.assignee(), processInstanceId, id, detail));
        return Map.of("taskId", id, "status", "ok");
    }

    @PostMapping("/{id}/comments")
    public Map<String, String> addComment(@PathVariable String id, @RequestBody CommentRequest req) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        String processInstanceId = task != null ? task.getProcessInstanceId() : null;
        taskService.addComment(id, processInstanceId, req.message());
        auditPublisher.publish(new AuditEvent("TASK_COMMENT", req.userId(),
                processInstanceId, id, Map.of("message", req.message())));
        return Map.of("status", "ok");
    }

    @GetMapping("/{id}/comments")
    public List<Map<String, Object>> getComments(@PathVariable String id) {
        return mapComments(taskService.getTaskComments(id));
    }

    private String resolveCompleteAuditType(Map<String, Object> vars) {
        if (Boolean.TRUE.equals(vars.get("rejected"))) return "TASK_REJECT";
        if (Boolean.FALSE.equals(vars.get("approved"))) return "TASK_RETURN";
        if (Boolean.TRUE.equals(vars.get("approved"))) return "TASK_APPROVE";
        return "TASK_APPROVE";
    }

    private Map<String, Object> toMap(Task t) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", t.getId());
        m.put("taskName", t.getName());
        m.put("assignee", t.getAssignee());
        m.put("processInstanceId", t.getProcessInstanceId());
        m.put("createTime", t.getCreateTime());
        m.put("dueDate", t.getDueDate());
        m.put("formKey", t.getFormKey());
        // Enrich with processDefinitionKey and businessKey
        try {
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(t.getProcessInstanceId()).singleResult();
            if (pi != null) {
                m.put("processDefinitionKey", pi.getProcessDefinitionKey());
                m.put("businessKey", pi.getBusinessKey());
            }
        } catch (Exception ignored) {}
        return m;
    }

    static List<Map<String, Object>> mapComments(List<org.flowable.engine.task.Comment> comments) {
        return comments.stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("message", c.getFullMessage());
                    m.put("userId", c.getUserId() != null ? c.getUserId() : "");
                    m.put("time", c.getTime().toString());
                    return m;
                }).toList();
    }
}
