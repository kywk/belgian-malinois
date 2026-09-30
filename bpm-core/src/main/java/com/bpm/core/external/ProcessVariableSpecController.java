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

    /**
     * 整批取代某個流程的變數規格。
     *
     * <h2>⚠️ 缺陷：重複儲存必定 500（#86）</h2>
     *
     * <p>改動前這裡是 {@code deleteByProcessDefinitionKey(key)}（衍生刪除）接著
     * {@code saveAll}。衍生刪除是「SELECT 出 entity → {@code em.remove()}」，
     * 而 {@code em.remove()} 只<b>排程</b>刪除；Hibernate 的 flush 順序固定是
     * <b>INSERT 在 DELETE 之前</b>，於是同一個 flush 裡每一筆 INSERT 都撞上
     * 尚未刪掉的同名舊列 →
     * {@code Violation of UNIQUE KEY constraint 'uk_bpm_process_variable_spec_key_name'} → 500。
     *
     * <p><b>實測（修前）：</b>同一個 key 連續呼叫兩次（內容相同），
     * 第一次 200、第二次 500。而 {@code ProcessVariableSpecAdmin.vue} 的
     * 「儲存」按鈕正是走這條路徑 —— 管理頁第二次按儲存必定壞。
     *
     * <p><b>⚠️ 觸發條件比「key 已有資料」更窄。</b>必須是<b>新批次與既有規格
     * 有同名變數</b>才會撞：INSERT 之所以失敗，是因為它寫進去的那個
     * {@code (key, variableName)} 還被舊列佔著。實測（缺陷期間）：
     * <ul>
     *   <li>新舊有重疊（改內容、或原樣重存）→ <b>500</b></li>
     *   <li>新舊完全不重疊（整批換成別的名字）→ 200，因為沒有任何一筆 INSERT
     *       會撞到同名的舊列</li>
     *   <li>送空陣列 → 200，因為沒有 INSERT</li>
     * </ul>
     * 這解釋了為什麼這個缺陷可以躲過「手動試一次看看」：剛建好規格時第一次存是好的，
     * 而管理頁的正常使用流程（改設定 → 儲存）必然與既有變數重疊。
     * 負向控制組也確認了這點 —— 缺陷期間紅的 4 條測試全部是「有重疊」形狀。
     *
     * <h2>修法：刪除改成原生 SQL（{@code deleteAllByProcessDefinitionKey}）</h2>
     *
     * <p>原生 SQL 在呼叫當下就送到資料庫，完全不進 Hibernate 的動作佇列，
     * 所以「INSERT 排在 DELETE 前面」這個排序再也碰不到它。
     * 該方法為什麼不能用衍生刪除寫在 repository 的註解裡。
     *
     * <p><b>不</b>選「衍生刪除後補 {@code flush()}」：那樣也能修好，
     * 但規則會散在「刪除」與「記得 flush」兩處 —— 這正是這個缺陷的成因
     * （create 與 update 對同一個參數有兩套規則那次也是同一個成因）。
     * 讓刪除只有一種形狀比較重要。
     *
     * <h2>沒有處理的相鄰缺陷：同一批內重複變數名仍會 500</h2>
     *
     * <p>送 {@code [{"variableName":"x",…},{"variableName":"x",…}]} 仍然是 500，
     * 那是<b>另一個</b>根因（呼叫端送了互相衝突的資料，而不是刪除與寫入的順序問題），
     * 修法是輸入驗證而不是調整刪除方式。刻意不混在這個工項裡一起改：
     * 兩者的驗證方式與回應狀態碼都不同，混在一起會讓「這個 500 修掉了嗎」
     * 變成一個無法回答的問題。已開成獨立工項 #87。
     */
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
        repo.deleteAllByProcessDefinitionKey(key);
        // 這個端點不在 security-audit 的 P0-4 清單內，但問題完全相同：
        // 以 entity 當 @RequestBody，夾帶 id 就會讓 saveAll 走 merge。
        // ⚠️ 這個 setId(null) 也讓「只刪真正消失的那幾列」這種修法不可行 ——
        // 保留舊 id 等於替夾帶 id 開一個繞道（理由見 repository 的註解）。
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
