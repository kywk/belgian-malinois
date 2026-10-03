package com.bpm.core.external;

import com.bpm.core.lint.BpmnLintService;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.job.api.ExternalWorkerJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工項 #22：External Worker Task 的 {@code /api/external/worker/**}。
 *
 * <h2>測什麼</h2>
 *
 * <ul>
 *   <li>BPMN 支援：範例（{@code bpmn/external-worker-demo.bpmn20.xml}）lint 零警告、
 *       部署後被 Flowable 解析成 {@code ExternalWorkerServiceTask}（topic 正確），
 *       且流程真的在該節點停下來等待認領。</li>
 *   <li>完整工作循環：acquire（含變數）→ complete（帶變數）→ async job 續行 →
 *       下一關卡與變數落地。</li>
 *   <li>鎖定：lockDuration 生效、鎖住時其他系統認領不到、過期後仍需「重設」
 *       才回到佇列（實測 Flowable 的 acquire 只挑未鎖定的 job，重設由
 *       async executor 的 ResetExpiredJobsRunnable 執行 —— 測試環境關掉
 *       async executor，因此測試直接呼叫同一個 ManagementService API）。
 *       ⚠️ 另一個實測：exclusive job 的 acquire 會一併鎖 process instance
 *       （{@code ACT_RU_EXECUTION.LOCK_TIME_}），unacquire 不清它 ——
 *       主動釋放後仍需等原鎖到期才真的能被再認領，見
 *       {@code Locking.unacquireReleasesJobButScopeLockHoldsUntilOriginalLockExpires}。</li>
 *   <li>fail 語意：retries 3→2→1→0，前兩次立刻回到佇列、第三次進死信
 *       （原始碼 ExternalWorkerJobFailCmd 的預設 retries=-1＝沿用目前值減一）。</li>
 *   <li>認證／授權：401、403（allowedActions 精確比對 {@code external_worker}）、
 *       workerId 不可偽造、他人的 job 一律 404 且零副作用。</li>
 * </ul>
 *
 * <h2>⚠️ 每一條拒絕都配「零副作用」斷言</h2>
 *
 * <p>只驗狀態碼的測試會被「先動了 job、再回錯誤」的實作騙過。所以每一條
 * 拒絕另外驗：job 還在、還鎖在原擁有者身上、變數沒被寫入、稽核筆數不變。
 *
 * <h2>⚠️ 負向控制組實測（2026-10-03）</h2>
 *
 * <p><b>控制組 1：把 workerId 的伺服器鑄造拿掉</b>（{@code effectiveWorkerId}
 * 改成信任 body 的 workerId，預檢與 Flowable 的 builder 都保留）。
 * <b>23 條中 2 紅 21 綠。</b>紅的只有兩條「主動偽造」測試：
 * {@code forgedWorkerIdIsRejectedOnComplete} 與
 * {@code forgedWorkerIdIsRejectedOnFail} —— 別的系統帶
 * {@code "workerId":"system:erp"} 就能完成／失敗他人的 job（看到 200 而不是
 * 400）。這正是 workerId 必須由伺服器鑄造的理由。
 *
 * <p>⚠️ 綠的 21 條裡有兩條<b>證明不了任何事</b>：
 * {@code otherSystemCompleteIs404AndLeavesJobUntouched} 與
 * {@code unacquireByOtherIs404}。它們只帶自己的 workerId，而 Flowable 的
 * builder 自己會比對 lock owner（{@code AbstractExternalWorkerJobCmd.resolveJob}），
 * 所以即使本服務的預檢或鑄造壞了，它們照樣綠。能抓到「信任呼叫端 workerId」
 * 的只有主動偽造的那兩條。
 *
 * <p><b>控制組 2：只移除本服務的預檢</b>（{@code requireLockedJob} 不比對
 * lock owner，伺服器鑄造保留）。<b>23 條全綠。</b>這證明 workerId 隔離的
 * 實際執行者是 Flowable 的 builder；本服務的預檢是 defense-in-depth 與
 * 「他人的 job 一律 404」的來源，不是唯一防線 —— 也再次說明單獨一條
 * 「他人的 job → 404」無法區分這兩種實作。
 *
 * <p>還原方式：控制組 1 把 {@code effectiveWorkerId} 換成
 * {@code requested instanceof String s && !s.isBlank() ? s : ExternalActorIdentity.of(systemId)}；
 * 控制組 2 把 {@code requireLockedJob} 的
 * {@code job == null || !workerId.equals(job.getLockOwner())} 改成
 * {@code job == null}。兩者跑完即還原（目前原始碼是還原後的版本）。
 */
class ExternalWorkerTaskTest extends IntegrationTestBase {

    private static final String PROCESS_KEY = "external-worker-demo";
    private static final String TOPIC = "demo-topic";
    private static final String ERP_KEY = "sk-22-erp";
    private static final String OTHER_KEY = "sk-22-other";

    @Autowired
    private ExternalSystemRepository externalRepo;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private BpmnLintService lintService;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 本測試啟動的流程實例。{@link #tearDown()} 會逐一刪除 —— 佇列
     * （ACT_RU_EXTERNAL_JOB）是跨測試共用的資源，不清理的話下一個測試的
     * acquire 會撈到前一個測試留下的 job，斷言就會看到不屬於它的資料。
     */
    private final List<String> startedPids = new ArrayList<>();

    /** 最近一次 {@link #startDemo} 的實例 id；{@link #acquireOne} 用它確認撈到的是自己的 job。 */
    private String lastStartedPid;

    @BeforeEach
    void setUp() {
        externalRepo.deleteAll();
        truncateAuditLog();
        deployDemo();
    }

    @AfterEach
    void tearDown() {
        for (String pid : startedPids) {
            if (runtimeService.createProcessInstanceQuery()
                    .processInstanceId(pid).singleResult() != null) {
                runtimeService.deleteProcessInstance(pid, "22 test cleanup");
            }
        }
        startedPids.clear();
        externalRepo.deleteAll();
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

    private ExternalSystem givenSystem(String systemId, String apiKey, String allowedActions) {
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId(systemId);
        sys.setSystemName("22 測試系統 " + systemId);
        sys.setApiKey(ApiKeyUtil.hash(apiKey));
        sys.setAllowedActions(allowedActions);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return externalRepo.save(sys);
    }

    private void givenErp() {
        givenSystem("erp", ERP_KEY, "[\"external_worker\"]");
    }

    private void givenOther() {
        givenSystem("other", OTHER_KEY, "[\"external_worker\"]");
    }

    /** 以引擎直接啟動範例流程（worker API 測的是「認領既有 job」，不是啟動）。 */
    private String startDemo() {
        return startDemo(Map.of());
    }

    private String startDemo(Map<String, Object> variables) {
        String pid = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                "EW-" + UUID.randomUUID(), variables).getId();
        startedPids.add(pid);
        lastStartedPid = pid;
        return pid;
    }

    // ── HTTP 工具 ─────────────────────────────────────────────────────

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String systemId, String apiKey, String payload)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (systemId != null) b.header("X-System-Id", systemId);
        if (apiKey != null) b.header("X-API-Key", apiKey);
        return send(b.build());
    }

    private HttpResponse<String> get(String path, String systemId, String apiKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + SERVLET_PORT + path)).GET();
        if (systemId != null) b.header("X-System-Id", systemId);
        if (apiKey != null) b.header("X-API-Key", apiKey);
        return send(b.build());
    }

    private HttpResponse<String> acquire(String systemId, String apiKey) throws Exception {
        return acquire(systemId, apiKey, "{\"topic\":\"" + TOPIC + "\"}");
    }

    private HttpResponse<String> acquire(String systemId, String apiKey, String payload)
            throws Exception {
        return post("/api/external/worker/tasks/acquire", systemId, apiKey, payload);
    }

    private HttpResponse<String> complete(String systemId, String apiKey, String jobId,
                                          String variablesJson) throws Exception {
        return post("/api/external/worker/tasks/" + jobId + "/complete", systemId, apiKey,
                "{\"variables\":" + variablesJson + "}");
    }

    private HttpResponse<String> completeRaw(String systemId, String apiKey, String jobId,
                                             String payload) throws Exception {
        return post("/api/external/worker/tasks/" + jobId + "/complete", systemId, apiKey, payload);
    }

    private HttpResponse<String> fail(String systemId, String apiKey, String jobId, String payload)
            throws Exception {
        return post("/api/external/worker/tasks/" + jobId + "/fail", systemId, apiKey, payload);
    }

    private HttpResponse<String> unacquire(String systemId, String apiKey, String jobId)
            throws Exception {
        return post("/api/external/worker/tasks/" + jobId + "/unacquire", systemId, apiKey, "{}");
    }

    private List<JsonNode> tasksOf(HttpResponse<String> res) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        objectMapper.readTree(res.body()).get("tasks").forEach(out::add);
        return out;
    }

    private record Acquired(String jobId, JsonNode job) {
    }

    /** 認領成功（前置條件斷言）並回傳 job。 */
    private Acquired acquireOne(String systemId, String apiKey) throws Exception {
        return acquireOne(systemId, apiKey, "{\"topic\":\"" + TOPIC + "\"}");
    }

    private Acquired acquireOne(String systemId, String apiKey, String payload) throws Exception {
        var res = acquire(systemId, apiKey, payload);
        assertThat(res.statusCode())
                .as("前置條件：acquire 必須成功，body=" + res.body())
                .isEqualTo(200);
        var tasks = objectMapper.readTree(res.body()).get("tasks");
        assertThat(tasks).as("前置條件：佇列必須有 job，body=" + res.body()).hasSize(1);
        assertThat(tasks.get(0).get("processInstanceId").asText())
                .as("acquire 撈到的必須是本測試啟動的實例；撈到別人代表清理沒做好")
                .isEqualTo(lastStartedPid);
        return new Acquired(tasks.get(0).get("jobId").asText(), tasks.get(0));
    }

    private ExternalWorkerJob job(String jobId) {
        return managementService.createExternalWorkerJobQuery().jobId(jobId).singleResult();
    }

    // ── 稽核工具 ─────────────────────────────────────────────────────

    private record AuditRow(String operatorId, String detail) {
    }

    private List<AuditRow> externalApiAudits() {
        List<AuditRow> rows = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operator_id, detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'EXTERNAL_API_CALL' ORDER BY id")) {
                var rs = ps.executeQuery();
                while (rs.next()) rows.add(new AuditRow(rs.getString(1), rs.getString(2)));
            }
        });
        return rows;
    }

    private long auditCount(String actionFragment) {
        return externalApiAudits().stream()
                .filter(r -> r.detail().contains(actionFragment)).count();
    }

    // ── a) 完整工作循環 ──────────────────────────────────────────────

    @Nested
    @DisplayName("a) 部署 → 啟動 → acquire → complete → 流程續行")
    class HappyPath {

        @Test
        @DisplayName("acquire 帶回輸入變數；complete 寫入輸出變數；async job 執行後流程續行到人工複核")
        void acquireCompleteContinuesProcessAndWritesVariables() throws Exception {
            givenErp();
            String pid = startDemo(Map.of("requestNo", "REQ-22"));

            // 流程在 external worker 節點停下：沒有 async job，只有 external job。
            assertThat(managementService.createJobQuery().processInstanceId(pid).singleResult())
                    .as("external worker 節點不應該產生 executable job")
                    .isNull();

            Acquired a = acquireOne("erp", ERP_KEY);
            assertThat(a.job().get("topic").asText()).isEqualTo(TOPIC);
            assertThat(a.job().get("processInstanceId").asText()).isEqualTo(pid);
            assertThat(a.job().get("locked").asBoolean()).isTrue();
            assertThat(a.job().get("lockOwner").asText()).isEqualTo("system:erp");
            assertThat(a.job().get("variables").get("requestNo").asText())
                    .as("worker 需要輸入資料才能工作 —— acquire 要帶回流程變數")
                    .isEqualTo("REQ-22");

            var res = complete("erp", ERP_KEY, a.jobId(),
                    "{\"approved\":true,\"result\":\"ok\"}");
            assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
            assertThat(res.body()).contains("completed");

            // external job 已被消耗。
            assertThat(job(a.jobId())).isNull();

            // ⚠️ complete 只把 external job 換成 executable async job；流程
            // 尚未續行。測試環境的 async executor 是關的，手動執行同一個 job。
            var asyncJob = managementService.createJobQuery().processInstanceId(pid).singleResult();
            assertThat(asyncJob).as("complete 後應留下 handler=external-worker-complete 的 async job")
                    .isNotNull();
            managementService.executeJob(asyncJob.getId());

            var review = taskService.createTaskQuery().processInstanceId(pid).singleResult();
            assertThat(review.getName()).isEqualTo("人工複核");
            assertThat(runtimeService.getVariable(pid, "approved")).isEqualTo(true);
            assertThat(runtimeService.getVariable(pid, "result")).isEqualTo("ok");

            // 稽核：acquire + complete 各一筆，operator 是系統身分。
            List<AuditRow> audits = externalApiAudits();
            assertThat(audits).hasSize(2);
            assertThat(audits).allMatch(r -> r.operatorId().equals("system:erp"));
            assertThat(audits.get(0).detail())
                    .contains("external_worker_acquire").contains(a.jobId()).contains(TOPIC);
            assertThat(audits.get(1).detail())
                    .contains("external_worker_complete").contains(a.jobId()).contains(TOPIC);
            assertThat(audits)
                    .as("⚠️ 流程變數不得進稽核庫")
                    .noneMatch(r -> r.detail().contains("approved") || r.detail().contains("result"));
        }

        @Test
        @DisplayName("佇列空時 acquire 回 200/空清單（不是 404），且不寫稽核")
        void emptyAcquireIsNotAnErrorAndIsNotAudited() throws Exception {
            givenErp();

            var res = acquire("erp", ERP_KEY);

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(tasksOf(res)).isEmpty();
            assertThat(externalApiAudits())
                    .as("worker 是輪詢模型；空輪詢若寫稽核，稽核量會隨輪詢頻率無上限成長")
                    .isEmpty();
        }
    }

    // ── b) 鎖定與過期 ────────────────────────────────────────────────

    @Nested
    @DisplayName("b) lockDuration、跨系統隔離與過期重設")
    class Locking {

        @Test
        @DisplayName("鎖住時自己與別系統都認領不到；過期後仍需重設才回到佇列（實測 Flowable 語意）")
        void expiredLockNeedsResetBeforeReacquire() throws Exception {
            givenErp();
            givenOther();
            String pid = startDemo();

            Acquired first = acquireOne("erp", ERP_KEY,
                    "{\"topic\":\"" + TOPIC + "\",\"lockDurationSeconds\":1}");
            ExternalWorkerJob locked = job(first.jobId());
            assertThat(locked.getLockOwner()).isEqualTo("system:erp");
            assertThat(locked.getLockExpirationTime())
                    .as("lockDurationSeconds=1 必須反映在到期時間上")
                    .isNotNull();

            assertThat(tasksOf(acquire("erp", ERP_KEY)))
                    .as("已鎖定的 job 不得被自己重複認領").isEmpty();
            assertThat(tasksOf(acquire("other", OTHER_KEY)))
                    .as("已鎖定的 job 不得被其他系統認領（跨系統隔離）").isEmpty();

            Thread.sleep(1_500);

            // ⚠️ 實測（不是假設）：acquire 的查詢條件是 LOCK_EXP_TIME_ is null
            // （flowable-job-service 的 selectExternalWorkerJobsToExecute），
            // 「過期」不等於「解鎖」。解鎖是 async executor 的
            // ResetExpiredJobsRunnable 週期性呼叫 JobManager.unacquire 做的。
            assertThat(tasksOf(acquire("erp", ERP_KEY)))
                    .as("過期但未重設的鎖仍會擋住 acquire —— 解鎖不是 acquire 的責任")
                    .isEmpty();
            assertThat(tasksOf(acquire("other", OTHER_KEY))).isEmpty();

            // 正式環境由 async executor 執行這個狀態轉移；測試環境關閉它
            // （application-test.yml），所以直接呼叫它會呼叫的同一個 API。
            managementService.unacquireExternalWorkerJob(first.jobId(), "system:erp");

            Acquired again = acquireOne("erp", ERP_KEY);
            assertThat(again.jobId()).isEqualTo(first.jobId());
            assertThat(job(first.jobId()).getLockOwner()).isEqualTo("system:erp");
        }

        @Test
        @DisplayName("unacquire 端點：job 鎖立即清除，但 exclusive job 的範圍鎖到原鎖到期前仍擋住所有認領（實測 Flowable 語意）")
        void unacquireReleasesJobButScopeLockHoldsUntilOriginalLockExpires() throws Exception {
            givenErp();
            givenOther();
            startDemo();
            // 用短鎖：範圍鎖的到期時間 = job 的到期時間，短鎖讓測試不必等 5 分鐘。
            Acquired a = acquireOne("erp", ERP_KEY,
                    "{\"topic\":\"" + TOPIC + "\",\"lockDurationSeconds\":1}");

            var res = unacquire("erp", ERP_KEY, a.jobId());

            assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
            ExternalWorkerJob released = job(a.jobId());
            assertThat(released.getLockOwner()).isNull();
            assertThat(released.getLockExpirationTime())
                    .as("unacquire 必須同時清掉到期時間 —— acquire 的查詢條件是 "
                            + "LOCK_EXP_TIME_ is null，只清 owner 的 job 會永遠回不到佇列")
                    .isNull();
            assertThat(auditCount("external_worker_unacquire")).isEqualTo(1);

            // ⚠️ 實測（不是假設）：external worker job 預設是 exclusive，
            // acquire 時除了 job 的鎖，還會經由 lockJobScope →
            // updateProcessInstanceLockTime 把 ACT_RU_EXECUTION.LOCK_TIME_
            // 設成 job 的到期時間。fail／complete 會透過
            // AbstractExternalWorkerJobCmd → UnlockExclusiveJobCmd 清掉它，
            // 而 unacquireExternalWorkerJob 是獨立 command，只清 job 的鎖、
            // 不清範圍鎖。範圍鎖的更新條件是 LOCK_TIME_ is null OR
            // LOCK_TIME_ < now —— 未到期前任何系統都認領不到，且失敗會被
            // acquireAndLock 的重試迴圈吞掉（回空清單，不是錯誤）。
            assertThat(tasksOf(acquire("other", OTHER_KEY)))
                    .as("範圍鎖未過期前，unacquire 過的 job 仍無法被任何系統認領")
                    .isEmpty();

            Thread.sleep(1_200);

            // 原鎖到期後（正式環境此時 async executor 的 ResetExpiredJobsRunnable
            // 也會把 job 的鎖重設），未鎖定的 job 先搶先贏。
            Acquired taken = acquireOne("other", OTHER_KEY);
            assertThat(taken.jobId()).isEqualTo(a.jobId());
        }

        @Test
        @DisplayName("GET /tasks 只看到未鎖定的與自己鎖定的 job，且不帶流程變數")
        void querySeesOnlyUnlockedAndOwnLockedJobs() throws Exception {
            givenErp();
            givenOther();
            startDemo();

            // 認領前：erp 看到一筆未鎖定。
            var before = tasksOf(get("/api/external/worker/tasks?topic=" + TOPIC, "erp", ERP_KEY));
            assertThat(before).hasSize(1);
            assertThat(before.get(0).get("locked").asBoolean()).isFalse();
            assertThat(before.get(0).get("lockOwner").isNull()).isTrue();
            assertThat(before.get(0).has("variables"))
                    .as("佇列檢視不帶流程變數；變數只在 acquire 時給實際認領者")
                    .isFalse();

            Acquired a = acquireOne("erp", ERP_KEY);

            // 認領後：erp 仍看得到（自己鎖的），other 看不到。
            var erpView = tasksOf(get("/api/external/worker/tasks?topic=" + TOPIC, "erp", ERP_KEY));
            assertThat(erpView).hasSize(1);
            assertThat(erpView.get(0).get("locked").asBoolean()).isTrue();
            assertThat(erpView.get(0).get("lockOwner").asText()).isEqualTo("system:erp");
            assertThat(tasksOf(get("/api/external/worker/tasks?topic=" + TOPIC, "other", OTHER_KEY)))
                    .as("他人鎖定的 job 不得出現在查詢結果（不洩漏存在）")
                    .isEmpty();

            // topic 過濾與缺 topic 的 400。
            assertThat(tasksOf(get("/api/external/worker/tasks?topic=no-such-topic",
                    "erp", ERP_KEY))).isEmpty();
            var missingTopic = get("/api/external/worker/tasks", "erp", ERP_KEY);
            assertThat(missingTopic.statusCode()).isEqualTo(400);
            assertThat(missingTopic.body()).contains("缺少 topic");
            assertThat(a.jobId()).isNotBlank();
        }
    }

    // ── c) fail 語意 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("c) fail：retries 遞減、立刻回佇列、第三次進死信")
    class FailureSemantics {

        @Test
        @DisplayName("實測 Flowable 7.2：3→2→1→0，前兩次 fail 後可立即再認領，第三次進死信且流程卡住")
        void failDecrementsRetriesAndThirdFailMovesToDeadLetter() throws Exception {
            givenErp();
            String pid = startDemo();
            Acquired a = acquireOne("erp", ERP_KEY);
            assertThat(a.job().get("retries").asInt())
                    .as("external worker job 的初始 retries = asyncExecutorNumberOfRetries（預設 3）")
                    .isEqualTo(3);

            // 第 1 次 fail：3 → 2，清空鎖，立即可再認領。
            var f1 = fail("erp", ERP_KEY, a.jobId(),
                    "{\"errorCode\":\"E1\",\"errorMessage\":\"boom-1\"}");
            assertThat(f1.statusCode()).as(f1.body()).isEqualTo(200);
            assertThat(f1.body()).contains("\"retriesLeft\":2");

            ExternalWorkerJob afterFirst = job(a.jobId());
            assertThat(afterFirst.getRetries()).isEqualTo(2);
            assertThat(afterFirst.getLockOwner()).as("fail 後應清除鎖擁有者").isNull();
            assertThat(afterFirst.getLockExpirationTime()).isNull();
            assertThat(afterFirst.getExceptionMessage()).isEqualTo("boom-1");
            assertThat(managementService.getExternalWorkerJobErrorDetails(a.jobId()))
                    .as("builder 沒有 errorCode 欄位；errorCode 放在 Flowable 的 errorDetails")
                    .contains("E1");

            // 失敗的 job 立刻回到佇列（未指定 retryTimeout → 無退避）。
            assertThat(acquireOne("erp", ERP_KEY).jobId()).isEqualTo(a.jobId());

            // 第 2 次 fail：2 → 1。
            var f2 = fail("erp", ERP_KEY, a.jobId(),
                    "{\"errorCode\":\"E2\",\"errorMessage\":\"boom-2\"}");
            assertThat(f2.body()).contains("\"retriesLeft\":1");
            assertThat(acquireOne("erp", ERP_KEY).jobId()).isEqualTo(a.jobId());

            // 第 3 次 fail：1 → 0 → 死信。
            var f3 = fail("erp", ERP_KEY, a.jobId(),
                    "{\"errorCode\":\"E3\",\"errorMessage\":\"boom-3\"}");
            assertThat(f3.body()).contains("\"retriesLeft\":0");
            assertThat(job(a.jobId())).as("retries 歸零後不再是 external worker job").isNull();
            assertThat(managementService.createDeadLetterJobQuery()
                    .jobId(a.jobId()).externalWorkers().singleResult())
                    .as("必須進死信佇列，而不是消失")
                    .isNotNull();

            // 流程停在原節點：沒有 async job 被建立，實例仍在執行中。
            assertThat(managementService.createJobQuery().processInstanceId(pid).singleResult())
                    .isNull();
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(pid).singleResult()).isNotNull();

            // 稽核：3 筆 acquire + 3 筆 fail；errorCode 進稽核、errorMessage 不進。
            assertThat(auditCount("external_worker_acquire")).isEqualTo(3);
            assertThat(auditCount("external_worker_fail")).isEqualTo(3);
            List<AuditRow> fails = externalApiAudits().stream()
                    .filter(r -> r.detail().contains("external_worker_fail")).toList();
            assertThat(fails).allMatch(r -> r.detail().contains("E1")
                    || r.detail().contains("E2") || r.detail().contains("E3"));
            assertThat(fails).noneMatch(r -> r.detail().contains("boom"));
        }

        @Test
        @DisplayName("fail 缺 errorCode/errorMessage 也合法（純技術性失敗）")
        void failWithoutErrorFieldsIsAccepted() throws Exception {
            givenErp();
            startDemo();
            Acquired a = acquireOne("erp", ERP_KEY);

            var res = fail("erp", ERP_KEY, a.jobId(), "{}");

            assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
            assertThat(res.body()).contains("\"retriesLeft\":2");
            assertThat(job(a.jobId()).getExceptionMessage()).isNull();
        }
    }

    // ── 形狀：400 零副作用 ───────────────────────────────────────────

    @Nested
    @DisplayName("payload 形狀：400 且零副作用")
    class Payload {

        @Test
        @DisplayName("acquire 缺 topic → 400")
        void acquireWithoutTopicIs400() throws Exception {
            givenErp();
            var res = acquire("erp", ERP_KEY, "{}");
            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("缺少 topic");
        }

        @Test
        @DisplayName("lockDurationSeconds 非整數或超出範圍 → 400（不靜默截斷）")
        void invalidLockDurationIs400() throws Exception {
            givenErp();
            assertThat(acquire("erp", ERP_KEY,
                    "{\"topic\":\"" + TOPIC + "\",\"lockDurationSeconds\":1.5}")
                    .statusCode()).isEqualTo(400);
            assertThat(acquire("erp", ERP_KEY,
                    "{\"topic\":\"" + TOPIC + "\",\"lockDurationSeconds\":0}")
                    .statusCode()).isEqualTo(400);
            assertThat(acquire("erp", ERP_KEY,
                    "{\"topic\":\"" + TOPIC + "\",\"lockDurationSeconds\":86401}")
                    .statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("complete 的 variables 不是物件 → 400，job 不動")
        void nonObjectVariablesIs400() throws Exception {
            givenErp();
            startDemo();
            Acquired a = acquireOne("erp", ERP_KEY);
            long auditsBefore = externalApiAudits().size();

            var res = completeRaw("erp", ERP_KEY, a.jobId(), "{\"variables\":[\"x\"]}");

            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("variables 必須是 JSON 物件");
            assertThat(job(a.jobId()).getLockOwner()).isEqualTo("system:erp");
            assertThat(externalApiAudits()).hasSize((int) auditsBefore);
        }

        @Test
        @DisplayName("complete 帶 '_' 保留前綴變數 → 400，擁有權變數不得被覆寫")
        void reservedVariableNameIs400() throws Exception {
            givenErp();
            String pid = startDemo();
            Acquired a = acquireOne("erp", ERP_KEY);
            long auditsBefore = externalApiAudits().size();

            var res = complete("erp", ERP_KEY, a.jobId(),
                    "{\"_externalSystemId\":\"other\"}");

            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("_").contains("保留");
            assertThat(job(a.jobId()).getLockOwner())
                    .as("被拒的 complete 不得動 job").isEqualTo("system:erp");
            assertThat(runtimeService.getVariable(pid, "_externalSystemId"))
                    .as("擁有權標記不得被 worker 覆寫（R-19/R-23 的同一缺陷面）")
                    .isNull();
            assertThat(externalApiAudits()).hasSize((int) auditsBefore);
        }
    }

    // ── d) 認證、授權、跨系統隔離 ────────────────────────────────────

    @Nested
    @DisplayName("d) 認證、授權與跨系統隔離")
    class AuthAndIsolation {

        @Test
        @DisplayName("缺 X-API-Key/X-System-Id → 401（既有 filter 行為）")
        void missingApiKeyIs401() throws Exception {
            givenErp();

            var res = acquire(null, null);

            assertThat(res.statusCode()).isEqualTo(401);
            assertThat(res.body()).contains("Missing X-API-Key");
        }

        @Test
        @DisplayName("金鑰錯誤 → 401")
        void wrongApiKeyIs401() throws Exception {
            givenErp();

            var res = acquire("erp", "wrong-key");

            assertThat(res.statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("allowedActions 不含 external_worker → 403（filter 解析出的 action 是 external_worker）")
        void actionNotAllowedIs403() throws Exception {
            givenSystem("plain", "sk-22-plain", "[\"start_process\"]");

            var res = acquire("plain", "sk-22-plain");

            assertThat(res.statusCode()).isEqualTo(403);
            assertThat(res.body()).contains("Action not allowed: external_worker");
        }

        @Test
        @DisplayName("稽核用的操作名（external_worker_acquire）不是授權單位，填它仍 403")
        void auditActionNameDoesNotAuthorize() throws Exception {
            givenSystem("auditonly", "sk-22-audit", "[\"external_worker_acquire\"]");

            var res = acquire("auditonly", "sk-22-audit");

            assertThat(res.statusCode())
                    .as("allowedActions 是精確比對；白名單項目是 external_worker，"
                            + "與稽核 detail 的 external_worker_acquire 是兩件事")
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("未知的 worker 路徑（PUT /tasks）→ 403 fail-closed")
        void unknownWorkerPathIs403() throws Exception {
            givenErp();

            var res = send(HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + SERVLET_PORT + "/api/external/worker/tasks"))
                    .header("X-API-Key", ERP_KEY).header("X-System-Id", "erp")
                    .PUT(HttpRequest.BodyPublishers.ofString("{}")).build());

            assertThat(res.statusCode()).isEqualTo(403);
            assertThat(res.body()).contains("Unrecognised external API endpoint");
        }

        @Test
        @DisplayName("已鎖定的 job，別的系統 acquire 不到")
        void otherSystemCannotAcquireLockedJob() throws Exception {
            givenErp();
            givenOther();
            startDemo();
            acquireOne("erp", ERP_KEY);

            var res = acquire("other", OTHER_KEY);

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(tasksOf(res)).isEmpty();
        }

        @Test
        @DisplayName("別的系統 complete 他人的 job → 404，job／變數／稽核都不動")
        void otherSystemCompleteIs404AndLeavesJobUntouched() throws Exception {
            givenErp();
            givenOther();
            String pid = startDemo();
            Acquired a = acquireOne("erp", ERP_KEY);
            long auditsBefore = externalApiAudits().size();

            var res = complete("other", OTHER_KEY, a.jobId(), "{\"approved\":true}");

            assertThat(res.statusCode()).isEqualTo(404);
            assertThat(res.body()).contains("未由本系統鎖定");
            assertThat(job(a.jobId()).getLockOwner())
                    .as("被拒的 complete 不得釋放或完成他人的 job")
                    .isEqualTo("system:erp");
            assertThat(runtimeService.getVariable(pid, "approved"))
                    .as("被拒的 complete 不得寫入變數").isNull();
            assertThat(externalApiAudits()).hasSize((int) auditsBefore);
        }

        @Test
        @DisplayName("別系統在 body 偽造 workerId=system:erp → 400（workerId 由伺服器鑄造）")
        void forgedWorkerIdIsRejectedOnComplete() throws Exception {
            givenErp();
            givenOther();
            String pid = startDemo();
            Acquired a = acquireOne("erp", ERP_KEY);
            long auditsBefore = externalApiAudits().size();

            var res = completeRaw("other", OTHER_KEY, a.jobId(),
                    "{\"workerId\":\"system:erp\",\"variables\":{\"approved\":true}}");

            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("workerId 由伺服器決定");
            assertThat(job(a.jobId()).getLockOwner()).isEqualTo("system:erp");
            assertThat(runtimeService.getVariable(pid, "approved")).isNull();
            assertThat(externalApiAudits()).hasSize((int) auditsBefore);
        }

        @Test
        @DisplayName("別系統在 fail 偽造 workerId=system:erp → 400，retries 不變")
        void forgedWorkerIdIsRejectedOnFail() throws Exception {
            givenErp();
            givenOther();
            startDemo();
            Acquired a = acquireOne("erp", ERP_KEY);
            long auditsBefore = externalApiAudits().size();

            var res = fail("other", OTHER_KEY, a.jobId(),
                    "{\"workerId\":\"system:erp\",\"errorCode\":\"X\"}");

            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(job(a.jobId()).getRetries()).isEqualTo(3);
            assertThat(job(a.jobId()).getLockOwner()).isEqualTo("system:erp");
            assertThat(externalApiAudits()).hasSize((int) auditsBefore);
        }

        @Test
        @DisplayName("他人的 job 與不存在的 job 共用同一個 404 回應（不可用狀態碼枚舉 jobId）")
        void foreignAndMissingJobsShareTheSame404() throws Exception {
            givenErp();
            givenOther();
            startDemo();
            Acquired a = acquireOne("erp", ERP_KEY);

            var foreign = complete("other", OTHER_KEY, a.jobId(), "{}");
            var missing = complete("other", OTHER_KEY, "no-such-job-id", "{}");

            assertThat(foreign.statusCode()).isEqualTo(404);
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(foreign.body()).contains("未由本系統鎖定");
            assertThat(missing.body()).contains("未由本系統鎖定");
        }

        @Test
        @DisplayName("別系統 unacquire 他人的 job → 404，鎖不動")
        void unacquireByOtherIs404() throws Exception {
            givenErp();
            givenOther();
            startDemo();
            Acquired a = acquireOne("erp", ERP_KEY);
            long auditsBefore = externalApiAudits().size();

            var res = unacquire("other", OTHER_KEY, a.jobId());

            assertThat(res.statusCode()).isEqualTo(404);
            assertThat(job(a.jobId()).getLockOwner()).isEqualTo("system:erp");
            assertThat(externalApiAudits()).hasSize((int) auditsBefore);
        }
    }

    // ── e) BPMN 範例 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("e) BPMN 範例")
    class SampleBpmn {

        @Test
        @DisplayName("範例 lint 零錯誤零警告，且部署後被解析成 ExternalWorkerServiceTask（type/topic 語法正確）")
        void sampleLintsCleanAndParsesAsExternalWorkerServiceTask() throws Exception {
            String xml = new ClassPathResource("bpmn/external-worker-demo.bpmn20.xml")
                    .getContentAsString(StandardCharsets.UTF_8);

            var lint = lintService.lint(xml);
            assertThat(lint.errors())
                    .as("範例必須 lint 零錯誤零警告：errors=" + lint.errors())
                    .isEmpty();
            assertThat(lint.valid()).isTrue();

            var definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(PROCESS_KEY).latestVersion().singleResult();
            assertThat(definition).as("範例應已被 setUp 部署").isNotNull();

            var model = repositoryService.getBpmnModel(definition.getId());
            var element = model.getMainProcess().getFlowElement("externalStep");
            assertThat(element)
                    .as("flowable:type=\"external-worker\" 必須解析成 ExternalWorkerServiceTask，"
                            + "而不是一般的 ServiceTask（後者部署成功但永遠不會產生 job）")
                    .isInstanceOf(org.flowable.bpmn.model.ExternalWorkerServiceTask.class);
            assertThat(((org.flowable.bpmn.model.ExternalWorkerServiceTask) element).getTopic())
                    .as("flowable:topic 必須是 acquire 比對的 topic")
                    .isEqualTo(TOPIC);
        }
    }
}
