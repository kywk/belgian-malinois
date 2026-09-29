package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 加簽可被第三方刪除以跳過簽核，且無痕（security-audit P0-6）。
 *
 * <p><b>問題。</b>{@code completeSubtask} 的兩個路徑參數
 * {@code {taskId}}（父）與 {@code {subtaskId}}（子）<b>從未驗證屬於同一組</b>
 * —— {@code getParentTaskId()} 根本沒被讀取。而
 * {@code taskService.deleteTask(subtaskId, true)} 的 cascade=true
 * 會<b>連歷史一併刪除</b>。
 *
 * <p>後果：帶任意 {@code {taskId}} 加上他人的 {@code {subtaskId}}，即可刪掉
 * 尚未審的加簽子任務。子任務一消失，父任務的守門立刻放行 →
 * <b>加簽人從未表態，案子照樣過關</b>。而該端點不發任何稽核事件，
 * 歷史又被 cascade 刪除 → 完全無痕。
 *
 * <p>（流程內的任務因為有 executionId 刪不掉，但加簽子任務是 standalone
 * task，可以。）
 *
 * <h2>⚠️ 2026-09-29 補上 {@code X-User-Id}（#77 延伸：加簽的持有者守衛）</h2>
 *
 * <p>本測試原本沿用預設身分 {@code user001}，也就是<b>申請人</b>，
 * 而非持有主管審核任務的 mgr001。加簽守衛加上之後那樣的請求本來會被擋下，
 * 而測試必須補上正確的身分才測得到它宣稱的東西：
 * 加簽由持有者（mgr001）發起、<b>被加簽者（user003）本人</b>完成。
 *
 * <p>唯一刻意仍用「不屬於任何人」身分的地方是 {@code mismatchedParentIsRejected}
 * 的<b>負向對照組</b>：那條要證明的是「即使持有者本人帶著不對的 parentTaskId
 * 也會被擋」，所以身分必須是<b>應該通過子任務授權檢查的人</b>（user003 = 被指派人），
 * 讓配對驗證成為唯一的拒絕原因。
 */
class CountersignTamperTest extends IntegrationTestBase {

    /** 持有主管審核任務的人（leave-approval 的 managerReview assignee）。 */
    private static final String HOLDER = "mgr001";

    /** 被加簽者（子任務的 assignee）—— 唯一有權完成那筆加簽的身分。 */
    private static final String COUNTERSIGNED = "user003";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    private Task startAndGetManagerTask() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        return taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
    }

    /** 由任務持有者（mgr001）建立一筆加簽。 */
    private String addCountersign(String parentId, String assignee) throws Exception {
        mockMvc.perform(post("/api/countersign/{taskId}", parentId)
                        .header("X-User-Id", HOLDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countersignUserId\":\"" + assignee + "\",\"message\":\"請確認\"}"))
                .andExpect(status().isOk());
        return taskService.getSubTasks(parentId).get(0).getId();
    }

    @Test
    @DisplayName("不得用不相符的 parentTaskId 完成／刪除他人的加簽子任務")
    void mismatchedParentIsRejected() throws Exception {
        Task victimParent = startAndGetManagerTask();
        String victimSub = addCountersign(victimParent.getId(), COUNTERSIGNED);

        // 另一個案件的父任務 —— 攻擊者自己有權限操作的那個
        Task attackerParent = startAndGetManagerTask();

        mockMvc.perform(put("/api/countersign/{taskId}/{subtaskId}/complete",
                        attackerParent.getId(), victimSub)
                        // 負向對照組：身分是「有權完成 victimSub 的人」（被指派人 user003），
                        // 因此唯一的拒絕原因是 parentTaskId 不符。
                        // 若這裡改用無權身分，測試會因為授權守衛而通過 ——
                        // 配對驗證壞掉時它就不會紅了。
                        .header("X-User-Id", COUNTERSIGNED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opinion\":\"skip\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("parentTaskId 與子任務的 parent 不符時必須拒絕")
                        .isBetween(400, 499));

        assertThat(taskService.getSubTasks(victimParent.getId()))
                .as("他人的加簽子任務不得被刪除 —— 否則守門立刻放行、加簽人從未表態")
                .hasSize(1);
    }

    @Test
    @DisplayName("父任務的守門在加簽被跳過後不得放行")
    void gateStillBlocksAfterFailedTamper() throws Exception {
        Task parent = startAndGetManagerTask();
        addCountersign(parent.getId(), COUNTERSIGNED);
        Task other = startAndGetManagerTask();

        mockMvc.perform(put("/api/countersign/{taskId}/{subtaskId}/complete",
                        other.getId(), taskService.getSubTasks(parent.getId()).get(0).getId())
                        .header("X-User-Id", COUNTERSIGNED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // 守門必須仍然攔住父任務
        mockMvc.perform(put("/api/tasks/{id}", parent.getId())
                        .header("X-User-Id", HOLDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":"
                                + "[{\"name\":\"approved\",\"value\":true}]}"));
        assertThat(taskService.createTaskQuery().taskId(parent.getId()).singleResult())
                .as("加簽未完成，父任務不得被完成").isNotNull();
    }

    @Test
    @DisplayName("正常完成加簽必須保留歷史（不得 cascade 刪除）")
    void completingCountersignKeepsHistory() throws Exception {
        Task parent = startAndGetManagerTask();
        String sub = addCountersign(parent.getId(), COUNTERSIGNED);

        mockMvc.perform(put("/api/countersign/{taskId}/{subtaskId}/complete",
                        parent.getId(), sub)
                        .header("X-User-Id", COUNTERSIGNED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opinion\":\"同意加簽\"}"))
                .andExpect(status().isOk());

        assertThat(taskService.getSubTasks(parent.getId()))
                .as("完成後應離開待辦").isEmpty();

        // 歷史必須留著 —— cascade=true 會把它一起刪掉，稽核就查不到加簽發生過
        assertThat(historyService.createHistoricTaskInstanceQuery()
                        .taskId(sub).count())
                .as("加簽子任務的歷史必須保留，否則「誰加簽、何時完成」無跡可查")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("加簽完成必須留下稽核事件")
    void completingCountersignIsAudited() throws Exception {
        truncateAuditLog();
        Task parent = startAndGetManagerTask();
        String sub = addCountersign(parent.getId(), COUNTERSIGNED);

        mockMvc.perform(put("/api/countersign/{taskId}/{subtaskId}/complete",
                        parent.getId(), sub)
                        .header("X-User-Id", COUNTERSIGNED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opinion\":\"同意\"}"))
                .andExpect(status().isOk());

        // 等 @Async 落地
        List<String> ops = List.of();
        for (int i = 0; i < 50; i++) {
            ops = auditOperationTypes();
            if (ops.size() >= 2) break;
            Thread.sleep(100);
        }
        assertThat(ops)
                .as("加簽建立與完成都必須有稽核事件 —— 改動前 completeSubtask 完全不發事件")
                .contains("TASK_COUNTERSIGN");
        assertThat(ops.size())
                .as("應同時有『建立加簽』與『完成加簽』兩筆")
                .isGreaterThanOrEqualTo(2);
    }

    private static List<String> auditOperationTypes() {
        List<String> out = new java.util.ArrayList<>();
        withAuditConnection(c -> {
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT operation_type FROM bpm_audit_log ORDER BY id")) {
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }
}
