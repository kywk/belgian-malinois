package com.bpm.core.uniform;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.security.CallerIdArgumentResolver;
import com.nanshan.uniform.boot4.UniformBffService;
import com.nanshan.uniform.boot4.UniformSpecResources;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 以可信定義與 Access Profile 驗證提交；動態來源由 SDK 真正執行 resolve。
 * contextData 只合併可編輯輸入與既有 canonical。全部通過才更新 submitted
 * 為可保存 canonical（含伺服器標籤）；本服務不寫資料庫。
 */
@Service
public class FormSubmissionService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** unknownField 的固定文案（SDK 的錯誤物件本身就是固定的）。 */
    private static final String UNKNOWN_FIELD_MESSAGE = "此欄位不存在於目前 schema";

    private final UniformBffService bff;
    private final UniformSpecResources specResources;
    private final ObjectMapper objectMapper;
    private final OptionsProviderRegistry optionsProviders;
    private final OptionsContext optionsContext;
    private final OptionLabelSnapshots labelSnapshots;

    /** 明確攜帶成功後才可保存的私有結果，沒有 ThreadLocal／全域暫存。 */
    public record SubmissionResult(List<String> errors, String optionLabelsJson, Set<String> clearedScalarFields) {
        public SubmissionResult(List<String> errors, String optionLabelsJson) { this(errors, optionLabelsJson, Set.of()); }
    }

    @Autowired
    public FormSubmissionService(UniformBffService bff, UniformSpecResources specResources,
                                 ObjectMapper objectMapper, OptionsProviderRegistry optionsProviders) {
        this.bff = bff;
        this.specResources = specResources;
        this.objectMapper = objectMapper;
        this.optionsProviders = optionsProviders;
        this.optionsContext = new OptionsContext(bff, specResources);
        this.labelSnapshots = new OptionLabelSnapshots(objectMapper);
    }

    /** 保留既有純驗證呼叫端；沒有 registry 的實例絕不接受動態來源。 */
    public FormSubmissionService(UniformBffService bff, UniformSpecResources specResources,
                                 ObjectMapper objectMapper) {
        this(bff, specResources, objectMapper, null);
    }

    /**
     * 驗證一筆提交。
     *
     * @param def       表單定義（提供 DSL）
     * @param profile   該 BPMN 節點的 Access Profile；{@code mode} 必須是 {@code fill}
     * @param submitted 本次提交物件；成功時更新為合併 canonical，須傳可修改 map
     * @param stored    前一列 canonical JSON 物件；無前一列傳空 map
     * @return 錯誤訊息清單（已組裝為面向呼叫端的字串）；<b>空清單＝通過</b>
     * @throws ResponseStatusException 500：SDK refusal（定義或資源問題，不是使用者輸入問題）
     */
    public List<String> validate(FormDefinition def, Map<String, Object> profile,
                                 Map<String, Object> submitted, Map<String, Object> stored) {
        return validateWithSnapshots(def, profile, submitted, stored, null).errors();
    }

    public SubmissionResult validateWithSnapshots(FormDefinition def, Map<String, Object> profile,
            Map<String, Object> submitted, Map<String, Object> stored, String previousOptionLabelsJson) {
        if (!"fill".equals(profile.get("mode"))) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "提交驗證只接受 mode=fill 的 Access Profile，收到 mode="
                            + profile.get("mode") + "（呼叫端必須先擋下非 fill 的節點）");
        }
        if (submitted.containsKey("$labels")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "$labels 由伺服器產生，不可由呼叫端提交");
        }
        Map<String, Object> dsl = parseSchema(def);
        OptionsProviderRegistry.requireSupported(dsl);
        Map<String, Object> original = new LinkedHashMap<>(submitted);
        Map<String, Object> previous = stored == null ? Map.of() : stored;
        OptionsContext.Prepared prepared = optionsContext.prepare(dsl, profile, original, previous, false);
        if (!prepared.errors().isEmpty()) return new SubmissionResult(prepared.errors(), null);
        Map<String, Object> candidate = prepared.candidate();
        Set<String> editable = prepared.editable();
        Map<String, Object> validationSubmission = prepared.submission();
        Map<String, Object> validationStored = new LinkedHashMap<>(previous);
        for (String key : prepared.clearedScalarFields()) OptionsContext.clearValue(validationStored, key);
        // DSL／profile 保持原樣：SDK 對已清除且 required 的欄位仍回必填錯誤；
        // optional 則沒有 occurrence，不呼叫 provider.resolve(null)。

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dsl", dsl);
        input.put("manifests", specResources.manifests());
        input.put("profile", profile);
        input.put("fieldTypes", new LinkedHashMap<>());
        input.put("rowFieldTypes", new LinkedHashMap<>());
        input.put("behaviorRegistry", OptionsProviderRegistry.behavior());
        input.put("locale", FormEnvelopeService.LOCALE);
        input.put("contextData", candidate);
        input.put("submittedData", validationSubmission);
        input.put("storedData", validationStored);
        input.put("rowOrigins", new LinkedHashMap<>());
        input.put("fileBindings", new ArrayList<>());

        Map<String, Object> result;
        Object privateLabels = List.of();
        if (OptionsProviderRegistry.hasSources(dsl)) {
            if (optionsProviders == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "動態選項來源尚未註冊");
            }
            String caller = CallerIdArgumentResolver.currentCallerId();
            AtomicBoolean sourceFailed = new AtomicBoolean();
            result = bff.validateWithServicesV1(input, optionsProviders.runtime(caller, sourceFailed),
                    optionsProviders.hostContext(def, caller));
            // SDK refusal 不公開 callback 例外；來源失敗由宿主明確映射 503。
            if (sourceFailed.get()) throw OptionsProviderRegistry.unavailable();
            if (Boolean.TRUE.equals(result.get("ok"))) {
                privateLabels = result.get("privateLabels");
                if (!(result.get("validation") instanceof Map<?, ?> validation)) throw refused(def);
                result = copyMap(validation);
            }
        } else {
            input.put("services", new LinkedHashMap<>());
            result = bff.validateV1(input);
        }
        if (!Boolean.TRUE.equals(result.get("ok"))) throw refused(def);
        if (!(result.get("errors") instanceof List<?> items)) throw refused(def);
        List<String> errors = messages(items, dsl, original);
        if (!errors.isEmpty()) return new SubmissionResult(errors, null);
        String snapshot = labelSnapshots.merge(previousOptionLabelsJson, dsl, candidate, original, editable, privateLabels);
        attachLabels(candidate, previous, original, editable, dsl, privateLabels);
        submitted.clear();
        submitted.putAll(candidate);
        return new SubmissionResult(List.of(), snapshot, prepared.clearedScalarFields());
    }

    /** 選項讀取也必須先驗證原始輸入權限；不能以裁剪後的公開資料冒充驗證。 */
    public Map<String, Object> optionsCandidate(FormDefinition def, Map<String, Object> profile,
            Map<String, Object> submitted, Map<String, Object> stored) {
        Map<String, Object> dsl = parseSchema(def);
        OptionsProviderRegistry.requireSupported(dsl);
        OptionsContext.Prepared prepared = optionsContext.prepare(dsl, profile,
                submitted == null ? Map.of() : submitted, stored == null ? Map.of() : stored, true);
        if (!prepared.errors().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                String.join("；", prepared.errors()));
        return prepared.candidate();
    }

    /** 只揭露固定本人部門來源的頂層 scalar label，不能直接公開私有服務結果。 */
    private static void attachLabels(Map<String, Object> candidate, Map<String, Object> stored,
            Map<String, Object> submitted, Set<String> editable, Map<String, Object> dsl, Object rawLabels) {
        Map<String, Object> labels = new LinkedHashMap<>();
        if (stored.get("$labels") instanceof Map<?, ?> oldLabels) {
            oldLabels.forEach((key, label) -> {
                if (key instanceof String text && label instanceof String && candidate.containsKey(text)) {
                    labels.put(text, label);
                }
            });
        }
        Set<String> sources = new LinkedHashSet<>();
        OptionsProviderRegistry.fields(dsl).forEach((key, field) -> {
            if (field.containsKey("optionsSource")) {
                if ("checkbox".equals(field.get("type"))) labels.remove(key);
                else sources.add(key);
            }
        });
        for (String key : sources) if (editable.contains(key) && submitted.containsKey(key)) labels.remove(key);
        if (rawLabels instanceof List<?> occurrences) for (Object raw : occurrences) {
            if (!(raw instanceof Map<?, ?> occurrence) || !(occurrence.get("path") instanceof String path)
                    || !path.startsWith("/") || path.indexOf('/', 1) >= 0) continue;
            String key = path.substring(1).replace("~1", "/").replace("~0", "~");
            if (!sources.contains(key) || !editable.contains(key) || !submitted.containsKey(key)
                    || !(occurrence.get("entries") instanceof List<?> entries) || entries.size() != 1
                    || !(entries.get(0) instanceof Map<?, ?> entry) || !(entry.get("label") instanceof String label)
                    || !java.util.Objects.equals(candidate.get(key), entry.get("value"))) continue;
            labels.put(key, label);
        }
        if (!labels.isEmpty()) candidate.put("$labels", labels);
        else candidate.remove("$labels");
    }

    private static Map<String, Object> copyMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> map.put((String) key, value));
        return map;
    }

    private static ResponseStatusException refused(FormDefinition def) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "表單提交驗證被拒絕（定義或資源問題）：formKey=" + def.getFormKey()
                        + "，version=" + def.getVersion() + "。請確認表單定義、服務註冊與 SDK 版本。");
    }

    public String dataJson(Map<String, Object> canonical) {
        try { return objectMapper.writeValueAsString(canonical); }
        catch (JacksonException failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "表單資料無法序列化");
        }
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
            if (parsed.containsKey("$labels")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "$labels 由伺服器產生，不可由呼叫端提交");
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
