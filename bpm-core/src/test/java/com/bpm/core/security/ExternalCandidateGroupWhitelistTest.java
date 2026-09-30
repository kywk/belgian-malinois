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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * #88 政策 B：{@code firstTaskCandidateGroups} 的授權白名單
 * （{@code ExternalSystem.allowedCandidateGroups}）。
 *
 * <h2>它修的缺陷是什麼</h2>
 *
 * <p>改動前 {@code firstTaskCandidateGroups} <b>完全沒有任何驗證</b> ——
 * 後端只檢查「{@code firstTaskAssignee}／{@code firstTaskCandidateGroups}
 * 至少有一個」，不檢查那些群組名稱是不是這個系統被授權用的。任何持有
 * API key 的系統都能把案件丟進<b>任意特權群組</b>的待辦池
 * （{@code docs/plan/2026-09-28-remediation-backlog.md:260}）。
 *
 * <h2>⚠️ 白名單留空的語意是「不限制」，而這個決定同時決定了兩件事</h2>
 *
 * <ol>
 *   <li><b>migration 之後既有資料不需要回填</b> —— 既有系統的這個欄位是 null，
 *       null = 不限制，所以它們的行為完全不變。</li>
 *   <li>反過來：<b>對既有系統而言這個檢查完全沒有效果</b>，直到管理員逐一設定。
 *       這與 {@code allowedProcessKeys} 是同一個已知狀況（R-21）。
 *       {@link Passes#nullWhitelistMeansUnrestricted} 是這個決定的直接證據，
 *       而它存在的另一個理由是：migration 若讓既有系統全部被鎖死，
 *       那是本專案拒絕的升級方向（既有整合被鎖死比新檢查不嚴更糟）。</li>
 * </ol>
 *
 * <h2>⚠️ 每條拒絕都驗「什麼都沒發生」</h2>
 *
 * <p>只斷言狀態碼的測試會被「先啟動流程、再丟 403」完全騙過 —— 而那正是
 * #88 要修的缺陷本身。所以每條拒絕都另外驗流程實例數與稽核筆數都沒變。
 *
 * <h2>狀態碼斷言走真實 HTTP</h2>
 *
 * <p>{@code ResponseStatusException} 走 ERROR dispatch 而 MockMvc 不做那次
 * dispatch（見 {@code ErrorDispatchTest}）。admin API 那幾條走 MockMvc，
 * 因為它們斷言的是「設定有沒有被寫進去」，不是狀態碼在線上的樣子。
 */
class ExternalCandidateGroupWhitelistTest extends IntegrationTestBase {

    private static final String PLAIN_KEY = "sk-t88-group-testkey";

    @Autowired
    private ExternalSystemRepository repo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private MockMvc mockMvc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seedExternalSystem() {
        repo.deleteAll();
    }

    /** 建立一個外部系統。{@code allowedCandidateGroups} 就是本工項的白名單。 */
    private ExternalSystem given(String allowedCandidateGroups) {
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId("erp");
        sys.setSystemName("T88 群組白名單測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys("[\"leave-approval\"]");
        sys.setAllowedCandidateGroups(allowedCandidateGroups);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return repo.save(sys);
    }

    private static String body(String extraFields) {
        return "{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"T88G-" + UUID.randomUUID() + "\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}"
                + (extraFields.isEmpty() ? "" : "," + extraFields) + "}";
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

    private long instances() {
        return runtimeService.createProcessInstanceQuery().count();
    }

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

    private String pidOf(HttpResponse<String> res) {
        return res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
    }

    private void assertRejectedWithoutSideEffect(HttpResponse<String> res, int expectedStatus,
                                                 long instancesBefore, long auditsBefore) {
        assertThat(res.statusCode()).isEqualTo(expectedStatus);
        assertThat(instances())
                .as("被拒的請求不得啟動流程（擋在啟動之後就會留下沒有人能簽的案件）")
                .isEqualTo(instancesBefore);
        assertThat(startProcessAudits())
                .as("被拒的請求不得留下「已發起」的稽核")
                .isEqualTo(auditsBefore);
    }

    /** 第一關實際掛上的候選群組名稱。 */
    private List<String> candidateGroupsOf(String pid) {
        return taskService.getIdentityLinksForTask(
                        taskService.createTaskQuery().processInstanceId(pid).singleResult().getId())
                .stream()
                .filter(l -> "candidate".equals(l.getType()))
                .map(l -> l.getGroupId())
                .toList();
    }

    // ── 缺陷本體 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("缺陷：未授權的候選群組必須被擋")
    class Rejections {

        @Test
        @DisplayName("#88：白名單外的群組 → 403，且不啟動流程")
        void unlistedGroupIsForbidden() throws Exception {
            given("[\"dept001\"]");
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskCandidateGroups\":\"dept001,hr:leave:approve\""));

            assertRejectedWithoutSideEffect(res, 403, before, audits);
            assertThat(res.body())
                    .as("必須指名那個未授權的群組，並指出授權在哪裡設定")
                    .contains("hr:leave:approve").contains("allowedCandidateGroups");
        }

        @Test
        @DisplayName("#88：子串不得誤放行（dept0011 不是 dept001）")
        void substringMustNotPass() throws Exception {
            // 與 isProcessKeyAllowed 同一個坑（R-09）：集合比對不是子串比對。
            given("[\"dept001\"]");
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskCandidateGroups\":\"dept0011\""));

            assertRejectedWithoutSideEffect(res, 403, before, audits);
        }

        @Test
        @DisplayName("#88：明確設定為 [] → 拒絕全部群組（不是「不限制」）")
        void explicitEmptyListDeniesAll() throws Exception {
            given("[]");
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskCandidateGroups\":\"dept001\""));

            assertRejectedWithoutSideEffect(res, 403, before, audits);
        }

        @Test
        @DisplayName("#88：⚠️ 群組授權必須排在身分檢查之前（否則呼叫端會去改錯的欄位）")
        void groupAuthorizationWinsOverPersonCheck() throws Exception {
            // 順序反了會是這樣：未授權的群組 + 拼錯的員工編號 → 先撞上
            // 「firstTaskAssignee 不是組織系統認識的人員」的 400，
            // 呼叫端於是去換一個**根本不是問題來源**的欄位，
            // 而真正的問題（它沒有這個群組的權限）要換完 id 再送一次才浮現。
            //
            // 這一條是「授權先決」這個原則在 #88 裡的延伸，
            // 與 #80 的 404 預檢排在 403 之後是同一個道理。
            given("[\"dept001\"]");
            long before = instances();

            var res = post(body("\"firstTaskCandidateGroups\":\"hr:leave:approve\","
                    + "\"firstTaskAssignee\":\"nobody-" + UUID.randomUUID() + "\""));

            assertThat(res.statusCode())
                    .as("授權檢查（403）必須先於身分檢查（400）")
                    .isEqualTo(403);
            assertThat(instances()).isEqualTo(before);
        }

        @Test
        @DisplayName("#88：只送分隔符（沒有任何群組）→ 400 而不是靜默卡死")
        void separatorsOnlyIsBadRequest() throws Exception {
            // 這個形狀在改動前會「通過至少有一個的檢查」（" , " 非 null），
            // 但實際上不會有任何群組被掛到任務上 → 第一關沒有 assignee
            // 也沒有候選人 → 靜默卡死，而且 #89 的 UnreachableTaskListener
            // 因為看到空字串的 identity link 而**不告警**。
            //
            // 現在空白項目被丟棄，於是「至少有一個」這條既有規則如實看到
            // 「沒有群組」。本工項刻意不新增「拒絕空白群組」那條規則
            // （那是政策決定），只讓既有規則看到事實。
            given(null);
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskCandidateGroups\":\" , ,\""));

            assertRejectedWithoutSideEffect(res, 400, before, audits);
            assertThat(res.body())
                    .as("訊息必須指向「必須指定受理人或群組」這條規則")
                    .contains("firstTaskCandidateGroups");
        }
    }

    // ── 對照組：白名單內的群組必須仍然可用 ──────────────────────────

    @Nested
    @DisplayName("對照組：白名單內的群組必須仍然啟動（不得「擋掉全部群組」）")
    class Passes {

        @Test
        @DisplayName("#88：白名單內的群組 → 200，且第一關真的掛上那個群組")
        void listedGroupStillStarts() throws Exception {
            // 少了這一條，一個「擋掉所有候選群組」的實作能讓上面每一條
            // 拒絕測試全綠 —— 而那個實作會讓依賴候選群組認領的整合完全不能用。
            // 而且它斷言的是**真的掛上去**，不只是回 200：
            // 一個「驗證通過但沒寫 identity link」的實作也會讓只斷狀態碼的測試綠。
            given("[\"dept001\",\"hr:leave:approve\"]");

            var res = post(body("\"firstTaskCandidateGroups\":\"dept001,hr:leave:approve\""));

            assertThat(res.statusCode())
                    .as("守衛必須是白名單比對，而不是擋掉一切")
                    .isEqualTo(200);
            assertThat(candidateGroupsOf(pidOf(res)))
                    .as("第一關必須真的掛上呼叫端指定的群組（驗證過的那一份清單）")
                    .containsExactlyInAnyOrder("dept001", "hr:leave:approve");
            assertThat(taskService.createTaskQuery()
                    .processInstanceId(pidOf(res)).singleResult().getAssignee())
                    .as("只給候選群組時不得指派任何人，否則群組成員認領不到")
                    .isNull();
        }

        @Test
        @DisplayName("#88：⚠️ 白名單為 null → 不限制（migration 不需要回填的理由）")
        void nullWhitelistMeansUnrestricted() throws Exception {
            // 這一條同時是「既有資料怎麼辦」的答案：既有系統的欄位是 null，
            // 而 null 必須代表「照舊可以指定任何群組」。
            //
            // ⚠️ 若實作把 null 當成「拒絕全部」，migration 上線的那一刻
            // 所有既有外部系統的候選群組發起會全部被鎖死 —— 而畫面上
            // 沒有任何地方顯示它們被鎖住了。
            given(null);

            var res = post(body("\"firstTaskCandidateGroups\":\"dept001,anything-goes\""));

            assertThat(res.statusCode())
                    .as("白名單留空代表不限制，與 allowedProcessKeys 同一條規則")
                    .isEqualTo(200);
            assertThat(candidateGroupsOf(pidOf(res)))
                    .containsExactlyInAnyOrder("dept001", "anything-goes");
        }

        @Test
        @DisplayName("#88：空白項目被丟棄，不影響其他群組（不得「整個請求被拒」）")
        void blankEntriesDoNotRejectTheRequest() throws Exception {
            given("[\"dept001\"]");

            var res = post(body("\"firstTaskCandidateGroups\":\" dept001 , ,\""));

            assertThat(res.statusCode())
                    .as("空白不是群組名稱；丟棄它不是放寬（沒有人是空字串群組的成員）")
                    .isEqualTo(200);
            assertThat(candidateGroupsOf(pidOf(res)))
                    .as("不得寫出空字串的 identity link —— 它會讓 UnreachableTaskListener"
                            + "誤判為「有候選人」而對真的沒有人能簽的情況不告警")
                    .containsExactly("dept001");
        }

        @Test
        @DisplayName("#88：同時給 assignee 與群組時，兩者都必須照送（不得互相取代）")
        void assigneeAndGroupsCoexist() throws Exception {
            given("[\"dept001\"]");

            var res = post(body("\"firstTaskAssignee\":\"mgr001\","
                    + "\"firstTaskCandidateGroups\":\"dept001\""));

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(taskService.createTaskQuery()
                    .processInstanceId(pidOf(res)).singleResult().getAssignee())
                    .isEqualTo("mgr001");
            assertThat(candidateGroupsOf(pidOf(res))).containsExactly("dept001");
        }
    }

    // ── 管理 API：白名單真的可以被設定與稽核 ──────────────────────

    @Nested
    @DisplayName("admin API：白名單必須可設定、可讀回、且變更留痕")
    class AdminApi {

        @Test
        @DisplayName("#88：PUT 設定白名單 → 寫進資料庫，並在 GET 裡讀得回來")
        void adminApiPersistsAndReturnsTheWhitelist() throws Exception {
            given(null);

            mockMvc.perform(put("/api/admin/external-systems/erp")
                            .header("X-User-Id", "admin001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"systemName\":\"T88 群組白名單測試系統\","
                                    + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                    + "\"enabled\":true,"
                                    + "\"allowedCandidateGroups\":\"[\\\"dept001\\\"]\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isOk());

            assertThat(repo.findBySystemId("erp").orElseThrow().getAllowedCandidateGroups())
                    .as("PUT 必須真的寫進資料庫")
                    .isEqualTo("[\"dept001\"]");

            // ⚠️ 讀回來這一半不是多餘的：ExternalSystemAdmin.vue 的 applyForm()
            // 只從列資料挑 blankForm() 認得的鍵，而後端 PUT 是整欄覆寫 ——
            // 若 list/get 不回傳這個欄位，管理員編輯任一系統都會把白名單弄丟，
            // 而畫面上完全看不出來（與 #68a 的授權繼承同一種形狀）。
            String listBody = mockMvc.perform(get("/api/admin/external-systems")
                            .header("X-User-Id", "admin001"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(listBody)
                    .as("管理頁必須讀得到白名單，否則編輯一次就會靜默清掉它")
                    .contains("allowedCandidateGroups")
                    .contains("dept001");
        }

        @Test
        @DisplayName("#88：設定後立刻生效（設定與執行是同一條規則，不是兩處各寫一份）")
        void configuredWhitelistTakesEffectImmediately() throws Exception {
            // 這一條防的是「管理頁設定存在資料庫，但守衛讀的是別的來源」
            // 這種只有接上整合才會發現的分岔。
            given(null);
            long before = instances(), audits = startProcessAudits();
            assertThat(post(body("\"firstTaskCandidateGroups\":\"dept001\"")).statusCode())
                    .as("前置條件：還沒設白名單時可以指定任何群組")
                    .isEqualTo(200);

            mockMvc.perform(put("/api/admin/external-systems/erp")
                            .header("X-User-Id", "admin001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"systemName\":\"T88 群組白名單測試系統\","
                                    + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                    + "\"enabled\":true,"
                                    + "\"allowedCandidateGroups\":\"[\\\"dept001\\\"]\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isOk());

            var res = post(body("\"firstTaskCandidateGroups\":\"hr:leave:approve\""));
            assertRejectedWithoutSideEffect(res, 403, before + 1, audits + 1);
            assertThat(post(body("\"firstTaskCandidateGroups\":\"dept001\"")).statusCode())
                    .as("被授權的群組必須仍然可用")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("#88：⚠️ 只改白名單的 PUT 也必須留下稽核前後值")
        void whitelistChangeIsAudited() throws Exception {
            // 這是 P2-4 記錄過的同型缺陷：allowOnBehalfOf 不在 AUDITED_FIELDS 時，
            // 「只改這一欄的 PUT」會被判定為「沒有變更」而完全不寫稽核 ——
            // 也就是擴大授權這件事在稽核軌跡裡完全不存在。
            // 候選群組白名單是同樣的授權維度，所以它必須在 AUDITED_FIELDS 裡。
            truncateAuditLog();
            given("[\"dept001\"]");

            mockMvc.perform(put("/api/admin/external-systems/erp")
                            .header("X-User-Id", "admin001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"systemName\":\"T88 群組白名單測試系統\","
                                    + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                    + "\"enabled\":true,"
                                    + "\"allowedCandidateGroups\":\"[\\\"dept001\\\",\\\"dept002\\\"]\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isOk());

            // AuditEventPublisher 是 @Async，要等它落地（AuditCoverageTest 同款）。
            String details = "";
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                StringBuilder sb = new StringBuilder();
                withAuditConnection(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT detail FROM bpm_audit_log "
                                    + "WHERE operation_type = 'CONFIG_CHANGE' ORDER BY id")) {
                        var rs = ps.executeQuery();
                        while (rs.next()) sb.append(rs.getString(1)).append('\n');
                    }
                });
                details = sb.toString();
                if (details.contains("allowedCandidateGroups")) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            assertThat(details)
                    .as("擴大「這個系統能把單子丟進哪些待辦池」必須留下前後值")
                    .contains("update").contains("allowedCandidateGroups")
                    .contains("dept002");
        }
    }
}