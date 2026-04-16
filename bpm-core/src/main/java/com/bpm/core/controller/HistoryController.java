package com.bpm.core.controller;

import org.flowable.engine.HistoryService;
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

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
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

    @GetMapping("/process-instances")
    public List<Map<String, Object>> getHistoricProcessInstances(
            @RequestParam(required = false) String initiator,
            @RequestParam(required = false, defaultValue = "false") boolean finished) {
        var query = historyService.createHistoricProcessInstanceQuery();
        if (initiator != null) query.variableValueEquals("initiator", initiator);
        if (finished) query.finished();
        return query.orderByProcessInstanceStartTime().desc().list().stream()
                .map(this::processToMap).toList();
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
        return m;
    }
}
