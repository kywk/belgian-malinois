package com.bpm.core.uniform;

import com.nanshan.uniform.boot4.UniformBffService;
import com.nanshan.uniform.boot4.UniformSpecResources;
import com.nanshan.uniform.core.access.AccessCalculator;
import com.nanshan.uniform.core.access.AccessProfile;
import com.nanshan.uniform.core.model.ManifestReader;
import com.nanshan.uniform.core.model.UniformDocumentReader;
import com.nanshan.uniform.core.registry.TypeRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 選項讀取及提交共用的可信候選；本類別不呼叫 provider，也不保存資料。 */
final class OptionsContext {
    record Prepared(Map<String, Object> candidate, Map<String, String> states, Set<String> editable,
                    Map<String, Object> submission, Set<String> clearedScalarFields, List<String> errors) {}

    private final UniformBffService bff;
    private final UniformSpecResources resources;
    private final TypeRegistry registry;

    OptionsContext(UniformBffService bff, UniformSpecResources resources) {
        this.bff = bff;
        this.resources = resources;
        // 內建 manifest 在服務生命週期內固定；只註冊一次，之後僅供唯讀權限計算。
        // 不把 DSL、profile 或使用者資料放進跨請求快取。
        this.registry = new TypeRegistry();
        for (Map<String, Object> manifest : resources.manifests()) {
            if (!registry.register(ManifestReader.read(manifest)).ok()) throw refused();
        }
    }

    Prepared prepare(Map<String, Object> dsl, Map<String, Object> profile,
                     Map<String, Object> submitted, Map<String, Object> stored, boolean forRead) {
        if (submitted.containsKey("$labels")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "$labels 由伺服器產生，不可由呼叫端提交");
        Map<String, Map<String, Object>> fields = OptionsProviderRegistry.fields(dsl);
        Set<String> clearRequests = new LinkedHashSet<>();
        fields.forEach((key, field) -> {
            if (field.containsKey("optionsSource")
                    && ("select".equals(field.get("type")) || "radio".equals(field.get("type")))
                    && submitted.containsKey(key) && submitted.get(key) == null) clearRequests.add(key);
        });
        Map<String, String> initial = states(dsl, profile, stored, true);
        Set<String> editable = new LinkedHashSet<>();
        initial.forEach((key, state) -> { if ("editable".equals(state)) editable.add(key); });
        Map<String, Object> candidate;
        Map<String, String> effective;
        // 每輪只減少可寫集合；初始 readonly／hidden 輸入無法開啟新的寫入權限。
        while (true) {
            candidate = new LinkedHashMap<>(stored);
            for (String key : editable) if (submitted.containsKey(key)) {
                // null 是宿主的明確清除命令；Access／params 與最後的 canonical 都看缺鍵。
                if (clearRequests.contains(key)) clearValue(candidate, key);
                else candidate.put(key, submitted.get(key));
            }
            effective = states(dsl, profile, candidate, false);
            Set<String> removed = new LinkedHashSet<>(editable);
            for (String key : editable) if ("editable".equals(effective.get(key))) removed.remove(key);
            if (!editable.removeAll(removed)) break;
        }
        Map<String, Object> validationSubmission = new LinkedHashMap<>(submitted);
        List<String> errors = new ArrayList<>();
        for (String key : submitted.keySet()) {
            if (!initial.containsKey(key) || !effective.containsKey(key)) {
                errors.add("此欄位不存在於目前 schema" + (!fields.containsKey(key)
                        ? "（未定義的欄位：" + key + "）" : ""));
            } else if (!editable.contains(key)) {
                if (!stored.containsKey(key) || !Objects.equals(stored.get(key), submitted.get(key))) {
                    errors.add("'" + key + "' 此欄位目前不可編輯");
                } else if (fields.get(key).containsKey("optionsSource")) {
                    // 原值照回的唯讀 occurrence 不進 SDK resolve，保留歷史值及標籤。
                    validationSubmission.remove(key);
                }
            }
        }
        Set<String> cleared = new LinkedHashSet<>(clearRequests);
        cleared.retainAll(editable);
        // 原始 null 仍先經過上方 hidden／readonly 檢查，不能先刪除而繞過授權。
        for (String key : cleared) validationSubmission.remove(key);
        if (errors.isEmpty() && !forRead) {
            for (var entry : fields.entrySet()) {
                String key = entry.getKey();
                if (!entry.getValue().containsKey("optionsSource") || !editable.contains(key)
                        || cleared.contains(key)) continue;
                if (submitted.containsKey(key)) {
                    // 所有參數在第一個 callback 前完成型別檢查；不交 SDK refusal 才變成 500。
                    OptionsProviderRegistry.params(entry.getValue(), candidate, effective);
                } else if (stored.containsKey(key) && !Objects.equals(
                        OptionsProviderRegistry.params(entry.getValue(), stored, initial),
                        OptionsProviderRegistry.params(entry.getValue(), candidate, effective))) {
                    errors.add("'" + key + "' 相依欄位已變動，請一併提交此欄位重新確認選項");
                }
            }
        }
        return new Prepared(candidate, effective, editable, validationSubmission, Set.copyOf(cleared), List.copyOf(errors));
    }

    /** 清除資料與同欄位標籤時複製 map，避免改到 stored 快照。 */
    static void clearValue(Map<String, Object> data, String key) {
        data.remove(key);
        if (data.get("$labels") instanceof Map<?, ?> previousLabels) {
            Map<String, Object> labels = new LinkedHashMap<>();
            previousLabels.forEach((name, label) -> {
                if (!key.equals(name)) labels.put((String) name, label);
            });
            if (labels.isEmpty()) data.remove("$labels");
            else data.put("$labels", labels);
        }
    }

    private Map<String, String> states(Map<String, Object> dsl, Map<String, Object> profile,
                                       Map<String, Object> data, boolean trustedData) {
        if (!OptionsProviderRegistry.hasSources(dsl)) {
            // 保留既有靜態表單的 validation 路徑（含 malformed repeater 的 SDK 欄位錯誤）。
            Map<String, String> result = new LinkedHashMap<>();
            AccessCalculator.calculate(UniformDocumentReader.read(dsl), registry,
                    AccessProfile.fromJson(profile), data, Map.of()).states().forEach((key, state) -> {
                        if (!"hidden".equals(state.specName())) result.put(key, state.specName());
                    });
            return result;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dsl", dsl);
        input.put("manifests", resources.manifests());
        input.put("profile", profile);
        input.put("data", data);
        input.put("fieldTypes", Map.of());
        input.put("behaviorRegistry", OptionsProviderRegistry.behavior());
        input.put("uniform", FormEnvelopeService.UNIFORM_VERSION);
        input.put("key", dsl.get("key"));
        input.put("version", 1);
        input.put("mode", profile.get("mode"));
        input.put("frame", FormEnvelopeService.FRAME);
        input.put("locale", FormEnvelopeService.LOCALE);
        input.put("includeSchema", true);
        Map<String, Object> projection = bff.projectV1(input);
        if (!Boolean.TRUE.equals(projection.get("ok"))
                || !(projection.get("envelope") instanceof Map<?, ?> envelope)
                || !(envelope.get("meta") instanceof Map<?, ?> meta)
                || !(meta.get("states") instanceof Map<?, ?> states)) {
            if (trustedData) throw refused();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "表單輸入無法套用目前欄位權限，請確認資料格式");
        }
        Map<String, String> result = new LinkedHashMap<>();
        states.forEach((key, value) -> result.put((String) key, (String) value));
        return result;
    }

    private static ResponseStatusException refused() {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "表單權限投影被拒絕，請確認定義與 SDK 資源");
    }
}
