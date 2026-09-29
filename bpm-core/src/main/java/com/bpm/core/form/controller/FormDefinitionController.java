package com.bpm.core.form.controller;

import com.bpm.core.security.CallerId;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.service.FormService;
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
    public FormDefinition create(@RequestBody FormDefinition def,
                                 @CallerId
                                 String userId) {
        FormDefinition saved = formService.create(def);
        audit("FORM_UPDATE", userId, saved, "create");
        return saved;
    }

    /**
     * 為既有 formKey 建立下一版 draft —— 已發布表單的改版路徑（P1-12）。
     *
     * <p>改動前完全沒有這個端點，因此 data.sql 種下的四張 published 表單
     * 透過 API 完全不可修改。
     */
    @PostMapping("/{formKey}/revisions")
    public FormDefinition createRevision(@PathVariable String formKey,
                                         @CallerId
                                         String userId) {
        FormDefinition draft = formService.createNextDraft(formKey, userId);
        audit("FORM_UPDATE", userId, draft, "revise");
        return draft;
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
    public FormDefinition update(@PathVariable String id, @RequestBody FormDefinition def,
                                 @CallerId
                                 String userId) {
        FormDefinition saved = formService.update(id, def);
        audit("FORM_UPDATE", userId, saved, "update");
        return saved;
    }

    @PostMapping("/{id}/publish")
    public FormDefinition publish(@PathVariable String id,
                                  @CallerId
                                  String userId) {
        FormDefinition saved = formService.publish(id);
        audit("FORM_UPDATE", userId, saved, "publish");
        return saved;
    }

    @PostMapping("/{id}/archive")
    public FormDefinition archive(@PathVariable String id,
                                  @CallerId
                                  String userId) {
        FormDefinition saved = formService.archive(id);
        audit("FORM_UPDATE", userId, saved, "archive");
        return saved;
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id,
                                      @CallerId
                                      String userId) {
        FormDefinition existing = formService.getById(id);
        formService.delete(id);
        audit("FORM_UPDATE", userId, existing, "delete");
        return Map.of("status", "deleted");
    }

    /**
     * 表單定義變更的稽核（security-audit P1-15）。
     *
     * <p>改動前這個 controller 注入了 {@code auditPublisher} 卻<b>一次都沒用</b>
     * → 表單定義的 create／update／publish／archive／delete <b>全部零稽核</b>。
     * 「誰改了審核表的欄位」沒有任何軌跡 —— 而改 schema 等於改流程行為
     * （表單欄位 id 就是流程變數名，spec §8.5）。
     */
    private void audit(String operationType, String userId, FormDefinition def, String action) {
        auditPublisher.publish(new AuditEvent(operationType,
                userId != null ? userId : "unknown",
                null,
                null, Map.of("action", action,
                       "formKey", def.getFormKey() != null ? def.getFormKey() : "",
                       "version", def.getVersion() != null ? def.getVersion() : 0,
                       "status", def.getStatus() != null ? def.getStatus() : "",
                       "formDefinitionId", def.getId() != null ? def.getId() : "")));
    }
}
