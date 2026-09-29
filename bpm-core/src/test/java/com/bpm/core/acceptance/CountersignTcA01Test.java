package com.bpm.core.acceptance;

import com.bpm.core.support.IntegrationTestBase;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * TC-A01 附屬簽（加簽）的驗收迴歸測試。
 *
 * <p>TC-A01 是 11 項驗收案例中未通過的 4 項之一。根因已於 2026-09-28 的
 * security-audit 查出並在此以測試固定下來：
 *
 * <ul>
 *   <li><b>前端呼叫錯誤</b>：{@code CountersignDialog.vue} 呼叫
 *       {@code createSubtask({parentTaskId, assignee, description})} 只傳一個參數，
 *       而簽章是 {@code (taskId, data)} → 實際發出
 *       {@code POST /api/countersign/[object Object]} 且 body 為 undefined。</li>
 *   <li><b>欄位名不一致</b>：前端送 {@code assignee}／{@code description}，
 *       後端讀 {@code countersignUserId}／{@code message}。</li>
 *   <li><b>NPE 造成案件死鎖</b>：assignee 為 null 時，子任務已在
 *       {@code saveTask} 落地（assignee 為 null → 沒人看得到它），
 *       接著 {@code Map.of("assignee", null)} 拋 NPE 變成 500。
 *       父任務被「有未完成加簽子任務」的守門永遠攔住 → <b>案件死鎖，
 *       只能進 DB 手動清</b>。</li>
 * </ul>
 *
 * <p>本測試針對後端契約。前端的呼叫方式沒有自動化測試框架可驗
 * （前端無測試框架），該部分以修正 + 手動走查覆蓋。
 */
class CountersignTcA01Test extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    /** 啟動一個請假流程並回傳主管審核任務。 */
    private Task startLeaveAndGetManagerTask() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(pi.getId()).list();
        assertThat(tasks).as("請假流程啟動後應有一個待辦任務").hasSize(1);
        return tasks.get(0);
    }

    @Test
    @DisplayName("加簽：帶正確欄位名時應建立子任務並指派給加簽人")
    void createSubtaskWithCorrectFieldNames() throws Exception {
        Task parent = startLeaveAndGetManagerTask();

        mockMvc.perform(post("/api/countersign/{taskId}", parent.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        // 後端讀的是 countersignUserId／message，不是 assignee／description
                        .content("{\"countersignUserId\":\"user003\",\"message\":\"請協助確認\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        List<Task> subs = taskService.getSubTasks(parent.getId());
        assertThat(subs).as("應建立一個加簽子任務").hasSize(1);
        assertThat(subs.get(0).getAssignee())
                .as("子任務必須有 assignee，否則沒人看得到它")
                .isEqualTo("user003");
        assertThat(subs.get(0).getDescription()).isEqualTo("請協助確認");
    }

    @Test
    @DisplayName("加簽：缺少 countersignUserId 時必須回 4xx，且不得留下孤兒子任務")
    void missingAssigneeMustNotDeadlockTheCase() throws Exception {
        Task parent = startLeaveAndGetManagerTask();

        // 這正是前端目前會送出的形狀（欄位名錯 → 後端讀到 null）。
        mockMvc.perform(post("/api/countersign/{taskId}", parent.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"user003\",\"description\":\"欄位名錯\"}"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    assertThat(s)
                            .as("缺少 countersignUserId 應為用戶端錯誤（4xx），"
                                    + "而不是 NPE 造成的 500")
                            .isBetween(400, 499);
                });

        // 最關鍵的斷言：失敗不可以留下任何子任務。
        // 留下 assignee 為 null 的子任務 = 沒人看得到它 + 父任務永遠被守門攔住 = 案件死鎖。
        assertThat(taskService.getSubTasks(parent.getId()))
                .as("請求失敗時不得留下孤兒子任務 —— 否則父任務永遠無法完成，案件死鎖")
                .isEmpty();

        // 而且父任務必須仍然可以正常完成。
        taskService.complete(parent.getId(),
                Map.of("approved", true, "rejected", false));
        assertThat(taskService.createTaskQuery().taskId(parent.getId()).singleResult())
                .as("父任務應已完成").isNull();
    }

    @Test
    @DisplayName("加簽：有未完成子任務時父任務不得完成；子任務完成後才可放行")
    void parentBlockedUntilSubtaskDone() throws Exception {
        Task parent = startLeaveAndGetManagerTask();

        mockMvc.perform(post("/api/countersign/{taskId}", parent.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countersignUserId\":\"user003\",\"message\":\"請確認\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        String subtaskId = taskService.getSubTasks(parent.getId()).get(0).getId();

        // 守門：有未完成加簽時必須回 409。
        // （改動前回 HTTP 200 帶 status:"error"，前端只看 axios 是否 throw
        //   → 顯示「操作成功」並導航離開。已於 P1-3 修正，
        //   詳細斷言在 TaskActionHardeningTest。）
        mockMvc.perform(put("/api/tasks/{id}", parent.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":"
                                + "[{\"name\":\"approved\",\"value\":true}]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict());
        assertThat(taskService.createTaskQuery().taskId(parent.getId()).singleResult())
                .as("有未完成加簽子任務時，父任務不得被完成").isNotNull();

        // 完成子任務，意見應附加到父任務的 comments（spec §4.4.1）
        mockMvc.perform(put("/api/countersign/{taskId}/{subtaskId}/complete",
                        parent.getId(), subtaskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opinion\":\"同意加簽\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        assertThat(taskService.getSubTasks(parent.getId()))
                .as("子任務完成後應不再存在").isEmpty();
        assertThat(taskService.getTaskComments(parent.getId()))
                .as("加簽意見必須附加到父任務的 comments")
                .anyMatch(c -> c.getFullMessage().contains("同意加簽"));

        // 子任務清空後，父任務應可正常完成
        taskService.complete(parent.getId(), Map.of("approved", true, "rejected", false));
        assertThat(taskService.createTaskQuery().taskId(parent.getId()).singleResult())
                .as("加簽完成後父任務應可完成").isNull();
    }

    @Test
    @DisplayName("加簽：查詢子任務清單應回傳 assignee 與說明")
    void getSubtasksReturnsDetails() throws Exception {
        Task parent = startLeaveAndGetManagerTask();
        mockMvc.perform(post("/api/countersign/{taskId}", parent.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countersignUserId\":\"user004\",\"message\":\"請看一下\"}"));

        mockMvc.perform(get("/api/countersign/{taskId}", parent.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].assignee").value("user004"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].description").value("請看一下"));
    }
}
