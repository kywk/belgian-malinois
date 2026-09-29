package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/history/tasks}（簽核時間軸）的授權。
 *
 * <h2>缺陷：整條攻擊鏈的 id 發射台</h2>
 *
 * <p>改動前沒有 {@code @CallerId}、沒有 {@code requireSelf}、沒有任何參與者檢查。
 * <b>不帶參數即回傳全公司所有已完成任務</b>（id＝taskId、name、assignee、
 * processInstanceId、起訖時間），{@code ?assignee=任何人} 也可以指定他人。
 *
 * <p>它比其他讀端端點更關鍵，因為<b>它產出的 id 正是其餘端點的輸入</b>：
 * {@code PUT /api/tasks/{id}}、{@code GET /api/tasks/{id}/comments}、
 * {@code GET /api/history/tasks/{taskId}/comments}、
 * {@code GET /api/process-instances/{id}/bpmn-xml}、{@code GET /api/countersign/{taskId}}。
 * 只在那些端點加守衛等於<b>在沒有門牌的地址上加門鎖</b>。
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：{@code ResponseStatusException} 會被
 * 容器轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@code ErrorDispatchTest}）。用 MockMvc 寫，這些測試在缺陷存在時
 * 會照樣全綠。
 *
 * <h2>⚠️ 本測試組最重要的兩條是「守衛沒有過度阻擋」</h2>
 *
 * <p>{@code reviewerSeesTheWholeTimeline} 與 {@code applicantSeesTheWholeTimeline}：
 * 修掉「不帶參數回傳全公司」最直覺的作法是無條件把 assignee 收斂成呼叫者，
 * 那會讓<b>審核人打開任何案件都只看得到自己那一格</b> ——
 * 而那正是前端 {@code ApprovalTimeline.vue:41} 依賴的行為
 * （它刻意不傳 assignee，理由就是要看完整簽核軌跡）。
 * 只寫負向斷言的測試會讓那個錯誤實作全綠，所以正向情境必須與負向並列。
 *
 * <h2>「非參與者」的取樣</h2>
 *
 * <p>測試共用同一個 MSSQL 容器（見 {@code IntegrationTestBase} 類別註解），
 * 所以「某人看不到這張單」這種斷言可能因為<b>別的測試</b>而失效。
 * {@link #OUTSIDER} 沒有任何流程、沒有權限，也不在組織 fixture 裡。
 */
class HistoricTaskTimelineAuthorizationTest extends IntegrationTestBase {

    /** 沒有任何流程、沒有權限、也不在組織 fixture 裡的 id（真正的局外人）。 */
    private static final String OUTSIDER = "outsider001";

    /** 持有 audit:log:read（權限中心 fixture），但不是任何案件的參與者。 */
    private static final String AUDITOR = "dir001";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    // ── HTTP 小工具 ────────────────────────────────────────────────

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── 情境小工具 ──────────────────────────────────────────────────

    /**
     * 一張走完「主管退回 → 申請人補件 → 主管核准」的單。
     *
     * <p>需要三個已完成關卡而不是一個，才能證明「時間軸看得到<b>不屬於自己</b>的
     * 那一格」—— 只用單一關卡的話，收斂 assignee 與不收斂的結果完全相同，
     * 測試就分辨不出來。
     */
    private record ReturnedCase(String processInstanceId, String managerTaskId,
                                String revisionTaskId) {}

    private ReturnedCase returnedCaseFor(String applicant) {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", applicant, "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        taskService.complete(manager.getId(), Map.of("approved", false, "rejected", false));
        Task revision = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(revision.getName())
                .as("退回後應產生申請人的補件任務").contains("補件");
        taskService.complete(revision.getId());
        Task second = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        taskService.complete(second.getId(), Map.of("approved", true, "rejected", false));
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(pi.getId()).count())
                .as("前置條件：案件應已結案，時間軸才有內容").isZero();
        return new ReturnedCase(pi.getId(), manager.getId(), revision.getId());
    }

    private HistoricTaskInstance historic(String taskId) {
        return historyService.createHistoricTaskInstanceQuery().taskId(taskId).singleResult();
    }

    private List<String> auditDetailsFor(String operatorId) {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operator_id = '" + operatorId + "' AND operation_type = 'DATA_ACCESS' "
                    + "ORDER BY id")) {
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    // ── 收斂規則：assignee 只准查自己 ───────────────────────────────

    @Test
    @DisplayName("歷史任務不帶參數 → 只回自己的，不得回傳全公司簽核紀錄")
    void omittedParametersReturnOnlyTheCallersOwn() throws Exception {
        // mgr001 與 mgr002 各審一張不同的單 —— 否則「別人的任務」其實也是
        // 同一個人負責的任務，測試就分辨不出有沒有修好。
        String mineTask = returnedCaseFor("user001").managerTaskId();
        String theirTask = returnedCaseFor("user004").managerTaskId();
        assertThat(historic(theirTask).getAssignee())
                .as("前置條件：user004 的主管不是 mgr001").isEqualTo("mgr002");

        var res = get("/api/history/tasks", "mgr001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("缺陷期間：不帶參數即回傳全公司所有已完成任務")
                .contains(mineTask).doesNotContain(theirTask);
    }

    @Test
    @DisplayName("歷史任務帶自己的 assignee → 200（Dashboard.vue:68 的呼叫方式）")
    void ownAssigneeParameterIsAllowed() throws Exception {
        String task = returnedCaseFor("user001").managerTaskId();

        var res = get("/api/history/tasks?assignee=mgr001", "mgr001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(task);
    }

    @Test
    @DisplayName("歷史任務帶別人的 assignee → 400，不得靜默忽略")
    void foreignAssigneeIsRejected() throws Exception {
        String theirTask = returnedCaseFor("user004").managerTaskId();

        var res = get("/api/history/tasks?assignee=mgr002", "mgr001");

        assertThat(res.statusCode())
                .as("靜默忽略會讓呼叫端以為它查得到對方的簽核紀錄，而實際拿到自己的")
                .isEqualTo(400);
        assertThat(res.body())
                .as("訊息要說清楚要改什麼，否則呼叫端只會看到 400（#73）")
                .contains("assignee");
        assertThat(res.body())
                .as("400 的回應不得洩漏對方的紀錄").doesNotContain(theirTask);
    }

    @Test
    @DisplayName("空白 processInstanceId 視同未帶（不得變成查不到東西的條件）")
    void blankProcessInstanceIdIsTreatedAsOmitted() throws Exception {
        String mineTask = returnedCaseFor("user001").managerTaskId();

        // 三種可能的實作在這裡有三种不同的結果：
        //   「當成有帶」 → requireReadAccess("") → 404（謊話：其實沒有條件）
        //   「完全不理會」 → processInstanceId("") → 200 + []（也是謊話）
        //   「正規化成未帶」 → 200 + 自己的紀錄（誠實的答案）
        var res = get("/api/history/tasks?processInstanceId=", "mgr001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(mineTask);
    }

    // ── 案件參與者：審核人與申請人都要看得到完整時間軸 ──────────────

    @Test
    @DisplayName("審核人查自己參與的案件 → 200，且看得到不屬於自己的那一關")
    void reviewerSeesTheWholeTimeline() throws Exception {
        ReturnedCase c = returnedCaseFor("user001");

        // ApprovalTimeline.vue:41 就是這個呼叫：只帶 processInstanceId，刻意不帶 assignee。
        var res = get("/api/history/tasks?processInstanceId=" + c.processInstanceId(), "mgr001");

        assertThat(res.statusCode())
                .as("這條是本次修改最大的風險點：若把 assignee 一律收斂成呼叫者，"
                        + "審核人只看得到自己審過的關卡，簽核時間軸就只剩一格")
                .isEqualTo(200);
        assertThat(res.body())
                .as("申請人補件那一關的 assignee 是 user001，不是 mgr001 —— "
                        + "看不到它就代表時間軸被收斂成「只看自己」了")
                .contains(c.revisionTaskId(), c.managerTaskId());
    }

    @Test
    @DisplayName("申請人查自己的案件 → 200，且看得到主管審核那一關")
    void applicantSeesTheWholeTimeline() throws Exception {
        ReturnedCase c = returnedCaseFor("user001");

        var res = get("/api/history/tasks?processInstanceId=" + c.processInstanceId(), "user001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("managerReview 的 assignee 是 mgr001，不是申請人")
                .contains(c.managerTaskId());
    }

    @Test
    @DisplayName("兩個參數都帶 → 查詢也收斂成呼叫者（呼叫端明確要求就看窄的）")
    void bothParametersNarrowToTheCaller() throws Exception {
        ReturnedCase c = returnedCaseFor("user001");

        var res = get("/api/history/tasks?processInstanceId=" + c.processInstanceId()
                + "&assignee=user001", "user001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("assignee=user001 → 只回 user001 審過的關卡")
                .contains(c.revisionTaskId());
        assertThat(res.body())
                .as("managerReview 的 assignee 是 mgr001，帶了 assignee 就應該被收斂掉 —— "
                        + "這條固定住「兩個條件都要滿足」不只是授權、查詢也照做")
                .doesNotContain(c.managerTaskId());
    }

    // ── 負向：非關係人 404，且不得洩漏、不得改動任何東西 ─────────────

    @Test
    @DisplayName("非參與者查他人案件的簽核時間軸 → 404（不是 403）")
    void foreignProcessInstanceIsNotFound() throws Exception {
        ReturnedCase c = returnedCaseFor("user001");

        var res = get("/api/history/tasks?processInstanceId=" + c.processInstanceId(), OUTSIDER);

        assertThat(res.statusCode())
                .as("403 會確認「這個案件存在」，對可枚舉的 id 等於把枚舉管道留著")
                .isEqualTo(404);
        assertThat(res.body())
                .as("404 的回應不得洩漏任何簽核紀錄 —— 這是本端點最嚴重的後果："
                        + "taskId 可直接餵給 PUT /api/tasks/{id}")
                .doesNotContain(c.managerTaskId(), c.revisionTaskId());

        // ⚠️ 被拒的請求不得改動任何資料。這個端點是唯讀的，所以「有沒有被改動」
        // 是用稽核與歷史列的完整性來證明，而不是靠直覺。
        HistoricTaskInstance manager = historic(c.managerTaskId());
        assertThat(manager).isNotNull();
        assertThat(manager.getAssignee()).isEqualTo("mgr001");
        assertThat(manager.getEndTime()).isNotNull();
        assertThat(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(c.processInstanceId()).finished().count())
                .as("被拒的讀取不得改變案件的歷史").isEqualTo(3);
    }

    @Test
    @DisplayName("完全不認識的身分看不到任何簽核紀錄")
    void outsiderSeesNothing() throws Exception {
        ReturnedCase c = returnedCaseFor("user001");

        var all = get("/api/history/tasks", OUTSIDER);

        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.body())
                .as("查得到空集合是可接受的（那是事實）；洩漏別人的紀錄不可接受")
                .doesNotContain(c.managerTaskId(), c.revisionTaskId(), c.processInstanceId());
    }

    // ── 稽核 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("被拒絕的案件存取必須留下 DATA_ACCESS 稽核")
    void deniedReadIsAudited() throws Exception {
        ReturnedCase c = returnedCaseFor("user001");

        get("/api/history/tasks?processInstanceId=" + c.processInstanceId(), OUTSIDER);

        assertThat(auditDetailsFor(OUTSIDER))
                .as("不留痕就只剩下一堆沒有來源的 404")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"));
    }

    @Test
    @DisplayName("稽核人員（audit:log:read）可讀他人案件的時間軸，且每次留痕")
    void auditorCanReadTheTimelineAndItIsRecorded() throws Exception {
        ReturnedCase c = returnedCaseFor("user001");

        // dir001 持有 audit:log:read，且不是這張單的參與者。
        var res = get("/api/history/tasks?processInstanceId=" + c.processInstanceId(), AUDITOR);

        assertThat(res.statusCode())
                .as("調查一張單時簽核軌跡是關鍵證據，與 variables／附件同一個讀端政策")
                .isEqualTo(200);
        assertThat(res.body()).contains(c.managerTaskId());
        assertThat(auditDetailsFor(AUDITOR))
                .as("旁路必須每次留痕，否則「誰以稽核身分調閱了哪些案件」無從追查")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"auditBypass\":true"));
    }

    @Test
    @DisplayName("關係人讀自己案件的時間軸不產生稽核旁路紀錄（那是日常操作）")
    void participantTimelineReadIsNotRecordedAsBypass() throws Exception {
        ReturnedCase c = returnedCaseFor("user001");

        get("/api/history/tasks?processInstanceId=" + c.processInstanceId(), "mgr001");

        assertThat(auditDetailsFor("mgr001")).isEmpty();
    }
}
