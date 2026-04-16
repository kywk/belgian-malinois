package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.dto.StartProcessRequest;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.image.ProcessDiagramGenerator;
import org.flowable.image.impl.DefaultProcessDiagramGenerator;
import org.flowable.task.api.Task;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.*;

@RestController
@RequestMapping("/api/process-instances")
public class ProcessController {

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final TaskService taskService;
    private final AuditEventPublisher auditPublisher;

    public ProcessController(RuntimeService runtimeService, RepositoryService repositoryService,
                             TaskService taskService, AuditEventPublisher auditPublisher) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
    }

    @PostMapping
    public Map<String, Object> startProcess(@RequestBody StartProcessRequest req) {
        Map<String, Object> vars = req.variables() != null ? new HashMap<>(req.variables()) : new HashMap<>();
        if (req.initiator() != null) vars.put("initiator", req.initiator());

        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                req.processDefinitionKey(), req.businessKey(), vars);

        auditPublisher.publish(new AuditEvent("PROCESS_START", req.initiator(),
                pi.getProcessInstanceId(), null,
                Map.of("processDefinitionKey", req.processDefinitionKey(),
                        "businessKey", req.businessKey() != null ? req.businessKey() : "")));

        Map<String, Object> result = new HashMap<>();
        result.put("processInstanceId", pi.getProcessInstanceId());
        result.put("businessKey", req.businessKey() != null ? req.businessKey() : "");
        result.put("status", "running");

        // Include currentTask info
        Task currentTask = taskService.createTaskQuery()
                .processInstanceId(pi.getProcessInstanceId()).singleResult();
        if (currentTask != null) {
            result.put("currentTask", Map.of(
                    "taskId", currentTask.getId(),
                    "taskName", currentTask.getName() != null ? currentTask.getName() : "",
                    "assignee", currentTask.getAssignee() != null ? currentTask.getAssignee() : ""));
        }
        return result;
    }

    @GetMapping
    public List<Map<String, Object>> getProcessInstances(@RequestParam(required = false) String initiator) {
        var query = runtimeService.createProcessInstanceQuery();
        if (initiator != null) query.variableValueEquals("initiator", initiator);
        return query.orderByProcessInstanceId().desc().list().stream()
                .map(pi -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("processInstanceId", pi.getProcessInstanceId());
                    m.put("processDefinitionKey", pi.getProcessDefinitionKey());
                    m.put("businessKey", pi.getBusinessKey() != null ? pi.getBusinessKey() : "");
                    m.put("startTime", pi.getStartTime());
                    m.put("status", "running");
                    // currentTask
                    Task task = taskService.createTaskQuery()
                            .processInstanceId(pi.getProcessInstanceId()).singleResult();
                    if (task != null) {
                        m.put("currentTask", Map.of(
                                "taskName", task.getName() != null ? task.getName() : "",
                                "assignee", task.getAssignee() != null ? task.getAssignee() : ""));
                    }
                    return m;
                }).toList();
    }

    @GetMapping(value = "/{id}/diagram", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] getDiagram(@PathVariable String id) throws Exception {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(id).singleResult();
        if (pi == null) return new byte[0];

        BpmnModel model = repositoryService.getBpmnModel(pi.getProcessDefinitionId());
        List<String> activeIds = runtimeService.getActiveActivityIds(id);
        ProcessDiagramGenerator gen = new DefaultProcessDiagramGenerator();
        try (InputStream is = gen.generateDiagram(model, "png", activeIds,
                Collections.emptyList(), "宋体", "宋体", "宋体", null, 1.0, true)) {
            return is.readAllBytes();
        }
    }
}
