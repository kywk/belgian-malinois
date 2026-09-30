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
            "assigneeResolver",
            // 補件關卡的受理人判斷（#83）。與 assigneeResolver 分開是因為
            // 兩者回答的是不同問題：那是「第一關該派給誰」，
            // 這是「這張單有沒有自然人申請人，沒有的話派給誰」。
            //
            // ⚠️ 三個補件 UserTask 在本項之前寫死 ${initiator}，於是外部系統
            // 發起時 assignee 是 system:<id> —— 不是人，沒有人能簽，案件靜默卡死。
            // 規則只能有一份，所以這裡與 FlowableConfig.setBeans() 必須同步。
            "applicantResolver");
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
            "initiator", "effectiveInitiator", "onBehalfOf", "firstTaskAssignee", "firstTaskCandidateGroups");

    private final FormService formService;
    private final com.bpm.core.repository.ProcessVariableSpecRepository specRepo;
    private final com.bpm.core.repository.ExternalSystemRepository externalSystemRepo;
    private final com.bpm.core.external.ExternalSystemPolicy externalSystemPolicy;

    public BpmnLintService(FormService formService,
                           com.bpm.core.repository.ProcessVariableSpecRepository specRepo,
                           com.bpm.core.repository.ExternalSystemRepository externalSystemRepo,
                           com.bpm.core.external.ExternalSystemPolicy externalSystemPolicy) {
        this.formService = formService;
        this.specRepo = specRepo;
        this.externalSystemRepo = externalSystemRepo;
        this.externalSystemPolicy = externalSystemPolicy;
    }

    /**
     * 是否有<b>啟用中</b>的外部系統被授權發起這個流程。
     *
     * <p>只看啟用中的：停用的系統無法發起流程，拿它來觸發規則 h 只是噪音。
     *
     * <p>讀不到資料時回 {@code false}（不觸發規則 h）。這個方向是刻意的 ——
     * 規則 h 檢查的是一個外部系統的授權設定，而授權設定讀不到時，
     * 噴一整頁「你不能用 initiator」是噪音：管理員此刻無法解決它
     * （他連外部系統清單都看不到），而部署該擋的是<b>已知不合法</b>的 BPMN。
     *
     * <p>⚠️ 但這也代表：<b>授權設定讀不到時，規則 h 不會擋任何人</b>。
     * 那是刻意選的一邊（見上），因為反過來（讀不到就擋）會讓
     * bpm-core 啟動順序或資料庫逾時變成「所有人都不能部署流程」。
     * 後果只是漏放，不是誤擋 —— 而誤擋會逼人繞過 lint，
     * 連帶讓<b>所有</b>規則一起失效（見 LintRuleCorrectnessTest 的類別註解）。
     */
    private boolean isExternallyStartable(String processKey) {
        if (processKey == null || processKey.isBlank()) return false;
        try {
            return externalSystemRepo.findAll().stream()
                    .filter(sys -> Boolean.TRUE.equals(sys.getEnabled()))
                    .anyMatch(sys -> externalSystemPolicy.isProcessKeyAllowed(sys, processKey));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安全設定過的 {@link XMLInputFactory}（security-audit P2-5）。
     *
     * <h2>實測結果：兩條攻擊路徑目前都走不通，但那是繼承來的，不是設計的</h2>
     *
     * <p>在執行中的服務上實測（{@code /api/bpmn/lint} 無需認證）：
     * <ul>
     *   <li><b>外部實體</b>：指向存在與不存在的檔案得到<b>完全相同</b>的錯誤
     *       —— 錯誤不因檔案存在而異，所以解析是關閉的，沒有 XXE 檔案洩漏。</li>
     *   <li><b>實體展開</b>：五層以上（放大約 1800 倍起）在 0.05 秒內被拒，
     *       也就是 JDK 的 {@code jdk.xml.entityExpansionLimit}（預設 64000）
     *       生效，billion laughs 打不動。</li>
     *   <li>但<b>內部實體確實會展開</b>（已驗證 {@code &inner;} 展開成字串）
     *       —— DTD 處理是開著的。</li>
     * </ul>
     *
     * <p>也就是說目前的安全性完全依賴 JDK 的預設值。換 JDK、換 StAX 實作、
     * 或有人為別的目的調了 {@code jdk.xml.*} 系統屬性，防線就沒了 ——
     * 而這是一個<b>不需要認證</b>的 XML 解析端點。
     *
     * <p>明確關掉 DTD 的成本是零（BPMN 從不需要 DTD），所以這裡不靠繼承，
     * 直接表明意圖。
     */
    static XMLInputFactory hardenedXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // BPMN 不需要 DTD。關掉它同時消滅實體展開與外部實體兩條路。
        trySet(factory, XMLInputFactory.SUPPORT_DTD, false);
        trySet(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /**
     * 不是每個 StAX 實作都支援每個屬性，setProperty 可能拋
     * {@code IllegalArgumentException}。不支援時忽略 ——
     * 硬化失敗不該讓 lint 整個不能用，而 JDK 預設值仍在（見上方實測）。
     */
    private static void trySet(XMLInputFactory factory, String name, boolean value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException e) {
            // 忽略：這個實作不認識這個屬性
        }
    }

    /**
     * 把例外鏈攤平成一句可排查的訊息。
     *
     * <p>Flowable 的 {@code BpmnXMLConverter} 把 StAX 的錯誤包成
     * 「Error reading XML」，原因全部藏在 cause 裡。對著那句話排查等於沒有訊息
     * —— 不知道是 XML 格式錯、實體上限、還是編碼問題。
     */
    private static String describeCause(Throwable e) {
        var parts = new ArrayList<String>();
        Throwable t = e;
        int depth = 0;
        while (t != null && depth++ < 5) {
            String m = t.getMessage();
            if (m != null && !m.isBlank() && parts.stream().noneMatch(m::equals)) parts.add(m);
            t = t.getCause();
        }
        return parts.isEmpty() ? e.getClass().getSimpleName() : String.join(" ← ", parts);
    }

    public LintResult lint(String xml) {
        List<LintError> errors = new ArrayList<>();
        BpmnModel model;
        try {
            XMLStreamReader reader = hardenedXmlInputFactory()
                    .createXMLStreamReader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            model = new BpmnXMLConverter().convertToBpmnModel(reader);
        } catch (Exception e) {
            // Flowable 的 converter 把原因包成一句「Error reading XML」，
            // 對著它排查等於沒有訊息。把 cause 鏈接出來（security-audit P2-5）。
            return new LintResult(false, List.of(
                    new LintError(null, null, "parse",
                            "BPMN XML 解析失敗: " + describeCause(e), "error")));
        }

        org.flowable.bpmn.model.Process process = model.getMainProcess();
        if (process == null) {
            return new LintResult(false, List.of(
                    new LintError(null, null, "parse", "找不到主流程", "error")));
        }

        // 規則 h 的前提條件（security-audit P2-5）。
        //
        // 改動前這裡是寫死的 false 加上一句「Could be read from process
        // extension」—— 也就是規則 h 從未執行過，是死碼。而它要抓的問題是真的：
        // 允許外部系統發起的流程，第一個 UserTask 若用 initiator 推導簽核人，
        // 那個 initiator 會是 system:<id>（不是人），組織系統查不到。
        //
        // 事實來源不在 BPMN 的 extension 裡，而在 bpm_external_system 的
        // allowedProcessKeys —— 那才是決定「誰能發起這個流程」的地方。
        boolean isExternalAllowed = isExternallyStartable(process.getId());

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
        //
        // ⚠️ severity 由 warning 升為 error（#68d）。
        //
        // 為什麼必須是 error 而不只是警告：它描述的後果是
        // **靜默卡死**。外部系統發起時 initiator 是 system:<id>，不是人，
        // 組織系統查不到它的主管 —— 第一關的 assignee 會是 null 或一個
        // 沒有人能持有的值，而流程看起來「啟動成功」。
        // 警告的話，唯一的保護是部署者恰好有在讀警告列表；
        // 升成 error 之後 {@code POST /api/deployments} 會直接擋下，
        // 也就是讓它在<b>還沒造成任何案件</b>的時候就失敗。
        //
        // 與規則 h 同一個理由的是 UnreachableTaskListener（#83）：
        // 那個是執行期的告警，而這個是部署前的閘門 —— 兩者互補，不可互相取代。
        //
        // ⚠️ 升級前已驗證（見 LintRuleCorrectnessTest 的 regressionTests）：
        //   bpm_external_system 沒有 seed SQL（乾淨庫上 isExternalAllowed 恆為 false）、
        //   兩支出廠 BPMN 的第一個 UserTask 都是 ${assigneeResolver.resolve(execution)}
        //   （字串裡沒有 "initiator"）、沒有任何測試斷言這個 severity。
        // 前兩項讓 scripts/seed-data.sh 不受影響 —— 那一條是用整合測試
        // 實際跑過部署路徑確認的，不是推論。
        if (externalAllowed && isFirstUserTask(ut, process)) {
            String allExprs = (assignee != null ? assignee : "") + candidateUsers + candidateGroups;
            if (allExprs.contains("initiator")) {
                errors.add(new LintError(ut.getId(), ut.getName(), "external-initiator",
                        "允許外部發起的流程，第一個 UserTask 不可使用 initiator EL 函數",
                        "error"));
            }
        }
    }

    /**
     * 規則 d：預設路徑。
     *
     * <h2>改動前會擋掉合法的 BPMN（security-audit P2-5）</h2>
     *
     * <p>原本對<b>每一個</b> ExclusiveGateway 都要求 default flow。但預設路徑
     * 只對<b>分流</b>閘道有意義 —— 它的作用是「所有條件都不成立時走哪條」。
     *
     * <p>兩種合法設計因此被誤擋：
     * <ul>
     *   <li><b>匯流閘道</b>（多進一出）：沒有條件要選，談不上預設路徑。
     *       而匯流正是合併分支的標準畫法（退回重送、平行審核後匯合）。</li>
     *   <li><b>出線中有無條件流</b>的分流閘道：無條件流恆為真，
     *       所以一定有路可走，不需要預設路徑。</li>
     * </ul>
     *
     * <p>誤擋比漏放更容易造成實質傷害：業務人員畫出合法流程卻部署不了，
     * 得到的訊息又指向一個他無法滿足的要求。那會逼他去繞過 lint
     * ——一旦繞過成為常態，所有規則就一起失效了。
     */
    private void lintGateway(ExclusiveGateway gw, List<LintError> errors) {
        List<SequenceFlow> outgoing = gw.getOutgoingFlows();
        if (outgoing == null || outgoing.size() <= 1) return;  // 匯流或直通

        boolean everyFlowHasCondition = outgoing.stream()
                .allMatch(f -> f.getConditionExpression() != null
                        && !f.getConditionExpression().isBlank());
        if (!everyFlowHasCondition) return;  // 有無條件流，一定有路可走

        if (gw.getDefaultFlow() == null || gw.getDefaultFlow().isBlank()) {
            errors.add(new LintError(gw.getId(), gw.getName(), "gateway-default",
                    "分流用的 ExclusiveGateway 每條出線都有條件，必須設定預設路徑 (default flow)"
                            + " —— 否則所有條件都不成立時流程會在此拋例外卡住", "error"));
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
