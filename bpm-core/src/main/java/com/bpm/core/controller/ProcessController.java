package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.dto.StartProcessRequest;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.image.ProcessDiagramGenerator;
import org.flowable.image.impl.DefaultProcessDiagramGenerator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/process-instances")
public class ProcessController {

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final AuditEventPublisher auditPublisher;

    public ProcessController(RuntimeService runtimeService, RepositoryService repositoryService,
                             AuditEventPublisher auditPublisher) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
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

        return Map.of(
                "processInstanceId", pi.getProcessInstanceId(),
                "businessKey", req.businessKey() != null ? req.businessKey() : "",
                "status", "running");
    }

    @GetMapping
    public List<Map<String, Object>> getProcessInstances(@RequestParam(required = false) String initiator) {
        var query = runtimeService.createProcessInstanceQuery();
        if (initiator != null) query.variableValueEquals("initiator", initiator);
        return query.orderByProcessInstanceId().desc().list().stream()
                .map(pi -> Map.<String, Object>of(
                        "processInstanceId", pi.getProcessInstanceId(),
                        "processDefinitionKey", pi.getProcessDefinitionKey(),
                        "businessKey", pi.getBusinessKey() != null ? pi.getBusinessKey() : "",
                        "startTime", pi.getStartTime()))
                .toList();
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
