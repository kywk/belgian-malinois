package com.bpm.core.external.v1;

import static com.bpm.core.external.v1.CaseError.VALIDATION_ERROR;

import com.bpm.core.controller.ProcessController;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

record CaseCommand(String requestId, String workflowCode, long bindingVersion, Ref caseRef, Actor actor,
        String processDefinitionId, Form formData, Map<String, Object> variables) {
    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final String BUSINESS_KEY = "[a-z0-9_-]{1,32}/[A-Za-z0-9_-]{1,64}/[A-Za-z0-9_-]{1,64}/[a-z0-9_-]{1,32}";
    static final JsonMapper JSON = JsonMapper.builder()
                                           .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                           .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                                                   DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                           .build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    record Ref(
            String domain, String applicationId, String submissionId, String workflowRole, long submissionRevision) {
        String key() {
            return String.join("/", domain, applicationId, submissionId, workflowRole);
        }
        static Ref from(String key, long revision) {
            String[] parts = key.split("/");
            return new Ref(parts[0], parts[1], parts[2], parts[3], revision);
        }
    }
    record Actor(String type, String agentCode, String issuer, String subject) {}
    record Form(String formDefinitionId, long formVersion, Map<String, Object> dataJson) {}

    static CaseCommand parse(String body) {
        try {
            var root = JSON.readValue(body, OBJECT);
            fields(root, "requestId", "workflowCode", "bindingVersion", "caseRef", "actor", "processDefinitionId",
                    "formData", "variables");
            var ref = object(root.get("caseRef"));
            fields(ref, "domain", "applicationId", "submissionId", "workflowRole", "submissionRevision");
            var actor = object(root.get("actor"));
            fields(actor, "type", "agentCode", "issuer", "subject");
            var vars = object(root.get("variables"));
            if (vars.keySet().stream().anyMatch(k -> !k.matches("[A-Za-z][A-Za-z0-9_]*") || reserved(k)))
                throw VALIDATION_ERROR.fail();
            Form form = null;
            if (root.get("formData") != null) {
                var f = object(root.get("formData"));
                fields(f, "formDefinitionId", "formVersion", "dataJson");
                if (!(f.get("dataJson") instanceof String data))
                    throw VALIDATION_ERROR.fail();
                var parsed = JSON.readValue(data, OBJECT);
                if (parsed == null || parsed.keySet().stream().anyMatch(k -> reserved(k) || vars.containsKey(k)))
                    throw VALIDATION_ERROR.fail();
                form = new Form(string(f, "formDefinitionId", UUID_PATTERN), positive(f, "formVersion"), parsed);
            }
            return new CaseCommand(string(root, "requestId", UUID_PATTERN),
                    string(root, "workflowCode", "[a-z0-9._-]{1,64}"), positive(root, "bindingVersion"),
                    new Ref(string(ref, "domain", "[a-z0-9_-]{1,32}"),
                            string(ref, "applicationId", "[A-Za-z0-9_-]{1,64}"),
                            string(ref, "submissionId", "[A-Za-z0-9_-]{1,64}"),
                            string(ref, "workflowRole", "[a-z0-9_-]{1,32}"), positive(ref, "submissionRevision")),
                    new Actor(string(actor, "type", "AGENT"), string(actor, "agentCode", "(?U)\\S{1,64}"),
                            string(actor, "issuer", "(?U)(?=.{1,500}$)https://\\S+"),
                            string(actor, "subject", "(?U)(?=.{1,256}$)\\S(?:.*\\S)?")),
                    string(root, "processDefinitionId", "(?s).{1,128}"), form, vars);
        } catch (JacksonException | ArithmeticException ex) {
            throw VALIDATION_ERROR.fail();
        }
    }
    private static boolean reserved(String name) {
        return ProcessController.isProtectedVariable(name)
                || Set.of("approved", "rejected", "firstTaskAssignee", "firstTaskCandidateGroups", "candidateGroups",
                              "callbackUrl")
                           .contains(name);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?,?>))
            throw VALIDATION_ERROR.fail();
        return (Map<String, Object>) value;
    }
    private static void fields(Map<String, Object> obj, String... keys) {
        if (obj == null || !obj.keySet().equals(Set.of(keys)))
            throw VALIDATION_ERROR.fail();
    }
    private static String string(Map<String, Object> obj, String key, String regex) {
        if (!(obj.get(key) instanceof String s) || !s.matches(regex))
            throw VALIDATION_ERROR.fail();
        return s;
    }
    private static long positive(Map<String, Object> obj, String key) {
        if (!(obj.get(key) instanceof Number n))
            throw VALIDATION_ERROR.fail();
        long v = new BigDecimal(n.toString()).longValueExact();
        if (v < 1 || v > 9007199254740991L)
            throw VALIDATION_ERROR.fail();
        return v;
    }
    static final int HASH_VERSION = 1;
    String hash() {
        var data = JSON.convertValue(this, OBJECT);
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical(data));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?,?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((k, v) -> sorted.put((String) k, canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(CaseCommand::canonical).toList();
        if (value instanceof Number n)
            return new BigDecimal(n.toString()).stripTrailingZeros();
        return value;
    }
}
