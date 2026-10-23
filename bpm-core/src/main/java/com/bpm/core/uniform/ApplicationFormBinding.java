package com.bpm.core.uniform;

import com.bpm.core.form.model.FormDefinition;
import org.flowable.bpmn.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 申請綁定的共同判準：主流程的唯一 none StartEvent 明列內部 literal formKey。
 * 無主表／external:／事件觸發流程不因此變成申請流程；不追蹤 CallActivity 的獨立流程。
 * 只核對主表、節點表單與 uniform:access 明列欄位，不宣稱能證明任意 EL 運算式相容。
 */
public final class ApplicationFormBinding {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ApplicationFormBinding() {}

    public static StartEvent ordinaryStart(org.flowable.bpmn.model.Process process) {
        if (process == null) return null;
        StartEvent selected = null;
        for (FlowElement element : process.getFlowElements()) {
            if (element instanceof StartEvent start && start.getEventDefinitions().isEmpty()) {
                if (selected != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "流程有多個一般起始事件，無法確定申請主表，請修正流程後重新開啟申請");
                }
                selected = start;
            }
        }
        return selected;
    }

    public static boolean isLiteralInternal(String formKey) {
        return formKey != null && !formKey.isBlank() && !formKey.startsWith("external:")
                && !formKey.contains("${") && !formKey.contains("#{");
    }

    /** 沒有內部主表回 null；動態 formKey 無法形成穩定申請契約，明確拒絕。 */
    public static String formKey(StartEvent start) {
        if (start == null || !start.getEventDefinitions().isEmpty()) return null;
        String key = start.getFormKey();
        if (key == null || key.isBlank() || key.startsWith("external:")) return null;
        if (!isLiteralInternal(key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "申請主表必須綁定固定的 formKey，不接受運算式");
        }
        return key;
    }

    public record Problem(String elementId, String elementName, String rule, String message) {}

    public static List<FlowElement> elements(Collection<FlowElement> source) {
        List<FlowElement> result = new ArrayList<>();
        for (FlowElement element : source) {
            result.add(element);
            if (element instanceof SubProcess sub) result.addAll(elements(sub.getFlowElements()));
        }
        return result;
    }

    /** 部署與開啟／送件共用；發布新表單後不必重部署，仍須檢查明列欄位依賴。 */
    public static List<Problem> problems(org.flowable.bpmn.model.Process process, FormDefinition definition) {
        List<Problem> problems = new ArrayList<>();
        Set<String> fieldKeys = new HashSet<>();
        try {
            JsonNode dsl = JSON.readTree(definition.getSchemaJson());
            if (dsl == null || !dsl.isObject() || !dsl.path("root").isArray()
                    || !definition.getFormKey().equals(dsl.path("key").asText())) {
                throw new IllegalArgumentException("主表 DSL 與 formKey 不一致");
            }
            collectFieldKeys(dsl.path("root"), fieldKeys);
        } catch (JacksonException | IllegalArgumentException e) {
            return List.of(new Problem(process.getId(), process.getName(), "application-form-schema",
                    "申請主表的 DSL 無法解析或識別不一致，請先修正表單"));
        }
        for (FlowElement element : elements(process.getFlowElements())) {
            if (element instanceof UserTask task) {
                String key = task.getFormKey();
                // 外部系統任務自行呈現表單；一般審核與補件必須沿用主表。
                if (key != null && key.startsWith("external:")) continue;
                if (!definition.getFormKey().equals(key)) {
                    problems.add(new Problem(task.getId(), task.getName(), "application-form-consistency",
                            "申請流程的審核／補件須使用主表 '" + definition.getFormKey() + "'"));
                    continue;
                }
            } else if (!(element instanceof StartEvent start)
                    || !definition.getFormKey().equals(start.getFormKey())) {
                continue;
            }
            for (List<ExtensionElement> extensions : element.getExtensionElements().values()) {
                for (ExtensionElement extension : extensions) {
                    if (!"access".equals(localName(extension.getName()))) continue;
                    for (List<ExtensionElement> children : extension.getChildElements().values()) {
                        for (ExtensionElement field : children) {
                            if (!"field".equals(localName(field.getName()))) continue;
                            String key = attribute(field, "key");
                            if (key != null && !key.isBlank() && !fieldKeys.contains(key)) {
                                problems.add(new Problem(element.getId(), element.getName(),
                                        "application-access-field", "uniform:access 引用主表不存在的欄位 '" + key + "'"));
                            }
                        }
                    }
                }
            }
        }
        return problems;
    }

    public static void requireCompatible(BpmnModel model, FormDefinition definition) {
        if (model == null || model.getMainProcess() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "流程模型不存在，請重新開啟申請");
        }
        List<Problem> problems = problems(model.getMainProcess(), definition);
        if (!problems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "主表與流程設定不相容：" + problems.stream()
                            .map(problem -> problem.elementId() + "：" + problem.message())
                            .collect(java.util.stream.Collectors.joining("；")));
        }
    }

    private static void collectFieldKeys(JsonNode nodes, Set<String> keys) {
        for (JsonNode node : nodes) {
            if (node.path("key").isTextual()) keys.add(node.path("key").asText());
            if (node.path("children").isArray()) collectFieldKeys(node.path("children"), keys);
        }
    }

    private static String attribute(ExtensionElement element, String name) {
        return element.getAttributes().entrySet().stream()
                .filter(entry -> name.equals(localName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream()).map(ExtensionAttribute::getValue)
                .findFirst().orElse(null);
    }

    private static String localName(String name) {
        if (name == null) return "";
        int prefix = Math.max(name.lastIndexOf('}'), name.lastIndexOf(':'));
        return name.substring(prefix + 1);
    }
}
