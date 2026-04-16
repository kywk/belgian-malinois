package com.bpm.core.external;

import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ProcessVariableSpecController {

    private final ProcessVariableSpecRepository repo;

    public ProcessVariableSpecController(ProcessVariableSpecRepository repo) {
        this.repo = repo;
    }

    // Admin API
    @PostMapping("/api/admin/process-definitions/{key}/variable-spec")
    @Transactional
    public List<ProcessVariableSpec> batchSave(@PathVariable String key,
                                                @RequestBody List<ProcessVariableSpec> specs) {
        repo.deleteByProcessDefinitionKey(key);
        specs.forEach(s -> s.setProcessDefinitionKey(key));
        return repo.saveAll(specs);
    }

    @PutMapping("/api/admin/process-definitions/{key}/variable-spec/{id}")
    public ProcessVariableSpec update(@PathVariable String key, @PathVariable String id,
                                       @RequestBody ProcessVariableSpec spec) {
        ProcessVariableSpec existing = repo.findById(id).orElseThrow();
        existing.setVariableName(spec.getVariableName());
        existing.setVariableType(spec.getVariableType());
        existing.setRequired(spec.getRequired());
        existing.setDescription(spec.getDescription());
        existing.setExample(spec.getExample());
        return repo.save(existing);
    }

    // External API (protected by ExternalApiAuthFilter)
    @GetMapping("/api/external/process-definitions/{key}/variable-spec")
    public List<ProcessVariableSpec> getSpec(@PathVariable String key) {
        return repo.findByProcessDefinitionKeyOrderByVariableName(key);
    }
}
