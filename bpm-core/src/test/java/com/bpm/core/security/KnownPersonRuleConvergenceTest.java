package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #93a：「指派給誰必須是組織系統認識的人」收斂成<b>一份</b>實作。
 *
 * <h2>收斂前有<b>三份形狀</b>，本工項把剩下兩個呼叫點接上</h2>
 *
 * <p>唯一一份實作是 {@code ExternalActorGuard.requireKnownPerson}（#88 建立），
 * 三層由便宜到昂貴：空白 → 400、{@code system:} 前綴（不區分大小寫）→ 400、
 * 其餘一律問組織系統（fail-closed；查無此人 → 400，故障 → 503）。
 * #92 把它接上 {@code TaskController} 的 {@code reassign} 分支。
 * 本工項處理剩下兩個：
 *
 * <ol>
 *   <li>{@code CountersignController.requireKnownEmployee(String)} —— 整份刪除。
 *       舊實作<b>只有第三層</b>（問一次組織系統），所以
 *       {@code countersignUserId: "system:evil"} 當時只靠「組織系統查不到」擋下。
 *       ⚠️ 而「組織系統說這個人不存在」只有在組織系統 <b>fail-closed</b> 時才是
 *       可信證據：本專案自己的 {@code MockOrgController} 整整兩輪都是 fail-open
 *       （對不認識的 id 回 {@code mgr001}），那正是 #83 的缺陷兩輪沒被任何測試
 *       抓到的原因。收斂之後 {@code system:} 前綴由<b>不打網路</b>的純字串比對擋下，
 *       不依賴任何前提。</li>
 *   <li>{@code TaskController} 的 {@code delegate} 分支 —— 完全沒有檢查。
 *       {@code {"action":"delegate","delegateUser":"nobody-xyz"}} 會成功，
 *       assignee 變成沒有人認得的人。缺陷本質與 reassign 相同。</li>
 * </ol>
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>{@code ResponseStatusException} 走 ERROR dispatch，MockMvc 不做那次 dispatch，
 * 狀態碼正是那條路徑決定的（見 {@code ErrorDispatchTest}）。
 * 503 更是本專案<b>新引進</b>在加簽路徑上的狀態碼，必須走真的。
 *
 * <h2>⚠️ 每一條拒絕都<b>先斷資料未變</b>、<b>後斷狀態碼</b></h2>
 *
 * <p>本 repo 反覆出現「回 400／404 但資料已被改掉」。只斷狀態碼的測試會被
 * 「先 delegateTask／先 saveTask，再丟例外」完全騙過 —— 而那正是要修的缺陷本身。
 *
 * <h2>⚠️ 非空斷言：確認請求真的送到了 controller</h2>
 *
 * <p>只用「拿到 404／400 就當作被擋」是不安全的 —— 如果請求根本沒進到 controller
 * （路徑打錯、驗證閘道擋掉、JSON 解析失敗），斷言同樣成立。因此每一條被拒的
 * 請求都另外斷言：
 * <ul>
 *   <li>被擋的那一層所特有的<b>訊息片段</b>（例如「不是組織系統認識的人員」、
 *       「系統身分」、「delegate 必須指定 delegateUser」），這些字串只可能由
 *       本 controller 的那段程式碼產生；</li>
 *   <li>以及同一條路徑的<b>合法對照組</b>（{@link Passes}）：完全相同的請求
 *       形狀、只換一個值，必須 200 且資料真的變了。
 *       「擋掉一切」的實作會讓 {@link Passes} 全紅。</li>
 * </ul>
 *
 * <h2>⚠️ 負向控制組實測（2026-10-01，把兩個 controller 還原成 HEAD）</h2>
 *
 * <p><b>22 條中 9 紅 13 綠。</b>
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅（9）</td>
 *       <td>{@link DelegateRejections} 的 6 條（<b>全部</b>）<br>
 *           {@link CountersignRejections#countersignToSystemIdentityIsRejected}<br>
 *           {@link CountersignRejections#countersignSystemIdentityIsCaseInsensitive}<br>
 *           {@link CountersignRejections#countersignOrgSystemFailureIsServiceUnavailable}</td>
 *       <td>見下方兩節。</td></tr>
 *   <tr><td>🟢 綠（13）</td>
 *       <td>{@link GuardsNotWeakened} 全部 3 條<br>
 *           {@link Passes} 全部 6 條<br>
 *           {@link CountersignRejections#countersignToUnknownPersonIsRejected}<br>
 *           {@link CountersignRejections#countersignToFullWidthSystemPrefixIsRejected}<br>
 *           {@link CountersignRejections#blankCountersignTargetIsRejected}<br>
 *           {@link DelegateRejections#blankDelegateUserIsRejected}</td>
 *       <td>非回歸對照。</td></tr>
 * </table>
 *
 * <h3>delegate 的 6 條紅：真缺陷</h3>
 *
 * <p>紅的原因全都是 {@code expected: 400/503 but was: 200}，body 是
 * {@code {"status":"ok"}}；把斷言順序調換（先斷 assignee、後斷狀態碼）後，
 * 訊息變成 {@code expected: "mgr001" but was: "nobody-…"} 與
 * {@code … but was: "system:evil"} —— <b>缺陷期間 assignee 與 owner 真的被改掉了</b>。
 *
 * <h3>加簽的 3 條紅：不是新缺陷，是<b>訊息與分層</b>的差別（重要）</h3>
 *
 * <p>{@link CountersignRejections#countersignToUnknownPersonIsRejected} 與
 * {@link CountersignRejections#countersignToFullWidthSystemPrefixIsRejected}
 * 在缺陷期間<b>就是綠的</b> —— 因為舊實作雖然只有第三層，而
 * {@code MockOrgController} 現在是 fail-closed，「查無此人 → 400」本來就成立。
 * 也就是說：
 *
 * <blockquote><b>加簽這條路徑在「組織系統 fail-closed」的 repo 內本來就擋得住
 * 未知的人與全形前綴。收斂帶來的改變只有兩件：{@code system:} 前綴改由不打網路
 * 的字串比對擋下（不依賴 fail-closed），以及錯誤訊息統一。</b></blockquote>
 *
 * <p>紅的那 3 條正是這兩件事的證據：它們斷言的是
 * <b>「{@code system:} 訊息裡出現『系統身分』」</b>與
 * <b>「503 的訊息提到『組織系統目前無法查詢』」</b>，這些字串舊實作都不會產生。
 *
 * <p>⚠️ <b>哪一種錯誤的修法也會讓這 3 條變綠</b>：任何「把舊實作的
 * try/catch 原封不動搬到別處」或「只在舊實作上加一行 {@code isSystemActor} 比對
 * 但仍留著自己的 try/catch」的修法都會讓它們綠，因為它們只看訊息不看分層。
 * <b>{@link Passes#countersignToChainTopPersonIsAllowed} 與
 * {@link GuardsNotWeakened#nonHolderCannotCountersignUnknownTarget} 才抓得住
 * 「分層順序對不對」與「授權有沒有被放寬」</b> —— 前者會擋掉總監本人，
 * 後者能分辨是哪一道檢查先擋。
 *
 * <p>⚠️ 反過來說，<b>delegate 的 6 條紅是真的缺陷</b>，而且它們紅在
 * <b>資料</b>上：缺陷期間 assignee 被換成送進去的那個字串
 * （{@code "nobody-…"}／{@code "system:evil"}／{@code "SYSTEM:evil"}／
 * {@code "ｓｙｓｔｅｍ：evil"}／{@code "fault-503"}／{@code " user003 "}）。
 * 這一組不是訊息差異，是「任務真的被交給沒有人認得的人」。
 *
 * <h2>⚠️ 已知的既有差異（不是本工項造成的，刻意未修）</h2>
 *
 * <p>加簽在驗證之前會 {@code assignee = assignee.trim()}（{@code CountersignController}
 * 第 2 段），所以加簽<b>不會</b>拒絕前後空白的 id；而 reassign／delegate
 * 不 trim，因此會拒絕。同一條規則的兩個入口對空白邊界的處理不一致。
 * 本工項<b>不</b>改它 —— 加簽的 trim 是既有行為（前端
 * {@code CountersignDialog.vue} 送出的是選單值，理論上不會有空白），
 * 改它會動到既有測試的斷言且屬於行為變更，應由 PM 裁決。
 * 這個差異寫在這裡以免被當成「本工項的後果」。
 */
class KnownPersonRuleConvergenceTest extends IntegrationTestBase {

    /** 沒有任何流程、沒有權限、也不在組織 fixture 裡的 id（真正的局外人）。 */
    private static final String OUTSIDER = "outsider001";

    /** leave-approval 主管審核任務的 assignee。 */
    private static final String HOLDER = "mgr001";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    // ── HTTP 小工具（真實 HTTP，理由見類別註解）─────────────────────

    private HttpResponse<String> put(String path, String userId, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
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

    private HttpResponse<String> delegate(String taskId, String callerId, String delegateUser)
            throws Exception {
        return put("/api/tasks/" + taskId, callerId,
                "{\"action\":\"delegate\",\"delegateUser\":" + jsonString(delegateUser) + "}");
    }

    private HttpResponse<String> countersign(String taskId, String callerId, String target)
            throws Exception {
        return post("/api/countersign/" + taskId, callerId,
                "{\"countersignUserId\":" + jsonString(target) + ",\"message\":\"請協助確認\"}");
    }

    /** 把值組成 JSON 字串字面值（含跳脫），{@code null} 會變成 JSON null。 */
    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ── 情境小工具 ──────────────────────────────────────────────────

    /** 以 initiator 啟動 leave-approval，回傳主管審核任務（assignee = mgr001）。 */
    private Task startLeaveAndGetManagerTask(String initiator) {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", initiator, "leaveType", "annual", "days", 1));
        return taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
    }

    /** purchase-approval 的財務審核：一個<b>真的帶著候選清單</b>的任務。 */
    private Task financeReviewTask() {
        var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "amount", 1000, "itemName", "測試"));
        Task mgr = taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
        taskService.complete(mgr.getId(), Map.of("approved", true, "rejected", false));
        return taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
    }

    private Task currentTask(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).singleResult();
    }

    private String assigneeOf(String taskId) {
        Task t = currentTask(taskId);
        return t == null ? null : t.getAssignee();
    }

    private long auditsOf(String operationType) {
        final long[] count = {0};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_audit_log WHERE operation_type = '"
                            + operationType + "'")) {
                var rs = ps.executeQuery();
                if (rs.next()) count[0] = rs.getLong(1);
            }
        });
        return count[0];
    }

    private long awaitAudits(String operationType, long atLeast) throws Exception {
        long rows = 0;
        for (int i = 0; i < 50; i++) {
            rows = auditsOf(operationType);
            if (rows >= atLeast) break;
            Thread.sleep(100);
        }
        return rows;
    }

    // ── 被拒的委派：資料未變的斷言 ─────────────────────────────────

    /**
     * 斷言「被拒的委派沒有改動 assignee <b>與 owner</b>」。
     *
     * <p>owner 也要斷：{@code delegateTask} 在 owner 為 null 時會把原本的
     * assignee 寫進 owner，所以一個「先委派、再丟例外」的實作會同時改動兩個欄位，
     * 而只斷 assignee 會漏掉一半。
     */
    private void assertDelegateRejectedWithoutMutation(HttpResponse<String> res, String taskId,
                                                       int expectedStatus) {
        Task t = currentTask(taskId);
        assertThat(t).as("被拒的委派不得改動 assignee —— 缺陷期間這裡會變成送進去的那個 id，"
                + "而任務從此沒有人能簽、也沒有告警").isNotNull();
        assertThat(t.getAssignee()).as("被拒的委派不得改動 assignee").isEqualTo(HOLDER);
        assertThat(t.getOwner())
                .as("被拒的委派不得寫入 owner（delegateTask 會在委派時設定它）").isNull();
        assertThat(res.statusCode()).as("回應狀態碼（body: %s）", res.body())
                .isEqualTo(expectedStatus);
    }

    /**
     * 斷言「被拒的加簽沒有建立任何子任務，而且父任務仍可正常完成」。
     *
     * <p>「父任務仍可完成」是防 DoS 的決定性斷言：只要有任何一筆子任務落地，
     * {@code TaskController} 的守門就會回 409，這個請求就變成 409 而非 200。
     * 也就是說這一條不可能「因為實作順序不對」而假綠。
     */
    private void assertCountersignRejectedWithoutMutation(HttpResponse<String> res, Task parent,
                                                          int expectedStatus) throws Exception {
        assertThat(taskService.getSubTasks(parent.getId()))
                .as("被拒的加簽不得留下任何子任務 —— 那就是持久化 DoS："
                        + "父任務會被 409 守門永久擋住").isEmpty();
        assertThat(res.statusCode()).as("回應狀態碼（body: %s）", res.body())
                .isEqualTo(expectedStatus);
        var complete = put("/api/tasks/" + parent.getId(), HOLDER,
                "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":true}]}");
        assertThat(complete.statusCode())
                .as("父任務必須仍可正常完成 —— 有未完成子任務時這裡會是 409").isEqualTo(200);
        assertThat(currentTask(parent.getId())).as("案件必須真的走完").isNull();
    }

    // ── 缺陷本體：delegate ──────────────────────────────────────────

    @Nested
    @DisplayName("缺陷：委派給組織系統不認識的人會成功（assignee 被換成沒有人認得的人）")
    class DelegateRejections {

        @Test
        @DisplayName("#93a：delegate 給查無此人的 id → 400，且 assignee／owner 都不變")
        void delegateToUnknownPersonIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");
            assertThat(task.getAssignee()).isEqualTo(HOLDER);

            var res = delegate(task.getId(), HOLDER, "nobody-" + UUID.randomUUID());

            assertDelegateRejectedWithoutMutation(res, task.getId(), 400);
            assertThat(res.body())
                    .as("非空斷言：這句話只可能由 actorGuard 產生 —— 若請求根本沒進到 "
                            + "controller，狀態碼斷言會假成立")
                    .contains("不是組織系統認識的人員");
        }

        @Test
        @DisplayName("#93a：delegate 給 system: 系統身分 → 400（沒有人能以它登入）")
        void delegateToSystemIdentityIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            var res = delegate(task.getId(), HOLDER, "system:evil");

            assertDelegateRejectedWithoutMutation(res, task.getId(), 400);
            assertThat(res.body()).contains("系統身分");
        }

        @Test
        @DisplayName("#93a：大小寫不同的 system: 前綴同樣 400（只比小寫會留下繞道）")
        void delegateSystemIdentityIsCaseInsensitive() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // 對照組：上方用的是小寫，這裡只換大小寫，其餘參數完全相同。
            for (String v : new String[]{"SYSTEM:evil", "System:evil", "sYsTeM:evil"}) {
                var res = delegate(task.getId(), HOLDER, v);
                assertDelegateRejectedWithoutMutation(res, task.getId(), 400);
                assertThat(res.body()).as("大小寫不同的系統身分必須同樣被指為系統身分: " + v)
                        .contains("系統身分");
            }
        }

        @Test
        @DisplayName("#93a：全形 system： 不被前綴比對命中，仍必須被組織查詢擋下")
        void delegateToFullWidthSystemPrefixIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // 這條是「問組織系統是主要規則、而不只是擋前綴」的直接證據。
            // 若實作只有前綴比對（isSystemActor 對全形回 false），這個值會通過。
            var res = delegate(task.getId(), HOLDER, "ｓｙｓｔｅｍ：evil");

            assertDelegateRejectedWithoutMutation(res, task.getId(), 400);
        }

        @Test
        @DisplayName("#93a：前後空白的員工編號 → 400（不靜默 trim，錯誤要讓呼叫端看到）")
        void delegateToPaddedUserIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // 對照組：Passes.delegateToKnownPersonStillWorks 用的是 "user003"，
            // 這裡只多兩個空白字元。加簽那條路徑會先 trim（既有行為），
            // delegate 不 trim —— 這個差異寫在類別註解裡，不在本工項裁決。
            var res = delegate(task.getId(), HOLDER, " user003 ");

            assertDelegateRejectedWithoutMutation(res, task.getId(), 400);
            assertThat(res.body())
                    .as("必須原樣回顯它送的值，呼叫端才知道要去比對哪裡").contains(" user003 ");
        }

        @Test
        @DisplayName("#93a：組織系統故障 → 503（可安全重試），不是 400")
        void delegateOrgSystemFailureIsServiceUnavailable() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // MockOrgController 對 fault- 前綴的 id 刻意丟 5xx（見其類別註解）。
            // 401 那個形狀特別值得斷言：它是我們的憑證／權限設定錯了，
            // 不是「這個人不存在」—— 回 400 會讓呼叫端去改一個沒問題的 payload。
            for (String v : new String[]{"fault-503", "fault-500", "fault-401"}) {
                var res = delegate(task.getId(), HOLDER, v);
                assertDelegateRejectedWithoutMutation(res, task.getId(), 503);
                assertThat(res.body()).as("503 的訊息必須說明這是暫時性問題: " + v)
                        .contains("組織系統目前無法查詢");
            }
            assertThat(auditsOf("TASK_DELEGATE"))
                    .as("被拒的委派不得留下 TASK_DELEGATE 稽核（不宣稱沒發生的變更）").isZero();
        }

        @Test
        @DisplayName("#93a：空白 delegateUser → 400，且訊息必須是「這欄沒給」")
        void blankDelegateUserIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            for (String v : new String[]{"null", "\"\"", "\"   \"", "\"\\t\""}) {
                var res = put("/api/tasks/" + task.getId(), HOLDER,
                        "{\"action\":\"delegate\",\"delegateUser\":" + v + "}");
                assertThat(res.statusCode())
                        .as("空白不得被當成「取消委派」: " + v).isEqualTo(400);
                assertThat(assigneeOf(task.getId()))
                        .as("空白不得被寫成空字串: " + v).isEqualTo(HOLDER);
                // 形狀檢查必須留在本地：actorGuard 對空白的訊息會提到
                // 「改用 firstTaskCandidateGroups」，而 delegate 的 body 裡沒有那個欄位。
                assertThat(res.body())
                        .as("空白的訊息必須是「這欄沒給」，不是「改用候選群組」: " + v)
                        .contains("delegate 必須指定 delegateUser")
                        .doesNotContain("firstTaskCandidateGroups");
            }
        }
    }

    // ── 缺陷本體：加簽（第二份實作的形狀）─────────────────────────

    @Nested
    @DisplayName("加簽：收斂之後擋的是「不是人」，而不只是「組織系統查不到」")
    class CountersignRejections {

        @Test
        @DisplayName("#93a：countersign 給 system: 系統身分 → 400，且訊息指為系統身分")
        void countersignToSystemIdentityIsRejected() throws Exception {
            Task parent = startLeaveAndGetManagerTask("user001");

            // ⚠️ 這一條是<b>收斂前後行為相同但訊息不同</b>的情形（見類別註解）：
            // 舊實作也會回 400（因為 MockOrgController fail-closed），
            // 但它的訊息不會說「系統身分」。紅的是訊息那一段。
            var res = countersign(parent.getId(), HOLDER, "system:evil");

            assertCountersignRejectedWithoutMutation(res, parent, 400);
            assertThat(res.body())
                    .as("收斂之後由 actorGuard 的第二層擋下，訊息必須指明那是系統身分 —— "
                            + "「沒有人能以它登入」比「查無此人」有用得多")
                    .contains("系統身分")
                    .contains("countersignUserId");
        }

        @Test
        @DisplayName("#93a：大小寫不同的 system: 前綴在加簽同樣 400 且指為系統身分")
        void countersignSystemIdentityIsCaseInsensitive() throws Exception {
            for (String v : new String[]{"SYSTEM:evil", "System:evil", "sYsTeM:evil"}) {
                Task parent = startLeaveAndGetManagerTask("user001");

                var res = countersign(parent.getId(), HOLDER, v);

                assertCountersignRejectedWithoutMutation(res, parent, 400);
                assertThat(res.body()).as("大小寫不同的系統身分必須同樣被指為系統身分: " + v)
                        .contains("系統身分");
            }
        }

        @Test
        @DisplayName("#93a：全形 system： 在加簽仍然被擋（問組織系統那一層真的還在）")
        void countersignToFullWidthSystemPrefixIsRejected() throws Exception {
            // isSystemActor("ｓｙｓｔｅｍ：x") 回 false，所以這個值必須靠
            // 「問組織系統」擋下。它證明收斂之後第三層<b>沒有被拿掉</b>。
            //
            // ⚠️ 這一條<b>缺陷期間也是綠的</b>（舊實作的 try/catch 同樣擋得住，
            // 因為 MockOrgController 是 fail-closed）。它是防禦性回歸證據，
            // 不是「原本壞掉」的證據 —— 誠實記在這裡以免被誤讀成新修的缺陷。
            Task parent = startLeaveAndGetManagerTask("user001");

            assertCountersignRejectedWithoutMutation(
                    countersign(parent.getId(), HOLDER, "ｓｙｓｔｅｍ：evil"), parent, 400);
        }

        @Test
        @DisplayName("#93a：加簽給查無此人的 id → 400（既有行為的非回歸）")
        void countersignToUnknownPersonIsRejected() throws Exception {
            // 這一條缺陷期間就綠（舊實作的第三層擋得住），
            // 它的作用是證明<b>收斂沒有放寬</b>：兩份實作都在時它必須仍然成立。
            Task parent = startLeaveAndGetManagerTask("user001");

            var res = countersign(parent.getId(), HOLDER, "nosuchemployee999");

            assertCountersignRejectedWithoutMutation(res, parent, 400);
            assertThat(res.body()).contains("不是組織系統認識的人員");
        }

        @Test
        @DisplayName("#93a：組織系統故障 → 加簽也必須 503（收斂後語意不變）")
        void countersignOrgSystemFailureIsServiceUnavailable() throws Exception {
            // 舊實作本來就分 503／400，收斂後沿用同一組政策。
            // 狀態碼這一格缺陷期間就是綠（舊實作也回 503），
            // 紅的是訊息 —— 舊實作說「請稍後再試」，actorGuard 說
            // 「組織系統目前無法查詢…重試不會產生重複案件」。
            Task parent = startLeaveAndGetManagerTask("user001");

            var res = countersign(parent.getId(), HOLDER, "fault-503");

            assertCountersignRejectedWithoutMutation(res, parent, 503);
            assertThat(res.body())
                    .as("訊息必須是 actorGuard 的版本，而不是舊實作那句")
                    .contains("組織系統目前無法查詢")
                    .doesNotContain("本次未建立任何加簽任務");
        }

        @Test
        @DisplayName("#93a：空白 countersignUserId → 400，且訊息是「這欄沒給」")
        void blankCountersignTargetIsRejected() throws Exception {
            // 形狀檢查保留在本地（與 reassign／delegate 同理）：
            // actorGuard 的空白訊息提到 firstTaskCandidateGroups，
            // 而加簽的 body 裡沒有那個欄位。
            Task parent = startLeaveAndGetManagerTask("user001");

            var res = countersign(parent.getId(), HOLDER, "");

            assertThat(taskService.getSubTasks(parent.getId()))
                    .as("空白不得建立子任務").isEmpty();
            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body())
                    .as("空白的訊息必須是「這欄沒給」，不是「改用候選群組」")
                    .contains("countersignUserId 為必填")
                    .doesNotContain("firstTaskCandidateGroups");
        }
    }

    // ── 守衛不得被放寬 ───────────────────────────────────────────────

    @Nested
    @DisplayName("對照組：新檢查不得放寬持有者守衛，也不得蓋掉它")
    class GuardsNotWeakened {

        @Test
        @DisplayName("#93a：非持有者 + 未知的 delegateUser → 404（持有者守衛先擋，不是 400）")
        void nonHolderCannotDelegateToUnknownPerson() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // 這個 id 會被「是不是人」擋下，所以這一條能分辨是哪一道檢查先擋：
            //   404 = TaskHolderGuard 先擋（正確）
            //   400 = 身分檢查被插到 requireHolder 之前（錯誤：等於開了枚舉管道）
            var res = delegate(task.getId(), OUTSIDER, "nobody-" + UUID.randomUUID());

            assertThat(res.statusCode())
                    .as("對非持有者必須是 404（授權），不是 400（payload 形狀）—— "
                            + "狀態碼的差異正是枚舉管道").isEqualTo(404);
            assertThat(assigneeOf(task.getId())).isEqualTo(HOLDER);
        }

        @Test
        @DisplayName("#93a：非持有者 + 合法的 delegateUser → 仍然是 404（合法值不得放寬守衛）")
        void nonHolderCannotDelegateEvenToKnownPerson() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            var res = delegate(task.getId(), OUTSIDER, "user003");

            assertThat(res.statusCode())
                    .as("持有者守衛與新檢查無關：對方是真人也不代表他有權委派").isEqualTo(404);
            assertThat(assigneeOf(task.getId())).isEqualTo(HOLDER);
            assertThat(auditsOf("TASK_DELEGATE")).isZero();
        }

        @Test
        @DisplayName("#93a：非持有者加簽給查無此人的對象 → 仍然 404（排序不得被顛倒）")
        void nonHolderCannotCountersignUnknownTarget() throws Exception {
            Task parent = startLeaveAndGetManagerTask("user001");

            // 與上面兩條同型：這兩個 id 都會被身分檢查擋下，
            // 因此 404 才代表 TaskHolderGuard 先擋。
            var res = countersign(parent.getId(), OUTSIDER, "nobody-" + UUID.randomUUID());

            assertThat(res.statusCode())
                    .as("對非持有者必須是 404（授權），不是 400（payload 形狀）").isEqualTo(404);
            assertThat(taskService.getSubTasks(parent.getId())).isEmpty();
        }
    }

    // ── 對照組：合法值必須仍然放行 ───────────────────────────────────

    @Nested
    @DisplayName("對照組：合法的對象必須仍然放行（不得「擋掉全部」）")
    class Passes {

        @Test
        @DisplayName("#93a：委派給一般的已知員工 → 200，且 assignee／owner 真的換了")
        void delegateToKnownPersonStillWorks() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            var res = delegate(task.getId(), HOLDER, "user003");

            assertThat(res.statusCode())
                    .as("守衛必須用組織系統查詢，而不是擋掉一切").isEqualTo(200);
            Task now = currentTask(task.getId());
            assertThat(now.getAssignee())
                    .as("正向對照必須真的委派，而不只是回 200").isEqualTo("user003");
            assertThat(now.getOwner()).as("原本的 assignee 必須被寫進 owner").isEqualTo(HOLDER);
            assertThat(awaitAudits("TASK_DELEGATE", 1))
                    .as("成功委派必須進稽核").isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("#93a：⚠️ 委派給<b>不在候選清單裡</b>的真人 → 必須允許（200）")
        void delegateToKnownPersonOutsideCandidateListIsAllowed() throws Exception {
            // 「驗是不是人而不是是不是候選人」這個判斷的唯一防護門。
            // 情境刻意挑一個<b>真的帶著候選清單</b>的任務。
            Task finance = financeReviewTask();
            List<String> candidates = taskService.getIdentityLinksForTask(finance.getId())
                    .stream()
                    .filter(l -> l.getType() != null && l.getType().contains("candidate"))
                    .map(IdentityLink::getUserId)
                    .filter(Objects::nonNull)
                    .toList();
            assertThat(candidates)
                    .as("這個對照組必須真的站在「有候選清單」的情境上，否則它證明不了任何事")
                    .contains("dir001", "mgr001")
                    .doesNotContain("user003");

            // dir001 是候選人（TaskHolderGuard 條件 3），所以他可以對這個任務動手。
            assertThat(put("/api/tasks/" + finance.getId(), "dir001",
                    "{\"action\":\"claim\"}").statusCode()).isEqualTo(200);

            var res = delegate(finance.getId(), "dir001", "user003");

            assertThat(res.statusCode())
                    .as("委派給不在候選清單裡、但組織系統認識的人必須允許 —— "
                            + "候選清單是啟動時的建議，不是委派的白名單").isEqualTo(200);
            assertThat(assigneeOf(finance.getId())).isEqualTo("user003");
        }

        @Test
        @DisplayName("#93a：⚠️ 鏈頂主管 dir001（沒有直屬主管）→ 委派仍然 200")
        void delegateToChainTopPersonIsAllowed() throws Exception {
            // 存在性判斷若寫成
            //     if (orgService.getDirectManager(x) == null) → 拒絕
            // 就會擋掉 dir001 與 admin001 —— 他們**存在**，只是沒有主管。
            // MockOrgController 對鏈頂人員回 {} 而不是 404 就是為了這個區分。
            // 「答案恰好等於預期值」是這個 repo 踩過最多次的坑。
            Task task = startLeaveAndGetManagerTask("user001");

            var res = delegate(task.getId(), HOLDER, "dir001");

            assertThat(res.statusCode())
                    .as("「沒有主管」是事實，不是「查無此人」").isEqualTo(200);
            assertThat(assigneeOf(task.getId())).isEqualTo("dir001");
        }

        @Test
        @DisplayName("#93a：⚠️ 鏈頂主管 dir001 → 加簽仍然 200（確認本呼叫點也通過該檢查）")
        void countersignToChainTopPersonIsAllowed() throws Exception {
            // 與上面同型的第三個呼叫點。ExternalActorGuard 已有單元層的
            // chainTopPersonPasses，但那是 mock；這裡證明<b>整合層真的通過</b>。
            Task parent = startLeaveAndGetManagerTask("user001");

            var res = countersign(parent.getId(), HOLDER, "dir001");

            assertThat(res.statusCode())
                    .as("「沒有主管」是事實，不是「查無此人」").isEqualTo(200);
            assertThat(taskService.getSubTasks(parent.getId()))
                    .hasSize(1);
            assertThat(taskService.getSubTasks(parent.getId()).get(0).getAssignee())
                    .isEqualTo("dir001");
        }

        @Test
        @DisplayName("#93a：加簽給一般員工（非主管）→ 200")
        void countersignToRegularEmployeeIsAllowed() throws Exception {
            // 第三種身分形狀：既不是主管也不是鏈頂。驗證不可依賴
            // 「他上面有人」這種推論。
            Task parent = startLeaveAndGetManagerTask("user001");

            assertThat(countersign(parent.getId(), HOLDER, "user003").statusCode())
                    .isEqualTo(200);
            assertThat(taskService.getSubTasks(parent.getId()).get(0).getAssignee())
                    .isEqualTo("user003");
        }

        @Test
        @DisplayName("#93a：⚠️ 加簽給<b>不在候選清單裡</b>的真人 → 必須允許（200）")
        void countersignToKnownPersonOutsideCandidateListIsAllowed() throws Exception {
            // 與 delegate 同型：驗的是「是不是人」，不是「是不是候選人」。
            // 加簽從來沒有候選清單的概念（子任務只有 assignee），
            // 所以這一條的意義是：若有人日後把「候選清單」加進加簽檢查，
            // 這裡會紅 —— 而那正是它存在的目的。
            Task finance = financeReviewTask();
            List<String> candidates = taskService.getIdentityLinksForTask(finance.getId())
                    .stream()
                    .filter(l -> l.getType() != null && l.getType().contains("candidate"))
                    .map(IdentityLink::getUserId)
                    .filter(Objects::nonNull)
                    .toList();
            assertThat(candidates)
                    .as("前置條件：這個任務真的有候選清單")
                    .contains("dir001").doesNotContain("user003");

            // dir001 是候選人，可以對這個任務動手（條件 3），因此能加簽。
            var res = countersign(finance.getId(), "dir001", "user003");

            assertThat(res.statusCode())
                    .as("加簽給不在候選清單裡、但組織系統認識的人必須允許").isEqualTo(200);
            assertThat(taskService.getSubTasks(finance.getId()).get(0).getAssignee())
                    .isEqualTo("user003");
        }
    }
}