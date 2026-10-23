package com.bpm.core.uniform;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 宿主私有的多選顯示快照；絕不寫入 SDK data、$labels 或流程變數。 */
@Component
public class OptionLabelSnapshots {
    private static final String FORMAT = "greyhound-option-labels-v1";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final ObjectMapper mapper;

    public OptionLabelSnapshots(ObjectMapper mapper) { this.mapper = mapper; }

    public String merge(String previousJson, Map<String, Object> dsl, Map<String, Object> candidate,
                        Map<String, Object> submitted, Set<String> editable, Object privateLabels) {
        Map<String, Map<String, Object>> fields = OptionsProviderRegistry.fields(dsl);
        Map<String, Object> result = select(read(previousJson), fields, candidate);
        for (var entry : fields.entrySet()) {
            String key = entry.getKey();
            if (checkbox(entry.getValue()) && editable.contains(key) && submitted.containsKey(key)) result.remove(key);
        }
        if (privateLabels instanceof List<?> occurrences) for (Object raw : occurrences) {
            if (!(raw instanceof Map<?, ?> occurrence) || !(occurrence.get("path") instanceof String path)
                    || !path.startsWith("/") || path.indexOf('/', 1) >= 0) continue;
            String key = path.substring(1).replace("~1", "/").replace("~0", "~");
            if (!checkbox(fields.get(key)) || !editable.contains(key) || !submitted.containsKey(key)) continue;
            List<Map<String, Object>> entries = matching(occurrence.get("entries"), candidate.get(key));
            if (entries != null && !entries.isEmpty()) result.put(key, entries);
        }
        // SDK 成功的非空多選必須有每個值的 label；不得將 partial/failed resolve 保存成成功快照。
        for (var entry : fields.entrySet()) {
            String key = entry.getKey();
            if (checkbox(entry.getValue()) && editable.contains(key) && submitted.containsKey(key)
                    && candidate.get(key) instanceof List<?> values && !values.isEmpty() && !result.containsKey(key)) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "多選欄位的可信標籤快照不完整");
            }
        }
        if (result.isEmpty()) return null;
        try { return mapper.writeValueAsString(Map.of("format", FORMAT, "fields", result)); }
        catch (JacksonException failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "選項標籤快照無法序列化");
        }
    }

    /** 先由 SDK 完成授權投影，再只釋出該接收者可見、且值仍逐項吻合的欄位。 */
    public Map<String, Object> attach(Map<String, Object> envelope, String snapshotJson) {
        if (!(envelope.get("schema") instanceof Map<?, ?> schema)
                || !(envelope.get("data") instanceof Map<?, ?> data)) return envelope;
        Map<String, Object> projectedDsl = copy(schema);
        Map<String, Object> fields = select(read(snapshotJson), OptionsProviderRegistry.fields(projectedDsl), copy(data));
        if (fields.isEmpty()) return envelope;
        Map<String, Object> result = new LinkedHashMap<>(envelope);
        Map<String, Object> ext = envelope.get("ext") instanceof Map<?, ?> old ? copy(old) : new LinkedHashMap<>();
        ext.put("greyhoundOptionLabels", fields);
        result.put("ext", ext);
        return result;
    }

    private Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> stored = mapper.readValue(json, MAP);
            if (stored != null && FORMAT.equals(stored.get("format"))
                    && stored.get("fields") instanceof Map<?, ?> fields) return copy(fields);
        } catch (JacksonException ignored) {
            // 不回顯損毀的私有本文，也不可讓下一次成功提交靜默抹除歷史快照。
            throw unreadable();
        }
        throw unreadable();
    }

    private static ResponseStatusException unreadable() {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "選項標籤快照無法解讀，請聯絡管理者");
    }

    private static Map<String, Object> select(Map<String, Object> stored,
            Map<String, Map<String, Object>> declared, Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        stored.forEach((key, raw) -> {
            if (!checkbox(declared.get(key))) return;
            List<Map<String, Object>> entries = matching(raw, values.get(key));
            if (entries != null && !entries.isEmpty()) result.put(key, entries);
        });
        return result;
    }

    private static boolean checkbox(Map<String, Object> field) {
        return field != null && "checkbox".equals(field.get("type"))
                && field.get("optionsSource") instanceof Map<?, ?> source
                && OptionsProviderRegistry.PROVIDER.equals(source.get("provider"));
    }

    private static List<Map<String, Object>> matching(Object raw, Object selected) {
        if (!(raw instanceof List<?> entries) || !(selected instanceof List<?> values)
                || entries.size() != values.size()) return null;
        Map<String, String> labels = new LinkedHashMap<>();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> entry) || !(entry.get("value") instanceof String value)
                    || !(entry.get("label") instanceof String label) || labels.putIfAbsent(value, label) != null) return null;
        }
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (Object rawValue : values) {
            if (!(rawValue instanceof String value) || !labels.containsKey(value)) return null;
            ordered.add(Map.of("value", value, "label", labels.remove(value)));
        }
        return labels.isEmpty() ? ordered : null;
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put((String) key, value));
        return result;
    }
}
