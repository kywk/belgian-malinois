package com.bpm.core.lint;

import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 規則 k {@code optional-assignee} 的 <b>#91c 放寬</b>：
 * {@code flowable:assignee} 只要<b>參照到任一 required=false 的已宣告變數</b>就警告，
 * 不再只認「整個運算式就是 {@code ${name}}」。
 *
 * <h2>缺口（#91 漏報 ②）</h2>
 *
 * <p>{@code flowable:assignee="${dept}-01"}，其中 {@code dept} 已宣告且
 * {@code required=false}，而執行期為空} → 求值成 {@code "-01"}
 * —— 沒有人持有這個 id。這與純 {@code ${dept}} 求值成空白是<b>同一種靜默卡死</b>
 * （assignee 非 null、候選人看不到），但放寬前的規則 k 不會警告。
 *
 * <h2>不對稱是刻意的</h2>
 *
 * <p>放寬<b>只</b>套用在 {@code assignee}：它是<b>單一值</b>，任何一個未填的可選
 * 變數都會讓整個值變成無效 id（{@code "alice-"}、{@code "-01"}）。
 * {@code candidateUsers}／{@code candidateGroups} 是<b>多值</b>（逗號分隔），
 * 一個空白元素不會讓其餘候選人消失（{@code extractCandidates} 會把它拆成
 * {@code ["DEP01", ""]}），對它放寬只會製造<b>假警告</b>。
 * 完整理由寫在 {@code BpmnLintService.checkOptionalAssigneeVariable} 的 javadoc。
 *
 * <h2>負向控制組實測結果（2026-10-01，#91c）</h2>
 *
 * <p>做法：保留放寬後的全部程式碼，只把
 * {@code if (ASSIGNEE_ATTRIBUTE.equals(a.attribute()))} 這道分支< b>停用</b>
 * （讓 assignee 落回原本的「只認純參照」路徑），重跑本類別。
 * 刻意不動其他任何東西 —— 只停用放寬，不停用整個規則。
 *
 * <p><b>紅（3 條，實測 EXIT=1）</b>：
 * <ul>
 *   <li>{@code mixedAssigneeWithOptionalVariableWarns} —— 這是缺口本身，必須紅。</li>
 *   <li>{@code mixedAssigneeWarnsForOptionalNotRequiredVariable} —— 混合式中只有
 *       required=false 變數要警告；停用放寬後連這條也沒有警告。</li>
 *   <li>{@code repeatedOptionalVariableWarnsOnce} —— {@code ${dept}-${dept}} 也是
 *       混合式，停用放寬後 0 條警告而非 1 條。</li>
 * </ul>
 *
 * <p><b>綠（其餘 5 條 ＋ 既有 10 條）</b>：純參照、required=true、未宣告、
 * 候選欄位混合、平台變數混合等對照組。⚠️ 它們斷言的是「不得誤擋」，
 * 在放寬存在與不存在兩種狀態下都必須綠 —— <b>它們證明不了新判準有接上</b>，
 * 那由上面 3 條負責。
 *
 * <p>⚠️ 另一個要誠實記下的地方：{@code pureAssigneeStillWarnsExactlyOnce} 在
 * 負向控制組裡是<b>綠</b>的（因為純參照本來就會被舊路徑抓到）。它不能證明
 * 「沒有重複警告」是放寬帶來的，只能證明放寬<b>沒有讓它變成兩條</b>。
 */
class OptionalAssigneeMixedExpressionLintTest extends IntegrationTestBase {

    private static final String RULE = "optional-assignee";

    @Autowired private BpmnLintService lintService;
    @Autowired private ProcessVariableSpecRepository specRepo;

    /** 本測試建立的流程 key；每條測試一個，收尾時整批刪掉。 */
    private final List<String> createdProcessKeys = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        // 用衍生刪除（deleteAll(find…)）而非 @Modifying 的原生 bulk delete：
        // 本測試的 save() 讓 entity 還在 persistence context 裡，bulk delete 會丟
        // InvalidDataAccessApiUsage。理由與 OptionalAssigneeVariableLintTest 相同。
        for (String key : createdProcessKeys) {
            var specs = specRepo.findByProcessDefinitionKeyOrderByVariableName(key);
            if (!specs.isEmpty()) specRepo.deleteAll(specs);
        }
        createdProcessKeys.clear();
    }

    /**
     * 建立<b>只有本測試在用</b>的流程 key。
     *
     * <p>⚠️ 必須唯一：{@link IntegrationTestBase} 的容器是 static，所有測試共用
     * 同一個資料庫，而規則 k 以 {@code process id} 查
     * {@code bpm_process_variable_spec}。寫死 key 會被其他測試類別的規格汙染。
     */
    private String newProcessKey() {
        String key = "optassignee91c-" + UUID.randomUUID().toString().substring(0, 8);
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
     * 單一 UserTask 的 BPMN。{@code formKey} 用 {@code external:} 前綴，
     * 讓這些測試不依賴表單資料（見 {@code BpmnLintService} 規則 c）。
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

    private long ruleCount(String processKey, String attrs, String rule) {
        return ruleIds(processKey, attrs).stream().filter(rule::equals).count();
    }

    private BpmnLintService.LintError ruleOf(String processKey, String attrs, String rule) {
        return lintService.lint(userTaskXml(processKey, attrs)).errors().stream()
                .filter(e -> rule.equals(e.rule())).findFirst().orElseThrow(
                        () -> new AssertionError("沒有 rule=" + rule + " 的警告；實際規則："
                                + ruleIds(processKey, attrs)));
    }

    // ── 放寬後必須警告（缺口本身）─────────────────────────────────

    @Test
    @DisplayName("[缺口] 混合式 assignee 參照到 required=false 的變數 → 必須警告")
    void mixedAssigneeWithOptionalVariableWarns() {
        String key = newProcessKey();
        givenSpec(key, "dept", false);

        // 放寬前這條是綠的（規則不警告），這正是 #91 漏報 ②。
        assertThat(ruleIds(key, "flowable:assignee=\"${dept}-01\""))
                .as("dept 為空時 assignee 會變成 '-01'（沒有人持有的 id）—— 與純 ${dept} 同樣會靜默卡死")
                .contains(RULE);
    }

    @Test
    @DisplayName("混合式 assignee 只對 required=false 的那個變數警告（required=true 不承擔）")
    void mixedAssigneeWarnsForOptionalNotRequiredVariable() {
        String key = newProcessKey();
        givenSpec(key, "a", true);
        givenSpec(key, "dept", false);

        var rule = ruleOf(key, "flowable:assignee=\"${a}-${dept}\"", RULE);

        assertThat(rule.message())
                .as("訊息要指出是哪個欄位、哪個變數，否則管理員不知道要改哪裡")
                .contains("flowable:assignee", "dept");
    }

    // ── 純參照：放寬不得讓它變成兩條 ───────────────────────────────

    @Test
    @DisplayName("純參照 assignee 仍然警告，且只有一條（放寬的判準涵蓋純參照，不得重複）")
    void pureAssigneeStillWarnsExactlyOnce() {
        String key = newProcessKey();
        givenSpec(key, "dept", false);

        assertThat(ruleCount(key, "flowable:assignee=\"${dept}\"", RULE))
                .as("assignee 放寬後與原本的純參照判準共用同一條路徑，不得各發一次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("同一變數在 assignee 出現兩次 → 只警告一次")
    void repeatedOptionalVariableWarnsOnce() {
        String key = newProcessKey();
        givenSpec(key, "dept", false);

        assertThat(ruleCount(key, "flowable:assignee=\"${dept}-${dept}\"", RULE))
                .as("重複的 ${dept} 是同一個問題，重複警告只會稀釋它")
                .isEqualTo(1);
    }

    // ── 對照組：放寬後仍不得警告 ───────────────────────────────────

    @Test
    @DisplayName("混合式 assignee 全部是 required=true → 不得警告")
    void mixedAssigneeWithAllRequiredVariablesDoesNotWarn() {
        String key = newProcessKey();
        givenSpec(key, "a", true);
        givenSpec(key, "b", true);

        assertThat(ruleIds(key, "flowable:assignee=\"${a}-${b}\""))
                .as("required=true 的變數缺值會被 ExternalApiController 擋（400），執行期不會是空")
                .doesNotContain(RULE);
    }

    @Test
    @DisplayName("混合式 assignee 的變數未宣告 → 只有 undeclared-variable，沒有 optional-assignee")
    void mixedAssigneeWithUndeclaredVariableOnlyUndeclared() {
        String key = newProcessKey();

        assertThat(ruleIds(key, "flowable:assignee=\"${dept}-01\""))
                .as("未宣告變數由 undeclared-variable（error）負責，規則 k 不重複發警告")
                .doesNotContain(RULE)
                .contains("undeclared-variable");
    }

    @Test
    @DisplayName("混合式的 candidateGroups／candidateUsers → 不得警告（多值欄位的刻意不對稱）")
    void mixedCandidateFieldsDoNotWarn() {
        String key = newProcessKey();
        givenSpec(key, "dept", false);
        givenSpec(key, "other", false);

        assertThat(ruleIds(key, "flowable:candidateGroups=\"${dept},${other}\""))
                .as("逗號並接的多值欄位：一個空白元素不會讓其餘候選群組消失，警告會是假警告")
                .doesNotContain(RULE);
        assertThat(ruleIds(key, "flowable:candidateUsers=\"${dept},${other}\""))
                .as("candidateUsers 與 candidateGroups 同為多值欄位，判準一致")
                .doesNotContain(RULE);
    }

    @Test
    @DisplayName("混合式 assignee 只用平台變數 → 不得警告")
    void mixedAssigneeWithPlatformVariableDoesNotWarn() {
        String key = newProcessKey();

        assertThat(ruleIds(key, "flowable:assignee=\"${initiator}-01\""))
                .as("initiator 是平台保證存在的變數，不是選擇性的流程變數")
                .doesNotContain(RULE);
    }
}
