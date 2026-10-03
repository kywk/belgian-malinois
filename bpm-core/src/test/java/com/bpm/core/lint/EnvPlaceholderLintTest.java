package com.bpm.core.lint;

import com.bpm.core.external.ExternalSystemPolicy;
import com.bpm.core.form.service.FormService;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * lint 對部署期環境變數佔位符 {@code ${ENV_*}} 的接受度（backlog #53，spec §12.3）—— 純單元測試。
 *
 * <h2>為什麼 lint 必須放行（而不是留給部署端）</h2>
 *
 * <p>{@code /api/bpmn/lint} 是設計器按「送審」時走的同一個入口，而部署端
 * （{@code POST /api/deployments}）會再 lint 一次原始 XML。兩者對 {@code ENV}
 * 的接受度若不一致，會出現兩種都壞的結果：
 * <ul>
 *   <li>lint 擋、部署放行 → 設計器紅燈但部署成功，訊息與事實脫鉤。</li>
 *   <li>lint 放行、部署擋 → 使用者被一個他無法在設計器裡解決的錯誤擋下。</li>
 * </ul>
 *
 * <p>改動前 {@code ${ENV_FINANCE_GROUP}} 會被規則 i（裸變數參照）判成
 * {@code undeclared-variable} —— 它是 {@code \$\{(\w+)\}}，與未宣告的流程變數
 * 形狀一模一樣。而它的語意不是「參照流程變數」，是「部署期會被替換成字面值」。
 *
 * <h2>豁免必須剛好等於命名空間</h2>
 *
 * <p>本測試同時釘住豁免的邊界：{@code ENV_[A-Z0-9_]+} 之外一律維持原本的
 * 未宣告變數錯誤。豁免放寬成「以 ENV_ 開頭」或「含底線」都會讓真正的
 * 拼錯（{@code ${env_x}}、{@code ${ENV_}}）漏到執行期才爆。
 *
 * <p>與 {@code WebhookPayloadTemplateLintTest} 同一手法：純 mock，
 * 不起 Testcontainers —— 被測的路徑只讀 BPMN 內容，四個依賴都不會被走到
 * （無 serviceTask、無流程變數規格、外部系統清單為空、formKey 用 external:）。
 */
class EnvPlaceholderLintTest {

    private static final BpmnLintService LINT = new BpmnLintService(
            mock(FormService.class),
            mock(ProcessVariableSpecRepository.class),
            mock(ExternalSystemRepository.class),
            mock(ExternalSystemPolicy.class));

    /** 每條測試一個唯一 process key，避免規則 h 的殘留狀態（本組不起 context，仍保守處理）。 */
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

    private static List<String> errorRules(String attrs) {
        var result = LINT.lint(userTaskXml("envlint", attrs));
        return result.errors().stream()
                .filter(e -> "error".equals(e.severity()))
                .map(BpmnLintService.LintError::rule).toList();
    }

    // ── 合法佔位符：不得被任何 error 規則擋下 ───────────────────────

    @Test
    @DisplayName("${ENV_FINANCE_GROUP} 放在 candidateGroups 必須放行（spec §12.3 的樣張形狀）")
    void envPlaceholderInCandidateGroupsIsAccepted() {
        assertThat(errorRules("flowable:candidateGroups=\"${ENV_FINANCE_GROUP}\""))
                .as("ENV 佔位符不是流程變數參照，部署時會被替換成字面值")
                .doesNotContain("undeclared-variable", "el-whitelist", "el-method-whitelist");
    }

    @Test
    @DisplayName("${ENV_*} 放在 assignee 必須放行")
    void envPlaceholderInAssigneeIsAccepted() {
        assertThat(errorRules("flowable:assignee=\"${ENV_FINANCE_APPROVER}\""))
                .doesNotContain("undeclared-variable", "el-whitelist", "el-method-whitelist");
    }

    @Test
    @DisplayName("${ENV_*} 放在 candidateUsers 必須放行")
    void envPlaceholderInCandidateUsersIsAccepted() {
        assertThat(errorRules("flowable:candidateUsers=\"${ENV_HR_GROUP},hr_other\""))
                .doesNotContain("undeclared-variable", "el-whitelist", "el-method-whitelist");
    }

    @Test
    @DisplayName("整份 lint 結果必須 valid —— 部署端只檢查 valid()")
    void placeholderDocumentIsValid() {
        // 這才是「部署不會被擋」的實際條件：DeploymentController 只看 valid()。
        assertThat(LINT.lint(userTaskXml("envlintvalid",
                "flowable:candidateGroups=\"${ENV_FINANCE_GROUP}\"")).valid())
                .isTrue();
    }

    // ── 豁免邊界：不在命名空間內的一律維持原本的錯誤 ────────────────

    @Test
    @DisplayName("${ENV_}（空名）不是合法佔位符，必須維持未宣告變數錯誤")
    void emptyEnvNameIsNotExempt() {
        // 替換端（BpmnEnvSubstitutor）不認它；放行的話會部署一份執行期
        // 求值 ${ENV_} 的 BPMN，錯誤延後到第一個送件的人。
        assertThat(errorRules("flowable:candidateGroups=\"${ENV_}\""))
                .contains("undeclared-variable");
    }

    @Test
    @DisplayName("${env_finance_group}（小寫）不在命名空間內，必須維持未宣告變數錯誤")
    void lowercaseEnvNameIsNotExempt() {
        assertThat(errorRules("flowable:candidateGroups=\"${env_finance_group}\""))
                .contains("undeclared-variable");
    }

    @Test
    @DisplayName("${ENV_X.foo} 是 EL 呼叫而非佔位符，必須被 bean 白名單擋下")
    void dottedEnvNameIsStillAnElCall() {
        // 替換端的 pattern 要求名稱後直接是 }；有點的形狀替換不到，
        // 放行等於部署一份執行期才爆的 BPMN。
        assertThat(errorRules("flowable:candidateGroups=\"${ENV_FINANCE_GROUP.foo}\""))
                .contains("el-whitelist");
    }

    @Test
    @DisplayName("一般未宣告的裸變數仍必須被擋（豁免沒有整條規則關掉）")
    void ordinaryBareVariableIsStillRejected() {
        assertThat(errorRules("flowable:candidateGroups=\"${dept}\""))
                .contains("undeclared-variable");
    }
}
