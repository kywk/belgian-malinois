package com.bpm.core.engine;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #83 的第二部分：{@code UnreachableTaskListener} 的告警繞過。
 *
 * <h2>缺陷：判準寫錯了問題</h2>
 *
 * <p>listener 原本的條件是「{@code assignee} 為 null／空白才告警」。
 * 而補件關卡在缺陷期間的 assignee 是 {@code system:erp} ——
 * <b>非空白</b>，所以直接 return，不告警。
 *
 * <p>但 {@code system:erp} 沒有人能登入，所以沒有任何人看得到那個任務、
 * 沒有任何人能簽它，而沒有任何錯誤訊息。
 * 也就是說：<b>這個 listener 唯一存在的目的（讓靜默卡死變得可見）
 * 恰好在真正發生靜默卡死的那個形狀上失效。</b>
 *
 * <h2>⚠️ 負向控制組實測（2026-09-30，把缺陷整份還原後重跑）</h2>
 *
 * <p>6 條中 <b>3 紅 3 綠</b>。綠的那三條<b>不是漏抓</b>，而是這組測試存在的理由：
 * 它們是「不得過度告警」的對照組。
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅</td>
 *       <td>{@code systemAssigneeIsAlerted}<br>
 *           {@code systemAssigneeIsCaseInsensitive}<br>
 *           {@code candidateDoesNotRescueASystemAssignee}</td>
 *       <td>缺陷期間 assignee 非空白 → 直接 return → 一筆告警都沒有。
 *           這正是 #83 難被察覺的原因。</td></tr>
 *   <tr><td>🟢 綠</td>
 *       <td>{@code humanAssigneeIsNotAlerted}<br>
 *           {@code missingAssigneeStillUsesTheOriginalReason}<br>
 *           {@code candidateAddedInSameTransactionIsStillNotAlerted}</td>
 *       <td>它們與本缺陷無關（缺陷期間就該綠）。<b>重點是修好之後仍必須綠</b> ——
 *           一個「全部都告警」的實作能讓三條紅的通過，卻會讓這三條紅。
 *           兩組必須成組存在。</td></tr>
 * </table>
 *
 * <p>還原方式：<b>整份還原</b> {@code UnreachableTaskListener.java}（不是只把
 * 那一行改回去）。理由見 round-3 handoff 第 4.4 節 —— 把缺陷放回錯誤的位置會
 * 讓它反過來擋掉一切、測試全綠，等於沒驗到。
 */
class UnreachableSystemAssigneeAlertTest extends IntegrationTestBase {

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired @Qualifier("primaryTransactionManager") private PlatformTransactionManager primaryTx;

    private static final String SYS_ASSIGNEE = "b83-system-assignee";
    private static final String SYS_WITH_CANDIDATE = "b83-system-assignee-with-candidate";
    private static final String HUMAN_ASSIGNEE = "b83-human-assignee";

    /**
     * 產生一支最小流程。
     *
     * <p>{@code taskAttrs} 刻意用<b>字面量</b>而非運算式 —— 這個測試要驗的是
     * listener 的判準，不是 resolver 的邏輯。用字面值才能保證 assignee 一定是
     * {@code system:erp}，不會被運算式求值成別的值（那樣這個測試就測不到目標）。
     */
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
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(SYS_ASSIGNEE).count() == 0) {
            repositoryService.createDeployment().addString(SYS_ASSIGNEE + ".bpmn20.xml",
                    bpmn(SYS_ASSIGNEE, "flowable:assignee=\"system:erp\"")).deploy();
            // 候選人救不了系統身分的 assignee：Flowable 的 taskCandidateUser 帶著
            // ASSIGNEE_ IS NULL，所以有 assignee 時候選人根本看不到這個任務。
            repositoryService.createDeployment().addString(SYS_WITH_CANDIDATE + ".bpmn20.xml",
                    bpmn(SYS_WITH_CANDIDATE,
                            "flowable:assignee=\"system:erp\" flowable:candidateUsers=\"mgr001\"")).deploy();
            // 對照組：有真人 assignee → 絕對不能告警。
            repositoryService.createDeployment().addString(HUMAN_ASSIGNEE + ".bpmn20.xml",
                    bpmn(HUMAN_ASSIGNEE, "flowable:assignee=\"mgr001\"")).deploy();
        }
    }

    /** TASK_UNREACHABLE 告警的 detail（用 reason 欄位分辨成因）。 */
    private List<String> alertReasons(String pid) {
        var out = new ArrayList<String>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operation_type = 'TASK_UNREACHABLE' AND process_instance_id = ?")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                while (rs.next()) {
                    String detail = rs.getString(1);
                    out.add(detail.contains("assignee-is-system-identity")
                            ? "assignee-is-system-identity" : "no-assignee-no-candidate");
                }
            }
        });
        return out;
    }

    private String start(String key) {
        return runtimeService.startProcessInstanceByKey(key,
                Map.of("initiator", "system:erp")).getId();
    }

    @Test
    @DisplayName("#83：assignee 是 system:<id> → 必須告警（缺陷期間直接 return 不告警）")
    void systemAssigneeIsAlerted() {
        String pid = start(SYS_ASSIGNEE);

        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("前置條件：assignee 確實是系統身分")
                .isEqualTo("system:erp");

        assertThat(alertReasons(pid))
                .as("非空白的 assignee 不代表有人能簽。缺陷期間這一格是空的 —— "
                        + "這正是 #83 難被察覺的原因")
                .containsExactly("assignee-is-system-identity");
    }

    @Test
    @DisplayName("#83：大小寫不同的系統身分同樣告警（firstTaskAssignee 沒有驗證）")
    void systemAssigneeIsCaseInsensitive() {
        // 外部系統可以用 firstTaskAssignee 送出 "SYSTEM:x"（body 沒有任何驗證），
        // 所以只比對小寫前綴會留下一個繞道。
        String key = "b83-system-assignee-upper";
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count() == 0) {
            repositoryService.createDeployment().addString(key + ".bpmn20.xml",
                    bpmn(key, "flowable:assignee=\"SYSTEM:erp\"")).deploy();
        }
        String pid = start(key);

        assertThat(alertReasons(pid)).containsExactly("assignee-is-system-identity");
    }

    @Test
    @DisplayName("#83：候選人救不了系統身分的 assignee（有 assignee 時候選人查不到）")
    void candidateDoesNotRescueASystemAssignee() {
        String pid = start(SYS_WITH_CANDIDATE);

        // 這條是本判準最容易寫錯的地方。直覺上「有候選人就有人能處理」，
        // 但 Flowable 的 taskCandidateUser 查詢帶著 ASSIGNEE_ IS NULL ——
        // assignee 一旦非 null，候選人在待辦清單裡看不到這個任務。
        // 若這裡誤判成「有人能處理」，缺陷就會從這條路徑复活。
        assertThat(alertReasons(pid))
                .as("assignee 與候選人互斥，不是互補")
                .containsExactly("assignee-is-system-identity");
    }

    @Test
    @DisplayName("對照：真人 assignee → 不得告警（否則上面三條就是「全部都報」）")
    void humanAssigneeIsNotAlerted() {
        String pid = start(HUMAN_ASSIGNEE);

        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .isEqualTo("mgr001");
        assertThat(alertReasons(pid))
                .as("mgr001 登入進來就看得到、也簽得掉 —— 這不是「沒有人看得到」")
                .isEmpty();
    }

    @Test
    @DisplayName("對照：原本的判準仍然成立（無 assignee 仍走 no-assignee 分支）")
    void missingAssigneeStillUsesTheOriginalReason() {
        String key = "b83-no-assignee";
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count() == 0) {
            repositoryService.createDeployment().addString(key + ".bpmn20.xml",
                    bpmn(key, "")).deploy();
        }
        String pid = start(key);

        assertThat(alertReasons(pid))
                .as("新增的判準不得改掉既有告警的成因標記 —— 監控靠它分流")
                .containsExactly("no-assignee-no-candidate");
    }

    @Test
    @DisplayName("對照：同一交易內補上候選人 → 不得告警（外部 API 的合法模式）")
    void candidateAddedInSameTransactionIsStillNotAlerted() {
        String key = "b83-no-assignee-then-candidate";
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count() == 0) {
            repositoryService.createDeployment().addString(key + ".bpmn20.xml",
                    bpmn(key, "")).deploy();
        }
        String pid = new TransactionTemplate(primaryTx).execute(status -> {
            String id = runtimeService.startProcessInstanceByKey(key,
                    Map.of("initiator", "system:erp")).getId();
            String taskId = taskService.createTaskQuery().processInstanceId(id).singleResult().getId();
            taskService.addCandidateGroup(taskId, "dept-hr");
            return id;
        });

        assertThat(alertReasons(pid))
                .as("建立當下沒有候選人，但 commit 時有 —— 這是合法模式，告警就是誤報。"
                        + "這條與 UnreachableTaskAlertTest 的同款案例重疊，是刻意的："
                        + "新判準不得為了抓系統身分而把這個誤報加回來")
                .isEmpty();
    }
}
