package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #88：{@code POST /api/external/process-instances} 的
 * {@code firstTaskAssignee} <b>完全沒有驗證</b>。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <pre>
 *   外部系統 erp 送 {"processDefinitionKey":"leave-approval",
 *                    "firstTaskAssignee":"system:evil"}
 *   → 後端只檢查「firstTaskAssignee／firstTaskCandidateGroups 至少有一個」
 *     （system:evil 非 null → 通過），完全不驗那是什麼
 *   → 第一個人工任務的 assignee = system:evil
 *   → 沒有人能以 system:evil 登入，TaskHolderGuard 的四個條件全部不命中
 *   → 沒有任何人看得到、沒有任何人能簽
 *   → 案件從**第一關**就卡住，沒有例外、沒有告警
 * </pre>
 *
 * <p>與 #83 是<b>同一個缺陷的兩個入口</b>：#83 修的是補件關卡（較晚），
 * 本工項是第一關（較早）。第一關更早 —— 使用者看到自己的單一進去就沒動靜，
 * 直覺是「系統故障」而不是「送件的參數錯了」。
 *
 * <h2>⚠️ 狀態碼斷言走<b>真實 HTTP</b>而不是 MockMvc</h2>
 *
 * <p>本專案的教訓（見 {@code ErrorDispatchTest} 與 #80 的同款說明）：
 * {@code ResponseStatusException} 走 ERROR dispatch，MockMvc 不做那次 dispatch。
 * 而本工項最危險的失敗型態是「狀態碼對了但流程還是啟動了」，
 * 所以除了狀態碼，每一條拒絕都另外驗<b>資料沒有變</b>
 * （流程實例數與稽核筆數都不動）。
 * 實測確認 {@code ResponseStatusException} 的 reason <b>確實會出現在回應 body</b>，
 * 所以訊息內容也一併斷言 —— 呼叫端要拿到「該換成什麼」而不只是一個 400。
 *
 * <h2>⚠️ 每一條拒絕都配一條「同樣參數、只有那個值不同」的放行對照</h2>
 *
 * <p>這是本測試組存在的核心理由。{@code firstTaskAssignee} 是一個
 * <b>自由字串</b>，所以「擋掉所有非 null 值」的實作能讓下面每一條拒絕測試
 * 全綠 —— 而那個實作會讓所有正常發起都回 400，缺陷從「卡死」換成「不能發」。
 * 對照組在 {@link Passes}，其中兩條特別關鍵：
 *
 * <ul>
 *   <li>{@link Passes#chainTopAssigneeStillStarts}：{@code dir001} 沒有主管，
 *       組織查詢回 {@code null}。<b>把回傳值當存在性判斷</b>的實作會擋掉
 *       總監本人 —— 而「答案恰好等於預期值」是這個 repo 踩過最多次的坑。</li>
 *   <li>{@link Passes#groupsOnlyStillStarts}：沒指名 assignee 是合法的
 *       （改用候選群組），守衛必須跳過而不是把它當成「沒指定對象」。</li>
 * </ul>
 *
 * <h2>⚠️ 負向控制組實測（2026-09-30，把 {@code ExternalApiController.java} 整份還原成 HEAD）</h2>
 *
 * <p><b>15 條中 6 紅 9 綠。</b>紅的正是缺陷本身，而且紅的原因全都是
 * {@code expected: 400 but was: 200} —— 也就是<b>流程真的被啟動了</b>，
 * 也就是本工項要修的那件事。
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅</td>
 *       <td>{@code systemIdentityIsRejected}<br>
 *           {@code systemIdentityIsCaseInsensitive}<br>
 *           {@code unknownAssigneeIsRejected}<br>
 *           {@code fullWidthSystemPrefixIsRejected}<br>
 *           {@code blankAssigneeIsRejected}<br>
 *           {@code paddedAssigneeIsRejected}</td>
 *       <td>缺陷期間 6 種形狀<b>全部 200</b>。回應的
 *           {@code processInstanceId} 與 200 一起回來，呼叫端完全看不出
 *           有問題 —— 這正是「靜默卡死」的定義。</td></tr>
 *   <tr><td>🟢 綠</td>
 *       <td>{@link Passes} 全部 6 條<br>
 *           {@link Ordering#authorizationStillWinsOverTheNewCheck}<br>
 *           {@code onBehalfOfUsesTheSameRule}<br>
 *           {@code onBehalfOfUnknownEmployeeIsStillRejected}</td>
 *       <td>前 7 條是刻意的<b>非回gression對照</b>：它們在缺陷期間就該綠，
 *           而且<b>修好之後仍必須綠</b> —— 一個「擋掉全部 assignee」的實作
 *           能讓 6 條紅的通過，卻會讓這 7 條紅。
 *           後 2 條證明 {@code onBehalfOf} 的驗證<b>改動前就存在且正確</b>
 *           （它靠「組織系統查不到 system:evil」擋下），本工項把它收進
 *           共用規則後行為不變；它們綠是<b>非回歸證據</b>，不是漏抓。</td></tr>
 * </table>
 *
 * <p>還原方式：<b>整份還原</b> {@code ExternalApiController.java} 成 HEAD
 * （不是只把守衛呼叫註解掉）。理由見 round-3 handoff 第 4.4 節 ——
 * 把缺陷放回錯誤的位置會讓它反過來擋掉一切、測試全綠，等於沒驗到。
 *
 * <h2>⚠️ 為什麼沒有「候選群組也要驗」這組測試</h2>
 *
 * <p>因為<b>刻意不驗</b>，而這是本工項最大的一個未完成項（見
 * {@code ExternalApiController} 中該段註解與工項報告）。候選群組名稱在
 * 本 repo 有三個互質來源（部門代碼／權限碼／JWT authority，見
 * {@code CandidateGroupMembership}），只有第一個有存在性 API。
 * {@link Passes#permissionCodeGroupIsAccepted} 是它的對照：若有人日後
 * 補上「把每個群組都當部門驗」的實作，這一條會紅 ——
 * 那正是本專案自己產生的 BPMN 會用的形狀（{@code hr:leave:approve}）。
 *
 * <h2>2026-09-30 政策裁決後的分工</h2>
 *
 * <p>本檔守的是「<b>firstTaskAssignee 必須是人</b>」這條規則。裁決後另外兩件事
 * 有自己的檔案，不要把它們的測試塞回本檔：
 * <ul>
 *   <li>組織系統<b>故障</b> → 503（與「查無此人」分開）→ {@code ExternalOrgSystemFailureTest}</li>
 *   <li>候選群組<b>授權白名單</b> → {@code ExternalCandidateGroupWhitelistTest}</li>
 * </ul>
 * 本檔唯一因此修改的是 {@code unknownAssigneeIsRejected} 的訊息斷言：
 * 裁決前 400 的訊息必須同時點名「payload 不對」與「組織系統可能不可用」，
 * 現在後者已經是 503，訊息若仍混入「請稍後重試」會讓呼叫端對一個
 * 永遠不會成功的請求一直重試。
 */
class ExternalFirstTaskAssigneeTest extends IntegrationTestBase {

    private static final String PLAIN_KEY = "sk-t88-external-testkey";

    @Autowired
    private ExternalSystemRepository repo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seedExternalSystem() {
        repo.deleteAll();
    }

    /**
     * 建立一個外部系統。{@code allowOnBehalfOf} 只有需要測代發時才開。
     *
     * <p>apiKey 以 SHA-256 雜湊存放（明文只給呼叫端，見 {@code ApiKeyUtil}）。
     */
    private ExternalSystem given(String allowedProcessKeys) {
        return given(allowedProcessKeys, false);
    }

    private ExternalSystem given(String allowedProcessKeys, boolean allowOnBehalfOf) {
        ExternalSystem sys = new ExternalSystem();
        // 刻意不設 id（@GeneratedValue(strategy = UUID)；自行指定會走 merge）。
        sys.setSystemId("erp");
        sys.setSystemName("T88 測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys(allowedProcessKeys);
        sys.setAllowOnBehalfOf(allowOnBehalfOf);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return repo.save(sys);
    }

    // ── 請求 ──────────────────────────────────────────────────────────

    private static String body(String processKey, String extraFields) {
        return "{\"processDefinitionKey\":\"" + processKey + "\","
                + "\"businessKey\":\"T88-" + UUID.randomUUID() + "\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}"
                + (extraFields.isEmpty() ? "" : "," + extraFields) + "}";
    }

    private static String assigneeBody(String value) {
        // JSON 字面值：null 與「欄位不存在」要能分開，所以用字面值而不是
        // 把 null 組進字串（那會變成 "null" 字串而不是 JSON null）。
        return body("leave-approval", "\"firstTaskAssignee\":" + value);
    }

    private HttpResponse<String> post(String payload) throws Exception {
        var req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT
                                + "/api/external/process-instances"))
                .header("X-API-Key", PLAIN_KEY)
                .header("X-System-Id", "erp")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── 斷言工具 ──────────────────────────────────────────────────────

    private long instances() {
        return runtimeService.createProcessInstanceQuery().count();
    }

    /** 外部 API 啟動流程的稽核筆數（用來驗「被拒的請求不留痕」）。 */
    private long startProcessAudits() {
        final long[] count = {0};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_audit_log WHERE operation_type = 'EXTERNAL_API_CALL'")) {
                var rs = ps.executeQuery();
                if (rs.next()) count[0] = rs.getLong(1);
            }
        });
        return count[0];
    }

    /**
     * 斷言「被拒絕，而且<b>什麼都沒發生</b>」。
     *
     * <p>後兩個斷言才是重點。只斷言狀態碼的測試，會被「先啟動流程、
     * 再丟 400」這種實作完全騙過 —— 而那個實作正是本工項要修的缺陷
     * 本身（案件已經存在，只是沒有人能簽）。
     */
    private void assertRejectedWithoutSideEffect(HttpResponse<String> res,
                                                  long instancesBefore, long auditsBefore) {
        assertThat(res.statusCode())
                .as("組織系統不認識的受理人必須 400（payload 不對，呼叫端該改參數），"
                        + "不是 403（授權）也不是 404（資源不存在）")
                .isEqualTo(400);
        assertThat(instances())
                .as("被拒的請求不得啟動流程 —— 若先啟動再回 400，"
                        + "案件就已經卡死在沒有人能簽的地方了")
                .isEqualTo(instancesBefore);
        assertThat(startProcessAudits())
                .as("被拒的請求不得留下「已發起」的稽核（不宣稱沒發生的變更）")
                .isEqualTo(auditsBefore);
    }

    private String pidOf(HttpResponse<String> res) {
        return res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
    }

    // ── 缺陷本體 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("缺陷：firstTaskAssignee 完全沒有驗證")
    class Rejections {

        @Test
        @DisplayName("#88：firstTaskAssignee=system:evil → 400 且不啟動流程")
        void systemIdentityIsRejected() throws Exception {
            given("[\"leave-approval\"]");
            long before = instances(), audits = startProcessAudits();

            var res = post(assigneeBody("\"system:evil\""));

            assertRejectedWithoutSideEffect(res, before, audits);
            assertThat(res.body())
                    .as("訊息必須指名成因：呼叫端看到 400 要知道該換成什麼")
                    .contains("系統身分");
        }

        @Test
        @DisplayName("#88：大小寫不同的 system: 前綴同樣 400（只比小寫會留下繞道）")
        void systemIdentityIsCaseInsensitive() throws Exception {
            given("[\"leave-approval\"]");
            long before = instances(), audits = startProcessAudits();

            // 對照組：上方 systemIdentityIsRejected 用的是小寫，
            // 這裡只換大小寫，其餘參數完全相同。
            for (String v : new String[]{"\"SYSTEM:evil\"", "\"System:evil\"", "\"sYsTeM:evil\""}) {
                var res = post(assigneeBody(v));
                assertThat(res.statusCode())
                        .as("大小寫不同的系統身分必須同樣被擋: " + v)
                        .isEqualTo(400);
                assertThat(instances())
                        .as("被拒的請求不得啟動流程: " + v).isEqualTo(before);
            }
            assertThat(startProcessAudits()).isEqualTo(audits);
        }

        @Test
        @DisplayName("#88：組織系統查無此人的員工編號 → 400（缺陷的真正形狀）")
        void unknownAssigneeIsRejected() throws Exception {
            given("[\"leave-approval\"]");
            long before = instances(), audits = startProcessAudits();

            var res = post(assigneeBody("\"nobody-" + UUID.randomUUID() + "\""));

            assertRejectedWithoutSideEffect(res, before, audits);
            assertThat(res.body())
                    .as("⚠️ 2026-09-30 政策裁決後，這句訊息<b>不再</b>需要點名「組織系統可能不可用」："
                            + "組織系統故障現在走 503（見 ExternalOrgSystemFailureTest），"
                            + "而 400 的訊息若仍混入「請稍後重試」會讓呼叫端對一個"
                            + "永遠不會成功的請求一直重試")
                    .contains("不是組織系統認識的人員")
                    .doesNotContain("請稍後重試");
        }

        @Test
        @DisplayName("#88：全形 system： 不被前綴比對命中，仍必須被組織查詢擋下")
        void fullWidthSystemPrefixIsRejected() throws Exception {
            given("[\"leave-approval\"]");
            long before = instances(), audits = startProcessAudits();

            // 這條是「問組織系統是主要規則、而不只是擋前綴」的直接證據。
            // 若實作只有前綴比對（isSystemActor 對全形回 false），
            // 這個值會通過 —— 而它一樣沒有人能簽。
            var res = post(assigneeBody("\"ｓｙｓｔｅｍ：evil\""));

            assertRejectedWithoutSideEffect(res, before, audits);
        }

        @Test
        @DisplayName("#88：空白 assignee → 400（空白不是「留給候選群組認領」）")
        void blankAssigneeIsRejected() throws Exception {
            given("[\"leave-approval\"]");
            long before = instances(), audits = startProcessAudits();

            // 缺陷期間這兩種形狀的後果不同，但都錯：
            //  * 單獨送 → 通過「至少有一個」檢查，流程啟動時去查
            //    system:erp 的主管 → 組織系統 fail-closed → 500。
            //  * 同時送候選群組 → setAssignee(taskId, "")，assignee 變成
            //    空字串（不是 null），而 Flowable 的候選群組查詢帶著
            //    RES.ASSIGNEE_ is null → 群組成員看不到 → 靜默卡死，
            //    而且 UnreachableTaskListener 也不告警。
            for (String v : new String[]{"\"\"", "\"   \"", "\"\\t\""}) {
                var res = post(body("leave-approval", "\"firstTaskAssignee\":" + v
                        + ",\"firstTaskCandidateGroups\":\"dept001\""));
                assertThat(res.statusCode())
                        .as("空白不得被當成「未指定」: " + v).isEqualTo(400);
                assertThat(instances()).as("不得啟動流程: " + v).isEqualTo(before);
            }
            assertThat(startProcessAudits()).isEqualTo(audits);
        }

        @Test
        @DisplayName("#88：前後空白的員工編號 → 400（不靜默 trim，錯誤要讓呼叫端看到）")
        void paddedAssigneeIsRejected() throws Exception {
            given("[\"leave-approval\"]");
            long before = instances(), audits = startProcessAudits();

            // 對照組：Passes.knownAssigneeStillStarts 用的是 "mgr001"，
            // 這裡只多兩個空白字元。
            var res = post(assigneeBody("\" mgr001 \""));

            assertRejectedWithoutSideEffect(res, before, audits);
            assertThat(res.body())
                    .as("必須原樣回顯它送的值，呼叫端才知道要去比對哪裡")
                    .contains(" mgr001 ");
        }

        @Test
        @DisplayName("#88：onBehalfOf 走同一條規則（規則只能有一份）")
        void onBehalfOfUsesTheSameRule() throws Exception {
            // 原本 onBehalfOf 就有驗證（inline try/catch），本工項把它收進
            // ExternalActorGuard。這個測試確保 refactor 沒有讓它變鬆：
            // 系統身分這個形狀以前靠「組織系統查不到」擋下，現在多一層前綴比對。
            given("[\"leave-approval\"]", true);
            long before = instances(), audits = startProcessAudits();

            var res = post(body("leave-approval", "\"onBehalfOf\":\"system:evil\""));

            assertThat(res.statusCode())
                    .as("onBehalfOf 必須與 firstTaskAssignee 走同一條規則")
                    .isEqualTo(400);
            assertThat(instances()).isEqualTo(before);
            assertThat(startProcessAudits()).isEqualTo(audits);
        }

        @Test
        @DisplayName("#88：onBehalfOf 查無此人仍然 400（refactor 的非回歸）")
        void onBehalfOfUnknownEmployeeIsStillRejected() throws Exception {
            given("[\"leave-approval\"]", true);
            long before = instances();

            var res = post(body("leave-approval", "\"onBehalfOf\":\"nobody-" + UUID.randomUUID() + "\""));

            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(instances()).isEqualTo(before);
        }
    }

    // ── 對照組：合法值必須仍然放行 ────────────────────────────────────

    @Nested
    @DisplayName("對照組：合法的受理人必須仍然啟動（不得「擋掉全部」）")
    class Passes {

        @Test
        @DisplayName("#88：firstTaskAssignee=mgr001 → 200，且第一關真的指給他")
        void knownAssigneeStillStarts() throws Exception {
            // 這一條與 Rejections 每一條必須成組存在。少了它，
            // 一個「擋掉所有非 null assignee」的實作會讓所有拒絕測試全綠。
            given("[\"leave-approval\"]");

            var res = post(assigneeBody("\"mgr001\""));

            assertThat(res.statusCode())
                    .as("守衛必須用組織系統查詢，而不是擋掉一切")
                    .isEqualTo(200);
            String pid = pidOf(res);
            assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                    .as("正向對照必須真的把第一關指給那個人，而不只是回 200")
                    .isEqualTo("mgr001");
            assertThat(runtimeService.getVariable(pid, "_externalSystemId"))
                    .as("擁有者仍由 server 寫入").isEqualTo("erp");
        }

        @Test
        @DisplayName("#88：⚠️ 鏈頂主管 dir001（沒有直屬主管）→ 仍然 200")
        void chainTopAssigneeStillStarts() throws Exception {
            // 為什麼這條最關鍵：存在性判斷若寫成
            //     if (orgService.getDirectManager(x) == null) → 拒絕
            // 就會擋掉 dir001 與 admin001 —— 他們**存在**，只是沒有主管。
            // MockOrgController 對鏈頂人員回 {} 而不是 404 就是為了這個區分。
            given("[\"leave-approval\"]");

            var res = post(assigneeBody("\"dir001\""));

            assertThat(res.statusCode())
                    .as("「沒有主管」是事實，不是「查無此人」")
                    .isEqualTo(200);
            assertThat(taskService.createTaskQuery()
                    .processInstanceId(pidOf(res)).singleResult().getAssignee())
                    .isEqualTo("dir001");
        }

        @Test
        @DisplayName("#88：一般員工 user001（不是主管）→ 仍然 200")
        void regularEmployeeAssigneeStillStarts() throws Exception {
            // 第三種身分形狀：既不是主管也不是鏈頂。驗證不可依賴
            // 「他上面有人」這種推論。
            given("[\"leave-approval\"]");

            assertThat(post(assigneeBody("\"user001\"")).statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("#88：只給候選群組（沒有 assignee）→ 仍然 200")
        void groupsOnlyStillStarts() throws Exception {
            // 沒有指名是合法的選擇，必須跳過守衛而不是被當成「沒指定對象」。
            // 對照組：Rejections 每一條都帶了 assignee 值。
            given("[\"leave-approval\"]");

            var res = post(body("leave-approval", "\"firstTaskCandidateGroups\":\"dept001\""));

            assertThat(res.statusCode()).isEqualTo(200);
            var task = taskService.createTaskQuery().processInstanceId(pidOf(res)).singleResult();
            assertThat(task.getAssignee())
                    .as("只給候選群組時不得指派任何人，否則群組成員認領不到")
                    .isNull();
        }

        @Test
        @DisplayName("#88：候選群組刻意不驗證 —— 權限碼形狀的群組必須仍然可用")
        void permissionCodeGroupIsAccepted() throws Exception {
            // 本專案最大的未完成項。候選群組名稱有三個來源
            // （CandidateGroupMembership 類別註解），只有「部門代碼」有
            // 存在性 API。若有人補上「把每個群組都當部門驗」的實作，
            // 這條會紅 —— 而 hr:leave:approve 正是本專案自己的 BPMN
            // （「特定單位」欄位）會產生的形狀。
            given("[\"leave-approval\"]");

            assertThat(post(body("leave-approval",
                    "\"firstTaskCandidateGroups\":\"hr:leave:approve\"")).statusCode())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("#88：已授權的 onBehalfOf 代員工發起仍然 200（refactor 的非回歸）")
        void authorizedOnBehalfOfStillStarts() throws Exception {
            given("[\"leave-approval\"]", true);

            var res = post(body("leave-approval", "\"onBehalfOf\":\"user001\""));

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(taskService.createTaskQuery().processInstanceId(pidOf(res))
                    .singleResult().getAssignee())
                    .as("代發時第一關仍走該員工的主管").isEqualTo("mgr001");
        }
    }

    // ── 對照組：授權先於一切（順序不可顛倒）────────────────────────────

    @Nested
    @DisplayName("對照組：授權檢查的順序不可被新檢查蓋掉")
    class Ordering {

        @Test
        @DisplayName("#88：未授權的流程 key 仍然是 403，即使 assignee 也是非法的")
        void authorizationStillWinsOverTheNewCheck() throws Exception {
            // 為什麼重要：若把 400 的身分檢查擺在 403 之前，
            // 一個只被授權 leave-approval 的系統就能用「400 變 403」
            // 探測出伺服器上部署了哪些流程定義 —— 那等於把授權檢查
            // 變成一個 discovery 工具（與 #80 的 404 預檢同一個理由）。
            given("[\"purchase-approval\"]");

            var res = post(assigneeBody("\"system:evil\""));

            assertThat(res.statusCode())
                    .as("授權檢查必須先於身分檢查，否則 400/403 的差異成了部署清單的探測工具")
                    .isEqualTo(403);
        }
    }
}
