package com.bpm.core.controller;

import com.bpm.core.service.InitialAssigneeResolver;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final TaskService taskService;

    public HistoryController(HistoryService historyService, TaskService taskService) {
        this.historyService = historyService;
        this.taskService = taskService;
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> getHistoricTasks(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String processInstanceId) {
        var query = historyService.createHistoricTaskInstanceQuery().finished();
        if (assignee != null) query.taskAssignee(assignee);
        if (processInstanceId != null) query.processInstanceId(processInstanceId);
        return query.orderByHistoricTaskInstanceEndTime().desc().list().stream()
                .map(this::taskToMap).toList();
    }

    @GetMapping("/tasks/{taskId}/comments")
    public List<Map<String, Object>> getHistoricTaskComments(@PathVariable String taskId) {
        return TaskController.mapComments(taskService.getTaskComments(taskId));
    }

    @GetMapping("/process-instances")
    public List<Map<String, Object>> getHistoricProcessInstances(
            @RequestParam(required = false) String initiator,
            @RequestParam(required = false, defaultValue = "false") boolean finished) {
        var query = historyService.createHistoricProcessInstanceQuery();
        // 代員工發起的案件（R-20）：initiator 是 system:<id>，員工記在 onBehalfOf。
        // 兩者都要比對，否則代發的單不會出現在那位員工的「我的申請」。
        // 回應以 onBehalf=true 標示，讓前端能顯示「由外部系統代為提出」——
        // 使用者看到一張自己沒送過的單，必須知道它是怎麼來的。
        java.util.Set<String> onBehalf = java.util.Set.of();
        if (initiator != null) {
            query.or().variableValueEquals("initiator", initiator)
                    .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, initiator).endOr();
            onBehalf = historyService.createHistoricProcessInstanceQuery()
                    .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, initiator).list()
                    .stream().map(HistoricProcessInstance::getId).collect(java.util.stream.Collectors.toSet());
        }
        if (finished) query.finished();
        final java.util.Set<String> delegated = onBehalf;
        return query.orderByProcessInstanceStartTime().desc().list().stream()
                .map(p -> {
                    Map<String, Object> m = processToMap(p);
                    m.put("onBehalf", delegated.contains(p.getId()));
                    return m;
                }).toList();
    }

    private Map<String, Object> taskToMap(HistoricTaskInstance t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("assignee", t.getAssignee());
        m.put("processInstanceId", t.getProcessInstanceId());
        m.put("startTime", t.getStartTime());
        m.put("endTime", t.getEndTime());
        return m;
    }

    private Map<String, Object> processToMap(HistoricProcessInstance p) {
        Map<String, Object> m = new HashMap<>();
        m.put("processInstanceId", p.getId());
        m.put("processDefinitionKey", p.getProcessDefinitionKey());
        m.put("businessKey", p.getBusinessKey());
        m.put("startTime", p.getStartTime());
        m.put("endTime", p.getEndTime());
        // Derive status
        if (p.getEndTime() == null) {
            m.put("status", "running");
        } else if (p.getDeleteReason() != null) {
            m.put("status", "cancelled");
        } else {
            m.put("status", "completed");
        }
        return m;
    }
}
