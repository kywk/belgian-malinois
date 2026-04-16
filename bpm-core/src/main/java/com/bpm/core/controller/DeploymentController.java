package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.lint.BpmnLintService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/deployments")
public class DeploymentController {

    private final RepositoryService repositoryService;
    private final BpmnLintService lintService;
    private final AuditEventPublisher auditPublisher;
    private final Path bpmnDir;

    public DeploymentController(RepositoryService repositoryService, BpmnLintService lintService,
                                AuditEventPublisher auditPublisher,
                                @Value("${bpm.bpmn-definitions-dir:./bpmn-definitions}") String bpmnDir) {
        this.repositoryService = repositoryService;
        this.lintService = lintService;
        this.auditPublisher = auditPublisher;
        this.bpmnDir = Path.of(bpmnDir);
    }

    @PostMapping
    public Object deploy(@RequestParam("file") MultipartFile file,
                         @RequestParam(defaultValue = "") String name) throws IOException {
        String xml = new String(file.getBytes());
        String deployName = name.isEmpty() ? file.getOriginalFilename() : name;

        // 1. Lint validation
        var lintResult = lintService.lint(xml);
        if (!lintResult.valid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BPMN Lint 驗證失敗: " + lintResult.errors().size() + " 個錯誤");
        }

        // 2. Save XML to bpmn-definitions/
        Files.createDirectories(bpmnDir);
        Files.writeString(bpmnDir.resolve(deployName != null ? deployName : "process.bpmn20.xml"), xml);

        // 3. Deploy to Flowable
        Deployment deployment = repositoryService.createDeployment()
                .name(deployName)
                .addString(deployName != null ? deployName : "process.bpmn20.xml", xml)
                .deploy();

        auditPublisher.publish(new AuditEvent("BPMN_DEPLOY", null, null, null,
                Map.of("deploymentId", deployment.getId(), "name", deployment.getName())));

        return Map.of("deploymentId", deployment.getId(), "name", deployment.getName(),
                "lint", lintResult);
    }
}
