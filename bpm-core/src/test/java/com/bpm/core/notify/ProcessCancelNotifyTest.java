package com.bpm.core.notify;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.NotifyTestSink;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #7 殘餘收尾：撤回時通知現任受理人（{@code process_cancelled}）。
 *
 * <h2>為什麼每一條正向斷言都要「訊息真的出現」</h2>
 *
 * <p>撤回通知的失敗型態是「什麼都不發生」：撤回照樣成功、沒有例外。
 * 所以「案件被刪掉了」不能當成通知成功的證據 —— 改動前的程式碼在那些
 * 斷言下也會全綠。每條測試因此都直接看 {@link NotifyTestSink} 收到的訊息，
 * 並把「案件真的撤回」與「通知真的送出」分成兩個非空斷言。
 *
 * <h2>收件人與發送時機（實作決策的驗證）</h2>
 *
 * <ul>
 *   <li><b>每任務一則</b>：平行關卡時各任務的受理人不同（
 *       {@link #cancelNotifiesEachParallelTaskSeparately} 驗證一案兩則）。
 *       與催辦的每任務一則一致。</li>
 *   <li><b>收件人在刪除前收集、發送在刪除後</b>：任務隨實例消失。
 *       被拒路徑（403／409）因此零通知，且案件必須仍在 runtime。</li>
 *   <li><b>候選群組略過</b>：群組沒有 email
 *       （{@link #cancelWithGroupOnlyCandidatesSendsNothing}），
 *       沒有可送對象就不送空訊息。</li>
 * </ul>
 *
 * <h2>P2-1：payload 不含敏感欄位</h2>
 *
 * <p>{@link #cancelNotifiesAssigneeExactlyOnce} 把鍵<b>恰好</b>釘住：
 * 沒有流程變數、沒有表單內容、沒有撤回原因（{@code reason} 是呼叫端
 * 提供的自由文字，可能夾帶個資）。
 *
 * <h2>負向控制組（2026-10-03 實測）</h2>
 *
 * <p>把 {@code ProcessController.cancelProcess} 的 ⑦ 通知迴圈整段移除
 * （其餘不動），跑本類別＋{@code ProcessCancelTest}：
 *
 * <ol>
 *   <li><b>本類別 6 條中 3 紅 3 綠。</b>紅的是三條正向斷言：
 *       {@link #cancelNotifiesAssigneeExactlyOnce}（0 則）、
 *       {@link #cancelNotifiesEachParallelTaskSeparately}（0 則）、
 *       {@link #cancelNotifiesCandidateUsers}（0 則）。綠的是
 *       {@link #cancelWithGroupOnlyCandidatesSendsNothing}、
 *       {@link #forbiddenCancelSendsNothing}、{@link #conflictCancelSendsNothing}
 *       —— 三條都是「預期零通知」的負向斷言，缺陷期間本來就會綠。</li>
 *   <li><b>{@code ProcessCancelTest} 全綠。</b>撤回路徑本身（授權、409、
 *       稽核）不受通知影響，證明紅燈是「通知沒發」而不是「撤回壞了」。</li>
 * </ol>
 *
 * <p><b>這組控制組證明不了什麼</b>：三條綠的負向測試對「通知完全不存在」
 * 與「通知正確地不發」無法分辨；{@code EmailConsumer} 的 switch 少了
 * {@code process_cancelled} 時，本類別也全部照綠（payload 照樣送上
 * RabbitMQ）—— 那個缺口由 {@code EmailConsumerCancelTest} 的單元測試守住。
 */
class ProcessCancelNotifyTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void setUp() {
        NotifyTestSink.install(rabbitTemplate, amqpAdmin);
        NotifyTestSink.reset();
        truncateAuditLog();
    }

    // ── 情境與工具 ──────────────────────────────────────────────────

    private record Case(String pid, String managerTaskId) {}

    /** 一張 leave-approval 的單，停在主管審核（assignee = mgr001）。 */
    private Case startLeave() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核的持有者必須是 mgr001")
                .isEqualTo("mgr001");
        return new Case(pi.getId(), manager.getId());
    }

    /** 部署一支單一 UserTask 的流程；屬性由呼叫端給（候選人／群組）。 */
    private String deployUserTaskProcess(String userTaskAttrs) {
        String key = "pcn-" + UUID.randomUUID().toString().substring(0, 8);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/process-cancel-notify-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="審核關卡"%s/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, userTaskAttrs);
        repositoryService.createDeployment()
                .name(key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    /** 部署一支平行關卡的流程：兩個任務各有自己的 assignee。 */
    private String deployParallelProcess() {
        String key = "pcn-par-" + UUID.randomUUID().toString().substring(0, 8);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/process-cancel-notify-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
                    <parallelGateway id="fork"/>
                    <sequenceFlow id="f2" sourceRef="fork" targetRef="approveA"/>
                    <sequenceFlow id="f3" sourceRef="fork" targetRef="approveB"/>
                    <userTask id="approveA" name="關卡A" flowable:assignee="mgr001"/>
                    <userTask id="approveB" name="關卡B" flowable:assignee="mgr002"/>
                    <sequenceFlow id="f4" sourceRef="approveA" targetRef="endA"/>
                    <sequenceFlow id="f5" sourceRef="approveB" targetRef="endB"/>
                    <endEvent id="endA"/>
                    <endEvent id="endB"/>
                  </process>
                </definitions>
                """.formatted(key);
        repositoryService.createDeployment()
                .name(key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    private String start(String key) {
        return runtimeService.startProcessInstanceByKey(key,
                Map.of("initiator", "user001")).getId();
    }

    private ResultActions cancel(String pid) throws Exception {
        return cancel(pid, "user001");
    }

    private ResultActions cancel(String pid, String user) throws Exception {
        return mockMvc.perform(post("/api/process-instances/{id}/cancel", pid)
                .header("X-User-Id", user));
    }

    private boolean runtimeStillExists(String pid) {
        return runtimeService.createProcessInstanceQuery().processInstanceId(pid).count() > 0;
    }

    /** 這個案件的 PROCESS_CANCEL 稽核列：{operator_id, detail}，依 id。 */
    private List<String[]> cancelAuditRows(String pid) {
        List<String[]> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operator_id, detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'PROCESS_CANCEL' AND process_instance_id = ? "
                            + "ORDER BY id")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        });
        return out;
    }

    // ── a) 有 assignee：恰一則，且 payload 鍵恰好是非敏感那一組 ──────

    @Test
    @DisplayName("#7 a) 有 assignee 的案件撤回 → 受理人恰收一則 process_cancelled；"
            + "案件撤回成功、稽核仍在")
    void cancelNotifiesAssigneeExactlyOnce() throws Exception {
        Case c = startLeave();
        // startLeave 的任務建立本身會發 task_assigned；先清空，只觀察撤回。
        NotifyTestSink.reset();

        cancel(c.pid())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        // 非空斷言 (1)：案件真的撤回（通知只是副作用）
        assertThat(runtimeStillExists(c.pid()))
                .as("撤回必須成功；通知失敗不影響撤回，通知成功也不影響")
                .isFalse();

        // 非空斷言 (2)：訊息真的出現（沒有這條，「完全沒發通知」也會綠）
        List<Map<String, Object>> cancelled =
                NotifyTestSink.events(NotifyTestSink.drain(), "process_cancelled");
        assertThat(cancelled).hasSize(1);
        Map<String, Object> msg = cancelled.get(0);
        assertThat(msg)
                .containsEntry("assignee", "mgr001")
                .containsEntry("taskId", c.managerTaskId())
                .containsEntry("processInstanceId", c.pid())
                .containsEntry("processDefinitionKey", "leave-approval")
                .containsEntry("initiator", "user001")
                // P2-1：鍵恰好是這一組 —— 沒有變數、沒有撤回原因、沒有表單內容。
                .containsOnlyKeys("event", "timestamp", "taskId", "taskName",
                        "processInstanceId", "processDefinitionKey", "assignee", "initiator");

        // 通知與稽核互不影響：通知 fail-open，稽核仍要 fail-closed 地寫進去。
        assertThat(cancelAuditRows(c.pid()))
                .as("通知不得讓稽核消失，也不得寫出第二筆")
                .hasSize(1);
    }

    // ── a2) 平行關卡：每任務一則，各送自己的受理人 ───────────────────

    @Test
    @DisplayName("#7 a2) 平行關卡兩任務 → 一案兩則，各自送給自己的受理人")
    void cancelNotifiesEachParallelTaskSeparately() throws Exception {
        String pid = start(deployParallelProcess());
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pid).list();
        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(Task::getAssignee)
                .containsExactlyInAnyOrder("mgr001", "mgr002");
        NotifyTestSink.reset();

        cancel(pid).andExpect(status().isOk());

        List<Map<String, Object>> cancelled =
                NotifyTestSink.events(NotifyTestSink.drain(), "process_cancelled");
        assertThat(cancelled)
                .as("平行關卡各任務受理人不同，一則合併信無法回答『我的哪個任務消失了』")
                .hasSize(2);
        assertThat(cancelled).extracting(m -> m.get("assignee"))
                .containsExactlyInAnyOrder("mgr001", "mgr002");
        assertThat(cancelled).extracting(m -> m.get("taskId"))
                .containsExactlyInAnyOrderElementsOf(tasks.stream().map(Task::getId).toList());
        assertThat(runtimeStillExists(pid)).isFalse();
    }

    // ── b) 候選任務：候選人收到；無人可送則零通知 ───────────────────

    @Test
    @DisplayName("#7 b) 候選任務（無 assignee）撤回 → 一則、candidateUsers 放候選人")
    @SuppressWarnings("unchecked")
    void cancelNotifiesCandidateUsers() throws Exception {
        String pid = start(deployUserTaskProcess(
                " flowable:candidateUsers=\"mgr001,mgr002\""));
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).as("前置條件：候選任務沒有 assignee").isNull();
        NotifyTestSink.reset();

        cancel(pid).andExpect(status().isOk());

        List<Map<String, Object>> cancelled =
                NotifyTestSink.events(NotifyTestSink.drain(), "process_cancelled");
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.get(0)).doesNotContainKey("assignee");
        assertThat((List<String>) cancelled.get(0).get("candidateUsers"))
                .as("候選『人』才收得到信；EmailConsumer 對 candidateUsers 逐人寄送")
                .containsExactlyInAnyOrder("mgr001", "mgr002");
        assertThat(runtimeStillExists(pid)).isFalse();
    }

    @Test
    @DisplayName("#7 b2) 只有候選群組（沒有 email）→ 零通知；案件仍撤回、稽核仍在")
    void cancelWithGroupOnlyCandidatesSendsNothing() throws Exception {
        String pid = start(deployUserTaskProcess(" flowable:candidateGroups=\"managers\""));
        NotifyTestSink.reset();

        cancel(pid).andExpect(status().isOk());

        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "process_cancelled"))
                .as("群組沒有 email，沒有可送對象就不送空訊息")
                .isEmpty();
        assertThat(runtimeStillExists(pid))
                .as("沒有人可通知不影響撤回本身")
                .isFalse();
        assertThat(cancelAuditRows(pid)).hasSize(1);
    }

    // ── c) 被拒路徑：零通知 ─────────────────────────────────────────

    @Test
    @DisplayName("#7 c) 參與者非申請人（403）→ 零通知；案件仍在 runtime")
    void forbiddenCancelSendsNothing() throws Exception {
        Case c = startLeave();
        NotifyTestSink.reset();

        cancel(c.pid(), "mgr001").andExpect(status().isForbidden());

        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "process_cancelled"))
                .as("被拒的撤回不得通知受理人 —— 否則受理人會以為案件真的沒了")
                .isEmpty();
        assertThat(runtimeStillExists(c.pid())).isTrue();
    }

    @Test
    @DisplayName("#7 c) 已有完成任務（409）→ 零通知；案件仍在 runtime")
    void conflictCancelSendsNothing() throws Exception {
        Case c = startLeave();
        // 完成主管關卡會觸發 process_returned（補件通知）；先清掉，
        // 讓 drain 看到的只有撤回路徑的訊息。
        taskService.complete(c.managerTaskId(), Map.of("approved", false, "rejected", false));
        NotifyTestSink.reset();

        cancel(c.pid()).andExpect(status().isConflict());

        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "process_cancelled"))
                .as("409 的撤回不得宣告案件已被撤回")
                .isEmpty();
        assertThat(runtimeStillExists(c.pid())).isTrue();
    }
}
