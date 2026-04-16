package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.DocumentRequest;
import com.bpm.core.repository.DocumentRequestRepository;
import com.bpm.core.service.OrgService;
import org.flowable.engine.RuntimeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentRequestRepository docRepo;
    private final RuntimeService runtimeService;
    private final OrgService orgService;
    private final AuditEventPublisher auditPublisher;

    public DocumentController(DocumentRequestRepository docRepo, RuntimeService runtimeService,
                              OrgService orgService, AuditEventPublisher auditPublisher) {
        this.docRepo = docRepo;
        this.runtimeService = runtimeService;
        this.orgService = orgService;
        this.auditPublisher = auditPublisher;
    }

    @PostMapping
    public DocumentRequest create(@RequestBody DocumentRequest req) {
        // Generate document number: DOC-{year}-{deptCode}-{seq}
        String deptCode = orgService.getDeptId(req.getCreatedBy());
        if (deptCode == null) deptCode = "GEN";
        String prefix = "DOC-" + Year.now().getValue() + "-" + deptCode.toUpperCase();
        int seq = docRepo.countByPrefix(prefix) + 1;
        req.setDocumentNumber(prefix + "-" + String.format("%03d", seq));

        // Start process
        Map<String, Object> vars = new HashMap<>();
        vars.put("initiator", req.getCreatedBy());
        vars.put("documentTitle", req.getTitle());
        vars.put("urgencyLevel", req.getUrgencyLevel());
        var pi = runtimeService.startProcessInstanceByKey(
                req.getCategory() != null ? req.getCategory() : "leave-approval",
                req.getDocumentNumber(), vars);
        req.setProcessInstanceId(pi.getProcessInstanceId());

        DocumentRequest saved = docRepo.save(req);
        auditPublisher.publish(new AuditEvent("PROCESS_START", req.getCreatedBy(),
                pi.getProcessInstanceId(), null,
                Map.of("documentNumber", saved.getDocumentNumber(), "title", saved.getTitle())));
        return saved;
    }

    @GetMapping("/{id}")
    public DocumentRequest getById(@PathVariable String id) {
        return docRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping
    public List<DocumentRequest> list(@RequestParam(required = false) String createdBy) {
        if (createdBy != null) return docRepo.findByCreatedByOrderByCreatedAtDesc(createdBy);
        return docRepo.findAll();
    }
}
