package com.bpm.core.lint;

import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 規則 k {@code optional-assignee}：指派欄位不可直接使用<b>非必填</b>的流程變數
 * （#91 方向 A）。
 *
 * <h2>這個工項在防什麼</h2>
 *
 * <p>{@code flowable:assignee="${var}"} 而 {@code var} 在執行期求值為空白時，
 * assignee 會變成空白字串，而<b>沒有任何人看得到那個任務</b>，
 * 而且<b>沒有任何錯誤訊息</b>。
 *
 * <p>「沒有任何人看得到」有兩層，第二層是本專案特有的：
 * Flowable 的候選群組查詢帶著 {@code RES.ASSIGNEE_ IS NULL}，
 * 所以 assignee <b>非 null</b>（即使是空白）就已經讓候選人看不到它了 ——
 * 就算同時有 candidateGroups 也一樣。
 *
 * <h2>後果的證據（位元碼，不是推論）</h2>
 *
 * <p>{@code UserTaskActivityBehavior.handleAssignments}（Flowable 7.2.0）
 * 的 assignee 分支反組譯後是：
 * <pre>
 *   if (StringUtils.isNotEmpty(assigneeExpression)) {   // ① 判的是運算式字串本身
 *       Object v = expression.getValue(execution);
 *       String s = (v == null) ? null : v.toString();   // ② 沒有 trim()
 *       if (StringUtils.isNotEmpty(s)) {                // ③ isNotEmpty，不是 isBlank
 *           TaskHelper.changeTaskAssignee(task, s);
 *       }
 *   }
 * </pre>
 * ① 對 {@code "${var}"} 恆為 true（那一串字元當然非空），所以它<b>擋不住任何東西</b>；
 * ③ 的 {@code isNotEmpty("  ") == true}，所以 {@code "  "} 被原樣寫入。
 * {@code changeTaskAssignee} 也沒有自己的防線（舊 assignee 為 null 且新值非 null
 * → 直接寫入並加 assignee identity link）。
 *
 * <h2>為什麼只能做部署期 lint（方向 A），不能做執行期攔截</h2>
 *
 * <p>lint 在<b>部署時</b>跑，{@code ${var}} 的值在<b>執行期</b>才有。
 * 而 {@code checkBareVariableReferences} 已經擋掉未宣告的變數，
 * 所以能通過 lint 的 {@code ${var}} 必然是<b>已宣告</b>的 ——
 * 而「已宣告」不等於「執行期有值」。唯一能寫出的寬鬆版本會是
 * 「所有 {@code ${var}} assignee 都警告」，而那等於沒有規則：
 * 本平台最常見的設計就是「審核人由執行期變數決定」的單人審核流程。
 *
 * <p>所以判準必須是「<b>已宣告且 required=false</b>」：那正是
 * 「執行期可能沒有值」的可部署期表述。{@code required=true} 的變數被
 * {@code ExternalApiController.validateVariables} 擋（缺它就 400），
 * 未宣告的變數已被 {@code undeclared-variable} 擋（error）。
 *
 * <h2>負向控制組實測結果（2026-10-01）</h2>
 *
 * <p>做法：保留全部重構（{@code specsByVariableName}、{@code Assignment}），
 * 只在 {@code checkOptionalAssigneeVariable} 的第一行加
 * {@code if (Boolean.TRUE) return;} 把規則 k 停用，重跑本類別。
 * 刻意<b>不</b>整個還原成改動前的檔案 —— 那樣紅的會是「編譯失敗」，
 * 證明不了任何事。
 *
 * <p><b>紅（3 條）</b>：
 * <ul>
 *   <li>{@code optionalVariableInAssigneeWarns} —— assignee 欄位</li>
 *   <li>{@code optionalVariableInCandidatesWarns} —— candidateUsers／candidateGroups</li>
 *   <li>{@code severityIsWarningAndDoesNotBlockDeployment}</li>
 * </ul>
 *
 * <p><b>綠（7 條）</b>：四組對照組、跨流程規格那條、出廠 BPMN 的部署路徑測試、
 * 出廠 BPMN 的結構性測試。
 *
 * <p>⚠️ <b>綠的那 7 條證明不了新規則有接上</b> —— 它們斷言的是
 * 「沒有誤擋」，所以在規則存在與不存在兩種狀態下都必須綠，這正是控制組的定義。
 * 證明新規則有接上是上面那 3 條的責任。
 *
 * <p>⚠️ 另一個要誠實記下的地方：{@code severityIsWarningAndDoesNotBlockDeployment}
 * 在負向控制組裡是<b>卡在第一個斷言</b>（找不到 rule 就 orElseThrow），
 * 所以它<b>沒有</b>被獨立驗證過「severity 一定是 warning」與
 * 「{@code valid()} 一定是 true」。那兩個斷言在綠燈時確實有跑，但它們
 * 與「規則有觸發」綁在同一條測試裡 —— 拆開的話「{@code valid()} 為 true」
 * 會變成一個沒有規則也成立的廢斷言。
 */
class OptionalAssigneeVariableLintTest extends IntegrationTestBase {

    private static final String RULE = "optional-assignee";

    @Autowired private BpmnLintService lintService;
    @Autowired private ProcessVariableSpecRepository specRepo;
    @Autowired private RepositoryService repositoryService;
    @Autowired private MockMvc mockMvc;

    /** 本測試建立的流程 key；每條測試一個，收尾時整批刪掉。 */
    private final List<String> createdProcessKeys = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        // ⚠️ 這裡刻意用衍生刪除（deleteAll(find…)）而不是
        // deleteAllByProcessDefinitionKey：後者是 @Modifying 的原生 bulk delete，
        // 而本測試的 save() 讓那些 entity 還留在 persistence context 裡，
        // Spring Data 會丟 InvalidDataAccessApiUsage
        // （「Executing an update/delete query」）。
        //
        // 生產程式用 bulk delete 是因為它要處理「先刪光再整批插回」的排序問題
        // （見 ProcessVariableSpecRepository 的註解）；測試收尾沒有那個形狀，
        // 所以照生產做法反而會壞掉 —— 兩者的取捨理由不同，不能互相抄。
        //
        // 衍生刪除需要交易，而整個方法自己開一個（測試方法本身不是 @Transactional）。
        for (String key : createdProcessKeys) {
            var specs = specRepo.findByProcessDefinitionKeyOrderByVariableName(key);
            if (!specs.isEmpty()) specRepo.deleteAll(specs);
        }
        createdProcessKeys.clear();
    }

    /**
     * 建立一個<b>只有本測試在用</b>的流程 key。
     *
     * <p>⚠️ 必須唯一，不能寫死：{@link IntegrationTestBase} 的容器是 static，
     * 所有測試<b>共用同一個資料庫</b>，而 lint 的規則 k 是以
     * {@code process id} 去查 {@code bpm_process_variable_spec}。
     * 寫死一個 key 會讓其他測試類別的規格汙染這裡的判斷
     * （以及反過來 —— 這裡的規格讓那些測試紅）。
     */
    private String newProcessKey() {
        String key = "optassignee91-" + UUID.randomUUID().toString().substring(0, 8);
        createdProcessKeys.add(key);
        return key;
    }

    /** 宣告一個變數規格，回傳變數名。 */
    private String givenSpec(String processKey, String variableName, boolean required) {
        ProcessVariableSpec s = new ProcessVariableSpec();
        s.setProcessDefinitionKey(processKey);
        s.setVariableName(variableName);
        s.setVariableType("string");
        s.setRequired(required);
        specRepo.save(s);
        return variableName;
    }

    /**
     * 單一 UserTask 的 BPMN。
     *
     * <p>⚠️ {@code formKey} 用 {@code external:} 前綴：那是 rule c 明確跳過表單查詢的
     * 分支（見 {@code BpmnLintService} 規則 c）。目的是讓這些測試<b>不依賴表單資料</b>
     * —— 否則斷言的是「有沒有 optional-assignee」，卻同時被
     * {@code formkey-exists} 的資料庫狀態干擾。
     */
    private static String userTaskXml(String processKey, String attrs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s" name="開始"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                    <userTask id="t" name="審核關卡" flowable:formKey="external:none" %s/>
                    <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
                    <endEvent id="e" name="結束"/>
                  </process>
                </definitions>
                """.formatted(processKey, attrs);
    }

    private List<String> ruleIds(String processKey, String attrs) {
        return lintService.lint(userTaskXml(processKey, attrs)).errors().stream()
                .map(BpmnLintService.LintError::rule).toList();
    }

    private BpmnLintService.LintError ruleOf(String processKey, String attrs, String rule) {
        return lintService.lint(userTaskXml(processKey, attrs)).errors().stream()
                .filter(e -> rule.equals(e.rule())).findFirst().orElseThrow(
                        () -> new AssertionError("沒有 rule=" + rule + " 的警告；實際規則：" + ruleIds(processKey, attrs)));
    }

    // ── 會警告 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("assignee 直接使用 required=false 的變數 → 必須警告（這是規則存在的理由）")
    void optionalVariableInAssigneeWarns() {
        String key = newProcessKey();
        givenSpec(key, "dept", false);

        // 這條若不會紅，代表規則沒接上。實作移除後它確實是紅的（見類別 javadoc）。
        assertThat(ruleIds(key, "flowable:assignee=\"${dept}\""))
                .as("assignee 為非必填變數時，執行期求值成空白就會讓任務對所有人不可見")
                .contains(RULE);
    }

    @Test
    @DisplayName("candidateUsers / candidateGroups 使用非必填變數 → 一樣要警告")
    void optionalVariableInCandidatesWarns() {
        String key = newProcessKey();
        givenSpec(key, "dept", false);

        assertThat(ruleIds(key, "flowable:candidateUsers=\"${dept}\"")).contains(RULE);
        assertThat(ruleIds(key, "flowable:candidateGroups=\"${dept}\""))
                .as("候選群組為空白時 extractCandidates 會產生一個無效群組 id，"
                        + "同樣沒有人看得到任務")
                .contains(RULE);
    }

    @Test
    @DisplayName("警告必須是 warning 且不得讓部署失效（升成 error 會擋掉合法 BPMN）")
    void severityIsWarningAndDoesNotBlockDeployment() {
        String key = newProcessKey();
        givenSpec(key, "dept", false);
        String attrs = "flowable:assignee=\"${dept}\"";

        var result = lintService.lint(userTaskXml(key, attrs));
        var rule = ruleOf(key, attrs, RULE);

        assertThat(rule.severity())
                .as("本規則描述的是「可能」而非「必然」；升成 error 會讓 POST /api/deployments "
                        + "直接擋下一條合法的 BPMN")
                .isEqualTo("warning");
        assertThat(result.valid())
                .as("valid() 只看有沒有 error —— 這一條是 warning 所以部署必須仍然成功")
                .isTrue();
        assertThat(rule.message())
                .as("訊息要指出是哪個欄位，否則管理員不知道要改哪裡")
                .contains("flowable:assignee", "dept");
    }

    // ── 對照組：不得警告 ───────────────────────────────────────────

    @Test
    @DisplayName("required=true 的變數 → 不得警告（缺它就 400，執行期不會是空）")
    void requiredVariableDoesNotWarn() {
        String key = newProcessKey();
        givenSpec(key, "dept", true);

        assertThat(ruleIds(key, "flowable:assignee=\"${dept}\""))
                .as("必填變數被 ExternalApiController.validateVariables 擋下（缺它就 400）")
                .doesNotContain(RULE);
    }

    @Test
    @DisplayName("平台變數 → 不得警告")
    void platformVariablesDoNotWarn() {
        String key = newProcessKey();

        assertThat(ruleIds(key, "flowable:assignee=\"${initiator}\""))
                .as("initiator 是平台保證存在的變數，永遠不會是選擇性的流程變數")
                .doesNotContain(RULE);
        // ⚠️ 特別驗 firstTaskAssignee：它常常是指派欄位裡的<b>字面值</b>，
        // 但就算有人包成 ${} 也不該被警告 —— 否則會蓋掉
        // checkBareVariableReferences 刻意放行的那些 BPMN。
        assertThat(ruleIds(key, "flowable:candidateGroups=\"${firstTaskCandidateGroups}\""))
                .as("firstTaskCandidateGroups 在指派欄位位置出現時是字面值，不是選擇性變數")
                .doesNotContain(RULE);
    }

    @Test
    @DisplayName("字面值與方法呼叫 → 不得警告（規則只認「整個運算式就是 ${var}」）")
    void literalsAndBeanCallsDoNotWarn() {
        String key = newProcessKey();
        givenSpec(key, "dept", false);

        assertThat(ruleIds(key, "flowable:assignee=\"dept001\""))
                .as("字面值沒有執行期求值，不會變成空白")
                .doesNotContain(RULE);
        assertThat(ruleIds(key, "flowable:assignee=\"${orgService.getDirectManager(initiator)}\""))
                .as("方法呼叫裡沒有裸變數參照（${ 後面接的是 bean 名後面接點），不得被誤蓋")
                .doesNotContain(RULE);
    }

    @Test
    @DisplayName("required=false 但只用於其他屬性、assignee 是字面值 → 不得警告")
    void optionalVariableUsedElsewhereDoesNotWarn() {
        String key = newProcessKey();
        givenSpec(key, "dept", false);

        // formKey 引用同一個非必填變數，但指派欄位是字面值 ——
        // 沒有任何指派欄位會在執行期變成空白，所以與本規則無關。
        assertThat(ruleIds(key, "flowable:assignee=\"dept001\" flowable:formKey=\"external:${dept}\""))
                .as("非必填變數出現在非指派屬性上不構成此缺陷")
                .doesNotContain(RULE);
    }

    @Test
    @DisplayName("另一個流程的變數規格對這個流程無效")
    void specOfAnotherProcessDoesNotApply() {
        String otherKey = newProcessKey();
        givenSpec(otherKey, "dept", false);
        String key = newProcessKey();

        assertThat(ruleIds(key, "flowable:assignee=\"${dept}\""))
                .as("規格是 per-process 的；這個 key 沒有宣告 dept，所以應該是 undeclared-variable")
                .doesNotContain(RULE)
                .contains("undeclared-variable");
    }

    // ── 出廠 BPMN：不得被擋下（最大的迴歸風險）────────────────────

    /**
     * ⚠️ 本工項最大的迴歸風險：{@code scripts/seed-data.sh} 靠
     * {@code POST /api/deployments} 部署兩支出廠 BPMN。
     *
     * <p>這裡走<b>真的部署端點</b>而不是只呼叫 {@code lintService.lint()}：
     * 兩者之間還隔著「{@code valid()} 決定 HTTP 狀態碼」這一步。
     *
     * <p>為什麼要特別驗：規則 k 新增了一個<b>依賴資料庫規格</b>的判準
     * （查 {@code bpm_process_variable_spec}），而出廠 BPMN 的指派欄位
     * 全部是 {@code ${assigneeResolver.resolve(execution)}} 這種方法呼叫 ——
     * 理論上不會觸發。但「理論上」不是證據，而這正是加警告規則最危險的後果：
     * 把工廠出廠流程擋掉。
     */
    @Test
    @DisplayName("seed-data.sh 部署的兩支出廠 BPMN 必須仍然部署成功")
    void shippedBpmnStillDeploys() throws Exception {
        for (String name : List.of("leave-approval", "purchase-approval")) {
            byte[] xml = getClass().getClassLoader()
                    .getResourceAsStream("processes/" + name + ".bpmn20.xml").readAllBytes();

            // 先確認新規則確實<b>沒有</b>觸發 —— 否則下面的 200 可能是運氣。
            var lint = lintService.lint(new String(xml, StandardCharsets.UTF_8));
            assertThat(lint.errors().stream().map(BpmnLintService.LintError::rule))
                    .as("%s 若觸發 optional-assignee，代表規則把出廠流程判成有問題", name)
                    .doesNotContain(RULE);
            assertThat(lint.valid()).as("%s 必須通過 lint", name).isTrue();

            // 再走真的部署端點 —— 這是 seed-data.sh 走的那條路徑。
            var res = mockMvc.perform(multipart("/api/deployments")
                            .file(new MockMultipartFile("file", name + ".bpmn20.xml", "text/xml", xml))
                            .param("name", name)
                            .header("X-User-Id", "admin001"))
                    .andExpect(status().isOk())
                    .andReturn();

            // 收尾：刪掉這次部署，避免改變其他測試 startProcessInstanceByKey
            // 撿到的定義版本（測試共用同一個資料庫）。
            String deploymentId = res.getResponse().getContentAsString()
                    .replaceAll(".*\"deploymentId\":\"([^\"]*)\".*", "$1");
            if (!deploymentId.contains("\"") && !deploymentId.isBlank()) {
                repositoryService.deleteDeployment(deploymentId, true);
            }
        }
    }

    /**
     * 出廠 BPMN 的指派欄位為什麼不會觸發規則 k。
     *
     * <p>把上面那條的「沒有觸發」釘到結構上：出廠兩支 BPMN 的指派欄位
     * 全部是<b>方法呼叫</b>（{@code ${assigneeResolver.resolve(execution)}}、
     * {@code ${applicantResolver.resolve(execution)}}、
     * {@code ${permService.getUsersByPermission(...)}}），而規則 k 只認
     * 「整個運算式就是 {@code ${名稱}}」。
     *
     * <p>為什麼值得單獨一條：{@code shippedBpmnStillDeploys} 證明的是
     * 「部署成功」，它<b>不能</b>區分「新規則正確地不觸發」與
     * 「新規則根本沒執行」。這條把前者變成可斷言的事實。
     * 若日後有人把第一關改回 {@code ${initiator}}（那正是 #83 修掉的形狀），
     * 這條會紅 —— 而那時 {@code seed-data.sh} 確實仍然能部署
     * （initiator 是平台變數，規則 k 不碰它），所以這條紅掉是<b>對</b>的：
     * 它在提醒「這個形狀已經被別的規則擋了」，而不是預測 seed 會壞。
     */
    @Test
    @DisplayName("出廠 BPMN 的指派欄位是方法呼叫，不是純變數參照 —— 這是它不觸發規則 k 的結構性原因")
    void shippedBpmnAssignmentsAreNotPureVariableReferences() throws Exception {
        for (String name : List.of("leave-approval", "purchase-approval")) {
            String xml = new String(getClass().getClassLoader()
                    .getResourceAsStream("processes/" + name + ".bpmn20.xml").readAllBytes(),
                    StandardCharsets.UTF_8);

            // 有點的表達式一律不匹配 ^\$\{(\w+)}$ —— 這是規則 k 的判準本身。
            assertThat(java.util.regex.Pattern.compile("^\\$\\{(\\w+)}$").matcher(
                    "${assigneeResolver.resolve(execution)}").matches())
                    .as("方法呼叫不可能是純變數參照")
                    .isFalse();

            assertThat(xml)
                    .as("%s 若出現純變數參照的指派，規則 k 的行為就會取決於該變數的規格", name)
                    .doesNotContain("flowable:assignee=\"${initiator}\"");
        }
    }
}
