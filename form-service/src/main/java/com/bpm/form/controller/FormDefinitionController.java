package com.bpm.form.controller;

import com.bpm.form.audit.AuditEventPublisher;
import com.bpm.form.model.FormDefinition;
import com.bpm.form.service.FormService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/forms")
public class FormDefinitionController {

    private final FormService formService;
    private final AuditEventPublisher auditPublisher;

    public FormDefinitionController(FormService formService, AuditEventPublisher auditPublisher) {
        this.formService = formService;
        this.auditPublisher = auditPublisher;
    }

    @PostMapping
    public FormDefinition create(@RequestBody FormDefinition def) {
        return formService.create(def);
    }

    @GetMapping
    public Page<FormDefinition> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return formService.list(PageRequest.of(page, size));
    }

    @GetMapping("/{formKey}")
    public FormDefinition getSchema(@PathVariable String formKey,
                                     @RequestParam(required = false) Integer version) {
        return formService.getSchema(formKey, version);
    }

    @PutMapping("/{id}")
    public FormDefinition update(@PathVariable String id, @RequestBody FormDefinition def) {
        return formService.update(id, def);
    }

    @PostMapping("/{id}/publish")
    public FormDefinition publish(@PathVariable String id) {
        return formService.publish(id);
    }

    @PostMapping("/{id}/archive")
    public FormDefinition archive(@PathVariable String id) {
        return formService.archive(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        formService.delete(id);
        return Map.of("status", "deleted");
    }
}
