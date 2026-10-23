package com.bpm.core.uniform;

import com.bpm.core.client.OrgRestClient;
import com.bpm.core.form.model.FormDefinition;
import com.nanshan.uniform.core.envelope.ServiceValidation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/** 宿主固定來源；DSL 只能引用名稱，身分及來源授權由伺服器綁定。 */
@Component
public class OptionsProviderRegistry {
    public static final String PROVIDER = "org.myDepartmentMembers";
    public static final String SNAPSHOT = "greyhound-options-v2";
    private static final int MAX_MEMBERS = 500;
    private static final Map<String, Integer> PARAM_LIMITS = Map.of("query", 100, "excludeUser", 255);
    private static final Pattern DATA_TOKEN = Pattern.compile(
            "\\{\\{data\\.([A-Za-z][A-Za-z0-9_]*)}}|\\{data\\.([A-Za-z][A-Za-z0-9_]*)}");
    private final OrgRestClient org;

    public OptionsProviderRegistry(OrgRestClient org) { this.org = org; }

    public List<Map<String, Object>> catalog() {
        return List.of(Map.of("provider", PROVIDER, "label", "本人部門成員",
                "params", List.of(
                        Map.of("name", "query", "label", "搜尋文字", "type", "string", "maxLength", 100),
                        Map.of("name", "excludeUser", "label", "排除人員", "type", "string", "maxLength", 255)),
                "types", List.of("select", "radio", "checkbox")));
    }

    public static Map<String, Object> behavior() {
        return Map.of("providers", List.of(Map.of("provider", PROVIDER,
                "frontendParams", List.of("query", "excludeUser"), "requiredInputs", List.of())),
                "actions", List.of(), "frames", List.of());
    }

    /** 不影響既有靜態選項；尚未支援的動態組合於存檔／發布時直接拒絕。 */
    public static void requireSupported(Map<String, Object> dsl) {
        Map<String, Map<String, Object>> fields = fields(dsl);
        inspect(dsl.get("root"), false, fields);
        for (String key : fields.keySet()) checkCycle(key, fields, new LinkedHashSet<>(), new LinkedHashSet<>());
        if (hasSources(dsl) && needsVerifier(dsl.get("root"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "動態選項表單目前不能同時使用 file／media:image，尚未接入可信檔案驗證來源");
        }
    }

    private static boolean needsVerifier(Object raw) {
        if (raw instanceof List<?> nodes) return nodes.stream().anyMatch(OptionsProviderRegistry::needsVerifier);
        if (!(raw instanceof Map<?, ?> node)) return false;
        return "file".equals(node.get("type")) || "media:image".equals(node.get("type"))
                || needsVerifier(node.get("children"));
    }

    private static void inspect(Object raw, boolean inRepeater, Map<String, Map<String, Object>> fields) {
        if (raw instanceof List<?> nodes) {
            for (Object node : nodes) inspect(node, inRepeater, fields);
            return;
        }
        if (!(raw instanceof Map<?, ?> node)) return;
        if (node.containsKey("optionsSource")) {
            boolean choice = Set.of("select", "radio", "checkbox").contains(node.get("type"));
            Map<?, ?> props = node.get("props") instanceof Map<?, ?> value ? value : Map.of();
            if (!choice || inRepeater || Boolean.TRUE.equals(props.get("allowOther"))
                    || "select".equals(node.get("type")) && Boolean.TRUE.equals(props.get("multiple"))
                    || !(node.get("optionsSource") instanceof Map<?, ?> source)
                    || !PROVIDER.equals(source.get("provider"))
                    || !Set.of("provider", "params", "dependsOn").containsAll(source.keySet())) throw unsupported();
            Object rawParams = source.containsKey("params") ? source.get("params") : Map.of();
            Object rawDeps = source.containsKey("dependsOn") ? source.get("dependsOn") : List.of();
            if (!(rawParams instanceof Map<?, ?> params) || !(rawDeps instanceof List<?> deps)
                    || !PARAM_LIMITS.keySet().containsAll(params.keySet())) throw unsupported();
            Set<String> references = new LinkedHashSet<>();
            for (var entry : params.entrySet()) {
                if (!(entry.getValue() instanceof String text)) throw unsupported();
                String reference = reference(text);
                if (reference == null) {
                    if (text.contains("{") || text.contains("}")
                            || text.length() > PARAM_LIMITS.get(entry.getKey())) throw unsupported();
                } else {
                    Map<String, Object> field = fields.get(reference);
                    if (reference.equals(node.get("key")) || field == null
                            || !Set.of("text", "textarea", "select", "radio").contains(field.get("type"))) throw unsupported();
                    Map<?, ?> boundProps = field.get("props") instanceof Map<?, ?> value ? value : Map.of();
                    if (Boolean.TRUE.equals(boundProps.get("multiple"))
                            || Boolean.TRUE.equals(boundProps.get("allowOther"))) throw unsupported();
                    references.add(reference);
                }
            }
            if (deps.stream().anyMatch(dep -> !(dep instanceof String))
                    || deps.size() != new LinkedHashSet<>(deps).size()
                    || !references.equals(new LinkedHashSet<>(deps))) throw unsupported();
        }
        inspect(node.get("children"), inRepeater || "repeater".equals(node.get("type")), fields);
    }

    private static void checkCycle(String key, Map<String, Map<String, Object>> fields,
                                   Set<String> active, Set<String> done) {
        if (done.contains(key)) return;
        if (!active.add(key)) throw unsupported();
        if (fields.get(key).get("optionsSource") instanceof Map<?, ?> source
                && source.get("dependsOn") instanceof List<?> dependencies) {
            for (Object dependency : dependencies) checkCycle((String) dependency, fields, active, done);
        }
        active.remove(key);
        done.add(key);
    }

    /** 僅收集資料的頂層欄位；layout 不形成資料層級，repeater 子欄位不接受綁定。 */
    public static Map<String, Map<String, Object>> fields(Map<String, Object> dsl) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        collectFields(dsl.get("root"), fields);
        return fields;
    }

    private static void collectFields(Object raw, Map<String, Map<String, Object>> fields) {
        if (raw instanceof List<?> nodes) { for (Object node : nodes) collectFields(node, fields); return; }
        if (!(raw instanceof Map<?, ?> node)) return;
        if (node.get("key") instanceof String key) {
            Map<String, Object> copy = new LinkedHashMap<>();
            node.forEach((name, value) -> copy.put((String) name, value));
            fields.put(key, copy);
        }
        if (!"repeater".equals(node.get("type"))) collectFields(node.get("children"), fields);
    }

    static String reference(String text) {
        var match = DATA_TOKEN.matcher(text);
        return match.matches() ? match.group(1) != null ? match.group(1) : match.group(2) : null;
    }

    /** 與 SDK SVC-04 相同的已支援子集：hidden／missing 的 optional 引用整個省略。 */
    public static Map<String, Object> params(Map<String, Object> field, Map<String, Object> candidate,
                                             Map<String, String> states) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (field.get("optionsSource") instanceof Map<?, ?> source
                && source.get("params") instanceof Map<?, ?> raw) {
            for (var entry : raw.entrySet()) {
                String reference = reference((String) entry.getValue());
                if (reference != null && (!states.containsKey(reference)
                        || "hidden".equals(states.get(reference)) || !candidate.containsKey(reference))) continue;
                result.put((String) entry.getKey(), reference == null ? entry.getValue() : candidate.get(reference));
            }
        }
        requireParams(result);
        return result;
    }

    private static void requireParams(Map<String, Object> params) {
        if (params == null || !PARAM_LIMITS.keySet().containsAll(params.keySet())) throw badParams();
        params.forEach((key, value) -> {
            if (value != null && (!(value instanceof String text) || text.length() > PARAM_LIMITS.get(key))) throw badParams();
        });
    }

    private static ResponseStatusException unsupported() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "動態選項只支援本人部門的 select／radio／checkbox；參數須為文字或宣告的純文字欄位引用，dependsOn 必須相符且不可循環");
    }

    private static ResponseStatusException badParams() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "動態選項參數型別或長度不合法");
    }

    public static boolean hasSources(Map<String, Object> dsl) { return hasSource(dsl.get("root")); }

    private static boolean hasSource(Object raw) {
        if (raw instanceof List<?> nodes) return nodes.stream().anyMatch(OptionsProviderRegistry::hasSource);
        if (!(raw instanceof Map<?, ?> node)) return false;
        return node.containsKey("optionsSource") || hasSource(node.get("children"));
    }

    /** 名稱與 ID 是這個來源唯一允許揭露的資料，絕不轉傳原始使用者物件。 */
    public List<Map<String, Object>> list(String caller) {
        return list(caller, Map.of());
    }

    public List<Map<String, Object>> list(String caller, Map<String, Object> params) {
        requireParams(params);
        return read(caller, null, params);
    }

    private List<Map<String, Object>> read(String caller, Set<Object> requested, Map<String, Object> params) {
        requireCaller(caller);
        String query = params.get("query") instanceof String text ? text.toLowerCase(Locale.ROOT) : "";
        String excluded = params.get("excludeUser") instanceof String text ? text : "";
        try {
            String department = org.getDepartment(caller);
            if (department == null || department.isBlank()) throw new IllegalStateException();
            List<String> members = org.getDeptMembers(department);
            if (members == null || members.size() > MAX_MEMBERS) throw new IllegalStateException();
            List<Map<String, Object>> options = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String id : members) {
                if (id == null || id.isBlank()) throw new IllegalStateException();
                if (!seen.add(id) || id.equals(excluded)) continue;
                if (requested != null && !requested.contains(id)) continue;
                Map<String, Object> user = org.getUser(id);
                if (user == null || !id.equals(user.get("userId"))
                        || !(user.get("name") instanceof String name) || name.isBlank()) {
                    throw new IllegalStateException();
                }
                if (!query.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(query)
                        && !name.toLowerCase(Locale.ROOT).contains(query)) continue;
                options.add(Map.of("value", id, "label", name));
            }
            return options;
        } catch (RuntimeException failure) {
            // 不帶外部本文、個資、憑證；不得退回空清單或靜態選項。
            throw unavailable();
        }
    }

    public ServiceValidation.Registry runtime(String caller, AtomicBoolean sourceFailed) {
        requireCaller(caller);
        Map<String, ServiceValidation.OptionsResolver> providers = new LinkedHashMap<>();
        providers.put(PROVIDER, (values, params, ctx) -> {
            if (!caller.equals(ctx.get("principal"))) {
                throw new IllegalArgumentException("不合法的服務綁定");
            }
            requireParams(params);
            try {
                // resolve 必須真正重讀來源，且只回本次要求中仍被允許的值。
                return read(caller, new LinkedHashSet<>(values), params);
            } catch (ResponseStatusException failure) {
                sourceFailed.set(true);
                throw failure;
            }
        });
        return new ServiceValidation.Registry(SNAPSHOT, providers, Map.of(), Map.of(), Map.of());
    }

    public Map<String, Object> hostContext(FormDefinition definition, String caller) {
        requireCaller(caller);
        return Map.of("binding", Map.of("tenant", "greyhound", "form", definition.getFormKey(),
                        "definitionVersion", definition.getVersion(), "registrySnapshot", SNAPSHOT),
                "principal", caller, "authorizationSnapshot", java.util.UUID.randomUUID().toString(),
                "templateData", Map.of());
    }

    private static void requireCaller(String caller) {
        if (caller == null || caller.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "請先登入再載入表單選項");
        }
    }

    public static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "部門成員來源暫時無法使用，請稍後重試");
    }
}
