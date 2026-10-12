package com.bpm.core.uniform;

import com.bpm.core.form.model.FormDefinition;
import com.nanshan.uniform.boot4.UniformBffService;
import com.nanshan.uniform.boot4.UniformSpecResources;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 以 {@code SubmissionValidation.validateV1} 驗證一筆表單提交
 * （docs/plan/2026-10-12-uniform-form-refactor.md §五 A5／§六 BE）。
 *
 * <h2>純驗證、零副作用</h2>
 *
 * <p>SDK 的 validateV1 是<b>純驗證入口</b>：不回傳 candidate、不合併資料、
 * 不存檔、不呼叫 provider。本服務把它的 {ok, errors} 轉成本專案的
 * 「400 訊息清單」或「500 refusal」，<b>一個字都不寫</b>。寫入與版本化是
 * 呼叫端（TaskController／ProcessController）的事，而且一律排在驗證之後。
 *
 * <h2>輸入的三份資料（同名參數不得取自使用者的同名宣稱）</h2>
 *
 * <ul>
 *   <li>{@code submittedData}＝本次提交值（未信任）。</li>
 *   <li>{@code contextData}＝本次提交值（本階段無條件：沒有可信的 Access
 *       快照來源，see {@link #validate}）。</li>
 *   <li>{@code storedData}＝前一列 canonical（可信：伺服器自己寫的），
 *       沒有前一列則為 {@code {}}。</li>
 * </ul>
 *
 * <h2>為什麼 mode 必須是 fill</h2>
 *
 * <p>validateV1 的契約固定 {@code profile.mode = "fill"}（SUB-01）。
 * 呼叫端在進到這裡之前就必須擋掉非 fill 的節點（view 節點沒有可接受的
 * 提交語意），因此本服務把非 fill 視為程式錯誤 → 500，
 * 而不是偷偷替呼叫端改寫 profile。
 */
@Service
public class FormSubmissionService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** unknownField 的固定文案（SDK 的錯誤物件本身就是固定的）。 */
    private static final String UNKNOWN_FIELD_MESSAGE = "此欄位不存在於目前 schema";

    private final UniformBffService bff;
    private final UniformSpecResources specResources;
    private final ObjectMapper objectMapper;

    public FormSubmissionService(UniformBffService bff, UniformSpecResources specResources,
                                 ObjectMapper objectMapper) {
        this.bff = bff;
        this.specResources = specResources;
        this.objectMapper = objectMapper;
    }

    /**
     * 驗證一筆提交。
     *
     * @param def       表單定義（提供 DSL）
     * @param profile   該 BPMN 節點的 Access Profile；{@code mode} 必須是 {@code fill}
     * @param submitted 本次提交的 canonical JSON 物件
     * @param stored    前一列 canonical JSON 物件；無前一列傳空 map
     * @return 錯誤訊息清單（已組裝為面向呼叫端的字串）；<b>空清單＝通過</b>
     * @throws ResponseStatusException 500：SDK refusal（定義或資源問題，不是使用者輸入問題）
     */
    public List<String> validate(FormDefinition def, Map<String, Object> profile,
                                 Map<String, Object> submitted, Map<String, Object> stored) {
        if (!"fill".equals(profile.get("mode"))) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "提交驗證只接受 mode=fill 的 Access Profile，收到 mode="
                            + profile.get("mode") + "（呼叫端必須先擋下非 fill 的節點）");
        }
        Map<String, Object> dsl = parseSchema(def);

        Map<String, Object> behavior = new LinkedHashMap<>();
        behavior.put("providers", new ArrayList<>());
        behavior.put("actions", new ArrayList<>());
        behavior.put("frames", new ArrayList<>());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dsl", dsl);
        input.put("manifests", specResources.manifests());
        input.put("profile", profile);
        input.put("fieldTypes", new LinkedHashMap<>());
        input.put("rowFieldTypes", new LinkedHashMap<>());
        input.put("behaviorRegistry", behavior);
        input.put("locale", FormEnvelopeService.LOCALE);
        // contextData 刻意等於 submitted：本階段沒有可信的 Access 快照來源，
        // 條件求值用「本次提交值」是唯一可解釋的選擇（計畫 §六 BE）。
        // ⚠️ 不可改成 stored：那會讓條件用舊值求值（readonly 的欄位其實已改）。
        input.put("contextData", submitted);
        input.put("submittedData", submitted);
        input.put("storedData", stored != null ? stored : new LinkedHashMap<>());
        input.put("rowOrigins", new LinkedHashMap<>());
        input.put("services", new LinkedHashMap<>());
        input.put("fileBindings", new ArrayList<>());

        Map<String, Object> result = bff.validateV1(input);
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "表單提交驗證被拒絕（定義或資源問題）：formKey=" + def.getFormKey()
                            + "，version=" + def.getVersion()
                            + "。請確認表單定義是合法 DSL、manifest 已註冊、且 SDK 版本相符。");
        }
        Object rawErrors = result.get("errors");
        if (!(rawErrors instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        return messages(items, dsl, submitted);
    }

    // ── 訊息組裝 ────────────────────────────────────────────────────

    /**
     * SDK 的 {@code errors[]} → 面向呼叫端的訊息清單。
     *
     * <p>格式：{@code '<path 最後一段>' <error.message>}，以 {@code ；} 串接
     * （計畫 §六 BE）。{@code path} 是 JSON Pointer（{@code /leaveType}），
     * 使用者看得懂的是最後一段（欄位 key）。
     *
     * <p><b>unknownField 的特別處理</b>：SDK 對「不存在的欄位」與「hidden
     * 欄位被提交」刻意回報<b>同一個 byte-identical 錯誤</b>（ENV-ERR-06(2)），
     * 且 path 固定空字串、message 固定文案 —— 也就是說錯誤本身<b>不可能</b>
     * 指出是哪個 key。但 UX 需要知道要改哪一個欄位，因此這裡另外把
     * 「submitted 有、DSL 沒有」的 top-level key 列進訊息。
     *
     * <p>⚠️ 這個附加清單<b>不洩漏 hidden 欄位</b>：它只列「DSL 完全沒有」的
     * key，而 hidden 欄位是 DSL 有的（只是被 profile 隱藏）。hidden 被提交時
     * 清單為空，訊息就只回固定文案 —— 與「欄位不存在」不可分辨，正是 SDK
     * 要的性質。
     */
    private List<String> messages(List<?> items, Map<String, Object> dsl, Map<String, Object> submitted) {
        Set<String> declared = declaredKeys(dsl.get("root"));
        List<String> unknownKeys = new ArrayList<>();
        for (String key : submitted.keySet()) {
            if (key.startsWith("$") || declared.contains(key)) {
                continue;
            }
            unknownKeys.add(key);
        }
        LinkedHashSet<String> messages = new LinkedHashSet<>();
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item)) {
                continue;
            }
            String path = item.get("path") instanceof String text ? text : "";
            String message = item.get("message") instanceof String text ? text : "";
            if ("unknownField".equals(item.get("code"))) {
                messages.add(unknownKeys.isEmpty()
                        ? UNKNOWN_FIELD_MESSAGE
                        : UNKNOWN_FIELD_MESSAGE + "（未定義的欄位：" + String.join("、", unknownKeys) + "）");
                continue;
            }
            messages.add("'" + lastSegment(path) + "' " + message);
        }
        return List.copyOf(messages);
    }

    /** {@code /a/b~1c} → {@code c}（JSON Pointer 最後一段；空 path 回空字串）。 */
    private static String lastSegment(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * DSL 內所有節點的 key 集合（含 layout 子節點與 repeater 子欄位）。
     *
     * <p>用途只是「把 submitted 的未知 key 指名出來」，不是 Access 計算；
     * 因此刻意<b>不</b>依 registry 判斷 repeater（本階段 palette 無 repeater）。
     */
    private static Set<String> declaredKeys(Object root) {
        Set<String> keys = new LinkedHashSet<>();
        collect(root, keys);
        return keys;
    }

    private static void collect(Object node, Set<String> keys) {
        if (node instanceof List<?> list) {
            for (Object child : list) {
                collect(child, keys);
            }
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        if (map.get("key") instanceof String key && !key.isBlank()) {
            keys.add(key);
        }
        collect(map.get("children"), keys);
    }

    // ── 共用（TaskController／ProcessController 需要同樣的 dataJson 解析語意）──

    /**
     * {@code dataJson} → canonical JSON 物件。
     *
     * @param what 訊息用的來源名稱（例如 {@code formData.dataJson}）
     * @throws ResponseStatusException 400：不是 JSON 物件（呼叫端的輸入問題）
     */
    public Map<String, Object> parseDataJson(String dataJson, String what) {
        if (dataJson == null || dataJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    what + " 為必填 —— 表單資料必須是 JSON 物件。");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(dataJson, MAP);
            if (parsed == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        what + " 必須是 JSON 物件（{...}）。");
            }
            return parsed;
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    what + " 不是合法的 JSON：" + e.getOriginalMessage());
        }
    }

    private Map<String, Object> parseSchema(FormDefinition def) {
        String raw = def.getSchemaJson();
        if (raw == null || raw.isBlank()) {
            throw unreadable(def, "schemaJson 為空");
        }
        try {
            Map<String, Object> dsl = objectMapper.readValue(raw, MAP);
            if (dsl == null) {
                throw unreadable(def, "schemaJson 不是 JSON 物件");
            }
            return dsl;
        } catch (JacksonException e) {
            throw unreadable(def, e.getOriginalMessage());
        }
    }

    private ResponseStatusException unreadable(FormDefinition def, String detail) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "表單定義（formKey=" + def.getFormKey() + "，version=" + def.getVersion()
                        + "）的 schemaJson 無法解讀：" + detail
                        + "。為避免寫入未經驗證的資料，本次提交已拒絕；"
                        + "請以設計器建立新版 draft 後發布。");
    }
}
