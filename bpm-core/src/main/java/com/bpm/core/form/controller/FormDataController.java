package com.bpm.core.form.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.form.model.FormData;
import com.bpm.core.form.service.FormService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/form-data")
public class FormDataController {

    private final FormService formService;
    private final AuditEventPublisher auditPublisher;

    public FormDataController(FormService formService, AuditEventPublisher auditPublisher) {
        this.formService = formService;
        this.auditPublisher = auditPublisher;
    }

    @PostMapping
    @Transactional("formTransactionManager")
    public FormData submit(@RequestBody FormData data) {
        FormData saved = formService.submitData(data);
        auditPublisher.publish(new AuditEvent("FORM_SUBMIT", data.getSubmittedBy(), data.getProcessInstanceId(),
                null, Map.of("formDefinitionId", data.getFormDefinitionId(), "formDataId", saved.getId())));
        return saved;
    }

    @GetMapping("/{processInstanceId}")
    public List<FormData> getByProcess(@PathVariable String processInstanceId) {
        return formService.getDataByProcess(processInstanceId);
    }

    @PutMapping("/{id}")
    @Transactional("formTransactionManager")
    public FormData update(@PathVariable String id, @RequestBody FormData data) {
        FormData saved = formService.updateData(id, data);
        auditPublisher.publish(new AuditEvent("FORM_UPDATE", data.getSubmittedBy(), saved.getProcessInstanceId(),
                null, Map.of("formDataId", id)));
        return saved;
    }
}
