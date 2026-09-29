package com.bpm.core.external;

import com.bpm.core.security.CallerId;
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
    private final com.bpm.core.audit.ConfigChangeAuditor auditor;

    public ProcessVariableSpecController(ProcessVariableSpecRepository repo,
                                          ExternalSystemPolicy policy,
                                         com.bpm.core.audit.ConfigChangeAuditor auditor) {
        this.repo = repo;
        this.policy = policy;
        this.auditor = auditor;
    }

    // Admin API
    @PostMapping("/api/admin/process-definitions/{key}/variable-spec")
    @Transactional
    public List<ProcessVariableSpec> batchSave(@PathVariable String key,
                                                @RequestBody List<ProcessVariableSpec> specs,
                                                @CallerId String operatorId) {
        // 先記下舊的變數名再刪 —— 這個端點是「整批取代」，
        // 所以移除了哪些變數跟新增了哪些一樣重要：
        // 刪掉一個 required 變數等於放寬外部系統的輸入驗證。
        var removed = repo.findByProcessDefinitionKeyOrderByVariableName(key).stream()
                .map(ProcessVariableSpec::getVariableName).sorted().toList();
        repo.deleteByProcessDefinitionKey(key);
        // 這個端點不在 security-audit 的 P0-4 清單內，但問題完全相同：
        // 以 entity 當 @RequestBody，夾帶 id 就會讓 saveAll 走 merge。
        specs.forEach(s -> {
            s.setId(null);
            s.setProcessDefinitionKey(key);
        });
        List<ProcessVariableSpec> saved = repo.saveAll(specs);

        // 變數規格限制外部系統能傳什麼進流程 —— 那是輸入驗證的定義，
        // 改動它需要軌跡（security-audit P2-4）。這個端點原本零稽核。
        auditor.record(operatorId, "process-variable-spec", "replace", key, java.util.Map.of(
                "processDefinitionKey", key,
                "before", String.join(",", removed),
                "after", saved.stream().map(ProcessVariableSpec::getVariableName).sorted()
                        .collect(java.util.stream.Collectors.joining(",")),
                "requiredAfter", saved.stream().filter(x -> Boolean.TRUE.equals(x.getRequired()))
                        .map(ProcessVariableSpec::getVariableName).sorted()
                        .collect(java.util.stream.Collectors.joining(","))));
        return saved;
    }

    @PutMapping("/api/admin/process-definitions/{key}/variable-spec/{id}")
    public ProcessVariableSpec update(@PathVariable String key, @PathVariable String id,
                                       @RequestBody ProcessVariableSpec spec,
                                       @CallerId String operatorId) {
        ProcessVariableSpec existing = repo.findById(id).orElseThrow();
        String before = "%s:%s required=%s".formatted(
                existing.getVariableName(), existing.getVariableType(), existing.getRequired());
        existing.setVariableName(spec.getVariableName());
        existing.setVariableType(spec.getVariableType());
        existing.setRequired(spec.getRequired());
        existing.setDescription(spec.getDescription());
        existing.setExample(spec.getExample());
        ProcessVariableSpec saved = repo.save(existing);

        auditor.record(operatorId, "process-variable-spec", "update", id, java.util.Map.of(
                "processDefinitionKey", key,
                "before", before,
                "after", "%s:%s required=%s".formatted(
                        saved.getVariableName(), saved.getVariableType(), saved.getRequired())));
        return saved;
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
