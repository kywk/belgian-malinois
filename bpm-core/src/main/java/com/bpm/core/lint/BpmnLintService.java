package com.bpm.core.lint;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class BpmnLintService {

    private static final Set<String> EL_WHITELIST = Set.of(
            "orgService", "permService", "bpmQueryService");
    private static final Set<String> DEFAULT_NAMES = Set.of(
            "Task", "Task 1", "Task 2", "Task 3", "");

    private final RestClient formClient;

    public BpmnLintService(@Value("${bpm.form-service-url:http://localhost:8081}") String formServiceUrl) {
        this.formClient = RestClient.builder().baseUrl(formServiceUrl).build();
    }

    public LintResult lint(String xml) {
        List<LintError> errors = new ArrayList<>();
        BpmnModel model;
        try {
            XMLStreamReader reader = XMLInputFactory.newInstance()
                    .createXMLStreamReader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            model = new BpmnXMLConverter().convertToBpmnModel(reader);
        } catch (Exception e) {
            return new LintResult(false, List.of(
                    new LintError(null, null, "parse", "BPMN XML 解析失敗: " + e.getMessage(), "error")));
        }

        org.flowable.bpmn.model.Process process = model.getMainProcess();
        if (process == null) {
            return new LintResult(false, List.of(
                    new LintError(null, null, "parse", "找不到主流程", "error")));
        }

        boolean isExternalAllowed = false; // Could be read from process extension

        for (FlowElement el : process.getFlowElements()) {
            if (el instanceof UserTask ut) {
                lintUserTask(ut, errors, isExternalAllowed, process);
            } else if (el instanceof ExclusiveGateway gw) {
                lintGateway(gw, errors);
            } else if (el instanceof ServiceTask st) {
                lintServiceTask(st, process, errors);
            }

            // Rule f: node name not empty or default
            if (el instanceof Activity || el instanceof Gateway) {
                if (el.getName() == null || DEFAULT_NAMES.contains(el.getName().trim())) {
                    errors.add(new LintError(el.getId(), el.getName(), "node-name",
                            "節點名稱不可為空或預設值", "warning"));
                }
            }
        }

        return new LintResult(errors.stream().noneMatch(e -> "error".equals(e.severity())), errors);
    }

    private void lintUserTask(UserTask ut, List<LintError> errors, boolean externalAllowed, org.flowable.bpmn.model.Process process) {
        String assignee = ut.getAssignee();
        String candidateUsers = String.join(",", ut.getCandidateUsers());
        String candidateGroups = String.join(",", ut.getCandidateGroups());

        // Rule a: must have assignee or candidates
        if (isBlank(assignee) && isBlank(candidateUsers) && isBlank(candidateGroups)) {
            errors.add(new LintError(ut.getId(), ut.getName(), "assignee-required",
                    "UserTask 必須設定 assignee 或 candidateGroups/candidateUsers", "error"));
        }

        // Rule b: must have formKey
        String formKey = ut.getFormKey();
        if (isBlank(formKey)) {
            errors.add(new LintError(ut.getId(), ut.getName(), "formkey-required",
                    "UserTask 必須設定 formKey", "error"));
        }

        // Rule c: if formKey is not external:, check form exists
        if (formKey != null && !formKey.startsWith("external:") && !isBlank(formKey)) {
            try {
                formClient.get().uri("/api/forms/{formKey}", formKey).retrieve().toBodilessEntity();
            } catch (Exception e) {
                errors.add(new LintError(ut.getId(), ut.getName(), "formkey-exists",
                        "表單定義 '" + formKey + "' 不存在於 Form Service", "error"));
            }
        }

        // Rule g: EL function whitelist
        for (String expr : List.of(
                assignee != null ? assignee : "",
                candidateUsers,
                candidateGroups)) {
            checkElWhitelist(expr, ut.getId(), ut.getName(), errors);
        }

        // Rule h: external-initiated process, first UserTask should not use initiator EL
        if (externalAllowed && isFirstUserTask(ut, process)) {
            String allExprs = (assignee != null ? assignee : "") + candidateUsers + candidateGroups;
            if (allExprs.contains("initiator")) {
                errors.add(new LintError(ut.getId(), ut.getName(), "external-initiator",
                        "允許外部發起的流程，第一個 UserTask 不可使用 initiator EL 函數", "warning"));
            }
        }
    }

    private void lintGateway(ExclusiveGateway gw, List<LintError> errors) {
        // Rule d: must have default flow
        if (gw.getDefaultFlow() == null || gw.getDefaultFlow().isBlank()) {
            errors.add(new LintError(gw.getId(), gw.getName(), "gateway-default",
                    "ExclusiveGateway 必須設定預設路徑 (default flow)", "error"));
        }
    }

    private void lintServiceTask(ServiceTask st, org.flowable.bpmn.model.Process process, List<LintError> errors) {
        // Rule e: must have error boundary event
        boolean hasBoundary = process.getFlowElements().stream()
                .filter(e -> e instanceof BoundaryEvent)
                .map(e -> (BoundaryEvent) e)
                .anyMatch(b -> st.getId().equals(b.getAttachedToRefId())
                        && b.getEventDefinitions().stream().anyMatch(d -> d instanceof ErrorEventDefinition));
        if (!hasBoundary) {
            errors.add(new LintError(st.getId(), st.getName(), "service-error-boundary",
                    "ServiceTask 必須有錯誤邊界事件", "warning"));
        }
    }

    private void checkElWhitelist(String expr, String elementId, String elementName, List<LintError> errors) {
        if (expr == null || !expr.contains("${")) return;
        // Extract bean name from ${beanName.method(...)}
        var matcher = java.util.regex.Pattern.compile("\\$\\{(\\w+)\\.").matcher(expr);
        while (matcher.find()) {
            String bean = matcher.group(1);
            if (!EL_WHITELIST.contains(bean)) {
                errors.add(new LintError(elementId, elementName, "el-whitelist",
                        "EL 函數 '" + bean + "' 不在白名單內（允許: " + EL_WHITELIST + "）", "error"));
            }
        }
    }

    private boolean isFirstUserTask(UserTask ut, org.flowable.bpmn.model.Process process) {
        for (FlowElement el : process.getFlowElements()) {
            if (el instanceof StartEvent se) {
                for (SequenceFlow flow : se.getOutgoingFlows()) {
                    if (ut.getId().equals(flow.getTargetRef())) return true;
                    // Check if connected via gateway
                    FlowElement target = process.getFlowElement(flow.getTargetRef());
                    if (target instanceof Gateway gw) {
                        return gw.getOutgoingFlows().stream()
                                .anyMatch(f -> ut.getId().equals(f.getTargetRef()));
                    }
                }
            }
        }
        return false;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    public record LintResult(boolean valid, List<LintError> errors) {}
    public record LintError(String elementId, String elementName, String rule, String message, String severity) {}
}
