package com.bpm.form.controller;

import com.bpm.form.audit.AuditEventPublisher;
import com.bpm.form.model.FormData;
import com.bpm.form.service.FormService;
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
    public FormData submit(@RequestBody FormData data) {
        FormData saved = formService.submitData(data);
        auditPublisher.publish("FORM_SUBMIT", data.getSubmittedBy(), data.getProcessInstanceId(),
                Map.of("formDefinitionId", data.getFormDefinitionId(), "formDataId", saved.getId()));
        return saved;
    }

    @GetMapping("/{processInstanceId}")
    public List<FormData> getByProcess(@PathVariable String processInstanceId) {
        return formService.getDataByProcess(processInstanceId);
    }

    @PutMapping("/{id}")
    public FormData update(@PathVariable String id, @RequestBody FormData data) {
        FormData saved = formService.updateData(id, data);
        auditPublisher.publish("FORM_UPDATE", data.getSubmittedBy(), saved.getProcessInstanceId(),
                Map.of("formDataId", id));
        return saved;
    }
}
