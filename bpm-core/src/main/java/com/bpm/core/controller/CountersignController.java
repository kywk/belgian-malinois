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
@RequestMapping("/api/countersign")
public class CountersignController {

    private final TaskService taskService;
    private final AuditEventPublisher auditPublisher;

    public CountersignController(TaskService taskService, AuditEventPublisher auditPublisher) {
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
    }

    @PostMapping("/{taskId}")
    public Map<String, Object> createSubtask(@PathVariable String taskId,
                                              @RequestBody Map<String, String> req) {
        Task parent = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (parent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");

        String assignee = req.get("countersignUserId");
        Task subtask = taskService.newTask();
        subtask.setParentTaskId(taskId);
        subtask.setAssignee(assignee);
        subtask.setName("加簽審核 - " + parent.getName());
        subtask.setDescription(req.getOrDefault("message", ""));
        taskService.saveTask(subtask);

        auditPublisher.publish(new AuditEvent("TASK_COUNTERSIGN", assignee,
                parent.getProcessInstanceId(), subtask.getId(),
                Map.of("parentTaskId", taskId, "assignee", assignee)));

        return Map.of("taskId", subtask.getId(), "parentTaskId", taskId,
                      "assignee", assignee, "name", subtask.getName());
    }

    @GetMapping("/{taskId}")
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

    @PutMapping("/{taskId}/{subtaskId}/complete")
    public Map<String, Object> completeSubtask(@PathVariable String taskId,
                                                @PathVariable String subtaskId,
                                                @RequestBody(required = false) Map<String, String> req) {
        Task subtask = taskService.createTaskQuery().taskId(subtaskId).singleResult();
        if (subtask == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        String opinion = req != null ? req.getOrDefault("opinion", "") : "";
        if (!opinion.isBlank()) {
            Task parent = taskService.createTaskQuery().taskId(taskId).singleResult();
            taskService.addComment(taskId, parent != null ? parent.getProcessInstanceId() : null,
                    "[加簽意見 - " + subtask.getAssignee() + "] " + opinion);
        }
        taskService.deleteTask(subtaskId, true);
        return Map.of("subtaskId", subtaskId, "allSubtasksDone", taskService.getSubTasks(taskId).isEmpty());
    }
}
