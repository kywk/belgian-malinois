package com.bpm.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #79：簽核意見的三個端點的物件層授權。
 *
 * <h2>缺陷（真實 JWT 線上實測，2026-09-30）</h2>
 *
 * <p>三個端點<b>完全沒有</b>任何檢查：
 * <ul>
 *   <li>{@code GET /api/tasks/{id}/comments}</li>
 *   <li>{@code GET /api/history/tasks/{taskId}/comments}</li>
 *   <li>{@code POST /api/tasks/{id}/comments}</li>
 * </ul>
 *
 * <p>taskId {@code ba0454b0-…}（user001 的請假單，持有者 mgr001）上掛著
 * mgr001 寫的「薪資調幅尚未報帳，請補附件後再簽」。實測
 * {@code user001}／{@code user002}／{@code mgr002} 全部
 * {@code GET 200}，而 {@code user002} 還成功在 mgr001 的任務上留下批註，
 * 稽核記 {@code TASK_COMMENT | operatorId = user002}。
 *
 * <p>危害在於簽核意見是<b>目前唯一</b>還能讀到「誰審的、審核意見原文」的端點
 * —— 退回理由、駁回原因、薪資調幅。#76 關掉的是 taskId 的<b>發射台</b>
 * （{@code GET /api/history/tasks}），但 taskId 仍可從稽核紀錄
 * （{@code audit:log:read}）取得，所以這三個端點是同一條鏈上最後還開著的門。
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：{@code ResponseStatusException} 會被
 * 容器轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@link ErrorDispatchTest}）。用 MockMvc 寫，這些測試在缺陷存在時
 * 會照樣全綠 —— 缺陷期間回的就是 200，修好後回 404，兩者都不會被 MockMvc 翻面。
 *
 * <h2>⚠️ 每一條拒絕都同時斷言「資料沒變」與「稽核沒寫」</h2>
 *
 * <p>只斷言狀態碼不夠：這個缺陷的危險之處是<b>真的讀到了／真的寫進去了</b>。
 * 因此被拒的請求一律另外驗證批註筆數不變、回應內不含意見原文，
 * 且<b>沒有</b> {@code TASK_COMMENT} 稽核紀錄。
 *
 * <h2>⚠️ 非空斷言：fixture 必須是守衛<b>不會</b>擋下的真實資料</h2>
 *
 * <p>新守衛最典型的失效模式，是讓原本用「不存在的 id」當 fixture 的測試
 * 提前被擋下，於是「資料沒被改動」這個斷言<b>在缺陷完全存在時也成立</b>
 * （見 handoff 第 4.2 節記錄的五個案例）。本測試組的 fixture 一律是
 * <b>真流程、真 assignee、真批註</b>，而且每一條守衛都配一條
 * 「同一個 fixture、不同身分」的放行對照：
 *
 * <ul>
 *   <li>{@link #unrelatedUserCannotReadTheApprovalComment} ↔
 *       {@link #taskHolderCanStillReadIt}／{@link #applicantCanStillReadIt}</li>
 *   <li>{@link #auditorCanStillReadItAndIsRecorded}（稽核旁路）</li>
 *   <li>{@link #unrelatedUserCannotComment} ↔
 *       {@link #holderCanComment}／{@link #applicantCanComment}</li>
 * </ul>
 *
 * <p>只有負向斷言時，「整條守衛壞掉、所有人都 404」也會讓它們全綠；
 * 只有正向斷言時，「守衛放行所有人」也會讓它們全綠。兩者必須並列。
 *
 * <h2>「非關係人」的取樣</h2>
 *
 * <p>{@link #OUTSIDER} 沒有任何流程、沒有權限，也不在組織 fixture 裡；
 * {@code user002} 是一個<b>真實登入者</b>、與該案無關 —— 後者才是實測報告裡的
 * 攻擊者，也是最危險的取樣。兩個都取樣，因為它們失敗的方式不同：
 * 前者可能只是「沒這個人」，後者是「有這個人但他不該看」。
 *
 * <h2>#79-2：對已結束關卡留言由 500 改為 404 —— 本組新增的三條是<b>對照組</b></h2>
 *
 * <p>使用者的裁決是回 404（與其他「找不到東西」的回應一致，呼叫端不會再重試）。
 * 但「回 404」本身有兩種截然不同的實作，必須用測試把它們分開：
 * <ul>
 *   <li><b>正確</b>：只有「runtime 裡沒有這個任務」才 404。
 *       由 {@link #commentingOnARunningTaskStillWorksAfterTheFix} 釘住。</li>
 *   <li><b>錯誤，且所有負向測試都會綠</b>：把 catch 寫得太寬
 *       （{@code catch (Exception)}）或直接在守衛擋掉，導致<b>所有</b>留言都 404。
 *       {@link #commentingOnAFinishedTaskIsNotFound} 一樣會通過。
 *       唯一能分辨的證據就是那條「執行中仍然 200 + 批註筆數 +1 + 稽核 +1」。</li>
 *   <li><b>第三種錯誤</b>：連<b>讀</b>端一起擋掉。
 *       已結束關卡的簽核意見屬於 {@code ACT_HI_COMMENT}，與 runtime 無關，
 *       擋掉會讓 {@code ApprovalTimeline} 在審結案件上整段空白 ——
 *       而且它 {@code catch} 成 {@code []}，<b>不會報錯</b>。
 *       由 {@link #readingCommentsOfAFinishedTaskStillWorksAfterTheFix} 釘住。</li>
 * </ul>
 *
 * <p>三條都另外驗「批註筆數沒變」與「稽核沒寫」：狀態碼對了不代表
 * 「什麼都沒發生」被驗過，而 404 最大的風險就是
 * 「回應看起來合理，但資料其實寫了一半」。
 */
class CommentAuthorizationTest extends IntegrationTestBase {

    /** 沒有任何流程、沒有權限、也不在組織 fixture 裡的 id（真正的局外人）。 */
    private static final String OUTSIDER = "outsider001";

    /** 持有 audit:log:read（權限中心 fixture），但不是 leave-approval 案件的參與者。 */
    private static final String AUDITOR = "dir001";

    /** 缺陷實測讀到的真實內容。斷言用「回應不得含有它」，比斷言 404 更有力。 */
    private static final String SENSITIVE = "薪資調幅尚未報帳，請補附件後再簽";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

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

    private HttpResponse<String> post(String path, String userId, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── 情境小工具 ──────────────────────────────────────────────────

    /**
     * 用 {@code taskService} 直接寫入批註，<b>不經過被測的 controller</b>。
     *
     * <p>理由：fixture 若依賴守衛本身，守衛壞掉時 fixture 會一起壞掉，
     * 測試就從「驗證守衛」變成「驗證 fixture」。而且
     * {@code Authentication} 是 ThreadLocal，必須用 try/finally 還原
     * ——與 {@code TaskController.addComment} 相同的原因。
     */
    private void writeComment(String taskId, String pid, String author, String message) {
        String previous = Authentication.getAuthenticatedUserId();
        try {
            Authentication.setAuthenticatedUserId(author);
            taskService.addComment(taskId, pid, message);
        } finally {
            Authentication.setAuthenticatedUserId(previous);
        }
    }

    private record Case(String pid, String managerTaskId) {}

    /** 一張掛著真實簽核意見的執行中案件（持有者 = mgr001，申請人 = initiator）。 */
    private Case commentedRunningCase(String initiator) {
        String pid = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", initiator, "leaveType", "annual", "days", 1)).getId();
        Task manager = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核關卡的持有者必須是 mgr001")
                .isEqualTo("mgr001");
        writeComment(manager.getId(), pid, "mgr001", SENSITIVE);
        return new Case(pid, manager.getId());
    }

    private record ReturnedCase(String pid, String managerTaskId, String revisionTaskId) {}

    /**
     * 一張走完「主管退回 → 申請人補件 → 主管核准」的單。
     *
     * <p>需要<b>三個</b>已完成關卡，且其中一個（{@code applicantRevision}）
     * 的 assignee 是 user001 而非 mgr001 —— 否則「只看得見自己審過的關卡」
     * 與「看得見完整軌跡」會得到相同的回應，測試就分辨不出來。
     */
    private ReturnedCase finishedCaseWithComments() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        writeComment(manager.getId(), pi.getId(), "mgr001", SENSITIVE);
        taskService.complete(manager.getId(), Map.of("approved", false, "rejected", false));

        Task revision = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(revision.getName()).as("退回後應產生申請人的補件任務").contains("補件");
        assertThat(revision.getAssignee())
                .as("前置條件：補件關卡的 assignee 必須不是 mgr001，否則本測組無法分辨")
                .isEqualTo("user001");
        writeComment(revision.getId(), pi.getId(), "user001", "已補上報帳憑證");
        taskService.complete(revision.getId());

        Task second = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        taskService.complete(second.getId(), Map.of("approved", true, "rejected", false));
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count())
                .as("前置條件：案件應已結案，時間軸才有內容").isZero();
        return new ReturnedCase(pi.getId(), manager.getId(), revision.getId());
    }

    private List<String> auditDetailsFor(String operatorId, String operationType) {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operator_id = '" + operatorId + "' AND operation_type = '"
                    + operationType + "' ORDER BY id")) {
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    private int auditCountFor(String operatorId, String operationType) {
        int[] n = {-1};
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log "
                    + "WHERE operator_id = '" + operatorId + "' AND operation_type = '"
                    + operationType + "'")) {
                var rs = ps.executeQuery();
                if (rs.next()) n[0] = rs.getInt(1);
            }
        });
        return n[0];
    }

    /** 批註筆數。用 engine 讀，而不是 HTTP —— 要證明的是資料本身沒變。 */
    private int commentCount(String taskId) {
        return taskService.getTaskComments(taskId).size();
    }

    // ── 讀端：非關係人 404，且不得洩漏 ─────────────────────────────

    @Test
    @DisplayName("#79：無關的使用者讀不到別人的簽核意見（兩個讀端點都是 404）")
    void unrelatedUserCannotReadTheApprovalComment() throws Exception {
        Case c = commentedRunningCase("user001");

        var live = get("/api/tasks/" + c.managerTaskId() + "/comments", "user002");
        var history = get("/api/history/tasks/" + c.managerTaskId() + "/comments", "user002");

        assertThat(live.statusCode())
                .as("缺陷期間這裡回 200，且 user002 讀到 mgr001 寫的意見全文")
                .isEqualTo(404);
        assertThat(history.statusCode())
                .as("缺陷期間這個「歷史」端點同樣回 200 —— 它讀的是同一張 ACT_HI_COMMENT")
                .isEqualTo(404);
        assertThat(live.body()).as("404 的回應不得洩漏意見原文").doesNotContain(SENSITIVE);
        assertThat(history.body()).as("404 的回應不得洩漏意見原文").doesNotContain(SENSITIVE);

        // 被拒的讀取不得改動任何資料。
        assertThat(commentCount(c.managerTaskId()))
                .as("被拒的讀取不得動到批註").isEqualTo(1);
        assertThat(taskService.getTaskComments(c.managerTaskId()).get(0).getUserId())
                .as("批註的作者不得被改寫").isEqualTo("mgr001");
    }

    @Test
    @DisplayName("#79：完全不認識的身分也讀不到（不帶關聯性的第二種取樣）")
    void outsiderCannotReadTheApprovalComment() throws Exception {
        Case c = commentedRunningCase("user001");

        assertThat(get("/api/tasks/" + c.managerTaskId() + "/comments", OUTSIDER).statusCode())
                .isEqualTo(404);
        assertThat(get("/api/history/tasks/" + c.managerTaskId() + "/comments", OUTSIDER)
                .statusCode()).isEqualTo(404);
        assertThat(commentCount(c.managerTaskId())).isEqualTo(1);
    }

    @Test
    @DisplayName("#79：不存在的 taskId 也是 404，不得回 200 + []")
    void unknownTaskIsNotFound() throws Exception {
        String ghost = UUID.randomUUID().toString();

        var live = get("/api/tasks/" + ghost + "/comments", "user001");
        var history = get("/api/history/tasks/" + ghost + "/comments", "user001");

        // 改動前兩個端點都是 200 + []。「查不到」與「沒權看」塌成同一個回應，
        // 呼叫端完全無法分辨該怎麼處理（與 ProcessAccessGuard.InstanceState 同理）。
        assertThat(live.statusCode()).isEqualTo(404);
        assertThat(history.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("#79：被拒絕的簽核意見存取必須留下 DATA_ACCESS 稽核")
    void deniedReadIsAudited() throws Exception {
        Case c = commentedRunningCase("user001");

        get("/api/tasks/" + c.managerTaskId() + "/comments", OUTSIDER);

        assertThat(auditDetailsFor(OUTSIDER, "DATA_ACCESS"))
                .as("不留痕就只剩下一堆沒有來源的 404")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"));
    }

    // ── 讀端：放行的對照（沒有這些，「整條守衛壞掉」會無處藏身）─────

    @Test
    @DisplayName("#79：任務持有者仍看得到（CommentPanel.vue:31 的路徑）")
    void taskHolderCanStillReadIt() throws Exception {
        Case c = commentedRunningCase("user001");

        var res = get("/api/tasks/" + c.managerTaskId() + "/comments", "mgr001");

        assertThat(res.statusCode())
                .as("CommentPanel 綁的 taskId 來自待辦清單，也就是呼叫者持有或可認領的任務 —— "
                        + "守衛不得把這條路擋掉")
                .isEqualTo(200);
        assertThat(res.body()).contains(SENSITIVE);
    }

    @Test
    @DisplayName("#79：申請人（關係人）仍看得到主管寫的意見")
    void applicantCanStillReadIt() throws Exception {
        Case c = commentedRunningCase("user001");

        var res = get("/api/tasks/" + c.managerTaskId() + "/comments", "user001");

        assertThat(res.statusCode())
                .as("申請人是 initiator，屬於 isParticipant 的條件 1")
                .isEqualTo(200);
        assertThat(res.body()).contains(SENSITIVE);
    }

    @Test
    @DisplayName("#79：稽核人員（audit:log:read）讀得到，且旁路每次留痕")
    void auditorCanStillReadItAndIsRecorded() throws Exception {
        Case c = commentedRunningCase("user001");

        var res = get("/api/tasks/" + c.managerTaskId() + "/comments", AUDITOR);

        assertThat(res.statusCode())
                .as("調查一張單時簽核意見是關鍵證據，與 variables／附件同一個讀端政策")
                .isEqualTo(200);
        assertThat(res.body()).contains(SENSITIVE);
        assertThat(auditDetailsFor(AUDITOR, "DATA_ACCESS"))
                .as("旁路必須每次留痕，否則「誰以稽核身分調閱了哪些案件」無從追查")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"auditBypass\":true"));
    }

    @Test
    @DisplayName("#79：關係人讀自己案件的意見不產生稽核旁路紀錄（那是日常操作）")
    void participantReadIsNotRecordedAsBypass() throws Exception {
        Case c = commentedRunningCase("user001");

        get("/api/tasks/" + c.managerTaskId() + "/comments", "mgr001");

        assertThat(auditDetailsFor("mgr001", "DATA_ACCESS"))
                .as("每次開單都會讀一次，留痕會把稽核表淹掉")
                .isEmpty();
    }

    // ── 前端相容性：審核人仍看得到完整軌跡 ──────────────────────────

    /**
     * {@code ApprovalTimeline.vue:41-47} 的逐字重演。
     *
     * <p>它先 {@code getHistoricTasks({ processInstanceId })} 取時間軸，
     * 再<b>對每一個 taskId</b> 呼叫 {@code getHistoricTaskComments}。
     * 這條測試必須同時證明兩件事：
     * <ol>
     *   <li>時間軸本身看得到（#76 修的），</li>
     *   <li>時間軸上<b>每一個</b> taskId 的意見都讀得到 —— 包括
     *       assignee 是 user001 的補件那一關。</li>
     * </ol>
     * 只斷言 (1) 會讓「意見端點被收斂成只看自己審過的關卡」全綠通過，
     * 而那正是本工項最大的迴歸風險。
     */
    @Test
    @DisplayName("#79：審核人仍能逐筆讀完自己參與案件的完整簽核軌跡")
    void reviewerCanStillTraverseTheWholeApprovalTimeline() throws Exception {
        ReturnedCase c = finishedCaseWithComments();

        var timeline = get("/api/history/tasks?processInstanceId=" + c.pid(), "mgr001");
        assertThat(timeline.statusCode()).isEqualTo(200);

        List<String> taskIds = new ArrayList<>();
        for (JsonNode node : mapper.readTree(timeline.body())) taskIds.add(node.get("id").asText());
        assertThat(taskIds)
                .as("前置條件：時間軸必須含三個關卡（主管關卡退回前後各一次），"
                        + "否則「看得到別人那一關」無從驗證")
                .hasSize(3)
                .contains(c.managerTaskId(), c.revisionTaskId());

        var revisionComments = get("/api/history/tasks/" + c.revisionTaskId() + "/comments", "mgr001");
        assertThat(revisionComments.statusCode())
                .as("這一關的 assignee 是 user001，不是 mgr001 —— 若守衛把意見收斂成"
                        + "「只看自己審過的關卡」，審核人就看不到別人那一關的意見，"
                        + "而 ApprovalTimeline 只會把它 catch 掉、畫面上少幾段文字")
                .isEqualTo(200);
        assertThat(revisionComments.body()).contains("已補上報帳憑證");

        var managerComments = get("/api/history/tasks/" + c.managerTaskId() + "/comments", "mgr001");
        assertThat(managerComments.statusCode()).isEqualTo(200);
        assertThat(managerComments.body()).contains(SENSITIVE);
    }

    @Test
    @DisplayName("#79：「歷史」端點對執行中的任務仍然可用（不得因只查歷史而 404）")
    void historyEndpointStillWorksForRunningTasks() throws Exception {
        Case c = commentedRunningCase("user001");
        assertThat(taskService.createTaskQuery().taskId(c.managerTaskId()).count())
                .as("前置條件：這個任務必須還在執行中").isEqualTo(1);

        var res = get("/api/history/tasks/" + c.managerTaskId() + "/comments", "mgr001");

        assertThat(res.statusCode())
                .as("getTaskComments 讀的是 ACT_HI_COMMENT，與任務是否結束無關 —— "
                        + "taskId 的解析若只查歷史表，會把這條既有可用的路徑打成 404")
                .isEqualTo(200);
        assertThat(res.body()).contains(SENSITIVE);
    }

    @Test
    @DisplayName("#79：非關係人連執行中任務的意見都讀不到")
    void outsiderCannotReadRunningTaskComments() throws Exception {
        Case c = commentedRunningCase("user001");

        var res = get("/api/history/tasks/" + c.managerTaskId() + "/comments", OUTSIDER);

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).doesNotContain(SENSITIVE);
        assertThat(commentCount(c.managerTaskId())).isEqualTo(1);
    }

    // ── 寫端：非關係人 404，且不得寫入任何東西 ───────────────────────

    @Test
    @DisplayName("#79：無關的使用者不得在別人的任務上留言（且不得留下任何痕跡）")
    void unrelatedUserCannotComment() throws Exception {
        Case c = commentedRunningCase("user001");

        var res = post("/api/tasks/" + c.managerTaskId() + "/comments", "user002",
                "{\"message\":\"我來插一句\"}");

        assertThat(res.statusCode())
                .as("缺陷期間這裡回 200，稽核留下 TASK_COMMENT | operatorId = user002")
                .isEqualTo(404);
        assertThat(commentCount(c.managerTaskId()))
                .as("被拒的留言不得寫進去 —— 只看狀態碼會漏掉這個")
                .isEqualTo(1);
        assertThat(taskService.getTaskComments(c.managerTaskId()))
                .allSatisfy(x -> assertThat(x.getFullMessage()).isEqualTo(SENSITIVE));
        assertThat(auditCountFor("user002", "TASK_COMMENT"))
                .as("不得寫出任何 TASK_COMMENT 紀錄 —— 那會讓稽核看起來像 user002 真的簽過")
                .isZero();
        assertThat(auditDetailsFor("user002", "DATA_ACCESS"))
                .as("被拒的存取嘗試本身值得知道").isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"));
    }

    /**
     * #79-2：對<b>已完成</b>的關卡留言由裸 500 改為 404（使用者已裁決）。
     *
     * <p>2026-09-30 線上實測（改動前）：對一個已完成的 taskId 留言，
     * 授權會通過（守衛查得到歷史，所以 pid 找得到、關係人也成立），
     * 接著 {@code AddCommentCmd} 因為 runtime 裡沒有這個任務而拋
     * {@code FlowableObjectNotFoundException} → 500。
     *
     * <p>改動前完全相同（{@code task == null} → pid 傳 null → 同一個例外），
     * 所以不是 #79 的迴歸。但「你有權，但這件事做不成」被講成「伺服器壞了」，
     * 而 500 讓呼叫端一直重試 —— 那個重試永遠不會成功。
     *
     * <p>⚠️ <b>這條測試本身不足以證明修法正確</b>：把整個守衛拿掉、
     * 讓每個 taskId 都回 404，它的斷言一樣會過。真正的對照是
     * {@link #commentingOnARunningTaskStillWorksAfterTheFix} —— 執行中
     * 的任務仍然必須留言成功。
     */
    @Test
    @DisplayName("#79-2：對已完成的關卡留言是 404，不是裸 500")
    void commentingOnAFinishedTaskIsNotFound() throws Exception {
        ReturnedCase c = finishedCaseWithComments();
        assertThat(taskService.createTaskQuery().taskId(c.managerTaskId()).count())
                .as("前置條件：這個關卡必須已經結束，否則測的是別的東西").isZero();
        truncateAuditLog();

        var res = post("/api/tasks/" + c.managerTaskId() + "/comments", "mgr001",
                "{\"message\":\"事後附註\"}");

        assertThat(res.statusCode())
                .as("改動前這裡是 500（FlowableObjectNotFoundException 未被翻譯）。"
                        + "404 與其他「找不到東西」的回應一致，呼叫端不會再重試")
                .isEqualTo(404);
        assertThat(res.body())
                .as("404 的回應不得把引擎例外或簽核意見原文送出去")
                .doesNotContain(SENSITIVE);

        // 非空斷言：狀態碼對了不代表「什麼都沒發生」被驗過。
        assertThat(commentCount(c.managerTaskId()))
                .as("被拒的留言不得寫進去 —— 只看狀態碼會漏掉這個")
                .isEqualTo(1);
        assertThat(taskService.getTaskComments(c.managerTaskId()))
                .allSatisfy(x -> assertThat(x.getFullMessage()).isEqualTo(SENSITIVE));
        assertThat(auditCountFor("mgr001", "TASK_COMMENT"))
                .as("沒有留言發生就不該宣稱有 —— 與「被拒的請求不得寫出稽核」同一條原則")
                .isZero();
    }

    /**
     * ⚠️ <b>#79-2 的必要對照：不能把「所有留言都 404」當成修好。</b>
     *
     * <p>這是本工項最危險的失敗形狀：例外轉譯如果寫得太寬
     * （例如 {@code catch (Exception)}），會把<b>執行中</b>的任務也打成 404，
     * 而所有「已完成 → 404」的測試仍然全綠。
     *
     * <p>因此這條必須同時斷言三件事，缺一不可：
     * <ol>
     *   <li>狀態碼是 200；</li>
     *   <li>批註筆數真的 +1（不是「碰巧沒被擋掉但也沒寫進去」）；</li>
     *   <li>稽核真的寫出一筆 {@code TASK_COMMENT} —— 這一條同時讓上面
     *       {@link #commentingOnAFinishedTaskIsNotFound} 的「稽核沒寫」
     *       斷言不是空轉。</li>
     * </ol>
     */
    @Test
    @DisplayName("#79-2：執行中（未完成）的任務仍然留言成功 —— 404 不得擴散到正常路徑")
    void commentingOnARunningTaskStillWorksAfterTheFix() throws Exception {
        Case c = commentedRunningCase("user001");
        assertThat(taskService.createTaskQuery().taskId(c.managerTaskId()).count())
                .as("前置條件：這個任務必須還在執行中").isEqualTo(1);
        truncateAuditLog();

        var res = post("/api/tasks/" + c.managerTaskId() + "/comments", "mgr001",
                "{\"message\":\"執行中補一句\",\"userId\":\"current_user\"}");

        assertThat(res.statusCode())
                .as("修法只該影響「runtime 裡沒有這個任務」；CommentPanel.vue:35 與 "
                        + "ActionDialog.vue:61 綁的都是待辦清單裡的執行中任務，"
                        + "把它們打成 404 就是把整個前端留言功能打死")
                .isEqualTo(200);
        assertThat(commentCount(c.managerTaskId()))
                .as("真的寫進去了 —— 只斷言 200 會讓「200 但沒寫入」也通過")
                .isEqualTo(2);
        assertThat(taskService.getTaskComments(c.managerTaskId()).get(1).getUserId())
                .as("作者仍是實際的呼叫者")
                .isEqualTo("mgr001");
        assertThat(auditCountFor("mgr001", "TASK_COMMENT"))
                .as("正向下端必須真的寫出稽核，否則「稽核沒寫」的負向斷言是空的")
                .isEqualTo(1);
    }

    /**
     * ⚠️ <b>#79-2 的第二個對照：讀端完全不受影響。</b>
     *
     * <p>本工項只動寫入端。若連讀端一起擋掉，已結束關卡的簽核軌跡就看不到了 ——
     * {@code ApprovalTimeline.vue:44} 正是對 {@code t.endTime} 為真的 taskId
     * 呼叫<b>歷史讀</b>端點，畫面上會整段空白，而且它會把錯誤
     * {@code catch} 成 {@code []}（{@code ApprovalTimeline.vue:45}），
     * <b>不會報錯</b> —— 「測試全綠但畫面上少了簽核意見」是最難察覺的一種迴歸。
     *
     * <p>所以這條同時驗<b>兩個</b>讀端點對已結束關卡都必須讀得到，
     * 而且批註筆數與內容不得因為讀取而改變。
     */
    @Test
    @DisplayName("#79-2：已完成任務的簽核意見仍然讀得到（兩個讀端點）")
    void readingCommentsOfAFinishedTaskStillWorksAfterTheFix() throws Exception {
        ReturnedCase c = finishedCaseWithComments();
        assertThat(taskService.createTaskQuery().taskId(c.managerTaskId()).count())
                .as("前置條件：這個關卡必須已經結束").isZero();

        var live = get("/api/tasks/" + c.managerTaskId() + "/comments", "mgr001");
        var history = get("/api/history/tasks/" + c.managerTaskId() + "/comments", "mgr001");

        assertThat(live.statusCode())
                .as("getTaskComments 讀的是 ACT_HI_COMMENT，與任務是否還在 runtime 無關 —— "
                        + "把它擋掉會讓審結案件的簽核軌跡在 ApprovalTimeline 上整段消失，"
                        + "而 ApprovalTimeline.vue:45 把錯誤 catch 成 []，不會報錯")
                .isEqualTo(200);
        assertThat(history.statusCode())
                .as("ApprovalTimeline.vue:44 對已結束關卡呼叫的就是這個端點")
                .isEqualTo(200);
        assertThat(live.body()).contains(SENSITIVE);
        assertThat(history.body()).contains(SENSITIVE);

        // 補件那一關（assignee 是 user001 而非 mgr001）也要讀得到，
        // 否則「只看得見自己審過的關卡」這種收斂會悄悄發生。
        assertThat(get("/api/history/tasks/" + c.revisionTaskId() + "/comments", "mgr001")
                .statusCode()).isEqualTo(200);

        assertThat(commentCount(c.managerTaskId()))
                .as("讀取不得動到批註").isEqualTo(1);
    }

    @Test
    @DisplayName("#79：對不存在的 taskId 留言是 404，不是裸 500")
    void unknownTaskCannotBeCommentedOn() throws Exception {
        String ghost = UUID.randomUUID().toString();

        var res = post("/api/tasks/" + ghost + "/comments", "user001",
                "{\"message\":\"沒有這張單\"}");

        // 負向控制組實測（拿掉守衛後）：這裡回 500，body 是
        // FlowableObjectNotFoundException: Cannot find task with id …。
        // 引擎的 AddCommentCmd 確實會驗任務存在，所以不會產生孤兒批註，
        // 但那個例外沒被翻成 404 → 呼叫端會一直重試。
        assertThat(res.statusCode())
                .as("與 updateTask 對不存在任務的處理一致；500 會讓呼叫端一直重試")
                .isEqualTo(404);
        assertThat(auditCountFor("user001", "TASK_COMMENT"))
                .as("沒有稽核紀錄就代表沒有 commit（兩者在同一個交易裡）")
                .isZero();
    }

    // ── 寫端：放行的對照 ───────────────────────────────────────────

    @Test
    @DisplayName("#79：任務持有者仍可留言（ActionDialog.vue:61 / CommentPanel.vue:35）")
    void holderCanComment() throws Exception {
        Case c = commentedRunningCase("user001");

        var res = post("/api/tasks/" + c.managerTaskId() + "/comments", "mgr001",
                "{\"message\":\"同意，但請附上交接清單\",\"userId\":\"current_user\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(commentCount(c.managerTaskId())).isEqualTo(2);
        assertThat(taskService.getTaskComments(c.managerTaskId()).get(1).getUserId())
                .as("作者必須是實際的呼叫者 —— 這同時固定了 body 的 userId 不會覆寫身分")
                .isEqualTo("mgr001");
        assertThat(auditCountFor("mgr001", "TASK_COMMENT"))
                .as("正向下端必須真的寫出稽核，否則上一條「稽核沒寫」的斷言是空的")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#79：申請人（關係人、非持有者）仍可留言 —— 守衛用的是 requireParticipant")
    void applicantCanComment() throws Exception {
        Case c = commentedRunningCase("user001");
        assertThat(taskService.createTaskQuery().taskId(c.managerTaskId()).singleResult()
                .getAssignee())
                .as("前置條件：這個任務的持有者是 mgr001，不是 user001")
                .isEqualTo("mgr001");

        var res = post("/api/tasks/" + c.managerTaskId() + "/comments", "user001",
                "{\"message\":\"請問需要補什麼格式？\"}");

        assertThat(res.statusCode())
                .as("批註不改變流程走向（spec §4.5），屬於「寫個案」而不是「動作任務」—— "
                        + "若誤用 requireHolder，申請人就會被平白擋掉")
                .isEqualTo(200);
        assertThat(commentCount(c.managerTaskId())).isEqualTo(2);
    }

    @Test
    @DisplayName("#79：稽核人員不得代別人留言（寫入端不開稽核旁路）")
    void auditorCannotComment() throws Exception {
        Case c = commentedRunningCase("user001");

        var res = post("/api/tasks/" + c.managerTaskId() + "/comments", AUDITOR,
                "{\"message\":\"稽核意見\"}");

        assertThat(res.statusCode())
                .as("稽核人員的職責是查閱，不是替別人的案子補簽核意見 —— "
                        + "旁路的理由在寫入端不成立")
                .isEqualTo(404);
        assertThat(commentCount(c.managerTaskId())).isEqualTo(1);
        assertThat(auditCountFor(AUDITOR, "TASK_COMMENT")).isZero();
    }
}
