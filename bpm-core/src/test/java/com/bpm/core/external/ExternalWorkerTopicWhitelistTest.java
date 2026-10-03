package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import tools.jackson.databind.ObjectMapper;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.job.api.ExternalWorkerJob;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #22 收尾：worker topic 白名單（{@code ExternalSystem.allowedWorkerTopics}）。
 *
 * <h2>它修的缺陷是什麼</h2>
 *
 * <p>改動前 {@code /api/external/worker/**} <b>不檢查 topic 歸屬</b>：
 * Flowable 的 acquire 只按 topic ＋「尚未鎖定」挑 job
 * （{@code selectExternalWorkerJobsToExecute} 的 {@code LOCK_EXP_TIME_ is null}），
 * 沒有任何「這個 job 屬於哪個系統」的維度。任何被授權 {@code external_worker}
 * 的系統都能認領任何未鎖定的 job，而 acquire 會帶回該流程的變數 ——
 * 跨系統洩漏。需要隔離時只能用系統專屬的 topic 名稱，但那是約定不是強制。
 *
 * <h2>⚠️ 白名單留空的語意是「不限制」，而這個決定同時決定了兩件事</h2>
 *
 * <ol>
 *   <li><b>migration 之後既有資料不需要回填</b> —— 既有系統的這個欄位是 null，
 *       null = 不限制，所以它們的行為完全不變。</li>
 *   <li>反過來：<b>對既有系統而言這個檢查完全沒有效果</b>，直到管理員逐一設定。
 *       這與 {@code allowedProcessKeys}／{@code allowedCandidateGroups}
 *       是同一個已知狀況（R-21）。{@code nullWhitelistMeansUnrestricted}
 *       是這個決定的直接證據。</li>
 * </ol>
 *
 * <h2>⚠️ 每條拒絕都驗「什麼都沒發生」</h2>
 *
 * <p>只斷言狀態碼的測試會被「先鎖了 job、再回 403」的實作騙過 ——
 * 而鎖走 job 正是這個缺陷的傷害本身。所以每條拒絕另外驗：job 還在、
 * 還沒被鎖定、稽核筆數不變。
 *
 * <h2>狀態碼斷言走真實 HTTP</h2>
 *
 * <p>{@code ResponseStatusException} 走 ERROR dispatch 而 MockMvc 不做那次
 * dispatch（見 {@code ErrorDispatchTest}），且 worker 端點的授權依賴 filter
 * 放進 request 的 {@code externalSystem} 屬性 —— 所以 worker 那幾條走真實
 * HTTP。admin API 那幾條走 MockMvc，因為它們斷言的是「設定有沒有被寫進去」，
 * 不是狀態碼在線上的樣子（與 {@code ExternalCandidateGroupWhitelistTest}
 * 同一種分工）。
 *
 * <h2>⚠️ 負向控制組實測（2026-10-03）</h2>
 *
 * <p><b>拿掉檢查</b>：把 {@link ExternalWorkerController} 裡 acquire 與 query
 * 的 {@code requireAllowedTopic(...)} 兩處呼叫註解掉，其餘不動。
 * <b>8 條中 4 紅 4 綠。</b>
 *
 * <p>紅的正是每一條拒絕路徑：
 * <ul>
 *   <li>{@code unlistedTopicIsForbiddenWithoutSideEffect} —— acquire 回 200，
 *       而且<b>真的把 job 鎖走了</b>（回應可見 {@code lockOwner=system:erp}）。
 *       這同時是缺陷本身的直接證據：沒有這道閘門，未授權的系統會拿到
 *       別人的 job 與流程變數。</li>
 *   <li>{@code substringMustNotPass}、{@code explicitEmptyListDeniesAll}、
 *       {@code configuredWhitelistTakesEffectImmediately}。</li>
 * </ul>
 *
 * <p>綠的 4 條<b>證明不了這道檢查存在</b>：
 * {@code nullWhitelistMeansUnrestricted} 與 {@code listedTopicStillAcquires}
 * 走的是正向路徑（沒有檢查時本來就會過）；兩條 admin 稽核測試驗的是
 * 寫入與留痕，不經過 controller 的閘門。能抓到「拿掉檢查」的只有上面
 * 那 4 條。
 *
 * <p>還原方式：{@code git checkout -- bpm-core/.../ExternalWorkerController.java}
 * （或把兩處呼叫加回來）。
 */
class ExternalWorkerTopicWhitelistTest extends IntegrationTestBase {

    private static final String PROCESS_KEY = "external-worker-demo";
    private static final String TOPIC = "demo-topic";
    private static final String ERP_KEY = "sk-22wt-erp";

    @Autowired
    private ExternalSystemRepository repo;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 本測試啟動的流程實例。{@link #tearDown()} 會逐一刪除 —— 佇列
     * （ACT_RU_EXTERNAL_JOB）是跨測試共用的資源，不清理的話下一個測試的
     * acquire 會撈到前一個測試留下的 job（與 {@code ExternalWorkerTaskTest}
     * 同一條規則）。
     */
    private final List<String> startedPids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        truncateAuditLog();
        deployDemo();
    }

    @AfterEach
    void tearDown() {
        for (String pid : startedPids) {
            if (runtimeService.createProcessInstanceQuery()
                    .processInstanceId(pid).singleResult() != null) {
                runtimeService.deleteProcessInstance(pid, "22 topic whitelist test cleanup");
            }
        }
        startedPids.clear();
        repo.deleteAll();
    }

    // ── fixture ──────────────────────────────────────────────────────

    /** 範例 BPMN 只部署一次；context 共用，重複部署會產生新版本。 */
    private void deployDemo() {
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(PROCESS_KEY).count() == 0) {
            repositoryService.createDeployment()
                    .name(PROCESS_KEY)
                    .addClasspathResource("bpmn/external-worker-demo.bpmn20.xml")
                    .deploy();
        }
    }

    /** 建立 erp 系統；{@code allowedWorkerTopics} 就是本工項的白名單。 */
    private ExternalSystem given(String allowedWorkerTopics) {
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId("erp");
        sys.setSystemName("T22W topic 白名單測試系統");
        sys.setApiKey(ApiKeyUtil.hash(ERP_KEY));
        sys.setAllowedActions("[\"external_worker\"]");
        sys.setAllowedWorkerTopics(allowedWorkerTopics);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return repo.save(sys);
    }

    /** 以引擎直接啟動範例流程（worker API 測的是「認領既有 job」，不是啟動）。 */
    private String startDemo() {
        String pid = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                "EWT-" + UUID.randomUUID()).getId();
        startedPids.add(pid);
        return pid;
    }

    private ExternalWorkerJob jobOf(String pid) {
        return managementService.createExternalWorkerJobQuery()
                .processInstanceId(pid).singleResult();
    }

    // ── HTTP 工具 ─────────────────────────────────────────────────────

    private HttpResponse<String> acquire(String topic) throws Exception {
        return httpPost("/api/external/worker/tasks/acquire",
                "{\"topic\":\"" + topic + "\"}");
    }

    private HttpResponse<String> query(String topic) throws Exception {
        return httpGet("/api/external/worker/tasks?topic=" + topic);
    }

    private HttpResponse<String> httpPost(String path, String payload) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-API-Key", ERP_KEY)
                .header("X-System-Id", "erp")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-API-Key", ERP_KEY)
                .header("X-System-Id", "erp")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<tools.jackson.databind.JsonNode> tasksOf(HttpResponse<String> res)
            throws Exception {
        List<tools.jackson.databind.JsonNode> out = new ArrayList<>();
        objectMapper.readTree(res.body()).get("tasks").forEach(out::add);
        return out;
    }

    // ── 稽核工具 ─────────────────────────────────────────────────────

    private long externalApiAuditCount() {
        final long[] count = {0};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_audit_log "
                            + "WHERE operation_type = 'EXTERNAL_API_CALL'")) {
                var rs = ps.executeQuery();
                if (rs.next()) count[0] = rs.getLong(1);
            }
        });
        return count[0];
    }

    /**
     * 等 CONFIG_CHANGE 的 detail 出現 {@code fragment} 後回傳全部 detail。
     *
     * <p>{@code AuditEventPublisher} 是 @Async，要等它落地
     * （{@code ExternalCandidateGroupWhitelistTest} 同款）。
     */
    private String awaitConfigChangeDetails(String fragment) {
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
            if (details.contains(fragment)) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return details;
    }

    // ── a) 白名單未設 → 不限制（既有行為）────────────────────────────

    @Nested
    @DisplayName("a) 白名單未設（null）→ 不限制")
    class Unrestricted {

        @Test
        @DisplayName("null 白名單：acquire 與查詢都照舊可用（migration 不需要回填的理由）")
        void nullWhitelistMeansUnrestricted() throws Exception {
            // 這一條同時是「既有資料怎麼辦」的答案：既有系統的欄位是 null，
            // 而 null 必須代表「照舊可以認領任何 topic」。若實作把 null 當成
            // 「拒絕全部」，migration 上線的那一刻所有既有 worker 整合
            // 會全部被鎖死，而畫面上沒有任何地方顯示它們被鎖住了。
            given(null);
            String pid = startDemo();

            var view = query(TOPIC);
            assertThat(view.statusCode()).as(view.body()).isEqualTo(200);
            assertThat(tasksOf(view)).as("白名單留空代表不限制，查詢必須看得到佇列").hasSize(1);

            var res = acquire(TOPIC);
            assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
            var tasks = tasksOf(res);
            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).get("processInstanceId").asText()).isEqualTo(pid);
            assertThat(tasks.get(0).get("variables").isObject())
                    .as("acquire 仍必須帶回 variables 欄位（worker 需要輸入資料）")
                    .isTrue();
            assertThat(jobOf(pid).getLockOwner()).isEqualTo("system:erp");
        }
    }

    // ── b) 白名單外的 topic 必須被擋（acquire 與查詢）────────────────

    @Nested
    @DisplayName("b) 白名單外的 topic：acquire 與查詢都必須 403 且零副作用")
    class Rejections {

        @Test
        @DisplayName("有真實 job 的 topic 不在白名單內 → 403，job 不被鎖、不寫稽核")
        void unlistedTopicIsForbiddenWithoutSideEffect() throws Exception {
            // 白名單故意是「別的 topic」：佇列上真的有一個 demo-topic 的 job，
            // 所以這條拒絕必須來自授權檢查，而不是「剛好沒東西可撈」。
            given("[\"allowed-topic\"]");
            String pid = startDemo();
            long auditsBefore = externalApiAuditCount();

            var acquireRes = acquire(TOPIC);

            assertThat(acquireRes.statusCode())
                    .as("未授權的 topic 不得進入 acquire；body=" + acquireRes.body())
                    .isEqualTo(403);
            assertThat(acquireRes.body())
                    .as("訊息必須指名 topic 與授權欄位，呼叫端才知道去哪裡改")
                    .contains(TOPIC).contains("allowedWorkerTopics");
            assertThat(jobOf(pid).getLockOwner())
                    .as("被拒的 acquire 不得鎖走 job")
                    .isNull();
            assertThat(jobOf(pid).getLockExpirationTime()).isNull();

            var queryRes = query(TOPIC);
            assertThat(queryRes.statusCode())
                    .as("未授權的系統連「這個 topic 有沒有 job」都不該看得到")
                    .isEqualTo(403);
            assertThat(queryRes.body()).contains("allowedWorkerTopics");

            assertThat(externalApiAuditCount())
                    .as("被拒的請求不得留下「已認領」的稽核")
                    .isEqualTo(auditsBefore);
        }

        @Test
        @DisplayName("子串不得誤放行（demo-topic-extended 不是 demo-topic）")
        void substringMustNotPass() throws Exception {
            // 與 isProcessKeyAllowed 同一個坑（R-09）：集合比對不是子串比對。
            given("[\"demo-topic-extended\"]");
            startDemo();

            var res = acquire(TOPIC);

            assertThat(res.statusCode())
                    .as("子串比對會在這裡誤放行；body=" + res.body())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("白名單內的 topic → 200，且真的認領到 job（不得「擋掉全部 topic」）")
        void listedTopicStillAcquires() throws Exception {
            // 少了這一條，一個「擋掉所有 topic」的實作能讓上面每一條拒絕
            // 測試全綠 —— 而那個實作會讓 worker 整合完全不能用。
            given("[\"demo-topic\"]");
            String pid = startDemo();

            var view = query(TOPIC);
            assertThat(view.statusCode()).as(view.body()).isEqualTo(200);
            assertThat(tasksOf(view)).hasSize(1);

            var res = acquire(TOPIC);
            assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
            var tasks = tasksOf(res);
            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).get("processInstanceId").asText()).isEqualTo(pid);
            assertThat(tasks.get(0).get("topic").asText()).isEqualTo(TOPIC);
            assertThat(jobOf(pid).getLockOwner()).isEqualTo("system:erp");
            assertThat(externalApiAuditCount()).as("真的認領到才寫一筆稽核").isEqualTo(1);
        }
    }

    // ── c) 明確的空清單 → 全拒 ──────────────────────────────────────

    @Nested
    @DisplayName("c) 明確設定為 [] → 拒絕全部 topic（不是「不限制」）")
    class DenyAll {

        @Test
        @DisplayName("[] 白名單：acquire 與查詢都 403，job 不被鎖")
        void explicitEmptyListDeniesAll() throws Exception {
            given("[]");
            String pid = startDemo();

            var acquireRes = acquire(TOPIC);
            var queryRes = query(TOPIC);

            assertThat(acquireRes.statusCode()).as(acquireRes.body()).isEqualTo(403);
            assertThat(queryRes.statusCode()).as(queryRes.body()).isEqualTo(403);
            assertThat(jobOf(pid).getLockOwner()).isNull();
            assertThat(externalApiAuditCount()).isZero();
        }
    }

    // ── d) 管理 API：欄位可設定、可讀回、變更留痕 ────────────────────

    @Nested
    @DisplayName("d) admin API：create／update／list 與稽核都要含新欄位")
    class AdminApi {

        @Test
        @DisplayName("create 帶 allowedWorkerTopics → 寫進資料庫，稽核含欄位與值")
        void createPersistsAndAuditsTheField() throws Exception {
            truncateAuditLog();

            mockMvc.perform(post("/api/admin/external-systems")
                            .header("X-User-Id", "admin001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"systemId\":\"t22w-new\",\"systemName\":\"T22W 新系統\","
                                    + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                    + "\"allowedActions\":\"[\\\"external_worker\\\"]\","
                                    + "\"allowedWorkerTopics\":\"[\\\"demo-topic\\\"]\"}"))
                    .andExpect(status().isOk());

            assertThat(repo.findBySystemId("t22w-new").orElseThrow().getAllowedWorkerTopics())
                    .as("create 必須真的把欄位寫進資料庫")
                    .isEqualTo("[\"demo-topic\"]");

            String details = awaitConfigChangeDetails("allowedWorkerTopics");
            assertThat(details)
                    .as("建立時給定 topic 白名單是授權設定，必須留下初始值")
                    .contains("create").contains("allowedWorkerTopics").contains("demo-topic");
        }

        @Test
        @DisplayName("PUT 設定白名單 → 寫進資料庫、GET list 讀得回來、稽核有前後值")
        void updatePersistsReturnsAndAuditsTheField() throws Exception {
            given(null);
            truncateAuditLog();

            mockMvc.perform(put("/api/admin/external-systems/erp")
                            .header("X-User-Id", "admin001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"systemName\":\"T22W topic 白名單測試系統\","
                                    + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                    + "\"allowedActions\":\"[\\\"external_worker\\\"]\","
                                    + "\"enabled\":true,"
                                    + "\"allowedWorkerTopics\":\"[\\\"demo-topic\\\"]\"}"))
                    .andExpect(status().isOk());

            assertThat(repo.findBySystemId("erp").orElseThrow().getAllowedWorkerTopics())
                    .as("PUT 必須真的寫進資料庫")
                    .isEqualTo("[\"demo-topic\"]");

            // ⚠️ 讀回來這一半不是多餘的：ExternalSystemAdmin.vue 的 applyForm()
            // 只從列資料挑 blankForm() 認得的鍵，而後端 PUT 是整欄覆寫 ——
            // 若 list 不回傳這個欄位，管理員編輯任一系統都會把白名單弄丟。
            String listBody = mockMvc.perform(get("/api/admin/external-systems")
                            .header("X-User-Id", "admin001"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(listBody)
                    .as("管理頁必須讀得到白名單，否則編輯一次就會靜默清掉它")
                    .contains("allowedWorkerTopics").contains("demo-topic");

            String details = awaitConfigChangeDetails("allowedWorkerTopics");
            assertThat(details)
                    .as("只改這一欄的 PUT 必須留下前後值（漏列 AUDITED_FIELDS 就完全不留痕）")
                    .contains("update").contains("allowedWorkerTopics").contains("demo-topic");
        }

        @Test
        @DisplayName("設定後立刻生效（admin 寫的與 worker 讀的是同一條規則）")
        void configuredWhitelistTakesEffectImmediately() throws Exception {
            // 這一條防的是「管理頁設定存在資料庫，但守衛讀的是別的來源」
            // 這種只有接上整合才會發現的分岔。
            given(null);
            String pid = startDemo();
            assertThat(query(TOPIC).statusCode())
                    .as("前置條件：還沒設白名單時查得到")
                    .isEqualTo(200);

            // 白名單改成「別的 topic」—— demo-topic 不再被授權。
            putWorkerTopics("[\"other-topic\"]");
            assertThat(acquire(TOPIC).statusCode())
                    .as("設定後立刻生效：未授權的 topic 應被 403 擋下")
                    .isEqualTo(403);
            assertThat(jobOf(pid).getLockOwner()).as("被拒時不得鎖走 job").isNull();

            // 改回 demo-topic 後恢復可用 —— 證明檢查讀的就是這個欄位，
            // 而不是某個寫死或快取的東西。
            putWorkerTopics("[\"demo-topic\"]");
            var res = acquire(TOPIC);
            assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
            assertThat(tasksOf(res)).hasSize(1);
        }

        private void putWorkerTopics(String topicsJson) throws Exception {
            mockMvc.perform(put("/api/admin/external-systems/erp")
                            .header("X-User-Id", "admin001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"systemName\":\"T22W topic 白名單測試系統\","
                                    + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                    + "\"allowedActions\":\"[\\\"external_worker\\\"]\","
                                    + "\"enabled\":true,"
                                    + "\"allowedWorkerTopics\":\"" + topicsJson.replace("\"", "\\\"") + "\"}"))
                    .andExpect(status().isOk());
        }
    }
}
