package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/deployments")
public class DeploymentController {

    private final RepositoryService repositoryService;
    private final AuditEventPublisher auditPublisher;

    public DeploymentController(RepositoryService repositoryService, AuditEventPublisher auditPublisher) {
        this.repositoryService = repositoryService;
        this.auditPublisher = auditPublisher;
    }

    @PostMapping
    public Map<String, Object> deploy(@RequestParam("file") MultipartFile file,
                                       @RequestParam(defaultValue = "") String name) throws Exception {
        String deployName = name.isEmpty() ? file.getOriginalFilename() : name;
        Deployment deployment = repositoryService.createDeployment()
                .name(deployName)
                .addInputStream(file.getOriginalFilename(), file.getInputStream())
                .deploy();

        auditPublisher.publish(new AuditEvent("BPMN_DEPLOY", null, null, null,
                Map.of("deploymentId", deployment.getId(), "name", deployName)));

        return Map.of("deploymentId", deployment.getId(), "name", deployment.getName());
    }
}
