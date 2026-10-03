package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.NotifyTestSink;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #6：催辦成功必須留下 {@code TASK_URGE} 稽核。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <pre>
 *   user001 催辦自己的請假單 → 200、通知真的送出
 *   → bpm_audit_log 完全沒有 TASK_URGE
 *   → OperationType.TASK_URGE 仍躺在 NOT_YET_IMPLEMENTED
 * </pre>
 *
 * <p>這不是「操作還沒實作」，是「操作在跑但沒留軌跡」—— 稽核缺口中
 * 最糟的一類：功能正常，所以只有稽核調查時才會發現查不到。
 * {@code OperationTypeCoverageTest} 無法單獨抓到它（值列在
 * {@code NOT_YET_IMPLEMENTED} 就合法），所以本檔直接驗資料庫。
 *
 * <h2>⚠️ 每一條拒絕都配「沒有 TASK_URGE」斷言</h2>
 *
 * <p>被拒的請求不得宣稱一件沒發生的事。403（參與者非申請人）與 429
 * （冷卻中）已在 {@code NotifyTriggerIntegrationTest} 驗過零通知；
 * 這裡補上「零稽核」。403 仍會有一筆 {@code DATA_ACCESS denied}
 * —— 那是既有授權留痕，不是 TASK_URGE。
 *
 * <h2>⚠️ detail 只放非敏感資訊</h2>
 *
 * <p>斷言 detail 的鍵<b>恰好</b>是冷卻分鐘數、任務數、收件人數
 * —— 催辦的稽核要回答「誰在何時催了哪張單、催了幾個對象」，
 * 不是複製案件內容。{@code recipients} 名單（誰被催辦，屬個資）、
 * 任務名稱、表單值與簽核意見都不在裡面。
 *
 * <h2>負向控制組實測（2026-10-02，拿掉 urgeTask 的 TASK_URGE publish、其餘不動）</h2>
 *
 * <p><b>4 條中 2 紅 2 綠。</b>紅的是成功催辦的兩條
 * （{@link #successfulUrgeWritesAuditWithNonSensitiveDetail} 與
 * {@link #cooldownUrgeWritesNoSecondAudit}）—— 資料庫查不到任何 TASK_URGE，
 * 正是本工項要修的缺口。同一個缺陷版的通知仍照常送出（成功那條的
 * 前置斷言是綠的），證明紅燈不是「催辦壞了」而是「催辦沒留痕」。
 *
 * <p>綠的是 403／404 兩條拒絕：拒絕路徑在缺陷期間本來就碰不到 publish，
 * 修好之後也必須維持「被拒不得宣告發生」。
 *
 * <p>還原方式：{@code command cp} 修好的 {@code TaskController.java}
 * 蓋回工作區，重跑後再移除 publish 做控制組。
 */
class TaskUrgeAuditTest extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

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

    // ── 工具 ────────────────────────────────────────────────────────

    /** 一張 leave-approval 的單，停在主管審核（assignee = mgr001）。 */
    private record Case(String pid, String managerTaskId) {}

    private Case startLeave() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核的持有者必須是 mgr001")
                .isEqualTo("mgr001");
        return new Case(pi.getId(), manager.getId());
    }

    private void urge(String pid, String user) throws Exception {
        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", user)
                        .param("processInstanceId", pid))
                .andExpect(status().isOk());
    }

    /** 這個案件的 TASK_URGE 稽核列：{operator_id, detail}，依 id。 */
    private List<String[]> urgeAuditRows(String pid) {
        List<String[]> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operator_id, detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'TASK_URGE' AND process_instance_id = ? "
                            + "ORDER BY id")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        });
        return out;
    }

    // ── 正向：成功催辦要留痕 ────────────────────────────────────────

    @Test
    @DisplayName("#6：成功催辦 → 一筆 TASK_URGE，operator=呼叫者、detail 只有非敏感統計")
    void successfulUrgeWritesAuditWithNonSensitiveDetail() throws Exception {
        Case c = startLeave();

        urge(c.pid(), "user001");

        // 非空斷言 (1)：通知真的送出了（改動前也送得出，這條不是本次修的行為）
        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "task_urged"))
                .as("前置條件：催辦必須真的送出通知，否則稽核測的是別的路徑")
                .hasSize(1);

        // 非空斷言 (2)：稽核真的在資料庫裡
        List<String[]> rows = urgeAuditRows(c.pid());
        assertThat(rows)
                .as("催辦成功的唯一證據現在必須是這筆紀錄；沒有它，"
                        + "「誰催過這張單」在稽核上不存在")
                .hasSize(1);
        assertThat(rows.get(0)[0])
                .as("operator 必須是呼叫者（催辦是申請人的動作，不是任務持有者）")
                .isEqualTo("user001");

        Map<String, Object> detail = MAPPER.readValue(rows.get(0)[1], new TypeReference<>() {});
        assertThat(detail)
                .as("detail 只放冷卻分鐘數、任務數、收件人數 —— 鍵恰好這三個")
                .containsOnlyKeys("cooldownMinutes", "taskCount", "recipientCount")
                .containsEntry("cooldownMinutes", 30)
                .containsEntry("taskCount", 1)
                .containsEntry("recipientCount", 1);
        assertThat(rows.get(0)[1])
                .as("不得複製案件內容：表單值、簽核意見、收件人名單都不在 detail 裡")
                .doesNotContain("leaveType", "days", "approved", "comment", "mgr001");
    }

    // ── 拒絕：不得留下 TASK_URGE ────────────────────────────────────

    @Test
    @DisplayName("#6：參與者但不是申請人催辦 → 403，不得寫 TASK_URGE")
    void forbiddenUrgeWritesNoTaskUrgeAudit() throws Exception {
        Case c = startLeave();

        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", "mgr001")
                        .param("processInstanceId", c.pid()))
                .andExpect(status().isForbidden());

        assertThat(urgeAuditRows(c.pid()))
                .as("被拒的請求不得宣告一件沒發生的催辦")
                .isEmpty();
    }

    @Test
    @DisplayName("#6：冷卻中的第二次催辦 → 429，不得寫第二筆 TASK_URGE")
    void cooldownUrgeWritesNoSecondAudit() throws Exception {
        Case c = startLeave();
        urge(c.pid(), "user001");

        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", "user001")
                        .param("processInstanceId", c.pid()))
                .andExpect(status().isTooManyRequests());

        assertThat(urgeAuditRows(c.pid()))
                .as("429 沒有送出通知，也就不該多一筆稽核（成功那次的一筆仍在）")
                .hasSize(1);
    }

    @Test
    @DisplayName("#6：對不存在的案件催辦 → 404，不得寫 TASK_URGE")
    void unknownProcessWritesNoTaskUrgeAudit() throws Exception {
        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", "user001")
                        .param("processInstanceId", "no-such-process-instance"))
                .andExpect(status().isNotFound());

        assertThat(urgeAuditRows("no-such-process-instance")).isEmpty();
    }
}
