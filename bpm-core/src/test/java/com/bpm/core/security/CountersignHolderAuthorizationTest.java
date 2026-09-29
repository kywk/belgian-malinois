package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.HistoryService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CountersignController} 三個端點的持有者守衛。
 *
 * <h2>缺陷：整個 class 連 {@code @CallerId} 都沒有</h2>
 *
 * <p>這不是「讀到不該讀的資料」，而是<b>業務停擺</b>：加簽子任務一經建立，
 * 父任務就會被 {@code TaskController} 的「有未完成的加簽子任務」守門<b>永久</b>擋住
 * （409），直到那筆子任務完成為止。任何登入者都能對<b>任何</b>任務加簽，
 * 攻擊者只要建立一筆自己不去完成的子任務就能讓案件停擺，
 * 而且沒有任何錯誤訊息指出原因。
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：{@code ResponseStatusException} 會被
 * 容器轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@code ErrorDispatchTest}）。用 MockMvc 寫，這些測試在缺陷存在時
 * 會照樣全綠。
 *
 * <h2>⚠️ 每一條被拒絕的斷言都同時驗「資料真的沒變」</h2>
 *
 * <p>這個缺陷的危險之處不在回應狀態碼，而在於<b>狀態真的被改掉</b>：
 * 子任務落地、意見寫進去、父任務被卡住。因此被拒的請求一律另外驗證
 * 子任務不存在、comment 沒寫入、案件仍在執行、父任務仍可正常完成。
 */
class CountersignHolderAuthorizationTest extends IntegrationTestBase {

    /** 沒有任何流程、沒有權限、也不在組織 fixture 裡的 id（真正的局外人）。 */
    private static final String OUTSIDER = "outsider001";

    /** user001 的主管關卡 assignee（leave-approval 的 managerReview）。 */
    private static final String HOLDER = "mgr001";

    /** 被加簽者（子任務的 assignee）。 */
    private static final String COUNTERSIGNED = "user003";

    /** 組織 fixture 裡不存在的人（用來驗「加簽對象必須存在」）。 */
    private static final String NOT_AN_EMPLOYEE = "nosuchemployee999";

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

    private HttpResponse<String> post(String path, String userId, String body) throws Exception {
        return send("POST", path, userId, body);
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        return send("GET", path, userId, null);
    }

    private HttpResponse<String> put(String path, String userId, String body) throws Exception {
        return send("PUT", path, userId, body);
    }

    private HttpResponse<String> send(String method, String path, String userId, String body)
            throws Exception {
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .method(method, payload);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ── 情境小工具 ──────────────────────────────────────────────────

    /** 以 applicant 啟動 leave-approval，回傳主管審核任務（assignee = mgr001）。 */
    private Task managerTaskFor(String applicant) {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", applicant, "leaveType", "annual", "days", 1));
        return taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
    }

    private Task currentTask(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).singleResult();
    }

    private String createBody(String target) {
        return "{\"countersignUserId\":\"" + target + "\",\"message\":\"請協助確認\"}";
    }

    /** 由持有者建立一筆加簽，回傳子任務 id。 */
    private String addCountersignAsHolder(String parentId, String target) throws Exception {
        var res = post("/api/countersign/" + parentId, HOLDER, createBody(target));
        assertThat(res.statusCode()).isEqualTo(200);
        List<Task> subs = taskService.getSubTasks(parentId);
        assertThat(subs).hasSize(1);
        return subs.get(0).getId();
    }

    /**
     * 被拒的建立請求之後，父任務必須<b>仍然可以正常完成</b>。
     *
     * <p>這是防 DoS 的決定性斷言：只要有任何一筆子任務落地，守門就會回 409，
     * 這個請求就會變成 409 而非 200。也就是說這一條不可能「因為實作順序不對」
     * 而假綠。
     */
    private void assertNoCountersignWasCreatedAndTheCaseStillMoves(Task parent) throws Exception {
        assertThat(taskService.getSubTasks(parent.getId()))
                .as("被拒的建立請求不得留下任何子任務 —— 那就是持久化 DoS："
                        + "父任務會被 409 守門永久擋住")
                .isEmpty();
        assertThat(taskService.getTaskComments(parent.getId()))
                .as("被拒的請求不得留下任何 comment").isEmpty();

        var complete = put("/api/tasks/" + parent.getId(), HOLDER,
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":true}]}");
        assertThat(complete.statusCode())
                .as("父任務必須仍可正常完成 —— 有未完成子任務時這裡會是 409")
                .isEqualTo(200);
        assertThat(currentTask(parent.getId()))
                .as("案件必須真的走完").isNull();
    }

    // ── 1. POST：非持有者不得建立加簽（DoS 防線）────────────────────

    @Test
    @DisplayName("無關的使用者不得建立加簽（否則父任務被 409 永久卡住 = 業務停擺）")
    void strangerCannotCreateCountersign() throws Exception {
        Task parent = managerTaskFor("user001");
        assertThat(parent.getAssignee()).isEqualTo(HOLDER);

        var res = post("/api/countersign/" + parent.getId(), OUTSIDER, createBody(COUNTERSIGNED));

        assertThat(res.statusCode())
                .as("缺陷期間回 200，攻擊者建立一筆自己不去完成的子任務即可讓案件停擺")
                .isEqualTo(404);
        assertNoCountersignWasCreatedAndTheCaseStillMoves(parent);
    }

    @Test
    @DisplayName("申請人（案件的關係人）也不得替主管加簽")
    void applicantCannotCreateCountersignOnTheManagersTask() throws Exception {
        // user001 是這個案件的 initiator，因此 isParticipant 為真。
        // 這一條是「用 requireHolder 而不是 requireParticipant」的決定性證據：
        // 若守衛用參與者判定，這裡會是 200，而申請人就能替主管決定找誰加簽。
        Task parent = managerTaskFor("user001");

        var res = post("/api/countersign/" + parent.getId(), "user001", createBody(COUNTERSIGNED));

        assertThat(res.statusCode())
                .as("加簽是把工作轉給別人的權力，屬於持有者，不是任何關係人")
                .isEqualTo(404);
        assertNoCountersignWasCreatedAndTheCaseStillMoves(parent);
    }

    @Test
    @DisplayName("持有者本人建立加簽 → 200 且子任務指派給指定的人")
    void holderCanCreateCountersign() throws Exception {
        Task parent = managerTaskFor("user001");

        var res = post("/api/countersign/" + parent.getId(), HOLDER, createBody(COUNTERSIGNED));

        assertThat(res.statusCode()).isEqualTo(200);
        List<Task> subs = taskService.getSubTasks(parent.getId());
        assertThat(subs).hasSize(1);
        assertThat(subs.get(0).getAssignee()).isEqualTo(COUNTERSIGNED);
        assertThat(subs.get(0).getParentTaskId()).isEqualTo(parent.getId());
    }

    @Test
    @DisplayName("加簽對象不是組織系統認識的人 → 400，且不得建立任何子任務")
    void unknownCountersignTargetIsRejected() throws Exception {
        // 這與「assignee 為空白」是同一個死鎖的兩個觸發點：
        // 子任務指派給一個沒有人認得的人 → 沒有人看得到、沒有人能完成
        // → 父任務被 409 永久擋住。空白至少回 400，打錯字不會有任何訊號。
        Task parent = managerTaskFor("user001");

        var res = post("/api/countersign/" + parent.getId(), HOLDER, createBody(NOT_AN_EMPLOYEE));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains(NOT_AN_EMPLOYEE);
        assertNoCountersignWasCreatedAndTheCaseStillMoves(parent);
    }

    // ── 2. GET：加簽鏈是 subtaskId 的發射台 ─────────────────────────

    @Test
    @DisplayName("非持有者不得列出加簽鏈（subtaskId 是 complete 端點的輸入）")
    void strangerCannotListTheCountersignChain() throws Exception {
        Task parent = managerTaskFor("user001");
        String sub = addCountersignAsHolder(parent.getId(), COUNTERSIGNED);

        var res = get("/api/countersign/" + parent.getId(), OUTSIDER);

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body())
                .as("回應不得含 subtaskId 或被指派人 —— 否則等於把下一站的攻擊輸入送出來")
                .doesNotContain(sub).doesNotContain(COUNTERSIGNED);
    }

    @Test
    @DisplayName("申請人不得列出加簽鏈，但持有者可以（DocumentDetail 的「加簽狀態」卡片）")
    void onlyHolderCanListTheCountersignChain() throws Exception {
        Task parent = managerTaskFor("user001");
        addCountersignAsHolder(parent.getId(), COUNTERSIGNED);

        assertThat(get("/api/countersign/" + parent.getId(), "user001").statusCode())
                .as("DocumentDetail.vue 只在自己的收件匣找到任務時才呼叫它，"
                        + "因此看得到加簽狀態的人本來就是持有者")
                .isEqualTo(404);

        var mine = get("/api/countersign/" + parent.getId(), HOLDER);
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(mine.body()).contains(COUNTERSIGNED);
    }

    // ── 3. PUT complete：配對正確也不等於你可以簽 ───────────────────

    @Test
    @DisplayName("非子任務 assignee 的人不得完成加簽（配對正確也擋）")
    void strangerCannotCompleteACountersign() throws Exception {
        Task parent = managerTaskFor("user001");
        String sub = addCountersignAsHolder(parent.getId(), COUNTERSIGNED);

        // ⚠️ 這一組路徑參數是<b>正確配對</b>的 —— 舊的 CountersignTamperTest
        // 只測了不配對，沒測「配對正確但非本人」，而缺陷期間這裡回 200：
        // 加簽人從未表態，子任務消失，父任務的守門立刻放行，案子照樣過關。
        var res = put("/api/countersign/" + parent.getId() + "/" + sub + "/complete",
                "user001", "{\"opinion\":\"我同意\"}");

        assertThat(res.statusCode())
                .as("缺陷期間：任何登入者帶著一組配對的 id 就能替別人簽加簽")
                .isEqualTo(404);

        assertThat(currentTask(sub))
                .as("子任務必須仍未完成 —— 否則父任務的守門會放行、加簽人從未表態")
                .isNotNull();
        assertThat(taskService.getTaskComments(parent.getId()))
                .as("意見不得被寫入 —— addComment 在 complete 之前，"
                        + "順序寫反就會出現「回 404 但留言已經寫進去」")
                .noneMatch(c -> c.getFullMessage().contains("我同意"));
        assertThat(historyService.createHistoricTaskInstanceQuery().taskId(sub).singleResult()
                .getEndTime())
                .as("被拒的 complete 不得把子任務標成已完成"
                        + "（saveTask 建立時就會有一筆 endTime 為 null 的歷史列，"
                        + "所以這裡要看 endTime 而不是有沒有列）")
                .isNull();
    }

    @Test
    @DisplayName("被加簽者本人完成加簽 → 200，且意見附在父任務上")
    void countersignTargetCanComplete() throws Exception {
        Task parent = managerTaskFor("user001");
        String sub = addCountersignAsHolder(parent.getId(), COUNTERSIGNED);

        var res = put("/api/countersign/" + parent.getId() + "/" + sub + "/complete",
                COUNTERSIGNED, "{\"opinion\":\"同意加簽\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"allSubtasksDone\":true");
        assertThat(taskService.getSubTasks(parent.getId())).isEmpty();
        assertThat(taskService.getTaskComments(parent.getId()))
                .as("加簽意見必須附在父任務上（spec §4.4.1）")
                .anyMatch(c -> c.getFullMessage().contains("同意加簽"));
        assertThat(historyService.createHistoricTaskInstanceQuery().taskId(sub).count())
                .as("完成後必須保留歷史（不得 cascade 刪除）").isEqualTo(1);
    }

    // ── 稽核：operatorId 必須是呼叫者 ───────────────────────────────

    @Test
    @DisplayName("加簽稽核的 operatorId 是呼叫者，不是被指派的人")
    void createAuditOperatorIsTheCallerNotTheTarget() throws Exception {
        Task parent = managerTaskFor("user001");

        post("/api/countersign/" + parent.getId(), HOLDER, createBody(COUNTERSIGNED));

        List<String[]> rows = new ArrayList<>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT operator_id, detail FROM bpm_audit_log "
                    + "WHERE operation_type = 'TASK_COUNTERSIGN' ORDER BY id")) {
                var rs = ps.executeQuery();
                while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        });

        assertThat(rows).as("建立加簽必須留下稽核").hasSize(1);
        assertThat(rows.get(0)[0])
                .as("缺陷期間 operatorId 傳的是 assignee = user003，"
                        + "稽核會說「user003 發起了加簽」，而實際按下去的是 mgr001")
                .isEqualTo(HOLDER);
        assertThat(rows.get(0)[1])
                .as("被指派人要留在 detail 裡 —— 稽核要回答「誰做的決定」與「決定了什麼」")
                .contains(COUNTERSIGNED);
    }

    @Test
    @DisplayName("被拒絕的加簽必須留下 DATA_ACCESS 稽核（有人嘗試替別人加簽）")
    void deniedCountersignIsAudited() throws Exception {
        Task parent = managerTaskFor("user001");

        post("/api/countersign/" + parent.getId(), OUTSIDER, createBody(COUNTERSIGNED));

        List<String> details = new ArrayList<>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operator_id = '" + OUTSIDER + "' AND operation_type = 'DATA_ACCESS'")) {
                var rs = ps.executeQuery();
                while (rs.next()) details.add(rs.getString(1));
            }
        });
        assertThat(details)
                .as("不留痕就只剩下一堆沒有來源的 404")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"));
    }
}
