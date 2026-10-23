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
import java.util.concurrent.atomic.AtomicBoolean;

/** 宿主固定來源；DSL 只能引用名稱，身分及來源授權由伺服器綁定。 */
@Component
public class OptionsProviderRegistry {
    public static final String PROVIDER = "org.myDepartmentMembers";
    public static final String SNAPSHOT = "greyhound-options-v1";
    private static final int MAX_MEMBERS = 500;
    private final OrgRestClient org;

    public OptionsProviderRegistry(OrgRestClient org) { this.org = org; }

    public List<Map<String, Object>> catalog() {
        return List.of(Map.of("provider", PROVIDER, "label", "本人部門成員",
                "params", List.of(), "types", List.of("select", "radio")));
    }

    public static Map<String, Object> behavior() {
        return Map.of("providers", List.of(Map.of("provider", PROVIDER,
                "frontendParams", List.of(), "requiredInputs", List.of())),
                "actions", List.of(), "frames", List.of());
    }

    /** 不影響既有靜態選項；尚未支援的動態組合於存檔／發布時直接拒絕。 */
    public static void requireSupported(Map<String, Object> dsl) {
        inspect(dsl.get("root"), false);
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

    private static void inspect(Object raw, boolean inRepeater) {
        if (raw instanceof List<?> nodes) {
            for (Object node : nodes) inspect(node, inRepeater);
            return;
        }
        if (!(raw instanceof Map<?, ?> node)) return;
        if (node.containsKey("optionsSource")) {
            boolean scalar = "select".equals(node.get("type")) || "radio".equals(node.get("type"));
            boolean multiple = node.get("props") instanceof Map<?, ?> props
                    && Boolean.TRUE.equals(props.get("multiple"));
            if (!scalar || multiple || inRepeater
                    || !(node.get("optionsSource") instanceof Map<?, ?> source)
                    || !PROVIDER.equals(source.get("provider"))
                    || !Set.of("provider", "params", "dependsOn").containsAll(source.keySet())
                    || source.containsKey("params") && !(source.get("params") instanceof Map<?, ?> params && params.isEmpty())
                    || source.containsKey("dependsOn") && !(source.get("dependsOn") instanceof List<?> dependencies && dependencies.isEmpty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "動態來源目前只支援單選 select／radio 的本人部門成員，且不接受參數、相依欄位或 repeater");
            }
        }
        inspect(node.get("children"), inRepeater || "repeater".equals(node.get("type")));
    }

    public static boolean hasSources(Map<String, Object> dsl) { return hasSource(dsl.get("root")); }

    private static boolean hasSource(Object raw) {
        if (raw instanceof List<?> nodes) return nodes.stream().anyMatch(OptionsProviderRegistry::hasSource);
        if (!(raw instanceof Map<?, ?> node)) return false;
        return node.containsKey("optionsSource") || hasSource(node.get("children"));
    }

    /** 名稱與 ID 是這個來源唯一允許揭露的資料，絕不轉傳原始使用者物件。 */
    public List<Map<String, Object>> list(String caller) {
        return read(caller, null);
    }

    private List<Map<String, Object>> read(String caller, Set<Object> requested) {
        requireCaller(caller);
        try {
            String department = org.getDepartment(caller);
            if (department == null || department.isBlank()) throw new IllegalStateException();
            List<String> members = org.getDeptMembers(department);
            if (members == null || members.size() > MAX_MEMBERS) throw new IllegalStateException();
            List<Map<String, Object>> options = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String id : members) {
                if (id == null || id.isBlank()) throw new IllegalStateException();
                if (!seen.add(id)) continue;
                if (requested != null && !requested.contains(id)) continue;
                Map<String, Object> user = org.getUser(id);
                if (user == null || !id.equals(user.get("userId"))
                        || !(user.get("name") instanceof String name) || name.isBlank()) {
                    throw new IllegalStateException();
                }
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
            if (!params.isEmpty() || !caller.equals(ctx.get("principal"))) {
                throw new IllegalArgumentException("不合法的服務綁定");
            }
            try {
                // resolve 必須真正重讀來源，且只回本次要求中仍被允許的值。
                return read(caller, new LinkedHashSet<>(values));
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
