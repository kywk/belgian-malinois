package com.bpm.core.lint;

import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 設計器產生的運算式必須在<b>部署時</b>被檢查出來（security-audit P2-6）。
 *
 * <h2>為什麼「部署時」是重點</h2>
 *
 * <p>這一組缺陷的共同特徵不是「會失敗」，而是<b>失敗的時間點與造成它的那次
 * 編輯完全脫鉤</b>。流程設計者在設計器裡選好審核對象、按部署、看到綠燈，
 * 問題留給第一個送件的人 —— 而那個人看到的是一個看不懂的引擎錯誤，
 * 或者更糟：什麼都沒發生。
 *
 * <p>所以這裡斷言的是 lint 的輸出，不是執行期行為。
 */
class DesignerExpressionLintTest extends IntegrationTestBase {

    @Autowired private BpmnLintService lintService;

    private static String userTaskXml(String attrs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="lint">
                  <process id="lintprobe" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                    <userTask id="t" name="審核" flowable:formKey="leave-review" %s/>
                    <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(attrs);
    }

    private java.util.List<String> ruleIds(String attrs) {
        return lintService.lint(userTaskXml(attrs)).errors().stream()
                .map(BpmnLintService.LintError::rule).toList();
    }

    // ── 規則 j：主管鏈索引 ─────────────────────────────────────────

    @Test
    @DisplayName("對 getManagerChain 直接做索引必須在部署時被擋下")
    void managerChainIndexingIsRejected() {
        // 這是設計器舊版對「直屬主管（N階）」產生的運算式，預設 N=2。
        // 執行期的後果不是拋錯而是 assignee=null（JUEL 對越界索引回 null，
        // 已實測）→ 任務對所有人都不可見，永遠卡在引擎裡。
        assertThat(ruleIds("flowable:assignee=\"${orgService.getManagerChain(initiator, 2)[1]}\""))
                .contains("manager-chain-index");
    }

    @Test
    @DisplayName("getManagerAtLevel 必須放行 —— 否則上面的斷言只是「全都擋掉」")
    void safeAccessorIsAccepted() {
        assertThat(ruleIds("flowable:assignee=\"${orgService.getManagerAtLevel(initiator, 2)}\""))
                .doesNotContain("manager-chain-index", "el-whitelist", "undeclared-variable");
    }

    @Test
    @DisplayName("不做索引的 getManagerChain 仍可使用（回傳整個清單是合法用途）")
    void nonIndexedManagerChainIsStillAllowed() {
        assertThat(ruleIds("flowable:candidateUsers=\"${orgService.getManagerChain(initiator, 3)}\""))
                .doesNotContain("manager-chain-index");
    }

    // ── 規則 i：裸變數參照 ─────────────────────────────────────────

    @Test
    @DisplayName("未宣告的裸變數參照必須在部署時被擋下")
    void undeclaredBareVariableIsRejected() {
        // ${dept} 是設計器舊版對「特定單位」的預設值；${dept001} 是使用者
        // 輸入部門代碼後被包成的樣子。兩者都不是任何地方設定的流程變數，
        // 執行期會拋 Unknown property used in expression（已實測）。
        assertThat(ruleIds("flowable:candidateGroups=\"${dept}\""))
                .contains("undeclared-variable");
        assertThat(ruleIds("flowable:candidateGroups=\"${dept001}\""))
                .contains("undeclared-variable");
    }

    @Test
    @DisplayName("字面值的部門代碼必須放行（設計器修正後的形式）")
    void literalDepartmentCodeIsAccepted() {
        assertThat(ruleIds("flowable:candidateGroups=\"dept001\""))
                .doesNotContain("undeclared-variable", "el-whitelist");
    }

    @Test
    @DisplayName("平台變數必須放行")
    void platformVariablesAreAccepted() {
        assertThat(ruleIds("flowable:assignee=\"${initiator}\""))
                .doesNotContain("undeclared-variable");
    }

    @Test
    @DisplayName("bean 呼叫不得被裸變數規則誤擋")
    void beanCallsAreNotFlaggedAsBareVariables() {
        // 規則 i 只看 ${名稱} 這種「名稱後面直接是 }」的形式。
        // 有點的由白名單規則處理，兩者不可互相干擾。
        assertThat(ruleIds("flowable:assignee=\"${orgService.getDirectManager(initiator)}\""))
                .doesNotContain("undeclared-variable");
    }

    // ── 規則 g：不存在的 bean ──────────────────────────────────────

    @Test
    @DisplayName("callbackService 不在白名單內 —— 設計器已移除該選項，但舊圖仍要被擋")
    void callbackServiceIsStillRejected() {
        // 後端完全沒有這個 bean。設計器原本提供「特定 Callback」選項，
        // 業務人員選了、填好名稱、按部署，只會拿到白名單錯誤。
        // 選項已移除，但既有的圖仍必須被擋下。
        assertThat(ruleIds("flowable:assignee=\"${callbackService.resolve('x')}\""))
                .contains("el-whitelist");
    }
}
