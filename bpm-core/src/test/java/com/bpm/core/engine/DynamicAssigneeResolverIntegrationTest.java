package com.bpm.core.engine;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #47 端到端：{@code dynamicAssignee} 在真實引擎內求值。
 *
 * <h2>本測試組在證明什麼</h2>
 *
 * <ol>
 *   <li><b>bean 真的在執行期命名空間內</b>：BPMN 的
 *       {@code flowable:assignee="${dynamicAssignee.…}"} 能建立任務 ——
 *       lint 白名單與 {@code FlowableConfig.setBeans()} 兩份清單都必須同步
 *       （少一邊：前者擋部署、後者讓使用者送出時才爆）。</li>
 *   <li><b>每個 UserTask 建立時各自求值</b>：兩個連續任務分別用第 1、2 階，
 *       第二關是在第一關完成後才建立的 —— 若運算式只在啟動時求值一次，
 *       第二關拿不到不同的值。這正是「執行期動態」的意義。</li>
 *   <li><b>找不到人不是靜默卡死</b>：鏈頂沒有主管、鏈上沒人持有權限碼，
 *       都讓啟動大聲失敗且交易回滾，不留下 assignee 為 null 的任務。</li>
 * </ol>
 *
 * <p>代理人代換（休假中的人改派代理人）需要組織系統有代理人資料，
 * 而 dev fixture 的 {@code MockOrgController} 固定回「沒有代理人」——
 * 那條路徑由 {@code DynamicAssigneeResolverTest} 的單元測試釘住。
 * 本測試證明的是引擎接線與挑人結果。
 *
 * <p>BPMN 的 {@code formKey} 用 {@code leave-review}（V5 migration 已 seed），
 * 讓測試 BPMN 也能通過 lint 的 {@code formkey-exists} 規則。
 */
class DynamicAssigneeResolverIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private com.bpm.core.lint.BpmnLintService lintService;

    /**
     * 把 {@code body} 包成一支最小流程（start → body → end），
     * 先過 lint 再部署。
     *
     * <p>lint 必須綠：這同時驗證 {@code dynamicAssignee} 已在
     * {@code BpmnLintService.EL_WHITELIST} 內。
     */
    private String deploy(String key, String body) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/dynamic-assignee-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    %s
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, body);

        var lint = lintService.lint(xml);
        assertThat(lint.valid())
                .as("測試 BPMN 必須通過 lint（EL 白名單需含 dynamicAssignee），實際錯誤：%s",
                        lint.errors())
                .isTrue();

        repositoryService.createDeployment()
                .name("dynamicassignee-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    /** 單一 UserTask 的流程內容。 */
    private static String singleTask(String assigneeExpr) {
        return """
                <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <userTask id="review" name="審核"
                          flowable:assignee="%s" flowable:formKey="leave-review"/>
                <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                """.formatted(assigneeExpr);
    }

    @Test
    @DisplayName("#47 第 N 階主管運算式在每個 UserTask 建立時各自求值")
    void managerAtLevelIsEvaluatedPerTask() {
        String key = deploy("dyn-manager-level", """
                <sequenceFlow id="f1" sourceRef="start" targetRef="review1"/>
                <userTask id="review1" name="一階主管審核"
                          flowable:assignee="${dynamicAssignee.managerAtLevel(initiator, 1)}"
                          flowable:formKey="leave-review"/>
                <sequenceFlow id="f2" sourceRef="review1" targetRef="review2"/>
                <userTask id="review2" name="二階主管審核"
                          flowable:assignee="${dynamicAssignee.managerAtLevel(initiator, 2)}"
                          flowable:formKey="leave-review"/>
                <sequenceFlow id="f3" sourceRef="review2" targetRef="end"/>
                """);

        var instance = runtimeService.startProcessInstanceByKey(key, Map.of("initiator", "user001"));

        Task first = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(first.getAssignee())
                .as("user001 的第 1 階主管是 mgr001")
                .isEqualTo("mgr001");

        taskService.complete(first.getId());

        Task second = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(second.getTaskDefinitionKey()).isEqualTo("review2");
        assertThat(second.getAssignee())
                .as("第 2 階＝dir001；第二關是完成第一關後才建立的，"
                        + "證明運算式在該任務建立當下求值，不是啟動時算一次")
                .isEqualTo("dir001");
    }

    @Test
    @DisplayName("#47 權限碼持有人第一位可受理 → 直接指派給 mgr001（不是候選清單）")
    void firstAvailableAssignsPickedUser() {
        String key = deploy("dyn-first-available", singleTask(
                "${dynamicAssignee.firstAvailable('finance:payment:approve')}"));

        var instance = runtimeService.startProcessInstanceByKey(key, Map.of("initiator", "user001"));

        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(task.getAssignee())
                .as("finance:payment:approve 的持有人依序是 mgr001、dir001；兩人都沒有代理人，"
                        + "取第一位")
                .isEqualTo("mgr001");
    }

    @Test
    @DisplayName("#47 主管鏈＋權限 → 指派給鏈上持有權限碼的 dir001")
    void managerWithPermissionAssignsMatchingManager() {
        String key = deploy("dyn-manager-perm", singleTask(
                "${dynamicAssignee.managerWithPermission(initiator, 'legal:contract:review')}"));

        var instance = runtimeService.startProcessInstanceByKey(key, Map.of("initiator", "user001"));

        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(task.getAssignee())
                .as("mgr001 沒有 legal:contract:review，dir001 有 —— 應跳過 mgr001")
                .isEqualTo("dir001");
    }

    @Test
    @DisplayName("#47 鏈上沒有人持有權限碼 → 啟動大聲失敗，不留孤兒流程")
    void managerWithPermissionWithoutMatchFailsLoudly() {
        String key = deploy("dyn-manager-perm-none", singleTask(
                "${dynamicAssignee.managerWithPermission(initiator, 'no:such:code')}"));

        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey(
                key, Map.of("initiator", "user001")))
                .as("回 null 會產生一個 assignee 為空、沒有候選人的任務 —— 對所有人都不可見")
                .isInstanceOf(FlowableException.class)
                .hasStackTraceContaining("no:such:code")
                .hasStackTraceContaining("沒有人持有")
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey(key).count())
                .as("失敗的啟動必須回滾，不能留下沒有受理人的案件")
                .isZero();
    }

    @Test
    @DisplayName("#47 鏈頂人員沒有主管 → 啟動大聲失敗，不回 null")
    void managerAtLevelWithoutManagerFailsLoudly() {
        String key = deploy("dyn-manager-none", singleTask(
                "${dynamicAssignee.managerAtLevel(initiator, 1)}"));

        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey(
                key, Map.of("initiator", "dir001")))
                .as("dir001 是鏈頂，組織系統回空鏈；沒有任何人可以簽，必須當場失敗")
                .isInstanceOf(FlowableException.class)
                .hasStackTraceContaining("沒有任何主管")
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey(key).count())
                .as("失敗的啟動必須回滾，不能留下沒有受理人的案件")
                .isZero();
    }
}
