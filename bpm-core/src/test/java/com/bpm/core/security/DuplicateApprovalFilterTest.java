package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「同一人不得重複簽核」過濾移除後的行為（security-audit P1-5）。
 *
 * <p><b>為什麼移除</b>（2026-09-28 決策）：
 *
 * <ol>
 *   <li>它<b>從未真正強制任何規則</b> —— 過濾只發生在待辦查詢，
 *       {@code PUT /api/tasks/{id}} 沒有對應檢查，知道 taskId 就能簽第二次。
 *       一個被當成業務規則展示、實際上只是隱藏的機制，比沒有它更糟：
 *       它讓人以為規則已經生效。</li>
 *   <li>它會讓<b>案件靜默卡死</b>。過濾範圍是整個流程實例而非節點。</li>
 * </ol>
 *
 * <p>本測試用真實的卡死情境驗證：採購流程的 {@code financeReview} 候選人是
 * {@code ${permService.getUsersByPermission('finance:payment:approve')}}
 * → mock 回 {@code [mgr001, dir001]}，而 {@code mgr001} 同時也是前一關的
 * 主管簽核人。因此 mgr001 完成主管關卡之後，財務關卡對他來說就是
 * 「同一個 instance 內、自己已完成過任務」的未指派候選任務 ——
 * 正是被過濾掉的那種。
 */
class DuplicateApprovalFilterTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Test
    @DisplayName("完成前一關之後，同一案件的候選任務仍必須出現在待辦中")
    void candidateTaskStaysVisibleAfterEarlierApproval() throws Exception {
        var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "amount", 5000, "itemName", "測試品項"));
        String pid = pi.getId();

        // 1. mgr001 完成主管審核
        Task mgrTask = taskService.createTaskQuery().processInstanceId(pid).list().get(0);
        assertThat(mgrTask.getAssignee()).isEqualTo("mgr001");
        taskService.complete(mgrTask.getId(), Map.of("approved", true, "rejected", false));

        // 2. 產生財務審核（未指派的候選任務，候選人含 mgr001）
        List<Task> financeTasks = taskService.createTaskQuery().processInstanceId(pid).list();
        assertThat(financeTasks).hasSize(1);
        Task finance = financeTasks.get(0);
        assertThat(finance.getAssignee()).as("財務審核應為未指派的候選任務").isNull();
        assertThat(taskService.getIdentityLinksForTask(finance.getId()))
                .as("mgr001 應為財務審核的候選人")
                .anySatisfy(link -> assertThat(link.getUserId()).isEqualTo("mgr001"));

        // 3. 關鍵斷言：mgr001 在此 instance 已完成過任務，
        //    移除過濾前這個財務任務會對他「永久隱藏」→ 若權限只有一人即卡死。
        var res = mockMvc.perform(get("/api/tasks").param("candidateUser", "mgr001")
                        .header("X-User-Id", "mgr001"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(res.getResponse().getContentAsString())
                .as("mgr001 已完成同一案件的主管關卡，但財務關卡仍須看得到 —— "
                        + "否則該權限只有一人時案件靜默卡死")
                .contains(finance.getId());
    }

    @Test
    @DisplayName("直接指派給自己的任務不受影響（原本也不會被過濾）")
    void directlyAssignedTasksUnaffected() throws Exception {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);

        var res = mockMvc.perform(get("/api/tasks").param("assignee", "mgr001")
                        .header("X-User-Id", "mgr001"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).contains(task.getId());
    }

    @Test
    @DisplayName("待辦查詢不得因移除過濾而漏掉或重複任務")
    void inboxRemainsConsistent() throws Exception {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        String taskId = taskService.createTaskQuery()
                .processInstanceId(pi.getId()).list().get(0).getId();

        var res = mockMvc.perform(get("/api/tasks").param("assignee", "mgr001")
                        .header("X-User-Id", "mgr001"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();

        // 同一個 taskId 不得出現兩次（taskMap 以 id 為 key，但移除過濾後
        // 若誤改了收集邏輯就可能重複）
        int first = body.indexOf(taskId);
        assertThat(first).isGreaterThanOrEqualTo(0);
        assertThat(body.indexOf(taskId, first + 1))
                .as("同一任務不得在待辦中出現兩次").isEqualTo(-1);
    }
}
