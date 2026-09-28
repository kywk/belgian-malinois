package com.bpm.core.lint;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;
import org.springframework.beans.factory.annotation.Value;
import com.bpm.core.form.service.FormService;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class BpmnLintService {

    /**
     * BPMN 運算式可以取用的 bean 名稱。
     *
     * <h2>⚠️ 必須與 {@code FlowableConfig.setBeans()} 保持一致</h2>
     *
     * <p>這是兩份各自維護的清單，沒有任何機制強制它們相同。方向性很重要：
     * <ul>
     *   <li>這裡有、{@code setBeans()} 沒有 → lint 放行，但執行期取不到那個 bean，
     *       錯誤會在<b>使用者送出簽核的時候</b>才出現，而不是部署時。</li>
     *   <li>{@code setBeans()} 有、這裡沒有 → 部署被 lint 擋下。安全，但訊息容易誤導。</li>
     * </ul>
     *
     * <p>{@code BpmnExpressionBeanScopeTest} 斷言這裡的每個名稱都在執行期命名空間內，
     * 防止第一種漂移。
     */
    // public 是為了讓 BpmnExpressionBeanScopeTest 從這裡推導測試對象
    // （測試在另一個 package）。不可變的 Set.of，外部無法修改。
    public static final Set<String> EL_WHITELIST = Set.of(
            "orgService", "permService", "bpmQueryService",
            // 第一個任務的受理人判斷（P2-7）。BPMN 的 managerReview 用它取代
            // 直接呼叫 orgService.getDirectManager(initiator) —— 後者在外部系統
            // 發起時會對 system:<id> 查主管，永遠查不到。
            "assigneeResolver");
    private static final Set<String> DEFAULT_NAMES = Set.of(
            "Task", "Task 1", "Task 2", "Task 3", "");

    /**
     * 平台保證一定存在的流程變數。
     *
     * <p>指派運算式裡的裸變數參照（{@code ${name}}，沒有點）只能是這些之一，
     * 或是在 {@code bpm_process_variable_spec} 裡宣告過的變數。
     *
     * <p>{@code firstTaskAssignee} / {@code firstTaskCandidateGroups} 只有外部 API
     * 發起時才會設定，但 {@code InitialAssigneeResolver} 是透過 {@code execution}
     * 讀它們而非 JUEL 識別字，所以在指派運算式裡直接參照仍會在人工發起時爆掉
     * —— 列在這裡是為了不誤擋刻意這樣寫的 BPMN，不代表推薦這種寫法。
     */
    static final Set<String> PLATFORM_VARIABLES = Set.of(
            "initiator", "effectiveInitiator", "firstTaskAssignee", "firstTaskCandidateGroups");

    private final FormService formService;
    private final com.bpm.core.repository.ProcessVariableSpecRepository specRepo;

    public BpmnLintService(FormService formService,
                           com.bpm.core.repository.ProcessVariableSpecRepository specRepo) {
        this.formService = formService;
        this.specRepo = specRepo;
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
        //
        // Stage 3（ADR-001 §2）：改為 in-process 查詢。改動前是跨服務同步 HTTP
        // 且 catch 所有例外都當成「表單不存在」—— 於是 form-service 短暫不可用
        // 時，一支完全正確的 BPMN 會被 lint 判為「表單定義不存在」而部署失敗，
        // 錯誤訊息還指向不存在的問題。in-process 之後「查不到」只會是真的查不到。
        if (formKey != null && !formKey.startsWith("external:") && !isBlank(formKey)) {
            try {
                formService.getSchema(formKey, null);
            } catch (Exception e) {
                errors.add(new LintError(ut.getId(), ut.getName(), "formkey-exists",
                        "表單定義 '" + formKey + "' 不存在或尚未發布", "error"));
            }
        }

        // Rule g: EL function whitelist
        List<String> exprs = List.of(
                assignee != null ? assignee : "",
                candidateUsers,
                candidateGroups);
        for (String expr : exprs) {
            checkElWhitelist(expr, ut.getId(), ut.getName(), errors);
        }

        // Rule i: 裸變數參照必須是平台變數或已宣告的流程變數（P2-6）
        for (String expr : exprs) {
            checkBareVariableReferences(expr, ut.getId(), ut.getName(), process.getId(), errors);
        }

        // Rule j: 不得對主管鏈直接做索引（P2-6）
        if (assignee != null && assignee.contains("getManagerChain")
                && assignee.matches(".*getManagerChain\\s*\\([^)]*\\)\\s*\\[.*")) {
            errors.add(new LintError(ut.getId(), ut.getName(), "manager-chain-index",
                    "不可對 getManagerChain(...) 直接做索引 —— 主管鏈可能比要求的短，"
                            + "而 JUEL 對越界索引不拋例外而是回 null，"
                            + "於是會產生 assignee 為空且無候選群組的任務（對所有人都不可見）。"
                            + "請改用 ${orgService.getManagerAtLevel(initiator, N)}", "error"));
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

    /**
     * 檢查裸變數參照 {@code ${name}}（沒有點的那種）。
     *
     * <h2>為什麼白名單 regex 抓不到它們（security-audit P2-6）</h2>
     *
     * <p>{@link #checkElWhitelist} 的 regex 是 {@code \$\{(\w+)\.} ——
     * <b>必須有「點」</b>才匹配，因為它要抽出 bean 名稱。所以
     * {@code ${dept}} 這種純變數參照完全不被任何規則檢查。
     *
     * <p>而設計器原本就會產生它們：使用者在「部門代碼」欄輸入 {@code dept001}，
     * 舊版的 setValue 會存成 {@code ${dept001}}。部署順利通過，
     * 等到使用者送出表單、引擎求值 candidateGroups 時才拋
     * {@code Unknown property used in expression}（已實測）。
     *
     * <p>也就是說錯誤出現的時間點與造成它的那次編輯完全脫鉤 ——
     * 流程設計者按了部署、看到綠燈，問題留給第一個送件的人。
     *
     * <h2>為什麼不直接禁止所有裸變數</h2>
     *
     * <p>用流程變數決定候選群組是合法的 BPMN 寫法。所以放行兩類：
     * 平台保證存在的變數，以及在 {@code bpm_process_variable_spec} 裡宣告過的。
     * 後者給了正當需求一條明路 —— 先宣告，再使用。
     */
    private void checkBareVariableReferences(String expr, String elementId, String elementName,
                                              String processKey, List<LintError> errors) {
        if (expr == null || !expr.contains("${")) return;

        // ${name} 且 name 之後直接是 } —— 有點或有括號的都由白名單規則處理
        var matcher = java.util.regex.Pattern.compile("\\$\\{(\\w+)\\}").matcher(expr);
        Set<String> declared = declaredVariables(processKey);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (PLATFORM_VARIABLES.contains(name) || declared.contains(name)) continue;
            errors.add(new LintError(elementId, elementName, "undeclared-variable",
                    "運算式參照了未宣告的流程變數 '" + name + "'。"
                            + "若這是字面值（例如部門代碼），請直接填寫不要包 ${}；"
                            + "若確實是流程變數，請先在流程變數規格中宣告它。"
                            + "（平台變數: " + PLATFORM_VARIABLES + "）", "error"));
        }
    }

    private Set<String> declaredVariables(String processKey) {
        if (processKey == null || processKey.isBlank()) return Set.of();
        try {
            return specRepo.findByProcessDefinitionKeyOrderByVariableName(processKey).stream()
                    .map(com.bpm.core.model.ProcessVariableSpec::getVariableName)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            // lint 不該因為讀不到規格而整個失敗；退化為只放行平台變數（較嚴格的一邊）。
            return Set.of();
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
