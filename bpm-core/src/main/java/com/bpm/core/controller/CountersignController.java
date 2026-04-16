package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class CountersignController {

    private final TaskService taskService;
    private final AuditEventPublisher auditPublisher;

    public CountersignController(TaskService taskService, AuditEventPublisher auditPublisher) {
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
    }

    /**
     * Create countersign subtask.
     */
    @PostMapping
    public Map<String, Object> createSubtask(@RequestBody Map<String, String> req) {
        String parentTaskId = req.get("parentTaskId");
        String assignee = req.get("assignee");
        String description = req.get("description");

        Task parent = taskService.createTaskQuery().taskId(parentTaskId).singleResult();
        if (parent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent task not found");

        Task subtask = taskService.newTask();
        subtask.setParentTaskId(parentTaskId);
        subtask.setAssignee(assignee);
        subtask.setName("加簽審核 - " + parent.getName());
        subtask.setDescription(description);
        taskService.saveTask(subtask);

        auditPublisher.publish(new AuditEvent("TASK_COUNTERSIGN", assignee,
                parent.getProcessInstanceId(), subtask.getId(),
                Map.of("parentTaskId", parentTaskId, "assignee", assignee)));

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", subtask.getId());
        result.put("parentTaskId", parentTaskId);
        result.put("assignee", assignee);
        result.put("name", subtask.getName());
        return result;
    }

    /**
     * Get subtasks for a parent task.
     */
    @GetMapping("/{taskId}/subtasks")
    public List<Map<String, Object>> getSubtasks(@PathVariable String taskId) {
        return taskService.getSubTasks(taskId).stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", t.getId());
            m.put("name", t.getName());
            m.put("assignee", t.getAssignee());
            m.put("description", t.getDescription());
            m.put("createTime", t.getCreateTime());
            return m;
        }).toList();
    }

    /**
     * Complete a subtask — appends comment to parent, checks if all subtasks done.
     */
    @PutMapping("/{taskId}/subtasks/{subtaskId}/complete")
    public Map<String, Object> completeSubtask(@PathVariable String taskId,
                                                @PathVariable String subtaskId,
                                                @RequestBody(required = false) Map<String, String> req) {
        Task subtask = taskService.createTaskQuery().taskId(subtaskId).singleResult();
        if (subtask == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        // Append opinion to parent task as comment
        String opinion = req != null ? req.getOrDefault("opinion", "") : "";
        if (!opinion.isBlank()) {
            Task parent = taskService.createTaskQuery().taskId(taskId).singleResult();
            taskService.addComment(taskId, parent != null ? parent.getProcessInstanceId() : null,
                    "[加簽意見 - " + subtask.getAssignee() + "] " + opinion);
        }

        taskService.deleteTask(subtaskId, true);

        // Check remaining subtasks
        List<Task> remaining = taskService.getSubTasks(taskId);
        boolean allDone = remaining.isEmpty();

        return Map.of("subtaskId", subtaskId, "allSubtasksDone", allDone);
    }
}
