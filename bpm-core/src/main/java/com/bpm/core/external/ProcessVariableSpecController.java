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
    @Transactional("primaryTransactionManager")
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

    /**
     * 修改單一變數規格。
     *
     * <h2>⚠️ 路徑的 {@code {key}} 必須與資料列一致（#85）</h2>
     *
     * <p>改動前 {@code findById(id)} 之後完全沒有比對
     * {@code existing.getProcessDefinitionKey()}，而路徑上的 {@code {key}}
     * 只被寫進稽核紀錄。實測（修前）：拿 purchase-approval 的那一筆 id
     * 打在 {@code .../leave-approval/variable-spec/{id}} → 回 <b>200</b>、
     * purchase-approval 的規格真的被改掉（{@code required} 由 true 變 false），
     * 而稽核紀錄寫的是 {@code processDefinitionKey = "leave-approval"}。
     *
     * <p>危害不是「改到別人的資料」—— 端點限 ADMIN，攻擊面很小。
     * 危害是<b>稽核紀錄會說謊</b>，而稽核是這個專案的核心賣點：
     * 變數規格是外部系統的<b>輸入驗證定義</b>（{@code required} 決定
     * {@code ExternalApiController.validateVariables} 會不會擋），把
     * {@code required} 從 true 改成 false 就是放寬外部系統的輸入驗證，
     * 而稽核上留下的是「有人在流程 A 上改了 amount」——
     * 追查的人會去查流程 A 的設定，而不是去查真正被改的那筆。
     *
     * <h2>為什麼是 404 而不是 403</h2>
     *
     * <p>{@code ProcessAccessGuard} 的既定政策：403 會確認「這個物件存在」，
     * 對可枚舉的 key／id 等於把枚舉管道留著。流程定義 key 是公開的、
     * 規格 id 會出現在稽核與錯誤訊息裡，所以回 404 ——
     * 呼叫端得到「這個資源不存在」而不是「這筆存在但換個流程就不給你」，
     * 那兩個回應合起來就是一個可以拿來枚舉資料存在性的 oracle。
     *
     * <h2>為什麼不靜默忽略 key 不一致（改成「以 id 為準，照樣更新」）</h2>
     *
     * <p>那樣資料庫是對的，但稽核紀錄仍然會寫錯的 key —— 除非把稽核也改成
     * 記 {@code existing} 的 key。而那正是 #66／#71／#72 定下的政策要排除的：
     * <b>明確拒絕冒用，不靜默忽略</b>。理由有兩條：
     *
     * <ol>
     *   <li>呼叫端送來的 key 與它想改的東西對不上，這是<b>呼叫端的 bug</b>
     *       （多半是前端快取了舊的 key）。靜默忽略等於回 200 讓它以為改對了，
     *       而實際改到別的地方 —— 下一次除錯會從錯誤的方向開始。
     *       明確 404 讓它立刻知道 key 對不上。</li>
     *   <li>「稽核寫實際值」必須建立在「資料確實只改該改的那筆」之上。
     *       先拒絕、稽核才可能永遠誠實。</li>
     * </ol>
     *
     * <h2>稽核寫的是實際被改的那筆</h2>
     *
     * <p>檢查通過之後 {@code key} 與 {@code existing.getProcessDefinitionKey()}
     * 必然相等，所以這裡寫哪一個都不會說謊。仍然寫
     * {@code existing.getProcessDefinitionKey()}：稽核的價值來自於
     * <b>記錄來自資料本身、而不是來自呼叫端宣稱的東西</b>。寫
     * {@code existing} 的話，萬一未來有人把檢查改成別的形狀（例如放寬成
     * 「不一致就用 existing 的 key」），稽核紀錄不會跟著一起壞掉 ——
     * 它壞掉的方式會變成「少了一筆紀錄」，那比說謊容易察覺。
     *
     * <p><b>被擋下來時不寫稽核。</b>寫一筆 {@code CONFIG_CHANGE/update}
     * 代表「這個變更發生了」，而實際上什麼都沒發生 —— 那正是這個缺陷本身
     * 在做的事。要留下「有人試圖用錯的 key」是另一種稽核型別，
     * 屬於稽核政策決定，不在 #85 的範圍內（真的需要時應比照
     * {@code ProcessAccessGuard.denyNonParticipant} 的
     * {@code DATA_ACCESS + denied}，走 {@code publishDetached}）。
     *
     * <h2>順帶修掉 id 不存在回 500</h2>
     *
     * <p>原本是 {@code orElseThrow()}（{@code NoSuchElementException} → 500）。
     * 這個端點屬於 {@code /api/admin/**}，屬於「回 404 才誠實」
     * （見 {@code NotifyAdminController.deleteTemplate}）那一類：
     * 呼叫端拿到 500 只會重試，而重試永遠不會成功。
     * 而且不修的話，這個方法會出現「id 不存在 → 500、id 存在但 key 不符 → 404」
     * 這種沒有人能解釋的組合。
     */
    @PutMapping("/api/admin/process-definitions/{key}/variable-spec/{id}")
    @Transactional("primaryTransactionManager")
    public ProcessVariableSpec update(@PathVariable String key, @PathVariable String id,
                                       @RequestBody ProcessVariableSpec spec,
                                       @CallerId String operatorId) {
        ProcessVariableSpec existing = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!key.equals(existing.getProcessDefinitionKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String before = "%s:%s required=%s".formatted(
                existing.getVariableName(), existing.getVariableType(), existing.getRequired());
        existing.setVariableName(spec.getVariableName());
        existing.setVariableType(spec.getVariableType());
        existing.setRequired(spec.getRequired());
        existing.setDescription(spec.getDescription());
        existing.setExample(spec.getExample());
        ProcessVariableSpec saved = repo.save(existing);

        auditor.record(operatorId, "process-variable-spec", "update", id, java.util.Map.of(
                "processDefinitionKey", existing.getProcessDefinitionKey(),
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
