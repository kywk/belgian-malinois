package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BPMN 運算式可取用的 bean 範圍（security-audit P0-2）。
 *
 * <p><b>問題。</b>{@code FlowableConfig} 只呼叫 {@code setEventListeners(...)}，
 * 沒有 {@code setBeans(...)}。Flowable 在 {@code beans} 未設定時<b>把整個
 * ApplicationContext 當成 EL 命名空間</b> → 執行期任何 Spring bean
 * （{@code taskService}、{@code dataSource}、{@code auditLogService}…）
 * 都能從 BPMN 運算式取用。
 *
 * <p>這一點推翻了 CLAUDE.md 原本「BPMN EL 白名單是唯一真正落實的授權邊界」
 * 的說法：{@code BpmnLintService} 的白名單只是<b>部署前的字串檢查</b>，
 * 執行期沒有任何限制。這是低程式碼平台 —— 業務人員能編輯 BPMN，
 * 等於能在應用權限下呼叫任意 bean。
 *
 * <p><b>本測試同時記錄修復的邊界。</b>{@code setBeans()} 關掉的是
 * 「任意 Spring bean」這條路，但<b>不會</b>關掉對字面值做反射的路
 * （{@code ${''.getClass().forName(...)}}）—— 那需要 lint 改用 AST 走訪
 * 並拒絕 getClass/forName 等 member access（審查建議的 (b)）。
 * {@link #reflectionOnLiteralsIsStillReachable()} 刻意記錄此現況，
 * 避免把本項誤認為已完整修復。
 */
class BpmnExpressionBeanScopeTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    /**
     * 產生一支最小流程：start → 條件閘道（帶待測運算式）→ end。
     * 運算式在啟動時就會被求值，因此「能不能取用該 bean」會立刻顯現。
     */
    private String deployWithCondition(String key, String expression) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="test">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="gw"/>
                    <exclusiveGateway id="gw"/>
                    <sequenceFlow id="f2" sourceRef="gw" targetRef="endA">
                      <conditionExpression xsi:type="tFormalExpression"
                           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><![CDATA[%s]]></conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="f3" sourceRef="gw" targetRef="endB"/>
                    <endEvent id="endA"/>
                    <endEvent id="endB"/>
                  </process>
                </definitions>
                """.formatted(key, key, expression);
        repositoryService.createDeployment()
                .addString(key + ".bpmn20.xml", xml)
                .name(key)
                .deploy();
        return key;
    }

    /**
     * 清單刻意<b>從 {@code BpmnLintService.EL_WHITELIST} 推導</b>，不是寫死（P2-7）。
     *
     * <p>lint 白名單與 {@code FlowableConfig.setBeans()} 是兩份各自維護的清單。
     * 若某個 bean 只加進 lint 白名單而忘了加進 {@code setBeans()}，
     * lint 會放行那支 BPMN，但執行期取不到那個 bean ——
     * 錯誤在<b>使用者送出簽核時</b>才出現，而不是部署時。
     *
     * <p>從白名單推導，新增 bean 時這個測試會自動涵蓋它，漏掉一邊就會紅。
     */
    @Test
    @DisplayName("lint 白名單內的每個 bean 都必須在執行期命名空間內")
    void whitelistedBeansRemainAvailable() {
        assertThat(com.bpm.core.lint.BpmnLintService.EL_WHITELIST)
                .as("白名單是空的，這個測試就成了空門")
                .isNotEmpty();

        for (String bean : com.bpm.core.lint.BpmnLintService.EL_WHITELIST) {
            String key = "beanok-" + bean.toLowerCase();
            deployWithCondition(key, "${" + bean + " != null}");
            // 能啟動且不拋例外 = 該 bean 在 EL 命名空間內
            assertThat(runtimeService.startProcessInstanceByKey(key))
                    .as("%s 在 lint 白名單內，但執行期取不到 —— FlowableConfig.setBeans() 漏了它", bean)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("白名單外的 Spring bean 不得可用（taskService／dataSource）")
    void nonWhitelistedBeansAreUnreachable() {
        // 這是本項修復的核心：改動前這兩個都能取用，
        // 等於 BPMN 編輯者可以直接操作引擎與資料庫連線。
        for (String bean : List.of("taskService", "dataSource")) {
            String key = "beanbad-" + bean.toLowerCase();
            deployWithCondition(key, "${" + bean + " != null}");
            assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey(key))
                    .as(bean + " 不得可從 BPMN 運算式取用")
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    @DisplayName("既有 BPMN 必須照常運作 —— notifyTaskListener 是硬性要求")
    void existingProcessesStillWork() {
        // purchase-approval.bpmn20.xml 用 delegateExpression="${notifyTaskListener}"。
        // setBeans() 的 map 若漏掉它，現有流程會在建立任務時立刻壞掉 ——
        // 這正是 CLAUDE.md 與升級計畫都特別點名的風險。
        var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "amount", 1000, "itemName", "測試品項"));
        assertThat(pi).isNotNull();
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count())
                .as("採購流程應產生主管審核任務（notifyTaskListener 必須解析成功）")
                .isGreaterThan(0);

        // leave-approval 用 ${orgService.getDirectManager(initiator)} 指派主管
        var pi2 = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        assertThat(taskService.createTaskQuery().processInstanceId(pi2.getId()).list())
                .as("請假流程的主管指派須成功（orgService 必須可用）")
                .isNotEmpty();
    }

    @Test
    @DisplayName("⚠️ 已知未修：對字面值做反射仍可達（需 lint AST 重寫，審查建議 (b)）")
    void reflectionOnLiteralsIsStillReachable() {
        // setBeans() 限制的是「哪些 bean 在命名空間內」，
        // 不會限制 EL 對字串字面值呼叫方法。因此這條路仍然通：
        //     ${''.getClass().forName('java.lang.Runtime')}
        // 本測試不斷言它被擋下（那會是假的），而是記錄現況，
        // 作為 lint AST 重寫完成後的變更偵測點。
        String key = "beanrefl";
        deployWithCondition(key, "${''.getClass() != null}");

        // 目前會成功 —— 這是缺陷而非期望行為。
        assertThat(runtimeService.startProcessInstanceByKey(key))
                .as("現況：字面值反射仍可達。lint 改 AST 走訪後此處應改為預期拋例外")
                .isNotNull();
    }
}
