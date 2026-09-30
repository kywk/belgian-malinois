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
 *
 * <h2>⚠️ #79：批註端點已加上案件關係人檢查</h2>
 *
 * <p>{@code POST /api/tasks/{id}/comments} 與兩個讀端點原本<b>零授權</b>，
 * 任何登入者都能在別人的單上留言、讀到別人的簽核意見全文。
 * 守衛是 {@code ProcessAccessGuard.requireTaskParticipant}（寫）與
 * {@code requireTaskReadAccess}（讀）—— 也就是「必須是這個案件的關係人」。
 *
 * <p>因此本類別的 fixture <b>必須改成真實關係人</b>：原版本的
 * {@link #multipleCommentersAreEachAttributed} 讓 dir001 在 user001 的
 * 請假單上留言，那其實是在測缺陷。授權的負向斷言在
 * {@code com.bpm.core.security.CommentAuthorizationTest}。
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

    /**
     * 啟動採購流程並讓主管關卡通過，產生 {@code financeReview}。
     *
     * <p>{@code financeReview} 的候選人是
     * {@code ${permService.getUsersByPermission('finance:payment:approve')}} = dir001，
     * 所以 dir001 對<b>這張</b>採購單是真實關係人（見
     * {@code InvolvedInstancesTest.candidateUserFlowIsUnaffected}）。
     */
    private Task startPurchaseAndGetFinanceTask() {
        var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "amount", 5000, "itemName", "測試品項"));
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pi.getId()).list();
        taskService.complete(tasks.get(0).getId(), Map.of("approved", true, "rejected", false));
        List<Task> finance = taskService.createTaskQuery().processInstanceId(pi.getId()).list();
        assertThat(finance).hasSize(1);
        assertThat(taskService.getIdentityLinksForTask(finance.get(0).getId()))
                .as("前置條件：dir001 必須是這個候選任務的候選人，否則他不是關係人")
                .anySatisfy(link -> assertThat(link.getUserId()).isEqualTo("dir001"));
        return finance.get(0);
    }

    /**
     * ⚠️ 這三個人必須<b>都是自己那張單的關係人</b>，否則這條測試會在測缺陷（#79）。
     *
     * <p>原版本是三個人全部留言在 user001 的請假單上，其中 dir001 與這張單
     * 毫無關係（主管關卡的 assignee 是 mgr001）。他能留言只是因為當時
     * {@code POST /api/tasks/{id}/comments} 零授權檢查（#79 修掉）。
     *
     * <p>現在改成兩張單各自留言：請假單上由申請人與主管留言（user001／mgr001），
     * 採購單的財務關卡由 dir001 留言 —— 而 dir001 對那張採購單是真實關係人。
     * 「多方意見」的功能意義（每筆都記得是誰說的）完全不打折。
     *
     * <p>「不相關的人留不留下批註」屬於授權，斷言在
     * {@code com.bpm.core.security.CommentAuthorizationTest}。
     */
    @Test
    @DisplayName("多人批註：每一筆都必須記錄是誰批的")
    void multipleCommentersAreEachAttributed() throws Exception {
        Task leaveManagerTask = startLeaveAndGetTask();
        assertThat(leaveManagerTask.getAssignee())
                .as("前置條件：請假單的主管關卡持有者是 mgr001")
                .isEqualTo("mgr001");
        Task purchaseFinanceTask = startPurchaseAndGetFinanceTask();

        for (String[] pair : new String[][]{
                {"leave", "user001", "請確認交接事項"},
                {"leave", "mgr001", "金額偏高，請說明"},
                {"purchase", "dir001", "已補充說明於附件"}}) {
            String target = pair[0].equals("purchase")
                    ? purchaseFinanceTask.getId() : leaveManagerTask.getId();
            mockMvc.perform(post("/api/tasks/{id}/comments", target)
                            .header("X-User-Id", pair[1])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"" + pair[2] + "\"}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/tasks/{id}/comments", leaveManagerTask.getId())
                        .header("X-User-Id", "mgr001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/tasks/{id}/comments", purchaseFinanceTask.getId())
                        .header("X-User-Id", "dir001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        var leaveComments = taskService.getTaskComments(leaveManagerTask.getId());
        var purchaseComments = taskService.getTaskComments(purchaseFinanceTask.getId());
        assertThat(leaveComments).hasSize(2);
        assertThat(purchaseComments).hasSize(1);

        // 這是本測試的核心：作者不可以是 null／空字串。
        // 全空的話「多人批註」等於一堆無主的字串，無法分辨誰說了什麼。
        assertThat(leaveComments)
                .as("每一筆批註都必須有作者 —— 全為 null 就是沒有設定 "
                        + "Flowable 的 Authentication")
                .allSatisfy(c -> assertThat(c.getUserId()).isNotBlank());
        assertThat(purchaseComments)
                .as("同上：採購單那一筆也必須有作者")
                .allSatisfy(c -> assertThat(c.getUserId()).isNotBlank());

        assertThat(leaveComments).extracting(org.flowable.engine.task.Comment::getUserId)
                .as("請假單的兩個批註者應各自被正確記錄")
                .containsExactlyInAnyOrder("mgr001", "user001");
        assertThat(purchaseComments).extracting(org.flowable.engine.task.Comment::getUserId)
                .as("採購單那一筆的作者必須是 dir001（不是預設身分、不是空字串）")
                .containsExactly("dir001");
    }

    /**
     * body 的 userId <b>不得</b>覆寫已認證的身分（R-01）。
     *
     * <h2>這個測試的斷言在 R-01 時被反轉</h2>
     *
     * <p>先前的版本叫 {@code userIdInBodyStillWorks}，斷言 body 的
     * {@code userId} 會成為批註作者 —— 那在「身分本來就是可偽造的標頭」的
     * 年代不算缺陷，因為偽造身分有更直接的方法。
     *
     * <p>但 R-01 之後身分來自簽章過的 JWT 或閘道認證。此時若 body 還能覆寫它，
     * 整套認證就失去意義：任何登入者都能以他人名義留下簽核批註，
     * 而稽核紀錄會如實記下那個假身分 —— 比沒有紀錄更糟，因為它看起來可信。
     *
     * <p>所以行為改為：已認證的身分優先，body 的 userId 被忽略。
     */
    @Test
    @DisplayName("批註：body 的 userId 不得覆寫已認證的身分（R-01）")
    void bodyUserIdCannotOverrideAuthenticatedIdentity() throws Exception {
        Task task = startLeaveAndGetTask();

        mockMvc.perform(post("/api/tasks/{id}/comments", task.getId())
                        .header("X-User-Id", "user001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"冒用他人身分的批註\",\"userId\":\"mgr002\"}"))
                .andExpect(status().isOk());

        assertThat(taskService.getTaskComments(task.getId()))
                .singleElement()
                .satisfies(c -> assertThat(c.getUserId())
                        .as("body 的 userId 覆寫了已認證的身分 —— "
                                + "任何登入者都能以他人名義留下簽核批註")
                        .isEqualTo("user001"));
    }

    /**
     * 沒有在請求裡明寫身分時仍應成功（不得破壞 TC-L04）。
     *
     * <h2>⚠️ #79 之後這條測試的形狀必須說清楚</h2>
     *
     * <p>「完全沒有身分」指的是<b>請求裡沒有寫</b>，不是「SecurityContext 裡沒有」。
     * {@code TestGatewayMockMvcCustomizer} 的 defaultRequest 會補上
     * {@code X-User-Id: user001}，所以實際的身分是 user001 ——
     * 也就是這張請假單的申請人（關係人）。因此守衛放行。
     *
     * <p>⚠️ <b>不要把這條當成「無需認證即可留言」的證據</b>：
     * {@code SecurityConfig} 的 {@code authenticated()} 擋在 controller 之前，
     * 未認證的請求到不了這裡；而 {@code requireTaskParticipant} 對
     * 空白身分回 404（與 {@code requireParticipant} 同一政策）。
     * 真正未認證的路徑由 {@code AuthenticationTest} 涵蓋。
     */
    @Test
    @DisplayName("批註：請求未明寫身分時仍應成功（defaultRequest 補 user001 = 申請人）")
    void anonymousCommentStillAccepted() throws Exception {
        Task task = startLeaveAndGetTask();

        // acceptance-test.sh 的 TC-L04 就是這個形狀：只有 X-User-Id 標頭、
        // body 沒有 userId。此處連標頭都不寫，套用 defaultRequest 的 user001。
        mockMvc.perform(post("/api/tasks/{id}/comments", task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"沒有身分的批註\"}"))
                .andExpect(status().isOk());

        assertThat(taskService.getTaskComments(task.getId()))
                .as("訊息仍須寫入（不可因為 body 沒帶 userId 而拒絕，否則 TC-L04 會退步）")
                .hasSize(1);
    }
}
