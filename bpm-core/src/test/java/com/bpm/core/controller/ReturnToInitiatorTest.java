package com.bpm.core.controller;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.NotifyTestSink;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工項 #1：退回申請人（{@code complete + returnTo=initiator}）。
 *
 * <h2>規格（spec §4.3）</h2>
 *
 * <p>「退回申請人 = complete + returnTo=initiator → 回起點」，與
 * {@code approved=false} 的「退回上一站」是兩個不同意義的操作，
 * 即使兩者的 BPMN 結果都是 {@code approved=false}。
 *
 * <h2>路由設計（本測試釘住的行為）</h2>
 *
 * <ul>
 *   <li>{@code leave-approval}：退回申請人與一般退回都走 gw1 的 default
 *       到 {@code applicantRevision} —— 本來就是回起點，BPMN 未加分支。
 *       差別只在稽核型別。</li>
 *   <li>{@code purchase-approval}：gw2（財務）新增
 *       {@code flowReturnToInitiator} 條件分支到 {@code revisionFromManager}
 *       —— 一般退回走 default 到 {@code revisionFromFinance}（上一站），
 *       退回申請人走新分支回起點。gw1（主管）的 default 本來就回
 *       {@code revisionFromManager}，未加分支。</li>
 * </ul>
 *
 * <h2>⚠️ 變數生命週期：{@code returnTo} 每輪重寫</h2>
 *
 * <p>{@link #returnToDoesNotLeakIntoTheNextRound} 是這個設計的防護門：
 * BPMN 閘道讀的是<b>流程變數</b>，若 {@code returnTo} 只在退回申請人時
 * 寫入，前一輪留下的 {@code initiator} 會讓下一輪的一般退回被 gw2 誤判成
 * 退回申請人。TaskController 因此在每一次 complete 都重寫它
 * （非退回申請人時寫空字串）。
 *
 * <h2>負向控制組（實測見交付報告）</h2>
 *
 * <p>把 gw2 的 {@code flowReturnToInitiator} 移除 →
 * {@link #financeReturnToInitiatorLandsOnManagerRevision} 與
 * {@link #returnToDoesNotLeakIntoTheNextRound} 的前半變紅（退回落到
 * {@code revisionFromFinance}）；把 returnTo 的驗證移除 → 四條 400 測試
 * 變紅（{@link #clientVariableCannotSetReturnTo} 仍綠：它擋在
 * {@code PROTECTED_VARIABLES}，與 returnTo 驗證是兩套獨立機制）。
 * 其餘在缺陷期間本來就會綠 —— 它們證明的是「不該變的沒變」。
 */
class ReturnToInitiatorTest extends IntegrationTestBase {

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

    // ── fixture／工具 ───────────────────────────────────────────────

    /** 一張 leave-approval 的單，停在主管審核（assignee = mgr001）。 */
    private record Leave(String pid, String managerTaskId) {}

    /** 一張 purchase-approval 的單，停在主管審核（assignee = mgr001）。 */
    private record Purchase(String pid, String managerTaskId) {}

    private Leave startLeave() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核的持有者必須是 mgr001")
                .isEqualTo("mgr001");
        return new Leave(pi.getId(), manager.getId());
    }

    private Purchase startPurchase() {
        var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "amount", 1000, "itemName", "測試"));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核的持有者必須是 mgr001")
                .isEqualTo("mgr001");
        return new Purchase(pi.getId(), manager.getId());
    }

    /** 送出 complete 並要求 200。 */
    private void complete(String taskId, String user, String json) throws Exception {
        mockMvc.perform(put("/api/tasks/{id}", taskId)
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    /** 案件目前唯一的待處理任務。 */
    private Task currentTask(String pid) {
        return taskService.createTaskQuery().processInstanceId(pid).singleResult();
    }

    private boolean ended(String pid) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(pid).count() == 0;
    }

    /** 這個案件的稽核列：{operation_type, operator_id}，依 id。 */
    private List<String[]> auditRows(String pid) {
        List<String[]> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operation_type, operator_id FROM bpm_audit_log "
                            + "WHERE process_instance_id = ? ORDER BY id")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        });
        return out;
    }

    // ── a) purchase：財務退回申請人 → 回起點（revisionFromManager）──

    @Test
    @DisplayName("#1 a) 財務關卡 returnTo=initiator → 落到 revisionFromManager（不是 revisionFromFinance），流程仍在跑")
    void financeReturnToInitiatorLandsOnManagerRevision() throws Exception {
        Purchase p = startPurchase();
        // 主管核准 → 財務關卡（候選人 mgr001／dir001）。
        complete(p.managerTaskId(), "mgr001",
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":true}]}");
        Task finance = currentTask(p.pid());
        assertThat(finance.getName()).as("前置條件：已到財務關卡").contains("財務");

        // 財務退回申請人：body 只帶 returnTo，approved／rejected 由伺服器寫入
        // —— 若伺服器漏寫，gw2 的 EL 會找不到變數而爆掉，這條測試就會紅。
        complete(finance.getId(), "dir001", "{\"action\":\"complete\",\"returnTo\":\"initiator\"}");

        Task revision = currentTask(p.pid());
        assertThat(revision.getTaskDefinitionKey())
                .as("退回申請人必須回起點（申請者補件（主管退回）），"
                        + "而不是財務的上一站（revisionFromFinance）")
                .isEqualTo("revisionFromManager");
        assertThat(revision.getName()).contains("補件", "主管退回");
        assertThat(revision.getAssignee()).isEqualTo("user001");
        assertThat(ended(p.pid())).as("退回是流程中事件，案件必須仍在跑").isFalse();
    }

    // ── b) leave：退回申請人 → applicantRevision（與原退回同路徑）──

    @Test
    @DisplayName("#1 b) leave 主管 returnTo=initiator → applicantRevision（預設路徑本來就是回起點）")
    void leaveReturnToInitiatorLandsOnApplicantRevision() throws Exception {
        Leave l = startLeave();

        complete(l.managerTaskId(), "mgr001", "{\"action\":\"complete\",\"returnTo\":\"initiator\"}");

        Task revision = currentTask(l.pid());
        assertThat(revision.getTaskDefinitionKey()).isEqualTo("applicantRevision");
        assertThat(revision.getAssignee()).isEqualTo("user001");
        assertThat(ended(l.pid())).isFalse();
    }

    // ── c) 一般退回行為不變（財務 → revisionFromFinance）────────────

    @Test
    @DisplayName("#1 c) 一般退回（approved=false、無 returnTo）行為完全不變：財務 → revisionFromFinance")
    void plainReturnStillLandsOnFinanceRevision() throws Exception {
        Purchase p = startPurchase();
        complete(p.managerTaskId(), "mgr001",
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":true}]}");
        Task finance = currentTask(p.pid());

        complete(finance.getId(), "dir001",
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":false}]}");

        Task revision = currentTask(p.pid());
        assertThat(revision.getTaskDefinitionKey())
                .as("沒有 returnTo 時不得被路由成退回申請人")
                .isEqualTo("revisionFromFinance");
        assertThat(revision.getName()).contains("補件", "財務退回");
    }

    // ── d) 驗證：語意衝突／未知值 → 400 且零副作用 ──────────────────

    @Test
    @DisplayName("#1 d) rejected=true + returnTo=initiator → 400，任務不動、無稽核")
    void rejectedPlusReturnToIsRejectedWithoutSideEffects() throws Exception {
        Leave l = startLeave();

        mockMvc.perform(put("/api/tasks/{id}", l.managerTaskId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"returnTo\":\"initiator\","
                                + "\"variables\":[{\"name\":\"rejected\",\"value\":true}]}"))
                .andExpect(status().isBadRequest());

        assertThat(taskService.createTaskQuery().taskId(l.managerTaskId()).singleResult())
                .as("400 必須零副作用：任務不得被完成")
                .isNotNull();
        assertThat(currentTask(l.pid()).getTaskDefinitionKey()).isEqualTo("managerReview");
        assertThat(auditRows(l.pid()))
                .as("被拒的請求不得留下任何稽核（尤其不得是 TASK_RETURN_INITIATOR）")
                .isEmpty();
    }

    @Test
    @DisplayName("#1 d) approved=true + returnTo=initiator → 400，任務不動、無稽核")
    void approvedPlusReturnToIsRejectedWithoutSideEffects() throws Exception {
        Leave l = startLeave();

        mockMvc.perform(put("/api/tasks/{id}", l.managerTaskId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"returnTo\":\"initiator\","
                                + "\"variables\":[{\"name\":\"approved\",\"value\":true}]}"))
                .andExpect(status().isBadRequest());

        assertThat(taskService.createTaskQuery().taskId(l.managerTaskId()).singleResult()).isNotNull();
        assertThat(auditRows(l.pid())).isEmpty();
    }

    @Test
    @DisplayName("#1 d) returnTo=foo → 400 並指名支援的值，任務不動、無稽核")
    void unknownReturnToValueIsRejected() throws Exception {
        Leave l = startLeave();

        mockMvc.perform(put("/api/tasks/{id}", l.managerTaskId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"returnTo\":\"foo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .as("錯誤訊息必須指名唯一支援的值，呼叫端才知道要改什麼")
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("initiator"));

        assertThat(taskService.createTaskQuery().taskId(l.managerTaskId()).singleResult()).isNotNull();
        assertThat(auditRows(l.pid())).isEmpty();
    }

    @Test
    @DisplayName("#1 d) 非 complete 的 action 帶 returnTo → 400，不靜默忽略")
    void returnToOnNonCompleteActionIsRejected() throws Exception {
        Leave l = startLeave();

        mockMvc.perform(put("/api/tasks/{id}", l.managerTaskId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"claim\",\"returnTo\":\"initiator\"}"))
                .andExpect(status().isBadRequest());

        assertThat(currentTask(l.pid()).getAssignee())
                .as("claim 不得因為夾帶 returnTo 而被執行")
                .isEqualTo("mgr001");
    }

    @Test
    @DisplayName("#1 d) client 不得用 variables 寫 returnTo（受保護變數）→ 400，無稽核")
    void clientVariableCannotSetReturnTo() throws Exception {
        Leave l = startLeave();

        mockMvc.perform(put("/api/tasks/{id}", l.managerTaskId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\","
                                + "\"variables\":[{\"name\":\"returnTo\",\"value\":\"initiator\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("returnTo"));

        assertThat(currentTask(l.pid()).getTaskDefinitionKey())
                .as("繞道 variables 不得把任務路由成退回申請人")
                .isEqualTo("managerReview");
        assertThat(auditRows(l.pid())).isEmpty();
    }

    // ── e) 稽核型別 ─────────────────────────────────────────────────

    @Test
    @DisplayName("#1 e) returnTo=initiator 成功 → TASK_RETURN_INITIATOR（不是 TASK_RETURN）")
    void returnToWritesReturnInitiatorAudit() throws Exception {
        Leave l = startLeave();

        complete(l.managerTaskId(), "mgr001", "{\"action\":\"complete\",\"returnTo\":\"initiator\"}");

        List<String[]> rows = auditRows(l.pid());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("TASK_RETURN_INITIATOR");
        assertThat(rows.get(0)[1]).isEqualTo("mgr001");
    }

    @Test
    @DisplayName("#1 e) 一般退回仍寫既有的 TASK_RETURN")
    void plainReturnWritesTaskReturnAudit() throws Exception {
        Leave l = startLeave();

        complete(l.managerTaskId(), "mgr001",
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":false}]}");

        List<String[]> rows = auditRows(l.pid());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("TASK_RETURN");
    }

    // ── f) 通知：恰好一則 process_returned ──────────────────────────

    @Test
    @DisplayName("#1 f) returnTo=initiator → 申請人恰一則 process_returned（不雙發、不漏發）")
    void returnToSendsExactlyOneProcessReturned() throws Exception {
        Leave l = startLeave();
        // 起點的通知（task_assigned）先丟掉，只觀察這次 complete 的結果。
        NotifyTestSink.reset();

        complete(l.managerTaskId(), "mgr001", "{\"action\":\"complete\",\"returnTo\":\"initiator\"}");

        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        List<String> processEvents = msgs.stream()
                .map(m -> String.valueOf(m.get("event")))
                .filter(e -> e.startsWith("process_"))
                .toList();
        assertThat(processEvents)
                .as("退回申請人的完整 process_* 序列必須恰好是 [process_returned]")
                .containsExactly("process_returned");

        Map<String, Object> returned = NotifyTestSink.events(msgs, "process_returned").get(0);
        assertThat(returned)
                .containsEntry("assignee", "user001")
                .containsEntry("processInstanceId", l.pid())
                .containsEntry("taskId", l.managerTaskId());
    }

    // ── g) 變數生命週期：不得殘留到下一輪 ────────────────────────────

    @Test
    @DisplayName("#1 g) 退回申請人後，同案件下一輪的一般退回仍走 revisionFromFinance（returnTo 不得殘留）")
    void returnToDoesNotLeakIntoTheNextRound() throws Exception {
        Purchase p = startPurchase();
        complete(p.managerTaskId(), "mgr001",
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":true}]}");
        Task finance = currentTask(p.pid());

        // 第一輪：財務退回申請人 → 回起點。
        complete(finance.getId(), "dir001", "{\"action\":\"complete\",\"returnTo\":\"initiator\"}");
        Task revision = currentTask(p.pid());
        assertThat(revision.getTaskDefinitionKey()).isEqualTo("revisionFromManager");

        // 申請人補件重送（不帶 returnTo）→ 回主管。
        complete(revision.getId(), "user001",
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"amount\",\"value\":2000}]}");
        Task managerAgain = currentTask(p.pid());
        assertThat(managerAgain.getTaskDefinitionKey()).isEqualTo("managerReview");

        // 主管再核准 → 財務。
        complete(managerAgain.getId(), "mgr001",
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":true}]}");
        Task financeAgain = currentTask(p.pid());
        assertThat(financeAgain.getName()).contains("財務");

        // 第二輪：財務一般退回（不帶 returnTo）→ 必須回上一站，不是起點。
        complete(financeAgain.getId(), "dir001",
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":false}]}");

        Task revisionAgain = currentTask(p.pid());
        assertThat(revisionAgain.getTaskDefinitionKey())
                .as("前一輪的 returnTo=initiator 若殘留在流程變數上，這裡會誤路由到 revisionFromManager")
                .isEqualTo("revisionFromFinance");
    }
}
