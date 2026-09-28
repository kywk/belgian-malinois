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

        String assignee = req == null ? null : req.get("countersignUserId");

        // ⚠️ 這個驗證必須在 saveTask 之前，順序是重點而非防禦性程式碼。
        //
        // 改動前沒有任何驗證：assignee 為 null 時子任務仍會在下面落地
        // （assignee 為 null → 任務清單查不到它、沒有人看得到），
        // 接著 Map.of("assignee", null) 拋 NPE 變成 500。
        // 結果是父任務被「有未完成加簽子任務」的守門永遠攔住 ——
        // 案件死鎖，只能進 DB 手動清。
        //
        // 前端過去正是送出 {assignee, description}（欄位名與後端不一致），
        // 因此每一次加簽都會踩到這條路徑。
        if (assignee == null || assignee.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "countersignUserId 為必填（注意：欄位名不是 assignee）");
        }

        Task subtask = taskService.newTask();
        subtask.setParentTaskId(taskId);
        subtask.setAssignee(assignee);
        subtask.setName("加簽審核 - " + parent.getName());
        subtask.setDescription(req.getOrDefault("message", ""));
        taskService.saveTask(subtask);

        // Map.of 不接受 null 值。assignee 在上面已驗證非空，
        // 但 subtask.getName() 仍可能為 null（parent.getName() 為 null 時），
        // 因此回傳值改用允許 null 的 HashMap，避免同一類 NPE 再次把
        // 成功的操作變成 500。
        auditPublisher.publish(new AuditEvent("TASK_COUNTERSIGN", assignee,
                parent.getProcessInstanceId(), subtask.getId(),
                Map.of("parentTaskId", taskId, "assignee", assignee)));

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", subtask.getId());
        result.put("parentTaskId", taskId);
        result.put("assignee", assignee);
        result.put("name", subtask.getName());
        return result;
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
