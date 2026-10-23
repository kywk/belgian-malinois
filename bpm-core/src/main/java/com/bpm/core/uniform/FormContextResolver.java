package com.bpm.core.uniform;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.service.FormService;
import com.bpm.core.service.FormVersionLocker;
import org.flowable.engine.RuntimeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任務／案件的表單脈絡解析：「這個 formKey 是哪一份定義」與「這個案件目前
 * 有什麼資料」（uni-form 重構，計畫 §五 A1／A5）。
 *
 * <h2>為什麼要獨立一支</h2>
 *
 * <p>同一套規則被兩條路徑共用：{@code GET /api/tasks/{taskId}/form}（讀）與
 * {@code PUT /api/tasks/{id}} 的 formData（寫）。兩邊各自實作會出現
 * 「讀到的版本與寫入驗證的版本不一致」的組合型缺陷 —— 那正是本 repo 反覆
 * 記載、最難察覺的一種。因此版本解析與最新列解析都留在這裡，只有
 * <b>狀態碼與訊息</b>（400／404 的差異是政策）留在各自的 controller。
 *
 * <h2>版本解析：版本鎖定優先</h2>
 *
 * <p>{@code _formVersions}（由 {@code FormVersionLocker} 在啟動時寫入）是
 * 「這個案件用哪一版」的唯一權威。新申請以 _formVersionPolicy 標記，缺鎖一律拒絕；
 * 無標記的歷史／技術案件保留既有 latest fallback，須另行修復。鎖定版本仍可封存：
 * <b>已完成或已封存的版本仍必須被進行中的案件用到</b>（封存只影響新案件）。
 */
@Service
public class FormContextResolver {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final FormService formService;
    private final RuntimeService runtimeService;
    private final ObjectMapper objectMapper;

    public FormContextResolver(FormService formService, RuntimeService runtimeService,
                               ObjectMapper objectMapper) {
        this.formService = formService;
        this.runtimeService = runtimeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 {@code formKey} 對應的定義（考慮 {@code _formVersions} 鎖定）。
     *
     * @param processInstanceId 案件 id；{@code null}＝沒有案件（start-form），直接取最新 published
     * @throws ResponseStatusException 404：沒有可用的定義（含鎖定版本不存在）
     */
    public FormDefinition definitionFor(String formKey, String processInstanceId) {
        Integer locked = lockedVersion(processInstanceId, formKey);
        try {
            FormDefinition definition = formService.getSchema(formKey, locked);
            if (locked != null && (definition == null || "draft".equals(definition.getStatus()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "案件鎖定的表單版本不存在或仍是草稿，拒絕改用最新版");
            }
            return definition;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() != 404) throw e;
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "找不到表單定義（formKey=" + formKey
                            + (locked != null ? "，version=" + locked : "，最新 published 版本")
                            + "）：請確認表單已發布，且此案件的 _formVersions 指到的版本存在。",
                    e);
        }
    }

    /**
     * {@code _formVersions} 的 formKey → version；沒有鎖定資訊回 {@code null}。
     *
     * <p>刻意<b>不</b>吞例外：呼叫端（任務／案件）在此之前都已確認實例存在；
     * 讀變數失敗是引擎故障，靜默降級成「用最新版」會讓改版在案件進行中生效，
     * 而表單欄位就是流程變數名（spec §8.5）—— 那是不可接受的靜默語意變更。
     */
    public Integer lockedVersion(String processInstanceId, String formKey) {
        if (processInstanceId == null) {
            return null;
        }
        Object raw = runtimeService.getVariable(processInstanceId, FormVersionLocker.VERSIONS_VARIABLE);
        if (raw instanceof Map<?, ?> versions && versions.get(formKey) instanceof Number number
                && number.intValue() > 0 && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        if (FormVersionLocker.APPLICATION_POLICY.equals(runtimeService.getVariable(
                processInstanceId, FormVersionLocker.POLICY_VARIABLE))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "申請案件缺少有效的表單版本鎖定，已停止處理；請由維運修復，不能自動改用最新版");
        }
        return null;
    }

    /** 案件最新一列 FormData（{@code submittedAt} 遞減的第一筆）；沒有資料回 {@code null}。 */
    public FormData latestRow(String processInstanceId) {
        List<FormData> rows = formService.getDataByProcess(processInstanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 一列 FormData 的 canonical JSON 物件；沒有列（{@code null}）回空物件。
     *
     * <p>資料列是<b>伺服器寫入的</b>，形狀壞掉是伺服器端問題 → 500（不是
     * 呼叫端的 400）。為避免「讀得出來就送進投影」，這裡對非物件也 fail-closed。
     */
    public Map<String, Object> storedDataOf(FormData row) {
        if (row == null || row.getDataJson() == null || row.getDataJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        String dataJson = row.getDataJson();
        try {
            Map<String, Object> parsed = objectMapper.readValue(dataJson, MAP);
            if (parsed == null) {
                throw corrupt(row, "不是 JSON 物件");
            }
            return parsed;
        } catch (JacksonException e) {
            throw corrupt(row, e.getOriginalMessage());
        }
    }

    private ResponseStatusException corrupt(FormData row, String detail) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "已儲存的表單資料無法解讀（formDataId=" + row.getId() + "）：" + detail
                        + "。資料已損毀，本次操作已拒絕；請由維運檢查 bpm_form_data。");
    }
}
