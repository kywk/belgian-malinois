package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/tasks/{id}} 的三個高風險問題（security-audit P1-1／P1-3／P1-4）。
 *
 * <p>三者都在同一個方法裡且互相牽動，因此一併處理：
 *
 * <ul>
 *   <li><b>P1-1 稽核查不出是誰核准的</b>：operatorId 一律取 {@code req.assignee()}，
 *       但前端主要簽核入口的 payload 只有 action 與 variables → TASK_APPROVE／
 *       TASK_RETURN／TASK_REJECT 的 operatorId 全是 null。
 *       另有三個 operationType（TASK_RESUBMIT／TASK_RESOLVE／TASK_UPDATE）
 *       不在 enum 裡 → valueOf 拋例外、被 @Async 吞掉 → 呼叫端收 200 但
 *       稽核表完全沒有那筆。兩支 BPMN 的補件任務名稱都含「補件」，
 *       因此<b>每一次退回重送都不進稽核</b>。</li>
 *   <li><b>P1-3 守門回 HTTP 200</b>：有未完成加簽時回
 *       {@code {"status":"error"}} 但 HTTP 200，前端只看 axios 是否 throw
 *       → 顯示「操作成功」並導航離開。</li>
 *   <li><b>P1-4 claim 搶佔保護可繞過</b>：claim 失敗後改送
 *       {@code {"assignee":"自己"}}（不帶 action）即可無條件奪取他人任務；
 *       {@code {"action":"claim"}} 空 body 可強制釋放他人任務；
 *       未知 action 帶 assignee 會讓「核准」靜默變成「改派」且回報 ok。</li>
 * </ul>
 */
class TaskActionHardeningTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    private Task startAndGetManagerTask() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        return taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
    }

    private List<String[]> auditRows() {
        List<String[]> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (var st = c.createStatement();
                 var rs = st.executeQuery(
                         "SELECT operation_type, operator_id FROM bpm_audit_log ORDER BY id")) {
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        });
        return out;
    }

    private List<String[]> awaitAudit(int atLeast) throws Exception {
        List<String[]> rows = List.of();
        for (int i = 0; i < 50; i++) {
            rows = auditRows();
            if (rows.size() >= atLeast) break;
            Thread.sleep(100);
        }
        return rows;
    }

    // ── P1-1 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("P1-1：簽核的稽核必須記錄操作者（改動前 operatorId 全為 null）")
    void approvalAuditRecordsOperator() throws Exception {
        Task task = startAndGetManagerTask();

        // 這正是前端主要簽核入口的 payload 形狀：只有 action 與 variables，
        // 沒有 assignee。身分在 X-User-Id 標頭裡。
        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":"
                                + "[{\"name\":\"approved\",\"value\":true}]}"))
                .andExpect(status().isOk());

        List<String[]> rows = awaitAudit(1);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(rows.size() - 1)[0]).isEqualTo("TASK_APPROVE");
        assertThat(rows.get(rows.size() - 1)[1])
                .as("稽核記錄存在但查不出是誰核准的，等於沒有稽核")
                .isEqualTo("mgr001");
    }

    @Test
    @DisplayName("P1-1：完成補件任務必須進稽核（TASK_RESUBMIT 先前不在 enum 裡）")
    void resubmitIsAudited() throws Exception {
        Task mgrTask = startAndGetManagerTask();
        // 退回 → 產生名稱含「補件」的任務
        taskService.complete(mgrTask.getId(), Map.of("approved", false, "rejected", false));
        Task resubmit = taskService.createTaskQuery()
                .processInstanceId(mgrTask.getProcessInstanceId()).list().get(0);
        assertThat(resubmit.getName()).contains("補件");

        truncateAuditLog();
        mockMvc.perform(put("/api/tasks/{id}", resubmit.getId())
                        .header("X-User-Id", "user001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":"
                                + "[{\"name\":\"approved\",\"value\":true}]}"))
                .andExpect(status().isOk());

        List<String[]> rows = awaitAudit(1);
        assertThat(rows)
                .as("改動前 TASK_RESUBMIT 不在 OperationType 裡 → valueOf 拋例外被 "
                        + "@Async 吞掉 → 每一次退回重送都不進稽核")
                .isNotEmpty();
        assertThat(rows.get(0)[0]).isEqualTo("TASK_RESUBMIT");
        assertThat(rows.get(0)[1]).isEqualTo("user001");
    }

    // ── P1-3 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("P1-3：有未完成加簽時必須回 409，不得回 200 讓前端誤判為成功")
    void pendingCountersignReturnsConflict() throws Exception {
        Task task = startAndGetManagerTask();
        // 加簽由任務持有者發起（assignee = mgr001）。2026-09-29 之前這裡沒有宣告
        // 身分，預設的 user001 是「申請人」—— 加簽守衛加上之後那樣的請求會被
        // 正確地擋下，而那正是它該被擋下的原因（申請人沒有權力替主管加簽）。
        mockMvc.perform(post("/api/countersign/{taskId}", task.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countersignUserId\":\"user003\",\"message\":\"請確認\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":"
                                + "[{\"name\":\"approved\",\"value\":true}]}"))
                .andExpect(status().isConflict());

        assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult())
                .as("任務不得被完成").isNotNull();
    }

    // ── P1-4 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("P1-4：未知 action 必須回 400，不得靜默改派並回報成功")
    void unknownActionIsRejected() throws Exception {
        Task task = startAndGetManagerTask();

        // typo 或舊版前端：本意是核准，改動前會落入改派分支並回 {"status":"ok"}
        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complet\",\"assignee\":\"attacker\"}"))
                .andExpect(status().isBadRequest());

        assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult().getAssignee())
                .as("未知 action 不得造成改派").isEqualTo("mgr001");
    }

    @Test
    @DisplayName("P1-4：不得以不帶 action 的 assignee 無條件奪取他人任務")
    void cannotHijackByBareAssignee() throws Exception {
        Task task = startAndGetManagerTask();

        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", "attacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"attacker\"}"))
                .andExpect(status().isBadRequest());

        assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult().getAssignee())
                .as("任務不得被奪取").isEqualTo("mgr001");
    }

    @Test
    @DisplayName("P1-4：claim 空 body 不得強制釋放他人任務")
    void claimWithoutAssigneeCannotReleaseOthersTask() throws Exception {
        Task task = startAndGetManagerTask();
        assertThat(task.getAssignee()).isEqualTo("mgr001");

        // claim(taskId, null) 的語意是「取消認領」且跳過已認領檢查
        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", "attacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"claim\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("不得成功釋放他人已認領的任務").isNotEqualTo(200));

        assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult().getAssignee())
                .as("他人的任務不得被釋放").isEqualTo("mgr001");
    }

    @Test
    @DisplayName("acceptance 腳本的 claim 形狀必須仍然可用（身分取自標頭）")
    void claimFromHeaderStillWorks() throws Exception {
        // scripts/acceptance-test.sh 送的就是 {"action":"claim"} 且無 assignee，
        // 身分在 X-User-Id。TC-P01／TC-P03 依賴這個行為。
        var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "amount", 1000, "itemName", "測試"));
        Task mgr = taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
        taskService.complete(mgr.getId(), Map.of("approved", true, "rejected", false));

        Task candidate = taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
        assertThat(candidate.getAssignee()).as("財務審核應為候選人任務（未指派）").isNull();

        mockMvc.perform(put("/api/tasks/{id}", candidate.getId())
                        .header("X-User-Id", "dir001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"claim\"}"))
                .andExpect(status().isOk());

        assertThat(taskService.createTaskQuery().taskId(candidate.getId()).singleResult().getAssignee())
                .as("claim 應把任務指派給標頭中的使用者").isEqualTo("dir001");
    }
}
