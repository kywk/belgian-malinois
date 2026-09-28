package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class ProcessVariableSpecController {

    private final ProcessVariableSpecRepository repo;
    private final ExternalSystemPolicy policy;

    public ProcessVariableSpecController(ProcessVariableSpecRepository repo,
                                          ExternalSystemPolicy policy) {
        this.repo = repo;
        this.policy = policy;
    }

    // Admin API
    @PostMapping("/api/admin/process-definitions/{key}/variable-spec")
    @Transactional
    public List<ProcessVariableSpec> batchSave(@PathVariable String key,
                                                @RequestBody List<ProcessVariableSpec> specs) {
        repo.deleteByProcessDefinitionKey(key);
        // 這個端點不在 security-audit 的 P0-4 清單內，但問題完全相同：
        // 以 entity 當 @RequestBody，夾帶 id 就會讓 saveAll 走 merge。
        specs.forEach(s -> {
            s.setId(null);
            s.setProcessDefinitionKey(key);
        });
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

    /**
     * External API（由 ExternalApiAuthFilter 驗證 API Key）。
     *
     * <p>⚠️ 改動前這裡只驗 API Key、不比對 allowedProcessKeys —— 任何持
     * {@code query_status} 權限的外部系統都能枚舉<b>全部</b>流程定義的變數規格
     * （變數名、型別、是否必填、說明、範例）。spec §9.1.3 步驟 6 明確要求
     * 必須檢查 allowedProcessKeys 是否包含目標流程。
     */
    @GetMapping("/api/external/process-definitions/{key}/variable-spec")
    public List<ProcessVariableSpec> getSpec(@PathVariable String key,
                                              @RequestAttribute("externalSystem") ExternalSystem sys) {
        if (!policy.isProcessKeyAllowed(sys, key)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "此系統未被授權存取流程: " + key);
        }
        return repo.findByProcessDefinitionKeyOrderByVariableName(key);
    }
}
