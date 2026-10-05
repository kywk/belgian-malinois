package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 外部系統授權設定的<b>寫入端</b>驗證（R-21）。
 *
 * <h2>為什麼驗證必須在寫入端</h2>
 *
 * <p>改動前 {@code allowedProcessKeys}／{@code allowedActions}／
 * {@code allowedCandidateGroups}／{@code allowedWorkerTopics} 都是自由文字欄位：
 * admin UI 沒有必填、沒有格式檢查，任何字串都能進資料庫。而讀取端
 * （{@link ExternalSystemPolicy}）對「空值」的語意是 {@code UNRESTRICTED}
 * —— 於是<b>照 UI 正常流程建立的外部系統預設可以啟動任何流程</b>，
 * 授權檢查在預設路徑上是 no-op。
 *
 * <p>更糟的是兩個欄位的方向相反：{@code allowedActions} 由 checkbox 產生
 * {@code "[]"}（拒絕全部），{@code allowedProcessKeys} 清空後是 {@code null}
 * （不限制）。畫面上兩者都只是「沒填」，但安全後果完全相反。
 *
 * <h2>這裡做什麼、不做什麼</h2>
 *
 * <ul>
 *   <li><b>四個欄位一律做 JSON 陣列格式驗證</b>：解析失敗 → 400 並指名欄位。
 *       垃圾值不再進資料庫（改動前它會讓該系統所有呼叫在讀取端被判定
 *       {@code INVALID} 而全數拒絕，線索只有一行 WARN）。</li>
 *   <li><b>{@code allowedProcessKeys} 強制非空</b>：欄位缺席／{@code null}／
 *       空白／{@code "[]"} 一律 400。空清單在讀取端是「拒絕全部」，
 *       把它當成合法設定只會製造一個永遠啟不了流程的外部系統。</li>
 *   <li><b>每個流程 key 必須是已部署的流程定義</b>：錯字與未部署的 key
 *       在寫入時就被擋下，而不是等到呼叫端拿到 403 才發現。</li>
 * </ul>
 *
 * <p>另外三個欄位<b>允許</b>空值：它們的語意本來就是「不限制」
 * （見 {@code ExternalSystemPolicy.Kind.UNRESTRICTED}），而 {@code "[]"} 是
 * 合法的「拒絕全部」——兩者刻意區分，不可互相轉換。
 *
 * <p>⚠️ <b>讀取端仍保留逗號分隔的容忍</b>（見 {@code ExternalSystemPolicy.parse}）：
 * 那是為了 migration 之後的既有資料列，不是新寫入的格式。寫入端只接受
 * JSON 陣列，新資料因此都是單一格式。
 */
@Component
public class ExternalSystemAuthorizationValidator {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final RepositoryService repositoryService;

    public ExternalSystemAuthorizationValidator(ObjectMapper objectMapper,
                                                RepositoryService repositoryService) {
        this.objectMapper = objectMapper;
        this.repositoryService = repositoryService;
    }

    /**
     * 驗證 create／update 傳入的授權欄位。
     *
     * <p>呼叫端必須在<b>變更任何實體欄位之前</b>呼叫，否則 400 回應會伴隨
     * 已寫入的變更（見 {@code ExternalSystemAdminController} 的呼叫點）。
     *
     * @throws ResponseStatusException 400，訊息指名出錯的欄位
     */
    public void validate(ExternalSystem req) {
        List<String> processKeys =
                requiredJsonArray(req.getAllowedProcessKeys(), "allowedProcessKeys");
        validateJsonArray(req.getAllowedActions(), "allowedActions");
        validateJsonArray(req.getAllowedCandidateGroups(), "allowedCandidateGroups");
        validateJsonArray(req.getAllowedWorkerTopics(), "allowedWorkerTopics");

        Set<String> deployed = deployedProcessKeys();
        List<String> unknown = processKeys.stream()
                .filter(key -> !deployed.contains(key))
                .toList();
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "allowedProcessKeys 含未部署的流程定義: " + unknown
                            + "（請以 GET /api/process-definitions 確認可用的 key）");
        }
    }

    /**
     * 必填欄位：解析後不得為空。
     *
     * <p>刻意回傳同一個「必填」訊息給「欄位缺席」與「{@code []}」兩種輸入。
     * Spring 以 entity 接收 body 時無法區分兩者（都是 null），但兩者的
     * <b>語意</b>在這裡都是不可接受的，訊息說得清楚比硬分兩種更有用。
     */
    private List<String> requiredJsonArray(String raw, String field) {
        List<String> values = validateJsonArray(raw, field);
        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 為必填：至少要授權一個已部署的流程定義"
                            + "（空值在授權層代表「不限制」，不可由寫入端產生）");
        }
        return values;
    }

    /**
     * JSON 字串陣列格式驗證。
     *
     * <p>空白（null／空字串）回傳空清單 —— 那是「不限制」的既有語意，
     * 對非必填欄位是合法的。
     *
     * <p>⚠️ 錯誤訊息只說「格式不對」，不帶解析器例外文字：本 repo 的政策是
     * 只有刻意寫的 reason 能進回應（見 {@code ErrorMessageDisclosureTest}），
     * Jackson 的例外訊息帶有類別與位置等實作細節。
     */
    private List<String> validateJsonArray(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<String> values;
        try {
            values = objectMapper.readValue(raw.trim(), STRING_LIST);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 必須是 JSON 字串陣列（例如 [\"leave-approval\"]）");
        }

        // readValue("null") 會回傳 null 而不是丟例外，必須另外擋。
        if (values == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 必須是 JSON 字串陣列（例如 [\"leave-approval\"]）");
        }
        // 空白元素在讀取端會被靜默丟棄（見 ExternalSystemPolicy.toParsed），
        // 那會讓「我明明設了這個值」與實際授權不一致 —— 寫入端直接拒絕。
        if (values.stream().anyMatch(v -> v == null || v.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 的每個元素都必須是非空字串");
        }
        return values.stream().map(String::trim).toList();
    }

    /** 目前所有已部署流程定義的 key（每個 key 取最新版本，key 本身不重複）。 */
    private Set<String> deployedProcessKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (ProcessDefinition pd : repositoryService.createProcessDefinitionQuery()
                .latestVersion().list()) {
            keys.add(pd.getKey());
        }
        return keys;
    }
}
