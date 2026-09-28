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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-A02 批註功能（多人批註）的驗收迴歸測試。
 *
 * <p>TC-A02 是未通過的 4 項驗收案例之一。根因於 2026-09-28 定位：
 * <b>批註不記錄作者</b>。
 *
 * <p>{@code TaskController.addComment} 呼叫
 * {@code taskService.addComment(id, processInstanceId, message)} —— Flowable 的
 * comment 作者取自 {@code Authentication.getAuthenticatedUserId()}，
 * 而本專案<b>從未設定</b>過它。因此每一筆 comment 的 {@code userId} 都是 null，
 * API 一律回傳空字串。
 *
 * <p>對「多人批註」而言這正好摧毀了功能的全部意義 —— 多方意見的重點就是
 * 分辨誰說了什麼。單人批註看起來「正常」（訊息有存到），所以這個缺陷
 * 在單人測試下不會顯現。
 *
 * <p>身分來源與其他端點一致：優先取 {@code X-User-Id} 請求標頭
 * （前端共用 axios instance 一律附上、acceptance 腳本也用它），
 * 退回 body 的 {@code userId}。
 */
class CommentTcA02Test extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private Task startLeaveAndGetTask() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pi.getId()).list();
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    @Test
    @DisplayName("多人批註：每一筆都必須記錄是誰批的")
    void multipleCommentersAreEachAttributed() throws Exception {
        Task task = startLeaveAndGetTask();

        // 三個不同的人各批註一次。身分走 X-User-Id 標頭（與 acceptance 腳本相同）。
        for (String[] pair : new String[][]{
                {"mgr001", "請確認交接事項"},
                {"dir001", "金額偏高，請說明"},
                {"user001", "已補充說明於附件"}}) {
            mockMvc.perform(post("/api/tasks/{id}/comments", task.getId())
                            .header("X-User-Id", pair[0])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"" + pair[1] + "\"}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/tasks/{id}/comments", task.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        var comments = taskService.getTaskComments(task.getId());
        assertThat(comments).hasSize(3);

        // 這是本測試的核心：作者不可以是 null／空字串。
        // 全空的話「多人批註」等於一堆無主的字串，無法分辨誰說了什麼。
        assertThat(comments)
                .as("每一筆批註都必須有作者 —— 全為 null 就是沒有設定 "
                        + "Flowable 的 Authentication")
                .allSatisfy(c -> assertThat(c.getUserId()).isNotBlank());

        assertThat(comments).extracting(org.flowable.engine.task.Comment::getUserId)
                .as("三個批註者應各自被正確記錄")
                .containsExactlyInAnyOrder("mgr001", "dir001", "user001");
    }

    @Test
    @DisplayName("批註：body 的 userId 仍可作為身分來源（相容前端既有呼叫）")
    void userIdInBodyStillWorks() throws Exception {
        Task task = startLeaveAndGetTask();

        mockMvc.perform(post("/api/tasks/{id}/comments", task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"來自 body 的身分\",\"userId\":\"mgr002\"}"))
                .andExpect(status().isOk());

        assertThat(taskService.getTaskComments(task.getId()))
                .singleElement()
                .satisfies(c -> assertThat(c.getUserId()).isEqualTo("mgr002"));
    }

    @Test
    @DisplayName("批註：完全沒有身分時仍應成功（不得破壞 TC-L04）")
    void anonymousCommentStillAccepted() throws Exception {
        Task task = startLeaveAndGetTask();

        // acceptance-test.sh 的 TC-L04 就是這個形狀：只有 X-User-Id 標頭、
        // body 沒有 userId。此處測更極端的情況（兩者皆無）以確保不會 400。
        mockMvc.perform(post("/api/tasks/{id}/comments", task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"沒有身分的批註\"}"))
                .andExpect(status().isOk());

        assertThat(taskService.getTaskComments(task.getId()))
                .as("訊息仍須寫入（不可因為缺身分而拒絕，否則 TC-L04 會退步）")
                .hasSize(1);
    }
}
