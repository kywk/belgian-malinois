package com.bpm.core.controller;

import com.bpm.core.model.NotifyConfig;
import com.bpm.core.model.NotifyTemplate;
import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class NotifyAdminController {

    private final NotifyTemplateRepository templateRepo;
    private final NotifyConfigRepository configRepo;

    public NotifyAdminController(NotifyTemplateRepository templateRepo, NotifyConfigRepository configRepo) {
        this.templateRepo = templateRepo;
        this.configRepo = configRepo;
    }

    // Templates
    @PostMapping("/notify-templates")
    public NotifyTemplate createTemplate(@RequestBody NotifyTemplate t) {
        // 必定新增。夾帶 id 的話 save() 會走 merge → 覆寫既有模板
        // （可植入釣魚連結），而本 controller 零稽核 → 無人知道被改過。
        t.setId(null);
        return templateRepo.save(t);
    }

    @GetMapping("/notify-templates")
    public List<NotifyTemplate> listTemplates() { return templateRepo.findAll(); }

    @GetMapping("/notify-templates/{id}")
    public NotifyTemplate getTemplate(@PathVariable String id) {
        return templateRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PutMapping("/notify-templates/{id}")
    public NotifyTemplate updateTemplate(@PathVariable String id, @RequestBody NotifyTemplate t) {
        NotifyTemplate existing = templateRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        existing.setName(t.getName());
        existing.setChannel(t.getChannel());
        existing.setSubjectTemplate(t.getSubjectTemplate());
        existing.setBodyTemplate(t.getBodyTemplate());
        return templateRepo.save(existing);
    }

    @DeleteMapping("/notify-templates/{id}")
    public void deleteTemplate(@PathVariable String id) { templateRepo.deleteById(id); }

    // Configs
    @PostMapping("/notify-configs")
    public NotifyConfig createConfig(@RequestBody NotifyConfig c) {
        c.setId(null);
        return configRepo.save(c);
    }

    @GetMapping("/notify-configs")
    public List<NotifyConfig> listConfigs(@RequestParam(required = false) String processDefinitionKey) {
        if (processDefinitionKey != null)
            return configRepo.findByProcessDefinitionKeyOrderByEventType(processDefinitionKey);
        return configRepo.findAll();
    }

    @PutMapping("/notify-configs/{id}")
    public NotifyConfig updateConfig(@PathVariable String id, @RequestBody NotifyConfig c) {
        NotifyConfig existing = configRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        existing.setProcessDefinitionKey(c.getProcessDefinitionKey());
        existing.setEventType(c.getEventType());
        existing.setChannel(c.getChannel());
        existing.setTemplateId(c.getTemplateId());
        existing.setEnabled(c.getEnabled());
        return configRepo.save(existing);
    }

    @DeleteMapping("/notify-configs/{id}")
    public void deleteConfig(@PathVariable String id) { configRepo.deleteById(id); }
}
