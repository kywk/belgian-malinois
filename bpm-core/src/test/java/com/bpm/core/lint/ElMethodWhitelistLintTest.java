package com.bpm.core.lint;

import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 規則 g 的方法層檢查 {@code el-method-whitelist}（#35）。
 *
 * <h2>它補的是什麼洞</h2>
 *
 * <p>改動前規則 g 只驗 bean 名稱：{@code ${orgService.getDirectManger(initiator)}}
 * （拼錯）部署綠燈，錯誤留到第一個送件的人 —— 執行期
 * {@code Unknown property used in expression}。這正是
 * {@link DesignerExpressionLintTest} 類別註解描述的「失敗時間與編輯時間脫鉤」。
 *
 * <h2>為什麼這些斷言落在 lint 而不是執行期</h2>
 *
 * <p>本規則的價值全部在部署前：執行期的錯誤訊息（Flowable 的 property
 * not found）與 lint 的訊息指向同一個問題，但前者已經有案件卡在流程裡。
 * 所以這裡只驗 {@code lintService.lint()} 的輸出。
 *
 * <h2>與 bean 層規則的分工（本測試要釘死的邊界）</h2>
 *
 * <ul>
 *   <li>bean 不在 {@code EL_WHITELIST} → 只有既有的 {@code el-whitelist}，
 *       <b>不</b>加方法層錯誤。</li>
 *   <li>bean 在白名單但方法不存在 → {@code el-method-whitelist}。</li>
 *   <li>裸用 {@code ${bean}} → 維持現狀（規則 i 處理，本規則不碰）。</li>
 * </ul>
 *
 * <p>測試 BPMN 的 {@code formKey} 用 {@code external:none}：那是規則 c
 * 明確跳過表單查詢的分支，讓斷言不被表單資料庫狀態干擾（與
 * {@link OptionalAssigneeVariableLintTest} 同一手法）。process key 每條測試
 * 都帶亂數：{@link IntegrationTestBase} 的容器是 static、所有測試共用
 * 資料庫，寫死 key 會讓其他測試類別建立的 {@code bpm_external_system}
 * 授權（規則 h）污染這裡。
 */
class ElMethodWhitelistLintTest extends IntegrationTestBase {

    private static final String RULE = "el-method-whitelist";

    @Autowired private BpmnLintService lintService;

    /** 每條測試一個唯一 process key，避免共用資料庫的規則 h 狀態互相污染。 */
    private static String newProcessKey() {
        return "elmethod35-" + UUID.randomUUID().toString().substring(0, 8);
    }

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

    private List<BpmnLintService.LintError> lint(String attrs) {
        return lintService.lint(userTaskXml(newProcessKey(), attrs)).errors();
    }

    private List<String> ruleIds(String attrs) {
        return lint(attrs).stream().map(BpmnLintService.LintError::rule).toList();
    }

    // ── 合法方法：不得產生方法層錯誤 ───────────────────────────────

    @Test
    @DisplayName("實際 BPMN 在用的合法方法一律放行（否則就是誤擋）")
    void knownGoodMethodsAreAccepted() {
        // 這些是出廠 BPMN、設計器產生器、規格 §4.1 與既有測試實際使用的形狀。
        // 逐一列出而不是抽樣：任一個被擋下都會讓現有流程部署不了。
        for (String expr : List.of(
                "${orgService.getDirectManager(initiator)}",
                "${orgService.getManagerAtLevel(initiator, 2)}",
                "${orgService.getManagerChain(initiator, 3)}",
                "${orgService.getDeptId(initiator)}",
                "${permService.getUsersByPermission('finance:payment:approve')}",
                "${assigneeResolver.resolve(execution)}",
                "${applicantResolver.resolve(execution)}",
                "${dynamicAssignee.managerAtLevel(initiator, 2)}",
                "${dynamicAssignee.firstAvailable('finance:payment:approve')}",
                "${dynamicAssignee.managerWithPermission(initiator, 'legal:contract:review')}")) {
            assertThat(ruleIds("flowable:assignee=\"" + expr + "\""))
                    .as("%s 被方法層規則誤擋", expr)
                    .doesNotContain(RULE);
        }
    }

    @Test
    @DisplayName("參數含逗號的 ${bean.method(a, b)} 不得被解析成別的方法")
    void commaSeparatedArgumentsDoNotConfuseTheParser() {
        // regex 只抓 bean 與方法名，參數不參與比對 —— 這條釘住
        // 「getManagerAtLevel 不會因為 ", 2" 而被當成別的東西」。
        assertThat(ruleIds("flowable:candidateUsers=\"${orgService.getManagerChain(initiator, 3)}\""))
                .doesNotContain(RULE);
        assertThat(ruleIds("flowable:assignee=\"${orgService.getManagerAtLevel(initiator, 2)}\""))
                .doesNotContain(RULE);
    }

    // ── 非法方法：必須是 error，訊息指名 bean.method ───────────────

    @Test
    @DisplayName("拼錯的方法名必須在部署時擋下，訊息含 bean.method 與允許清單")
    void misspelledMethodIsRejectedWithActionableMessage() {
        // getDirectManger（少一個 a）是改動前會部署成功的形狀。
        var errors = lint("flowable:assignee=\"${orgService.getDirectManger(initiator)}\"");
        var e = errors.stream().filter(x -> RULE.equals(x.rule())).findFirst().orElseThrow(
                () -> new AssertionError("沒有 " + RULE + " 錯誤；實際規則："
                        + errors.stream().map(BpmnLintService.LintError::rule).toList()));

        assertThat(e.severity())
                .as("warning 不會讓 POST /api/deployments 擋下，這條規則就沒有意義")
                .isEqualTo("error");
        assertThat(e.message())
                .as("訊息必須讓設計者知道是哪個 bean 的哪個方法")
                .contains("orgService.getDirectManger");
        assertThat(e.message())
                .as("允許清單要能直接照著改")
                .contains("getDirectManager");
        assertThat(lintService.lint(userTaskXml(newProcessKey(),
                "flowable:assignee=\"${orgService.getDirectManger(initiator)}\"")).valid())
                .as("valid=false 才是部署端點擋下的實際條件")
                .isFalse();
    }

    @Test
    @DisplayName("多個 ${} 在同一運算式：合法與非法各自獨立判斷")
    void multipleExpressionsAreCheckedIndividually() {
        // 合法項不該掩蓋非法項，非法項也不該讓合法項被誤報。
        var errors = lint("flowable:candidateUsers=\""
                + "${orgService.getDeptId(initiator)},${orgService.noSuchMethod(initiator)}\"");
        var methodErrors = errors.stream().filter(x -> RULE.equals(x.rule())).toList();

        assertThat(methodErrors)
                .as("兩個呼叫應該只有一個被擋；實際：%s", errors)
                .hasSize(1);
        assertThat(methodErrors.getFirst().message()).contains("orgService.noSuchMethod");
    }

    @Test
    @DisplayName("同一個字串裡非白名單 bean 與方法拼錯並存時，兩種錯誤各自成立")
    void beanLevelAndMethodLevelErrorsCoexist() {
        var errors = lint("flowable:assignee=\""
                + "${callbackService.resolve('x')} ${orgService.noSuchMethod(initiator)}\"");

        assertThat(errors.stream().map(BpmnLintService.LintError::rule))
                .contains("el-whitelist", RULE);
    }

    // ── 邊界：bean 層與裸用的既有行為不變 ─────────────────────────

    @Test
    @DisplayName("非白名單 bean 的方法呼叫不新增方法層錯誤（bean 層既有檢查不變）")
    void nonWhitelistedBeanKeepsOnlyBeanLevelError() {
        for (String expr : List.of(
                "${callbackService.resolve('x')}",
                // 方法名不存在也一樣：bean 都不在清單裡，沒有方法清單可比對。
                "${callbackService.noSuchMethod('x')}")) {
            assertThat(ruleIds("flowable:assignee=\"" + expr + "\""))
                    .as("%s 應該只有 bean 層錯誤", expr)
                    .contains("el-whitelist")
                    .doesNotContain(RULE);
        }
    }

    @Test
    @DisplayName("${bean.} 沒有方法名時，bean 層既有錯誤仍要照發（regex 改動的回歸保護）")
    void beanLevelCheckSurvivesTheOptionalMethodGroup() {
        // 原本的 regex \$\{(\w+)\. 對 ${callbackService.} 會 match 並擋 bean。
        // 新的 regex 多了可選的方法名 group，不可因此漏掉 bean 層。
        assertThat(ruleIds("flowable:assignee=\"${callbackService.}\""))
                .contains("el-whitelist")
                .doesNotContain(RULE);
    }

    @Test
    @DisplayName("裸用 ${bean} 維持現狀：不因本次改動新增方法層錯誤")
    void bareBeanReferenceIsUnchanged() {
        // ${orgService} 由規則 i（undeclared-variable）處理；本規則不碰它。
        assertThat(ruleIds("flowable:assignee=\"${orgService}\""))
                .doesNotContain(RULE);
    }

    @Test
    @DisplayName("白名單 bean 的任意成員存取（${orgService.getClass()}）也被方法層擋下")
    void arbitraryMemberAccessOnWhitelistedBeanIsCaught() {
        // 這是逐方法白名單的副作用：security-audit P2-5 指出 bean 層 regex 只驗
        // 鏈的第一節，所以 ${orgService.getClass().forName(...)} 原本照樣通過。
        // 逐方法之後第一節必須是業務方法，反射起手式（getClass 等）不再是
        // 合法方法名。⚠️ 更長的鏈（合法方法之後再接 .getClass()）與字面值反射
        // （${''.getClass()}）仍繞得過 —— 那些要 AST 走訪（P2-5 (b)），不在本項。
        assertThat(ruleIds("flowable:assignee=\"${orgService.getClass()}\""))
                .contains(RULE);
    }

    // ── 排除清單：維運方法與未實作 stub 必須擋下 ─────────────────

    @Test
    @DisplayName("維運方法與 P1-7 的未實作 stub 必須在部署時擋下，不是執行期才爆")
    void excludedMethodsAreRejected() {
        // 三個形狀各有理由（見 BpmnLintService.EL_METHOD_EXCLUDED 的註解）：
        // invalidateCache 是管理端點在用的維運 API；另外兩個是 security-audit
        // P1-7 刻意留成「永遠拋例外」的 stub —— audit 對它們的修法就是
        // 「實作前應直接 throw，或加進 lint 的 error 規則」，本規則兌現後者。
        for (String expr : List.of(
                "${orgService.getAuthorizedManager(initiator, 100)}",
                "${orgService.invalidateCache(initiator, 'all')}",
                "${permService.getUsersByPermissionAndCondition('hr:approve', attrs)}")) {
            var errors = lint("flowable:assignee=\"" + expr + "\"");
            assertThat(errors.stream().map(BpmnLintService.LintError::rule))
                    .as("%s 應該被方法層規則擋下", expr)
                    .contains(RULE);
        }
    }
}
