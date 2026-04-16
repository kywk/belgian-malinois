package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.dto.CommentRequest;
import com.bpm.core.dto.TaskActionRequest;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final AuditEventPublisher auditPublisher;

    public TaskController(TaskService taskService, AuditEventPublisher auditPublisher) {
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
    }

    @GetMapping
    public List<Map<String, Object>> getTasks(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String candidateUser,
            @RequestParam(required = false) String candidateGroups) {
        TaskQuery query = taskService.createTaskQuery();
        if (assignee != null) query.taskAssignee(assignee);
        if (candidateUser != null) query.taskCandidateUser(candidateUser);
        if (candidateGroups != null) {
            query.taskCandidateGroupIn(List.of(candidateGroups.split(",")));
        }
        return query.orderByTaskCreateTime().desc().list().stream()
                .map(this::toMap).toList();
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateTask(@PathVariable String id, @RequestBody TaskActionRequest req) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        String action = req.action();

        if ("claim".equals(action)) {
            taskService.claim(id, req.assignee());
        } else if ("complete".equals(action)) {
            Map<String, Object> vars = new HashMap<>();
            if (req.variables() != null) {
                req.variables().forEach(v -> vars.put(v.name(), v.value()));
            }
            taskService.complete(id, vars);
        } else if ("delegate".equals(action)) {
            taskService.delegateTask(id, req.delegateUser());
        } else if ("resolve".equals(action)) {
            taskService.resolveTask(id);
        } else if (req.assignee() != null) {
            taskService.setAssignee(id, req.assignee());
        }

        auditPublisher.publish(new AuditEvent(
                "TASK_" + (action != null ? action.toUpperCase() : "REASSIGN"),
                req.assignee(),
                task != null ? task.getProcessInstanceId() : null,
                id, Map.of("action", action != null ? action : "reassign")));
        return Map.of("taskId", id, "status", "ok");
    }

    @PostMapping("/{id}/comments")
    public Map<String, String> addComment(@PathVariable String id, @RequestBody CommentRequest req) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        taskService.addComment(id, task != null ? task.getProcessInstanceId() : null, req.message());
        auditPublisher.publish(new AuditEvent("TASK_COMMENT", req.userId(),
                task != null ? task.getProcessInstanceId() : null, id,
                Map.of("message", req.message())));
        return Map.of("status", "ok");
    }

    @GetMapping("/{id}/comments")
    public List<Map<String, Object>> getComments(@PathVariable String id) {
        return taskService.getTaskComments(id).stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "message", c.getFullMessage(),
                        "userId", c.getUserId() != null ? c.getUserId() : "",
                        "time", c.getTime().toString()))
                .toList();
    }

    private Map<String, Object> toMap(Task t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("assignee", t.getAssignee());
        m.put("processInstanceId", t.getProcessInstanceId());
        m.put("createTime", t.getCreateTime());
        m.put("dueDate", t.getDueDate());
        m.put("formKey", t.getFormKey());
        return m;
    }
}
