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
 * #92：{@code PUT /api/tasks/{id} {"action":"reassign","assignee":X}} 的新 assignee
 * <b>完全沒有驗證</b> —— 只擋空白，任何 id 都放行。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <pre>
 *   持有者 mgr001 送出 {"action":"reassign","assignee":"nobody-xyz"}
 *   → setAssignee(taskId, "nobody-xyz") 成功 → 200 {"status":"ok"}
 *   → assignee 非 null，而 Flowable 的候選人查詢帶著 RES.ASSIGNEE_ IS NULL
 *     （Task.xml 的 selectTaskByCandidateUser*），所以**候選人救不了它**：
 *     兩個條件互斥
 *   → UnreachableTaskListener 的判準是「有沒有人能動它」，而 nobody-xyz
 *     非空白、非 system: 前綴 → **不告警**
 *   → 結果：一個沒有人看得到、沒有人能簽、也沒有任何告警的任務。
 * </pre>
 *
 * <p>與 #88（{@code firstTaskAssignee}）、{@code CountersignController}
 * （{@code countersignUserId}）是<b>同一條規則的三個入口</b>：被指派的對象
 * 必須是組織系統認識的人。本工項重用 {@code ExternalActorGuard}，
 * 不寫第三份實作。
 *
 * <h2>⚠️ 狀態碼斷言走<b>真實 HTTP</b>而不是 MockMvc</h2>
 *
 * <p>本專案的教訓（見 {@code ErrorDispatchTest} 與 {@code TaskHolderAuthorizationTest}）：
 * {@code ResponseStatusException} 走 ERROR dispatch，MockMvc 不做那次 dispatch。
 * 503 更是本專案<b>新引進</b>在這條路徑上的狀態碼，必須走真的。
 *
 * <h2>⚠️ 每一條拒絕都同時斷言「assignee 真的沒被改掉」</h2>
 *
 * <p>只斷言狀態碼的測試會被「先 setAssignee、再丟 400」這種實作完全騙過 ——
 * 而那個實作正是本工項要修的缺陷本身（任務已經指給沒有人能認得的人，
 * 只是呼叫端收到一個錯誤碼）。本 repo 反覆出現「回 400／404 但資料已被改掉」，
 * 所以狀態碼與資料是<b>成對</b>的斷言，缺一不可。
 *
 * <h2>⚠️ 「不是候選人」必須<b>允許</b>（本工項最關鍵的一條對照）</h2>
 *
 * <p>{@link Passes#reassignToKnownPersonOutsideCandidateListIsAllowed}：
 * 把一個<b>真的帶著候選清單</b>的任務（purchase-approval 的財務審核，
 * 候選人 mgr001／dir001）改派給 {@code user001} —— 他是組織系統認識的人，
 * 但<b>不在候選清單裡</b>，必須<b>允許</b>。
 *
 * <p>為什麼：候選清單是流程啟動時的<b>建議</b>，不是改派的白名單。
 * 主管把任務交給一位不在候選清單裡但確實該處理的人（出差、代班、跨部門支援）
 * 是正常業務行為；拿候選清單當白名單，唯一合法結果是把「改派」功能打死。
 * 本工項要擋的是「指給一個沒有人認得、沒有人能登入的字串」。
 *
 * <p>⚠️ 同理<b>不</b>驗「assignee 必須是候選人」—— 若日後有人把它當成需求補上，
 * 這一條會紅，而那正是本測試存在的目的（讓那個決定必須是一個明確的決定，
 * 而不是一個順手加上的檢查）。
 *
 * <h2>⚠️ 負向控制組實測（2026-10-01，把 {@code TaskController.java} 整份還原成 HEAD）</h2>
 *
 * <p><b>14 條中 6 紅 8 綠。</b>紅的正是缺陷本身，紅的原因全都是
 * {@code expected: 400/503 but was: 200}，body 是 {@code {"status":"ok"}}。
 *
 * <p>而且把斷言順序<b>刻意調換</b>再跑一次（先斷 assignee、後斷狀態碼），
 * 得到同樣 6 條紅，訊息變成：
 * <pre>
 *   expected: "mgr001" but was: "nobody-29075c65-…"
 *   expected: "mgr001" but was: "system:evil"
 *   expected: "mgr001" but was: " ｓｙｓｔｅｍ：evil"
 *   expected: "mgr001" but was: "fault-503"
 * </pre>
 * 也就是<b>缺陷期間資料真的被改掉了</b>，不只是狀態碼不對 ——
 * 這正是本 repo 反覆出現的「回 200／400 但資料已被改掉」那種失敗型態，
 * 也是每一條拒絕都必須成對斷言狀態碼與資料的理由。
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅（6）</td>
 *       <td>{@code reassignToUnknownPersonIsRejected}<br>
 *           {@code reassignToSystemIdentityIsRejected}<br>
 *           {@code systemIdentityIsCaseInsensitive}<br>
 *           {@code fullWidthSystemPrefixIsRejected}<br>
 *           {@code paddedAssigneeIsRejected}<br>
 *           {@code orgSystemFailureIsServiceUnavailable}</td>
 *       <td>缺陷期間這 6 種形狀<b>全部 200</b>，而且 assignee <b>真的被改掉</b>
 *           —— 這正是「靜默卡死」的定義：任務沒有人能簽、沒有告警、
 *           稽核還記著一筆 TASK_REASSIGN。</td></tr>
 *   <tr><td>🟢 綠（8）</td>
 *       <td>{@link GuardsNotWeakened} 全部 2 條<br>
 *           {@link Passes} 全部 5 條<br>
 *           {@link Rejections#blankAssigneeIsRejected}</td>
 *       <td>刻意挑的<b>非回歸對照</b>：缺陷期間就該綠，修好之後仍必須綠。
 *           它們的作用是讓「紅的 6 條」有意義 ——
 *           一個「擋掉所有非 null assignee」的實作能讓紅的 6 條全過，
 *           卻會讓 {@link Passes} 的 5 條全紅。</td></tr>
 * </table>
 *
 * <p><b>「綠了哪幾條」揭露了什麼（比紅了哪幾條更有資訊）：</b>
 * <ul>
 *   <li>{@link GuardsNotWeakened} 的 2 條在缺陷期間<b>本來就綠</b> ——
 *       404 來自 {@code TaskHolderGuard}，與新檢查無關。也就是說
 *       <b>只靠「紅的 6 條」無法證明新檢查的排序是對的</b>。
 *       真正釘住排序的是
 *       {@link GuardsNotWeakened#nonHolderCannotReassignToUnknownPerson}：
 *       它刻意送<b>未知的 id</b>（會被兩道檢查同時拒絕），所以能分辨
 *       是哪一道先擋 —— 404 = 持有者守衛先擋（正確）；
 *       400 = 身分檢查被插到 {@code requireHolder} 之前（錯誤：等於開了
 *       枚舉管道）。
 *       若改成送<b>合法</b> id（同一個 Nested 裡的另一條那樣），缺陷期間與
 *       修好之後都會是 404，<b>完全釘不住排序</b>。這是本 repo 記錄過的
 *       失敗型態：<b>一個錯誤的修法也會讓「缺陷期間是綠的」那些測試繼續綠。</b></li>
 *   <li>{@code blankAssigneeIsRejected} 在缺陷期間也綠（既有的空白檢查就擋得住）。
 *       它是<b>非回歸證據</b>，不是漏抓：它證明新增的身分檢查<b>沒有取代</b>
 *       形狀檢查。若有人為了「規則只能有一份」而刪掉本地空白檢查、只留
 *       actorGuard，狀態碼仍是 400（這條仍綠），但訊息會變成
 *       「請改用 firstTaskCandidateGroups」，而 reassign 的 body 裡
 *       <b>根本沒有那個欄位</b> —— 這一條的訊息斷言就是為了讓那個錯誤實作變紅。</li>
 * </ul>
 *
 * <h2>⚠️ 已知且刻意不驗的一格：訊息用詞</h2>
 *
 * <p>{@code ExternalActorGuard} 的訊息是為 {@code firstTaskAssignee} 寫的：
 * 系統身分那一句提到「簽第一關」、空白那一句提到「改用 firstTaskCandidateGroups」。
 * 對 reassign 而言那兩句不精確（方向仍正確：那是系統身分，沒有人能簽；
 * 而 reassign 的 body 沒有那個欄位）。本工項<b>不修改</b>
 * {@code ExternalActorGuard}（共用元件，且由另一個工項並行修改），
 * 所以 {@link Rejections#reassignToSystemIdentityIsRejected} 只斷言
 * 「系統身分」這一段，不斷言那兩句。訊息用詞已回報給 PM。
 */
class ReassignKnownPersonTest extends IntegrationTestBase {

    /** 沒有任何流程、沒有權限、也不在組織 fixture 裡的 id（真正的局外人）。 */
    private static final String OUTSIDER = "outsider001";

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

    private HttpResponse<String> reassign(String taskId, String callerId, String assignee)
            throws Exception {
        return put("/api/tasks/" + taskId, callerId,
                "{\"action\":\"reassign\",\"assignee\":" + jsonString(assignee) + "}");
    }

    private HttpResponse<String> put(String path, String userId, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
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
        return taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
    }

    private String assigneeOf(String taskId) {
        Task t = taskService.createTaskQuery().taskId(taskId).singleResult();
        return t == null ? null : t.getAssignee();
    }

    private long reassignAudits() {
        final long[] count = {0};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_audit_log WHERE operation_type = 'TASK_REASSIGN'")) {
                var rs = ps.executeQuery();
                if (rs.next()) count[0] = rs.getLong(1);
            }
        });
        return count[0];
    }

    private long awaitReassignAudits(long atLeast) throws Exception {
        long rows = 0;
        for (int i = 0; i < 50; i++) {
            rows = reassignAudits();
            if (rows >= atLeast) break;
            Thread.sleep(100);
        }
        return rows;
    }

    /**
     * 斷言「被拒絕，而且<b>任務的 assignee 沒有被改掉</b>」。
     *
     * <p>第二個斷言才是重點：只斷言狀態碼的測試會被「先 setAssignee 再丟 400」
     * 完全騙過，而那正是本工項要修的缺陷本身。順序刻意是
     * <b>先資料、後狀態碼</b>，理由見類別註解的負向控制組。
     */
    private void assertRejectedWithoutMutation(HttpResponse<String> res, String taskId,
                                               int expectedStatus, String untouched) {
        assertThat(assigneeOf(taskId))
                .as("被拒的改派不得改動 assignee —— 缺陷期間這裡會變成被送進去的那個 id，"
                        + "而任務從此沒有人能簽、也沒有告警")
                .isEqualTo(untouched);
        assertThat(res.statusCode()).as("回應狀態碼（body: %s）", res.body())
                .isEqualTo(expectedStatus);
    }

    // ── 缺陷本體 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("缺陷：改派給組織系統不認識的人會成功")
    class Rejections {

        @Test
        @DisplayName("#92：reassign 給查無此人的 id → 400 且 assignee 不變")
        void reassignToUnknownPersonIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");
            assertThat(task.getAssignee()).isEqualTo("mgr001");

            var res = reassign(task.getId(), "mgr001", "nobody-" + UUID.randomUUID());

            assertRejectedWithoutMutation(res, task.getId(), 400, "mgr001");
            assertThat(res.body())
                    .as("訊息必須指名成因：呼叫端看到 400 要知道該換成什麼")
                    .contains("不是組織系統認識的人員");
        }

        @Test
        @DisplayName("#92：reassign 給 system: 系統身分 → 400（沒有人能以它登入）")
        void reassignToSystemIdentityIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            var res = reassign(task.getId(), "mgr001", "system:evil");

            assertRejectedWithoutMutation(res, task.getId(), 400, "mgr001");
            assertThat(res.body()).contains("系統身分");
        }

        @Test
        @DisplayName("#92：大小寫不同的 system: 前綴同樣 400（只比小寫會留下繞道）")
        void systemIdentityIsCaseInsensitive() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // 對照組：上方用的是小寫，這裡只換大小寫，其餘參數完全相同。
            for (String v : new String[]{"SYSTEM:evil", "System:evil", "sYsTeM:evil"}) {
                assertRejectedWithoutMutation(
                        reassign(task.getId(), "mgr001", v), task.getId(), 400, "mgr001");
            }
        }

        @Test
        @DisplayName("#92：全形 system： 不被前綴比對命中，仍必須被組織查詢擋下")
        void fullWidthSystemPrefixIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // 這條是「問組織系統是主要規則、而不只是擋前綴」的直接證據。
            // 若實作只有前綴比對（isSystemActor 對全形回 false），這個值會通過
            // —— 而它一樣沒有人能簽。
            assertRejectedWithoutMutation(
                    reassign(task.getId(), "mgr001", "ｓｙｓｔｅｍ：evil"),
                    task.getId(), 400, "mgr001");
        }

        @Test
        @DisplayName("#92：前後空白的員工編號 → 400（不靜默 trim，錯誤要讓呼叫端看到）")
        void paddedAssigneeIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // 對照組：Passes.reassignToKnownPersonStillWorks 用的是 "mgr002"，
            // 這裡只多兩個空白字元。
            var res = reassign(task.getId(), "mgr001", " mgr002 ");

            assertRejectedWithoutMutation(res, task.getId(), 400, "mgr001");
            assertThat(res.body())
                    .as("必須原樣回顯它送的值，呼叫端才知道要去比對哪裡")
                    .contains(" mgr002 ");
        }

        @Test
        @DisplayName("#92：組織系統故障 → 503（可安全重試），不是 400")
        void orgSystemFailureIsServiceUnavailable() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // MockOrgController 對 fault- 前綴的 id 刻意丟 5xx（見其類別註解）。
            // 401 那個形狀特別值得斷言：它是我們的憑證／權限設定錯了，
            // 不是「這個人不存在」—— 回 400 會讓呼叫端去改一個沒問題的 payload。
            for (String v : new String[]{"fault-503", "fault-500", "fault-401"}) {
                assertRejectedWithoutMutation(
                        reassign(task.getId(), "mgr001", v), task.getId(), 503, "mgr001");
            }
            assertThat(reassignAudits())
                    .as("被拒的改派不得留下 TASK_REASSIGN 稽核（不宣稱沒發生的變更）")
                    .isZero();
        }

        @Test
        @DisplayName("#92：空白 assignee → 400（既有形狀檢查的非回歸）")
        void blankAssigneeIsRejected() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            for (String v : new String[]{"null", "\"\"", "\"   \"", "\"\\t\""}) {
                var res = put("/api/tasks/" + task.getId(), "mgr001",
                        "{\"action\":\"reassign\",\"assignee\":" + v + "}");
                assertThat(res.statusCode()).as("空白不得被當成「取消指派」: " + v).isEqualTo(400);
                assertThat(assigneeOf(task.getId()))
                        .as("空白不得被寫成空字串（空 assignee + 候選人查詢互斥 → 靜默卡死）: " + v)
                        .isEqualTo("mgr001");
                // 這一段是「形狀檢查必須留在本地」的證據。若有人為了
                // 「規則只有一份」把本地空白檢查刪掉、只留 actorGuard，
                // 狀態碼仍是 400（上一個斷言照樣綠），但訊息會變成
                // 「請改用 firstTaskCandidateGroups」—— 而 reassign 的 body
                // 裡沒有那個欄位。
                assertThat(res.body())
                        .as("空白的訊息必須是「這欄沒給」，不是「改用候選群組」: " + v)
                        .contains("reassign 必須指定 assignee")
                        .doesNotContain("firstTaskCandidateGroups");
            }
        }
    }

    // ── 守衛不得被放寬 ───────────────────────────────────────────────

    @Nested
    @DisplayName("對照組：新檢查不得放寬持有者守衛，也不得蓋掉它")
    class GuardsNotWeakened {

        @Test
        @DisplayName("#92：非持有者 + 未知的 assignee → 404（持有者守衛先擋，不是 400）")
        void nonHolderCannotReassignToUnknownPerson() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            // 這個 id 會被「是不是人」擋下，所以這一條能分辨是哪一道檢查先擋：
            //   404 = TaskHolderGuard 先擋（正確）
            //   400 = 身分檢查被插到 requireHolder 之前（錯誤：等於開了枚舉管道）
            var res = reassign(task.getId(), OUTSIDER, "nobody-" + UUID.randomUUID());

            assertThat(res.statusCode())
                    .as("對非持有者必須是 404（授權），不是 400（payload 形狀）—— "
                            + "狀態碼的差異正是枚舉管道")
                    .isEqualTo(404);
            assertThat(assigneeOf(task.getId())).isEqualTo("mgr001");
        }

        @Test
        @DisplayName("#92：非持有者 + 合法的 assignee → 仍然是 404（合法值不得放寬守衛）")
        void nonHolderCannotReassignEvenToKnownPerson() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            var res = reassign(task.getId(), OUTSIDER, "mgr002");

            assertThat(res.statusCode())
                    .as("持有者守衛與新檢查無關：對方是真人也不代表他有權改派")
                    .isEqualTo(404);
            assertThat(assigneeOf(task.getId())).isEqualTo("mgr001");
            assertThat(reassignAudits()).isZero();
        }
    }

    // ── 對照組：合法值必須仍然放行 ───────────────────────────────────

    @Nested
    @DisplayName("對照組：合法的受理人必須仍然放行（不得「擋掉全部」）")
    class Passes {

        @Test
        @DisplayName("#92：改派給一般的已知員工 → 200，且 assignee 真的換了")
        void reassignToKnownPersonStillWorks() throws Exception {
            Task task = startLeaveAndGetManagerTask("user001");

            var res = reassign(task.getId(), "mgr001", "mgr002");

            assertThat(res.statusCode())
                    .as("守衛必須用組織系統查詢，而不是擋掉一切")
                    .isEqualTo(200);
            assertThat(assigneeOf(task.getId()))
                    .as("正向對照必須真的改派，而不只是回 200").isEqualTo("mgr002");
            assertThat(awaitReassignAudits(1))
                    .as("成功改派必須進稽核").isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("#92：⚠️ 鏈頂主管 dir001（沒有直屬主管）→ 仍然 200")
        void reassignToChainTopPersonIsAllowed() throws Exception {
            // 為什麼這條最關鍵：存在性判斷若寫成
            //     if (orgService.getDirectManager(x) == null) → 拒絕
            // 就會擋掉 dir001 與 admin001 —— 他們**存在**，只是沒有主管。
            // MockOrgController 對鏈頂人員回 {} 而不是 404 就是為了這個區分。
            // 「答案恰好等於預期值」是這個 repo 踩過最多次的坑。
            Task task = startLeaveAndGetManagerTask("user001");

            var res = reassign(task.getId(), "mgr001", "dir001");

            assertThat(res.statusCode())
                    .as("「沒有主管」是事實，不是「查無此人」").isEqualTo(200);
            assertThat(assigneeOf(task.getId())).isEqualTo("dir001");
        }

        @Test
        @DisplayName("#92：改派給一般員工（非主管）→ 仍然 200")
        void reassignToRegularEmployeeIsAllowed() throws Exception {
            // 第三種身分形狀：既不是主管也不是鏈頂。驗證不可依賴
            // 「他上面有人」這種推論。
            Task task = startLeaveAndGetManagerTask("user001");

            assertThat(reassign(task.getId(), "mgr001", "user001").statusCode()).isEqualTo(200);
            assertThat(assigneeOf(task.getId())).isEqualTo("user001");
        }

        @Test
        @DisplayName("#92：⚠️ 改派給<b>不在候選清單裡</b>的真人 → 必須允許（200）")
        void reassignToKnownPersonOutsideCandidateListIsAllowed() throws Exception {
            // 這一條是本工項最重要的非回歸對照，也是「驗是不是人而不是是不是
            // 候選人」這個判斷的唯一防護門。
            //
            // 情境刻意挑一個<b>真的帶著候選清單</b>的任務：purchase-approval 的
            // 財務審核關卡，候選人來自 permService（MockPermController 給的是
            // mgr001／dir001）。我們把它改派給 user001 ——
            // 他是組織系統認識的人，但<b>不在候選清單裡</b>。
            //
            // 為什麼必須允許：候選清單是流程啟動時的建議，不是改派的白名單。
            // 主管把任務交給一位不在候選清單裡但確實該處理的人（出差、代班、
            // 跨部門支援）是正常業務行為。若有人把「assignee 必須是候選人」
            // 補上（TaskController 原本的註解描述的就是那個檢查），這一條會紅 ——
            // 而那正是它存在的目的：讓那個改動必須是一個明確的決定。
            var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                    Map.of("initiator", "user001", "amount", 1000, "itemName", "測試"));
            Task mgr = taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
            taskService.complete(mgr.getId(), Map.of("approved", true, "rejected", false));

            Task finance = taskService.createTaskQuery()
                    .processInstanceId(pi.getId()).list().get(0);
            List<String> candidates = taskService.getIdentityLinksForTask(finance.getId())
                    .stream()
                    .filter(l -> l.getType() != null && l.getType().contains("candidate"))
                    .map(IdentityLink::getUserId)
                    .filter(Objects::nonNull)
                    .toList();
            assertThat(candidates)
                    .as("這個對照組必須真的站在「有候選清單」的情境上，否則它證明不了任何事")
                    .contains("dir001", "mgr001")
                    .doesNotContain("user001");

            // dir001 是候選人（TaskHolderGuard 條件 3），所以他可以對這個任務動手。
            assertThat(put("/api/tasks/" + finance.getId(), "dir001",
                    "{\"action\":\"claim\"}").statusCode()).isEqualTo(200);

            var res = reassign(finance.getId(), "dir001", "user001");

            assertThat(res.statusCode())
                    .as("改派給不在候選清單裡、但組織系統認識的人必須允許 —— "
                            + "候選清單是啟動時的建議，不是改派的白名單")
                    .isEqualTo(200);
            assertThat(assigneeOf(finance.getId())).isEqualTo("user001");
        }

        @Test
        @DisplayName("#92：委派出去之後 owner 仍可改派（TaskHolderGuard 的 delegate 生命週期）")
        void ownerCanReassignAfterDelegation() throws Exception {
            // 非回歸：requireHolder 放行 owner（條件 2），而 owner 正是
            // delegate 之前那個 assignee。把 delegatee 改派走之後 owner 還要能动，
            // 否則「委派」就變成一條單向路。
            Task task = startLeaveAndGetManagerTask("user001");

            assertThat(put("/api/tasks/" + task.getId(), "mgr001",
                    "{\"action\":\"delegate\",\"delegateUser\":\"user001\"}").statusCode())
                    .isEqualTo(200);
            assertThat(assigneeOf(task.getId())).isEqualTo("user001");

            // owner = mgr001（新檢查不該影響這裡：他是真人）
            var res = reassign(task.getId(), "mgr001", "mgr002");

            assertThat(res.statusCode())
                    .as("owner 必須仍能改派（TaskHolderGuard 條件 2）").isEqualTo(200);
            assertThat(assigneeOf(task.getId())).isEqualTo("mgr002");
        }
    }
}
