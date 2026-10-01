package com.bpm.core.engine;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #91 方向 B：指派層把求值為空白的 assignee 視為「未指定」。
 *
 * <h2>缺陷</h2>
 *
 * <p>{@code flowable:assignee="${dept}"} 而 {@code dept} 在執行期求值成空白
 * 字串時，assignee 會被寫成<b>空白字串，不是 null</b>
 * （{@code handleAssignments} 的 {@code isNotEmpty} 判斷的是運算式<b>字串</b>，
 * 不是求值<b>結果</b>）。Flowable 的候選查詢帶著 {@code RES.ASSIGNEE_ IS NULL}，
 * 所以候選人看不到那個任務 —— 任務建立成功、沒有例外、案件靜默卡死。
 * 這一條管理員部署的 BPMN 就能觸發，不經任何 API。
 *
 * <p>修法：全域的
 * {@link BlankAssigneeNormalizingInterceptor}（{@code CreateUserTaskInterceptor}）
 * 在 {@code handleAssignments} 之後把空白 assignee 收斂成 null。
 *
 * <h2>⚠️ 每一條缺陷測試都先斷言「候選人真的查得到」</h2>
 *
 * <p>只斷言 {@code task.getAssignee() == null} 不足以證明修的是對的東西 ——
 * 它可能只是把 assignee 空著、候選查詢仍不命中。所以每條都先斷言
 * {@code taskCandidateGroup(...).count() == 1}：<b>證實群組成員真的看得到</b>。
 *
 * <h2>⚠️ identity link 的實際狀態必須斷言，不能只斷 assignee</h2>
 *
 * <p>規格要求查證「正規化之後是否留下 userId 是空白字串的 assignee
 * identity link」。#89 的 {@code TaskService.setAssignee(taskId, "")} 路徑確實
 * 會建出這種 link（{@code AddIdentityLinkCmd}）。因此這裡直接查
 * {@code taskService.getIdentityLinksForTask(taskId)}，斷言
 * <b>沒有任何 link 的 userId 是空白</b>，並且<b>候選群組 link 仍在</b>。
 *
 * <h2>⚠️ 負向控制組（把 interceptor 停用後重跑）</h2>
 *
 * <p>停用方式：把 {@code BlankAssigneeNormalizingInterceptor.afterCreateUserTask}
 * 的方法體改成直接 {@code return}。結果記錄如下，<b>綠的那幾條不是漏抓，而是
 * 它們存在的理由</b>。
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅</td>
 *       <td>{@code blankExpressionAssigneeIsNormalizedAndCandidateCanSee}<br>
 *           {@code blankExpressionAssigneeWithoutCandidateKeepsAlert}</td>
 *       <td>兩條都在第一道斷言（assignee 必須為 null）就紅，實際是
 *           {@code expected: null but was: "  "} —— 證實「求值為空白 →
 *           非 null 的空白字串」正是被 interceptor 改掉的行為。
 *           ⚠️ 誠實註記：由於在第一道就失敗，後面的候選查詢 count 與告警
 *           reason 斷言<b>沒有被執行到</b>。缺陷期間候選 count 為 0 是
 *           #89 已實測的事實；無候選人那條若走到告警斷言，reason 會是
 *           {@code assignee-blocks-candidates}（assignee 非 null）而非正規化
 *           後的 {@code no-assignee-no-candidate}。</td></tr>
 *   <tr><td>🟢 綠</td>
 *       <td>{@code whitespaceLiteralAssigneeIsNull}<br>
 *           {@code humanAssigneeIsUnchanged}<br>
 *           {@code nullAssigneeStaysNullAndCandidateWorks}</td>
 *       <td>它們與本缺陷無關，缺陷期間就該綠：
 *           字面值 {@code " "} 被 {@code createExpression} 的 {@code trim()}
 *           收成空字串、再被 {@code isNotEmpty} 擋掉，本來就是 null；
 *           真人 assignee 與「本來就 null」都不該被動到。
 *           <b>重點是修好之後仍必須綠</b> —— 一個「把非空白 assignee 也清掉」
 *           的實作能讓紅的通過，卻會讓這兩條紅。兩組必須成組存在。</td></tr>
 * </table>
 */
class BlankAssigneeNormalizationTest extends IntegrationTestBase {

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;

    /** 候選群組名稱。查詢都帶 taskId，與其他測試共用不影響。 */
    private static final String GROUP = "b91b-dept-hr";

    private static final String BLANK_EXPR_WITH_CANDIDATE = "b91b-blank-expr-cand";
    private static final String BLANK_EXPR_NO_CANDIDATE = "b91b-blank-expr-nocand";
    private static final String BLANK_LITERAL = "b91b-blank-literal";
    private static final String HUMAN = "b91b-human";
    private static final String CANDIDATE_ONLY = "b91b-candidate-only";

    private static String bpmn(String key, String taskAttrs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="task"/>
                    <userTask id="task" name="測試關卡" %s/>
                    <sequenceFlow id="f1" sourceRef="task" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, taskAttrs);
    }

    @BeforeEach
    void deploy() {
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(BLANK_EXPR_WITH_CANDIDATE).count() == 0) {
            // 缺陷路徑 + 候選群組：運算式求值為空白。
            repositoryService.createDeployment().addString(BLANK_EXPR_WITH_CANDIDATE + ".bpmn20.xml",
                    bpmn(BLANK_EXPR_WITH_CANDIDATE,
                            "flowable:assignee=\"${dept}\" flowable:candidateGroups=\"" + GROUP + "\"")).deploy();
            // 缺陷路徑 + 沒有候選人：正規化後仍必須被 UnreachableTaskListener 告警。
            repositoryService.createDeployment().addString(BLANK_EXPR_NO_CANDIDATE + ".bpmn20.xml",
                    bpmn(BLANK_EXPR_NO_CANDIDATE, "flowable:assignee=\"${dept}\"")).deploy();
            // 字面值純空白：Flowable 的 createExpression 會 trim，本來就是 null（非缺陷路徑）。
            repositoryService.createDeployment().addString(BLANK_LITERAL + ".bpmn20.xml",
                    bpmn(BLANK_LITERAL,
                            "flowable:assignee=\" \" flowable:candidateGroups=\"" + GROUP + "\"")).deploy();
            // 對照組：真人 assignee 不得被動到。
            repositoryService.createDeployment().addString(HUMAN + ".bpmn20.xml",
                    bpmn(HUMAN, "flowable:assignee=\"mgr001\"")).deploy();
            // 對照組：本來就 null + 有候選群組，候選查詢必須正常。
            repositoryService.createDeployment().addString(CANDIDATE_ONLY + ".bpmn20.xml",
                    bpmn(CANDIDATE_ONLY, "flowable:candidateGroups=\"" + GROUP + "\"")).deploy();
        }
    }

    /** TASK_UNREACHABLE 告警的 reason 欄位（分辨三種成因）。 */
    private List<String> alertReasons(String pid) {
        var out = new ArrayList<String>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operation_type = 'TASK_UNREACHABLE' AND process_instance_id = ?")) {
                ps.setString(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String detail = rs.getString(1);
                        if (detail.contains("assignee-is-system-identity")) out.add("assignee-is-system-identity");
                        else if (detail.contains("assignee-blocks-candidates")) out.add("assignee-blocks-candidates");
                        else out.add("no-assignee-no-candidate");
                    }
                }
            }
        });
        return out;
    }

    /** 候選群組查得到這個任務嗎？0 = 群組成員在待辦清單裡看不到它。 */
    private long candidatesCanSee(String pid) {
        var t = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        return t == null ? -1 : taskService.createTaskQuery()
                .taskCandidateGroup(GROUP).taskId(t.getId()).count();
    }

    private static String firstTaskId(TaskService taskService, String pid) {
        return taskService.createTaskQuery().processInstanceId(pid).singleResult().getId();
    }

    // ── 缺陷：運算式求值為空白 ─────────────────────────────────────────

    @Test
    @DisplayName("#91B：assignee 運算式求值為空白 + 有候選群組 → 正規化成 null，候選人查得到，identity link 乾淨")
    void blankExpressionAssigneeIsNormalizedAndCandidateCanSee() {
        String pid = runtimeService.startProcessInstanceByKey(
                BLANK_EXPR_WITH_CANDIDATE, Map.of("dept", "  ")).getId();
        String taskId = firstTaskId(taskService, pid);

        assertThat(taskService.createTaskQuery().taskId(taskId).singleResult().getAssignee())
                .as("求值為空白的 assignee 必須被正規化成 null（不是空字串、不是純空白）")
                .isNull();

        assertThat(candidatesCanSee(pid))
                .as("正規化成 null 之後，候選群組成員必須真的看得到 —— "
                        + "只把 assignee 清成 null 但不命中候選查詢等於沒修")
                .isEqualTo(1);

        List<IdentityLink> links = taskService.getIdentityLinksForTask(taskId);
        // 用非空斷言：候選群組的 identity link 必須還在（正規化不得誤刪它）。
        assertThat(links)
                .as("候選群組 link 必須保留（identity link 不是空的）")
                .isNotEmpty();
        assertThat(links)
                .as("候選群組 link 的 groupId 必須是 " + GROUP)
                .anyMatch(l -> "candidate".equals(l.getType()) && GROUP.equals(l.getGroupId()));
        // ⚠️ 本項的核心查證：不得留下 userId 是空白的 assignee identity link。
        // 若 Flowable 未來版本在 handleAssignments 也建 task assignee link，
        // 這一條會抓到 —— 那時就要在 interceptor 裡清掉它。
        assertThat(links)
                .as("不得留下 userId 是空白的 identity link（#89 的 AddIdentityLinkCmd 會建出它）")
                .noneMatch(l -> l.getUserId() != null && l.getUserId().isBlank());

        assertThat(alertReasons(pid))
                .as("assignee 為 null 且候選人看得到 —— 有人能處理，不該告警")
                .isEmpty();
    }

    @Test
    @DisplayName("#91B：純空白 assignee + 沒有候選人 → 正規化後仍被 UnreachableTaskListener 告警（分工不變）")
    void blankExpressionAssigneeWithoutCandidateKeepsAlert() {
        String pid = runtimeService.startProcessInstanceByKey(
                BLANK_EXPR_NO_CANDIDATE, Map.of("dept", "  ")).getId();

        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("正規化後 assignee 是 null")
                .isNull();

        assertThat(alertReasons(pid))
                .as("沒有候選人時仍然沒有人能簽 —— listener 必須如實告警。"
                        + "正規化之後成因是 no-assignee-no-candidate（原本缺陷期間會是 "
                        + "assignee-blocks-candidates）")
                .containsExactly("no-assignee-no-candidate");
    }

    // ── 非缺陷路徑：字面值純空白 ──────────────────────────────────────

    @Test
    @DisplayName("釘住：BPMN 的 flowable:assignee=\" \" 字面值本來就是 null（Flowable 會 trim），不是缺陷路徑")
    void whitespaceLiteralAssigneeIsNull() {
        // createExpression 對所有運算式字串先 trim()，所以字面值 " " 收成 ""，
        // 再被 handleAssignments 的 isNotEmpty 擋掉 → assignee 維持 null。
        // 這一條在「停用 interceptor」的負向控制組中仍為綠 —— 記錄下來，見類別註解。
        String pid = runtimeService.startProcessInstanceByKey(BLANK_LITERAL, Map.of()).getId();

        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("字面值純空白本來就不會被寫入；若這條轉紅代表有人動了 Flowable 以外的地方")
                .isNull();
        assertThat(candidatesCanSee(pid)).as("assignee 為 null 時候選人看得到").isEqualTo(1);
    }

    // ── 非回歸對照組：必要的，因為「把非空白也清掉」也能讓缺陷那條綠 ──

    @Test
    @DisplayName("非回歸：真人 assignee 不得被動到（mgr001 原樣保留）")
    void humanAssigneeIsUnchanged() {
        String pid = runtimeService.startProcessInstanceByKey(HUMAN, Map.of()).getId();

        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("interceptor 只處理 isBlank()，非空白的真人 id 必須原樣保留")
                .isEqualTo("mgr001");
        assertThat(alertReasons(pid)).as("有人能簽，不該告警").isEmpty();
    }

    @Test
    @DisplayName("非回歸：原本就是 null 的 assignee 維持 null，candidateGroups 正常運作")
    void nullAssigneeStaysNullAndCandidateWorks() {
        String pid = runtimeService.startProcessInstanceByKey(CANDIDATE_ONLY, Map.of()).getId();

        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("本來是 null 的不得被改成別的值")
                .isNull();
        assertThat(candidatesCanSee(pid))
                .as("候選群組查詢必須正常（count=1）")
                .isEqualTo(1);
        assertThat(alertReasons(pid)).as("候選人看得到，不該告警").isEmpty();
    }
}
