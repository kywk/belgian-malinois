package com.bpm.core.lint;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;
import org.springframework.beans.factory.annotation.Value;
import com.bpm.core.form.service.FormService;
import com.bpm.core.webhook.WebhookConfig;
import com.bpm.core.webhook.WebhookPayloadTemplate;
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
            "applicantResolver",
            // 執行期動態計算審核人（#47），任何 UserTask 都可用。
            // 三個方法都套代理人、找不到人時拋例外 —— 既有 orgService／
            // permService／bpmQueryService 的對應方法各有最後一哩的缺口，
            // 見 DynamicAssigneeResolver 類別註解。與 setBeans() 必須同步。
            "dynamicAssignee");

    /**
     * 每個 bean 允許被 BPMN 呼叫的方法（#35）。
     *
     * <h2>為什麼 bean 層不夠</h2>
     *
     * <p>改動前（#35 之前），規則 g 只檢查 {@code ${bean.…}} 的
     * bean 名稱 —— 只要 bean 在清單上，<b>任何方法名都放行</b>。
     * 兩個後果：
     * <ul>
     *   <li><b>打錯字要到執行期才爆</b>：{@code ${orgService.getDirectManger(initiator)}}
     *       部署綠燈，第一個送件的人拿到 {@code Unknown property used in expression}
     *       —— 正是本 repo 反覆在修的「失敗時間與編輯時間脫鉤」。</li>
     *   <li><b>維運方法也進得了 BPMN</b>：{@code orgService.invalidateCache(...)}
     *       之類管理端點在用的方法，業務人員在設計器就能呼叫。</li>
     * </ul>
     *
     * <h2>判準（與 bean 層的關係）</h2>
     *
     * <ul>
     *   <li>bean 不在 {@link #EL_WHITELIST} → 只發既有的 bean 層 error，
     *       <b>不</b>再加一條方法層錯誤（bean 清單裡根本沒有它的方法清單）。</li>
     *   <li>bean 在 {@link #EL_WHITELIST}、運算式出現 {@code .method} →
     *       方法必須在本表；否則發 {@code el-method-whitelist} error。</li>
     *   <li>裸用 {@code ${bean}}（沒有點）維持現狀 —— 由規則 i 處理。</li>
     * </ul>
     *
     * <p>⚠️ 這是<b>字串層</b>的 regex 檢查，不是 AST 走訪：已知可被空白
     * （{@code ${ orgService.…}}）與更長的 member access 鏈繞過，
     * 與 bean 層規則繼承同一個限制（見 {@code docs/plan/2026-09-28-security-audit.md}
     * P2-5 的 (b)）。本表不假裝解決那個問題。
     *
     * <p>⚠️ 本表與實際類別必須同步 —— {@code ElMethodWhitelistDriftTest}
     * 用 reflection 斷言「列出的方法真的存在」，以及「類別的 public 方法
     * 不在本表就必須在 {@link #EL_METHOD_EXCLUDED}」。新增 bean 時
     * 兩張表與 {@link #EL_WHITELIST} 三者都要補。
     */
    // public 與 EL_WHITELIST 一致：這是 lint 的對外契約，測試與其他檢查
    // 可以從這裡推導對象。不可變的 Map／Set.of，外部無法修改。
    public static final Map<String, Set<String>> EL_METHOD_WHITELIST = Map.of(
            "orgService", Set.of(
                    // 直屬主管（設計器「直屬主管（一階）」產生的運算式）
                    "getDirectManager",
                    // 完整主管鏈。⚠️ 不可直接索引（規則 j）；要第 N 階請用 getManagerAtLevel
                    "getManagerChain",
                    // 第 N 階主管（設計器「直屬主管（N 階）」）；鏈不足時回最高階，完全沒有主管拋例外
                    "getManagerAtLevel",
                    // 所屬部門代碼（設計器「發起人所屬單位」候選群組）
                    "getDeptId",
                    // 所屬部門代碼的舊名稱（spec §4.1 對設計師承諾過的別名）
                    "getDeptGroup",
                    // 部門成員清單
                    "getDeptMembers",
                    // 代理人代換：有代理人回代理人，否則回本人（#5）
                    "resolveEffective",
                    // 這個人是否可受理工作（沒有代理人）；供條件判斷使用
                    "isUserAvailable"),
            "permService", Set.of(
                    // 權限持有人清單（設計器「特定權限」候選人）
                    "getUsersByPermission",
                    // 部門範圍的權限持有人清單（spec §4.1）
                    "getUsersByPermissionAndDept",
                    // 是否持有指定權限碼（spec §4.1）
                    "hasPermission",
                    // 使用者的完整權限清單
                    "getUserPermissions",
                    // 第一位可受理的權限持有人；⚠️ 沒有持有人時回 null，
                    // 指派欄位請改用 dynamicAssignee.firstAvailable（找不到人拋例外）
                    "getFirstAvailableUser"),
            "bpmQueryService", Set.of(
                    // 主管鏈上最近一位持有權限碼的主管（spec §4.1）；找不到時回 null
                    "getManagerWithPermission",
                    // 同部門持有權限碼且可受理的人（spec §4.1）
                    "getDeptUsersWithPermission"),
            "assigneeResolver", Set.of(
                    // 第一個任務的受理人（出廠兩支 BPMN 使用）；參數必須是 execution
                    "resolve",
                    // 代理代換；ExternalApiController 在啟動後重設第一關受理人時使用
                    "effectiveAssignee"),
            "applicantResolver", Set.of(
                    // 補件關卡的受理人（出廠兩支 BPMN 使用）；參數必須是 execution
                    "resolve",
                    // 三段規則的共用入口（催辦與補件關卡共用同一份判定，見 #3）
                    "resolveApplicant"),
            "dynamicAssignee", Set.of(
                    // 第 N 階主管＋代理人；找不到人拋例外，不回 null
                    "managerAtLevel",
                    // 權限碼持有人第一位＋代理人；沒有持有人拋例外
                    "firstAvailable",
                    // 主管鏈上持有權限碼的主管＋代理人；找不到人拋例外
                    "managerWithPermission"));

    /**
     * 明確<b>排除</b>的 public 方法（#35）：存在於 bean 類別上，但刻意不讓
     * BPMN 呼叫。{@code ElMethodWhitelistDriftTest} 用它來斷言
     * 「類別的每個 public 方法都有歸屬」—— 新增方法時若兩邊都沒列，
     * 測試會紅，強迫作者做一次「這是不是 EL 函式」的決定。
     *
     * <p>三種排除理由：
     * <ol>
     *   <li><b>維運 API</b>（{@code invalidateCache} 等）：管理端點在用，
     *       業務流程沒有理由清快取。</li>
     *   <li><b>靜態工具</b>（{@code putIfPresent}）：給 controller 寫啟動變數，
     *       不是 EL 函式。</li>
     *   <li><b>P1-7 的刻意 stub</b>（{@code getAuthorizedManager}／
     *       {@code getUsersByPermissionAndCondition}）：永遠拋
     *       {@code UnsupportedOperationException}。security-audit 對這兩個
     *       方法的修法正是「實作前應直接 throw，<b>或加進 lint 的 error 規則</b>」
     *       —— 部署期擋下比執行期才爆更早。真正實作後把它們移進白名單。</li>
     * </ol>
     */
    // package-private：只有同 package 的 ElMethodWhitelistDriftTest 在用，
    // 不是 lint 執行期需要的資料。
    static final Map<String, Set<String>> EL_METHOD_EXCLUDED = Map.of(
            "orgService", Set.of(
                    // 維運 API：組織快取失效（管理端點呼叫）
                    "invalidateCache",
                    // 維運 API：部門成員快取失效（管理端點呼叫）
                    "invalidateDeptMembers",
                    // P1-7 刻意 stub：金額分級核決未實作，永遠拋例外
                    "getAuthorizedManager"),
            "permService", Set.of(
                    // 維運 API：權限快取失效（管理端點呼叫）
                    "invalidateCache",
                    // P1-7 刻意 stub：條件式權限查詢未實作，永遠拋例外
                    "getUsersByPermissionAndCondition"),
            "assigneeResolver", Set.of(
                    // static 工具：供 controller 寫 firstTaskAssignee／
                    // firstTaskCandidateGroups，不是 EL 函式
                    "putIfPresent"));

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

        // ⚠️ 整個 lint 只查一次變數規格，並把結果傳下去。
        //
        // 改動前 declaredVariables() 是由 checkBareVariableReferences() 呼叫的，
        // 而那個方法對 assignee／candidateUsers／candidateGroups 各呼叫一次
        // —— 也就是每個 UserTask 三次資料庫查詢，且整支流程的查詢次數
        // 隨 UserTask 數量線性成長。規則 k（optional-assignee）需要同一份資料的
        // `required`，若各自查就變成四次，而且四次結果可能不一致。
        //
        // 所以形狀比照同一段裡的 isExternalAllowed：同樣的資料只取一次，
        // 同樣的原因（同一份事實不該有兩條取得路徑）。
        Map<String, Boolean> declaredVariables = specsByVariableName(process.getId());

        for (FlowElement el : process.getFlowElements()) {
            if (el instanceof UserTask ut) {
                lintUserTask(ut, errors, isExternalAllowed, process, declaredVariables);
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

        // Rule l: webhook payloadTemplate 只能引用 payload 既有欄位（#28）。
        // 與上面的 per-element 規則分開跑：webhook 容器可以掛在 process 上，
        // 也可以掛在任何 FlowElement 上，遍歷形狀與 UserTask 專屬規則不同。
        lintWebhookPayloadTemplates(process, errors);

        return new LintResult(errors.stream().noneMatch(e -> "error".equals(e.severity())), errors);
    }

    private void lintUserTask(UserTask ut, List<LintError> errors, boolean externalAllowed,
                              org.flowable.bpmn.model.Process process,
                              Map<String, Boolean> declaredVariables) {
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

        // 指派欄位一覽。帶屬性名是為了讓警告訊息能指出是哪一個欄位 ——
        // assignee 與 candidateGroups 空白時的後果不同（見規則 j 的訊息），
        // 管理者要能一眼看出要改哪裡。
        List<Assignment> assignments = List.of(
                new Assignment(ASSIGNEE_ATTRIBUTE, assignee != null ? assignee : ""),
                new Assignment("flowable:candidateUsers", candidateUsers),
                new Assignment("flowable:candidateGroups", candidateGroups));

        // Rule g: EL function whitelist
        for (Assignment a : assignments) {
            checkElWhitelist(a.expr(), ut.getId(), ut.getName(), errors);
        }

        // Rule i: 裸變數參照必須是平台變數或已宣告的流程變數（P2-6）
        for (Assignment a : assignments) {
            checkBareVariableReferences(a.expr(), ut.getId(), ut.getName(), declaredVariables, errors);
        }

        // Rule k: 指派欄位不可直接使用非必填變數（#91 方向 A）——
        // 見 checkOptionalAssigneeVariable 的註解（後果的位元碼證據、
        // 為什麼只認純參照、為什麼是 warning）。
        for (Assignment a : assignments) {
            checkOptionalAssigneeVariable(a, declaredVariables, ut.getId(), ut.getName(), errors);
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
     * 規則 l：webhook 的 {@code payloadTemplate} 只能引用 payload 既有欄位（#28）。
     *
     * <h2>為什麼是 warning 而不是 error</h2>
     *
     * <p>未知欄位在投遞時的行為是<b>原樣保留</b>（見
     * {@link WebhookPayloadTemplate}）：body 仍送得出去，接收端會看到一個
     * 沒被代換的 {@code {{typo}}}。這是「可能寫錯」而不是「必然壞掉」，
     * 而且模板本身是合法的 BPMN —— 用 error 擋部署會讓一支能跑的流程部署不了，
     * 而誤擋會逼人繞過 lint（見 {@code LintRuleCorrectnessTest} 類別註解）。
     * 這裡的價值是部署前讓人<b>看得見</b>拼錯的欄位名。
     *
     * <p>對照的是 {@link WebhookPayloadTemplate#KNOWN_FIELDS}（任務層＋流程層
     * 欄位名的聯集）。刻意<b>不</b>依事件細分：同一個模板語意在 lint 時
     * 不需要知道事件（模板與 event 屬性是獨立的設定），而且「流程層模板引用
     * taskId」在執行期只是原樣保留，不是錯誤 —— 細分只會製造假警告。
     *
     * <p>這條規則同時是 P2-1 紅線的部署期可見性：模板寫 {@code {{salary}}}
     * 之類的流程變數名稱會落在「未知欄位」而被警告。執行期它們本來就拿不到
     * （模板只查 payload map，結構上碰不到流程變數），所以這裡不是安全閘門，
     * 只是讓人知道「這個欄位不會被代換」。
     */
    private void lintWebhookPayloadTemplates(org.flowable.bpmn.model.Process process,
                                             List<LintError> errors) {
        // 流程層（<process>）與節點層用同一份檢查 —— 與 resolver 的兩層對稱一致。
        checkWebhookPayloadTemplates(process, process.getId(), process.getName(), errors);
        for (FlowElement el : process.getFlowElements()) {
            checkWebhookPayloadTemplates(el, el.getId(), el.getName(), errors);
        }
    }

    private void checkWebhookPayloadTemplates(BaseElement element, String elementId, String elementName,
                                              List<LintError> errors) {
        for (ExtensionElement container : element.getExtensionElements()
                .getOrDefault(WebhookConfig.ELEMENT, List.of())) {
            for (ExtensionElement hook : container.getChildElements()
                    .getOrDefault(WebhookConfig.CHILD, List.of())) {
                // ⚠️ namespace 傳 null：屬性在 XML 上無前置（同 resolver 的說明）。
                String template = hook.getAttributeValue(null, WebhookConfig.ATTR_PAYLOAD_TEMPLATE);
                Set<String> unknown = WebhookPayloadTemplate.unknownFields(template);
                if (unknown.isEmpty()) continue;
                errors.add(new LintError(elementId, elementName, "webhook-payload-template",
                        "webhook payloadTemplate 引用了未知欄位 " + unknown
                                + "：投遞時會原樣保留、不會被代換。可用欄位: "
                                + WebhookPayloadTemplate.KNOWN_FIELDS
                                + "。模板只查得到 webhook payload 的欄位，"
                                + "刻意不提供流程變數（security-audit P2-1）。", "warning"));
            }
        }
    }

    /**
     * 檢查裸變數參照 {@code ${name}}（沒有點的那種）。
     *
     * <h2>為什麼白名單 regex 抓不到它們（security-audit P2-6）</h2>
     *
     * <p>{@link #checkElWhitelist} 的 regex 是 {@code \$\{(\w+)\.(\w+)?} ——
     * <b>bean 名稱後面必須有「點」</b>才匹配（有點才可能是 bean 呼叫）。所以
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
                                             Map<String, Boolean> declaredVariables,
                                             List<LintError> errors) {
        if (expr == null || !expr.contains("${")) return;

        // ${name} 且 name 之後直接是 } —— 有點或有括號的都由白名單規則處理
        var matcher = BARE_VARIABLE.matcher(expr);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (PLATFORM_VARIABLES.contains(name) || declaredVariables.containsKey(name)) continue;
            if (isEnvPlaceholderName(name)) continue;
            errors.add(new LintError(elementId, elementName, "undeclared-variable",
                    "運算式參照了未宣告的流程變數 '" + name + "'。"
                            + "若這是字面值（例如部門代碼），請直接填寫不要包 ${}；"
                            + "若確實是流程變數，請先在流程變數規格中宣告它。"
                            + "（平台變數: " + PLATFORM_VARIABLES + "）", "error"));
        }
    }

    /**
     * 這個裸變數名是不是部署期的環境變數佔位符（backlog #53，spec §12.3）。
     *
     * <h2>為什麼規則 i 必須豁免它</h2>
     *
     * <p>{@code ${ENV_FINANCE_GROUP}} 的形狀與未宣告的流程變數
     * （{@code ${dept}}）一模一樣：都是 {@code \$\{(\w+)\}}。但語意完全不同 ——
     * 它不是要在執行期求值的變數，而是<b>部署時會被替換成字面值</b>的佔位符
     * （{@code BpmnEnvSubstitutor}）。不豁免的話，spec §12.3 的標準寫法
     * 根本部署不了。
     *
     * <h2>⚠️ 豁免邊界必須與 BpmnEnvSubstitutor.PLACEHOLDER 逐字相同</h2>
     *
     * <p>這裡刻意用與替換端相同的 {@code ENV_[A-Z0-9_]+}，不是「以 ENV_ 開頭」：
     * <ul>
     *   <li>{@code ${ENV_}}（空名）、{@code ${env_x}}（小寫）：替換端不認，
     *       維持 {@code undeclared-variable} 錯誤。放行等於部署一份執行期
     *       求值 {@code ${ENV_}} 的 BPMN，錯誤延後到第一個送件的人。</li>
     *   <li>{@code ${ENV_X.foo}}：有點的形狀是 EL 呼叫（替換端 pattern 要求
     *       名稱後直接是 {@code }}），由 bean 白名單規則處理，不受本豁免影響。</li>
     * </ul>
     *
     * <p>替換失敗（值未設定）在部署端是 400；設計器 lint 不檢查值是否存在
     * —— lint 是<b>環境無關</b>的（同一份 XML 要能通過所有環境的 lint），
     * 值的存在與否只有部署到某個環境時才成立。這是兩個檢查的分工，不是遺漏。
     */
    static boolean isEnvPlaceholderName(String name) {
        return ENV_PLACEHOLDER_NAME.matcher(name).matches();
    }

    /**
     * 環境變數佔位符的名稱（不含 {@code ${}}）：
     * {@code ENV_[A-Z0-9_]+}。與 {@code BpmnEnvSubstitutor.PLACEHOLDER} 同步。
     */
    static final java.util.regex.Pattern ENV_PLACEHOLDER_NAME =
            java.util.regex.Pattern.compile("ENV_[A-Z0-9_]+");

    /**
     * 純變數參照（整個運算式就是 {@code ${name}}，前後沒有多餘字元）。
     *
     * <p>與 {@link #BARE_VARIABLE} 的差別是<b>錨定</b>：那個找出字串裡出現的
     * 所有裸變數，這個只認「整個運算式就是它」。
     *
     * <p><b>只有 {@code candidateUsers}／{@code candidateGroups} 用它</b>。
     * {@code assignee} 改用 {@link #BARE_VARIABLE} 放寬 —— 理由見
     * {@link #checkOptionalAssigneeVariable} 的「不對稱」一節。
     */
    private static final java.util.regex.Pattern PURE_BARE_VARIABLE =
            java.util.regex.Pattern.compile("^\\$\\{(\\w+)}$");

    /** 字串中出現的裸變數參照 {@code ${name}}（name 之後直接是 }）。 */
    private static final java.util.regex.Pattern BARE_VARIABLE =
            java.util.regex.Pattern.compile("\\$\\{(\\w+)\\}");

    /** assignee 屬性的名字。指派欄位「放寬 vs 維持純參照」的不對稱以此為界。 */
    private static final String ASSIGNEE_ATTRIBUTE = "flowable:assignee";

    /**
     * 規則 k：指派欄位用<b>非必填</b>的流程變數（#91 方向 A；#91c 對 assignee 放寬）。
     *
     * <h2>後果是什麼（證據來自位元碼，不是推論）</h2>
     *
     * <p>{@code UserTaskActivityBehavior.handleAssignments}（Flowable 7.2.0）
     * 對 assignee 的判斷是：
     * <pre>
     *   if (StringUtils.isNotEmpty(assigneeExpression)) {          // 判的是運算式字串本身
     *       Object v = expression.getValue(execution);
     *       String s = v == null ? null : v.toString();            // 沒有 trim()
     *       if (StringUtils.isNotEmpty(s)) {                      // 判的是求值結果
     *           TaskHelper.changeTaskAssignee(task, s);           // 空白字串照樣寫入
     *       }
     *   }
     * </pre>
     * 第一道判斷看的是<b>運算式字串</b>（{@code "${dept}"} 當然非空），所以它擋不住
     * 任何東西；第二道判斷是 {@code isNotEmpty} 而不是 {@code isBlank}，
     * 而 {@code isNotEmpty("  ")} 為 true —— 於是 {@code "  "} 被原樣寫入 assignee。
     *
     * <p>而 Flowable 的候選群組查詢帶著 {@code RES.ASSIGNEE_ IS NULL}，
     * assignee 非 null（即使是空白）就<b>已經</b>讓候選人看不到它。
     * 結果：任務建立成功、沒有例外、沒有任何錯誤訊息，而沒有人的待辦清單裡有它。
     * 案件靜默卡死。（{@code UnreachableTaskListener} 會告警，所以是看得見的 —
     * 但那是執行期，而這裡是部署期：部署到第一個人送出之間可能已經過了幾個月。）
     *
     * <h2>#91c 放寬：為什麼 assignee 認「有參照到」、候選欄位只認「純參照」</h2>
     *
     * <p>這個不對稱來自<b>欄位是單一值還是多值</b>：
     *
     * <ul>
     *   <li><b>{@code assignee} 是單一值</b>：整串求值結果就是那個任務的唯一受理人 id。
     *       任何一個非必填變數在執行期沒有值，都會讓<b>整個值</b>壞掉 ——
     *       {@code ${dept}-01} 在 dept 為空時是 {@code "-01"}、
     *       {@code ${a}-${b}} 是 {@code "alice-"}，兩者都是<b>沒有人持有</b>的 id。
     *       這與純 {@code ${dept}} 求值成空白是同一種靜默卡死，所以判準放寬成
     *       「運算式裡用 {@link #BARE_VARIABLE} 找到任一個 required=false 的已宣告變數」。</li>
     *   <li><b>{@code candidateUsers}／{@code candidateGroups} 是多值</b>（逗號分隔）：
     *       {@code extractCandidates} 對字串做的是 {@code s.split("[\\s]*,[\\s]*")}。
     *       Java 的 split 預設<b>丟掉尾端空字串</b>、<b>保留前端與中間的空字串</b>，
     *       所以（{@code javap -c} 讀 Flowable 7.2.0 的 {@code extractCandidates} 後
     *       以 Java 實測）：{@code "DEP01,"} → {@code ["DEP01"]}、
     *       {@code ",hr"} → {@code ["", "hr"]}、{@code "a,,b"} → {@code ["a", "", "b"]}。
     *       也就是<b>只要還留下一個有效項，任務就仍有人看得到</b>
     *       —— {@code "hr,${dept}"} 在 dept 為空時仍留有 {@code "hr"}。
     *       對這些形狀發警告就是<b>假警告</b>：管理員無法解決一個不存在的問題，
     *       而假警告會訓練大家忽略警告（見 {@code LintRuleCorrectnessTest} 類別註解
     *       為什麼誤擋比漏放更貴）。所以這裡維持只認純參照。</li>
     * </ul>
     *
     * <p>⚠️ <b>本不對稱刻意不涵蓋的殘餘邊界</b>：{@code "${a},${b}"} 若兩者皆為
     * required=false 且執行期皆為空，split 的結果是 {@code "," → []}
     * —— 一個候選人都沒有，任務確實不可達，與 assignee 的靜默卡死同級。
     * 之所以仍然不警告，是因為把候選欄位放寬成「任一可選參照就警告」會對最常見的
     * {@code "hr,${dept}"}（固定群組 ＋ 可選變數，dept 空時仍有 hr）製造大量假警告；
     * 兩害相權取其輕，選擇漏放這個較少見、且需要「所有元素皆為可選且皆空」才成立的形狀。
     * 這是<b>已知的取捨</b>，不是沒想到。
     *
     * <p>⚠️ 這個不對稱是<b>刻意</b>的，不是沒寫完：{@code assignee} 放寬後，
     * 純 {@code ${dept}} 仍會被警告，但只會有一條 —— 放寬的判準（{@link #BARE_VARIABLE}）
     * 涵蓋了純參照的情形，兩者共用同一條路徑，不會各發一次。
     *
     * <h2>為什麼是 warning 而不是 error</h2>
     *
     * <p>本規則描述的是「<b>可能</b>」，不是「必然」：非必填變數在多數案件裡都有值。
     * 而且 {@code valid()} 只看有沒有 error，升成 error 會讓
     * {@code POST /api/deployments} 直接擋下部署 —— 擋掉的是一條
     * <b>合法</b>的 BPMN（審核人由執行期變數決定正是本平台最常見的設計）。
     * 擋掉合法 BPMN 的危害見 {@code LintRuleCorrectnessTest} 類別註解：
     * 它逼人繞過 lint，而繞過會讓<b>所有</b>規則一起失效。
     *
     * <p>方向 B（指派層把空白視為未指定）才是真正擋住它的機制；
     * 這裡是部署前讓人<b>看得見</b>，兩者互補，不可互相取代
     * （與規則 h 對 {@code UnreachableTaskListener} 的註解同一個道理）。
     */
    private void checkOptionalAssigneeVariable(Assignment a, Map<String, Boolean> declaredVariables,
                                               String elementId, String elementName,
                                               List<LintError> errors) {
        if (a.expr() == null) return;

        if (ASSIGNEE_ATTRIBUTE.equals(a.attribute())) {
            // 放寬：assignee 是單一值，任一非必填變數都會讓整個 id 無效。
            // 用 BARE_VARIABLE 找出運算式裡所有 ${name} —— 純參照 ${dept} 也在其中，
            // 所以只有這一條路徑，不會對純 ${dept} 發兩次警告。
            var matcher = BARE_VARIABLE.matcher(a.expr());
            Set<String> alreadyWarned = new HashSet<>();
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!isOptionalDeclaredVariable(name, declaredVariables)) continue;
                // 同一變數在運算式裡出現多次（${dept}-${dept}）只警告一次。
                if (!alreadyWarned.add(name)) continue;
                errors.add(new LintError(elementId, elementName, "optional-assignee",
                        "指派欄位 " + a.attribute() + " 參照了非必填的流程變數 '" + name
                                + "'（required=false）。assignee 是單一值：只要該變數在執行期"
                                + "沒有值或只有空白，整個值就會變成沒有人持有的無效 id"
                                + "（例如 \"alice-\"、\"-01\"）；候選群組查詢帶著 ASSIGNEE_ IS NULL，"
                                + "因此沒有任何人看得到這個任務，而且不會有任何錯誤訊息。"
                                + "請改用 required=true 的變數、給它一個非空的預設值，"
                                + "或改用方法呼叫（${orgService.…}）在執行期推導出實際值。",
                        "warning"));
            }
            return;
        }

        // candidateUsers／candidateGroups：刻意只認純參照（多值欄位放寬會製造假警告，
        // 理由見上方「#91c 放寬」一節）。去掉前後空白再比對：字面空白會讓結果變成
        // 非空白的無效值（" ${dept} " 在 dept 為空時求值成 " "），那正是本規則要抓的形狀。
        var matcher = PURE_BARE_VARIABLE.matcher(a.expr().trim());
        if (!matcher.matches()) return;

        String name = matcher.group(1);
        if (!isOptionalDeclaredVariable(name, declaredVariables)) return;

        errors.add(new LintError(elementId, elementName, "optional-assignee",
                "指派欄位 " + a.attribute() + " 直接使用非必填的流程變數 '" + name
                        + "'（required=false）。若執行期該變數沒有值或只有空白，"
                        + "會產生一個無效的候選人／群組 id；候選群組查詢帶著 ASSIGNEE_ IS NULL，"
                        + "因此沒有任何人看得到這個任務，而且不會有任何錯誤訊息"
                        + "（候選人／candidateGroups 必須另外設定才看得到）。"
                        + "請改用 required=true 的變數、給它一個非空的預設值，"
                        + "或改用方法呼叫（${orgService.…}）在執行期推導出實際值。",
                "warning"));
    }

    /**
     * 這個裸變數參照是不是「已宣告且 required=false」的流程變數
     * —— 也就是規則 k 唯一會警告的那一種。
     */
    private static boolean isOptionalDeclaredVariable(String name, Map<String, Boolean> declaredVariables) {
        // 平台變數恆存在，不是「選擇性」的流程變數。
        // 特別是 firstTaskAssignee／firstTaskCandidateGroups：它們在指派欄位
        // 位置出現時常是字面值而非變數參照，但即使被包成 ${} 也不該被警告 ——
        // 否則會蓋掉 checkBareVariableReferences 刻意放行的那些 BPMN。
        if (PLATFORM_VARIABLES.contains(name)) return false;
        // get() 對未宣告的變數回 null → 不警告（那由 undeclared-variable 擋，error）。
        // 讀不到規格而退化成空 Map 時也是同一條路徑：漏發警告而非誤擋。
        // 這是刻意的取捨 —— 警告是諮詢性的，讀不到規格時少講一句不會造成事故，
        // 反之若因此誤擋，就等於讓資料庫逾時變成「所有人都不能部署流程」。
        return Boolean.FALSE.equals(declaredVariables.get(name));
    }

    /**
     * 這個流程宣告的變數：變數名 → required。
     *
     * <p>⚠️ 整個 lint 只呼叫這一次（由 {@link #lint()} 傳下去），原因見該處。
     * 這是「同一份資料不該查兩次」的落實：{@code required} 與「是否已宣告」
     * 來自同一列，查兩次除了多打一次資料庫之外，還有兩次查詢結果不一致的風險。
     *
     * <p>回傳空 Map 而非 null，讓呼叫端不必處理兩種形狀。
     */
    private Map<String, Boolean> specsByVariableName(String processKey) {
        if (processKey == null || processKey.isBlank()) return Map.of();
        try {
            return specRepo.findByProcessDefinitionKeyOrderByVariableName(processKey).stream()
                    // ⚠️ 明確給 merge function：(processDefinitionKey, variableName)
                    // 上有唯一約束，理論上不會重複，但 Collectors.toMap 預設遇到
                    // 重複會丟 IllegalStateException，而那會被下面的 catch 吞成
                    // 空 Map —— 於是一筆重複資料會讓 undeclared-variable 對
                    // <b>所有</b>變數誤報。取 (a, b) -> a || b 是保守的一邊。
                    .collect(java.util.stream.Collectors.toMap(
                            com.bpm.core.model.ProcessVariableSpec::getVariableName,
                            // required 是 Boolean 而非 boolean：資料庫裡若是 NULL，
                            // 取出來是 null。Boolean.TRUE.equals(null) 為 false
                            // —— 與 Model 欄位預設值 false 的語意一致。
                            s -> Boolean.TRUE.equals(s.getRequired()),
                            (a, b) -> a || b));
        } catch (Exception e) {
            // lint 不該因為讀不到規格而整個失敗；退化為只放行平台變數（較嚴格的一邊）。
            return Map.of();
        }
    }

    /** 指派欄位：屬性名（用於訊息）＋ 運算式。 */
    private record Assignment(String attribute, String expr) {}

    /**
     * 從指派運算式抽出 {@code ${bean.method} 的 bean 與方法名。
     *
     * <p>形狀刻意與改動前的 {@code \$\{(\w+)\.} 相同，只多一個<b>可選</b>的
     * 方法名 capture group：方法名可選是為了保留原行為 —— {@code ${bean.}}
     * 這種沒有方法名的寫法，bean 層錯誤仍然要照發。不引入 parser 相依
     * （JUEL AST 的走訪是 security-audit P2-5 (b) 的另一個工項）。
     */
    private static final java.util.regex.Pattern EL_BEAN_METHOD =
            java.util.regex.Pattern.compile("\\$\\{(\\w+)\\.(\\w+)?");

    private void checkElWhitelist(String expr, String elementId, String elementName, List<LintError> errors) {
        if (expr == null || !expr.contains("${")) return;
        // Extract bean name (and method, when present) from ${beanName.method(...)}
        var matcher = EL_BEAN_METHOD.matcher(expr);
        while (matcher.find()) {
            String bean = matcher.group(1);
            if (!EL_WHITELIST.contains(bean)) {
                errors.add(new LintError(elementId, elementName, "el-whitelist",
                        "EL 函數 '" + bean + "' 不在白名單內（允許: " + EL_WHITELIST + "）", "error"));
                // 非白名單 bean 的方法不另外檢查：bean 本身已被擋下，
                // 而「該 bean 的合法方法」這份事實根本不存在（刻意如此）。
                continue;
            }
            String method = matcher.group(2);
            // 沒有方法名（${bean.}）或裸用（${bean}，不會 match）都維持現狀。
            if (method == null) continue;
            Set<String> allowed = EL_METHOD_WHITELIST.get(bean);
            // 白名單 bean 必然有方法清單（ElMethodWhitelistDriftTest 釘住）。
            // 這裡防禦性跳過而不是 NPE：lint 掛掉會讓所有規則一起失效。
            if (allowed == null) continue;
            if (!allowed.contains(method)) {
                // 排序後再輸出：Set.of 的迭代順序未定義，訊息每次不同會讓
                // 「貼錯誤訊息去搜尋」與測試斷言都不穩定。
                errors.add(new LintError(elementId, elementName, "el-method-whitelist",
                        "EL 方法 '" + bean + "." + method + "' 不在白名單內（允許: "
                                + new java.util.TreeSet<>(allowed) + "）", "error"));
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
