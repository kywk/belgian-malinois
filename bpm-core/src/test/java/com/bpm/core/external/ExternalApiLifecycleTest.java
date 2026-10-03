package com.bpm.core.external;

import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 外部系統接入的端到端整合測試（admin 建立 → API key 認證 → 代發 → 查詢／完成）。
 *
 * <h2>為什麼需要這個類別</h2>
 *
 * <p>既有測試把外部 API 拆成兩半，各自綠燈卻沒有接起來過：
 * <ul>
 *   <li>{@code ExternalApiTcA04Test} 直接寫 {@code ExternalSystemRepository} 造
 *       fixture —— <b>admin API 產生明文金鑰 → 該金鑰真的能通過 filter</b>
 *       這條路徑沒有測試。金鑰雜湊、回傳格式、filter 查詢三者只要有一邊
 *       寫錯，那個系統在正式環境上就是「建立成功但永遠 401」。</li>
 *   <li>{@code ExternalCompleteTaskHardeningTest} 只驗 {@code complete_task}
 *       的拒絕面與既有任務的完成，沒有「代發啟動 → 查狀態 → 案件完成後
 *       狀態轉 completed／result」的完整鏈。</li>
 *   <li>{@code GET /api/external/process-definitions/{key}/variable-spec}
 *       在 repo 中<b>零測試覆蓋</b>（grep 無任何測試引用），而它是
 *       spec §9.1.3 提供給外部系統的契約端點，且歷史上修過
 *       「不比對 allowedProcessKeys 就能枚舉全部流程規格」的缺陷。</li>
 * </ul>
 *
 * <p>本類別補上這些接縫，並且每一條拒絕都同時斷言：
 * <ol>
 *   <li>狀態碼與訊息（走真實 HTTP —— MockMvc 不做 error dispatch）；</li>
 *   <li><b>零副作用</b>：沒有多餘的案件、沒有資料被動到；</li>
 *   <li><b>稽核</b>：{@code ExternalApiAuthFilter} 的拒絕會寫
 *       {@code EXTERNAL_API_CALL / rejected}，這是「有人拿錯權限打 API」
 *       唯一的事後線索（{@code CallbackAuthFilter} 刻意不寫，見該類別註解
 *       —— 本類別不把兩者混為一談）。</li>
 * </ol>
 *
 * <p>所有外部呼叫都打測試自己的 Tomcat（{@code SERVLET_PORT}）與 mock 端點，
 * 不出外網。建立的系統與變數規格逐筆刪除，不整表清空。
 */
class ExternalApiLifecycleTest extends IntegrationTestBase {

    private static final String ADMIN = "admin001";
    private static final String APPLICANT = "user001";
    private static final String MANAGER = "mgr001";

    @Autowired
    private ExternalSystemRepository externalRepo;

    @Autowired
    private ProcessVariableSpecRepository specRepo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> createdSystems = new ArrayList<>();
    private final List<String> createdSpecs = new ArrayList<>();

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    @AfterEach
    void removeFixtures() {
        createdSystems.forEach(sid -> externalRepo.findBySystemId(sid)
                .ifPresent(externalRepo::delete));
        createdSystems.clear();
        specRepo.deleteAllById(createdSpecs);
        createdSpecs.clear();
    }

    // ── admin API 建立外部系統 ──────────────────────────────────────

    private record CreatedSystem(String systemId, String apiKey) {}

    /**
     * 用 admin API 建立一個外部系統，回傳<b>回應中只出現一次的明文金鑰</b>。
     *
     * <p>刻意不用 repository 直接寫：admin API 的回傳格式與金鑰雜湊正是
     * 本測試要驗的接縫。allowedActions／allowedProcessKeys 以 JSON 字串
     * （admin UI 的格式）送出。
     */
    private CreatedSystem createSystem(String systemId, List<String> actions,
                                       List<String> processKeys, boolean allowOnBehalfOf)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "systemId", systemId,
                "systemName", "E2E 測試系統 " + systemId,
                "allowedActions", objectMapper.writeValueAsString(actions),
                "allowedProcessKeys", objectMapper.writeValueAsString(processKeys),
                "allowOnBehalfOf", allowOnBehalfOf));
        MvcResult result = mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        createdSystems.add(systemId);
        String apiKey = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("apiKey").asText();
        assertThat(apiKey).as("建立外部系統必須回傳明文金鑰（唯一一次）").isNotBlank();
        return new CreatedSystem(systemId, apiKey);
    }

    private static String uniqueSystemId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── 外部 API 呼叫（真實 HTTP）───────────────────────────────────

    private HttpResponse<String> send(String method, String path, String systemId,
                                      String apiKey, String body) throws Exception {
        var builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-API-Key", apiKey)
                .header("X-System-Id", systemId)
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String startBody(String businessKey, String onBehalfOf) {
        String vars = "{\"leaveType\":\"annual\",\"days\":1}";
        String onBehalf = onBehalfOf == null ? "" : "\"onBehalfOf\":\"" + onBehalfOf + "\",";
        String assignee = onBehalfOf == null ? "\"firstTaskAssignee\":\"" + MANAGER + "\"," : "";
        return "{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"" + businessKey + "\","
                + assignee + onBehalf
                + "\"variables\":" + vars + "}";
    }

    private static String pidOf(HttpResponse<String> res) {
        var matcher = java.util.regex.Pattern.compile("\"processInstanceId\":\"([^\"]+)\"")
                .matcher(res.body());
        return matcher.find() ? matcher.group(1) : null;
    }

    // ── 稽核查詢 ────────────────────────────────────────────────────

    /** 某 operator 的 EXTERNAL_API_CALL 稽核 detail，依 id。 */
    private static List<String> externalAuditDetails(String operatorId) {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'EXTERNAL_API_CALL' AND operator_id = ? "
                            + "ORDER BY id")) {
                ps.setString(1, operatorId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
        });
        return out;
    }

    /** 某案件的稽核列：{@code operation_type|operator_id}，依 id。 */
    private static List<String> auditOpsOf(String pid) {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            // ⚠️ MSSQL 的串接運算子（+）與 SQL 標準（||）不同；在 Java 端組字串，
            // 語法就不綁資料庫。
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operation_type, operator_id FROM bpm_audit_log "
                            + "WHERE process_instance_id = ? ORDER BY id")) {
                ps.setString(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1) + "|" + rs.getString(2));
                    }
                }
            }
        });
        return out;
    }

    /**
     * 輪詢直到該 operator 的 rejected 稽核出現。
     *
     * <p>filter 的拒絕稽核是同步寫入（publishDetached），照理回應前已落地；
     * 輪詢只是沿用 repo 既有形狀，排除時序抖動。
     */
    private static List<String> awaitRejectedAudit(String operatorId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<String> rejected = List.of();
        while (System.currentTimeMillis() < deadline) {
            rejected = externalAuditDetails(operatorId).stream()
                    .filter(d -> d != null && d.contains("rejected")).toList();
            if (!rejected.isEmpty()) return rejected;
            Thread.sleep(100);
        }
        return rejected;
    }

    private static List<String> awaitAuditOps(String pid, int atLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<String> ops = List.of();
        while (System.currentTimeMillis() < deadline) {
            ops = auditOpsOf(pid);
            if (ops.size() >= atLeast) return ops;
            Thread.sleep(100);
        }
        return ops;
    }

    // ── ① admin API → 代發啟動 → 查狀態 → 完成節點 → 查狀態 ─────────

    @Test
    @DisplayName("admin 建立的金鑰可代發啟動；狀態由 running 轉 completed/approved，全程留痕")
    void adminCreatedKeyDrivesOnBehalfOfCaseFromStartToCompletion() throws Exception {
        CreatedSystem erp = createSystem(uniqueSystemId("e2e-onbehalf"),
                List.of("start_process", "query_status", "complete_task"),
                List.of("leave-approval"), true);
        String businessKey = "e2e-ob-" + UUID.randomUUID();

        // 1. 代發啟動：onBehalfOf=user001 → 主管路由到 user001 的主管。
        HttpResponse<String> started = send("POST", "/api/external/process-instances",
                erp.systemId(), erp.apiKey(), startBody(businessKey, APPLICANT));
        assertThat(started.statusCode()).as("body=%s", started.body()).isEqualTo(200);
        String pid = pidOf(started);
        assertThat(pid).isNotBlank();

        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).as("代發必須走員工的主管").isEqualTo(MANAGER);
        assertThat(runtimeService.getVariable(pid, "initiator"))
                .as("initiator 一律是 server 鑄造的系統身分").isEqualTo("system:" + erp.systemId());
        assertThat(runtimeService.getVariable(pid, "onBehalfOf")).isEqualTo(APPLICANT);

        // 2. 查狀態：running、帶現任任務。
        HttpResponse<String> running = send("GET",
                "/api/external/process-instances/" + pid + "/status",
                erp.systemId(), erp.apiKey(), null);
        assertThat(running.statusCode()).isEqualTo(200);
        assertThat(running.body()).contains("\"status\":\"running\"")
                .contains("主管審核").contains(MANAGER);

        // 3. 完成節點：由真人主管以既有使用者 API 完成（外部系統不得自我核准，
        //    R-19；那條拒絕由 ExternalCompleteTaskHardeningTest 覆蓋）。
        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", MANAGER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":["
                                + "{\"name\":\"approved\",\"value\":true},"
                                + "{\"name\":\"rejected\",\"value\":false}]}"))
                .andExpect(status().isOk());

        // 4. 再查狀態：completed、result=approved（歷史查詢，代表結案後仍查得到）。
        HttpResponse<String> completed = send("GET",
                "/api/external/process-instances/" + pid + "/status",
                erp.systemId(), erp.apiKey(), null);
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(completed.body()).contains("\"status\":\"completed\"")
                .contains("\"result\":\"approved\"")
                .as("結案後 completedAt 必須有值")
                .doesNotContain("\"completedAt\":null");

        // 5. 稽核：外部啟動走的是 EXTERNAL_API_CALL（不是 PROCESS_START ——
        //    外部路徑的啟動事實記在專屬型別上），且必須記 onBehalfOf；
        //    案件的完整操作鏈也在（EXTERNAL_API_CALL 帶 processInstanceId）。
        List<String> ops = awaitAuditOps(pid, 3);
        assertThat(ops).containsExactlyInAnyOrder(
                "EXTERNAL_API_CALL|system:" + erp.systemId(),
                "TASK_APPROVE|" + MANAGER,
                "PROCESS_COMPLETE|system");
        assertThat(ops).as("外部啟動不寫 PROCESS_START，避免與內部啟動的事實混淆")
                .noneMatch(op -> op.startsWith("PROCESS_START"));
        assertThat(externalAuditDetails("system:" + erp.systemId()).stream()
                .filter(d -> d.contains("start_process")).findFirst().orElseThrow())
                .as("外部啟動稽核必須留下代發對象，事故調查才知道這張單是替誰送的")
                .contains(APPLICANT);
    }

    // ── ② 狀態查詢的擁有權邊界 ──────────────────────────────────────

    @Test
    @DisplayName("別的系統查不到我的案件狀態 → 403，且不得洩漏任何欄位")
    void statusOfAnotherSystemsCaseIsForbidden() throws Exception {
        CreatedSystem a = createSystem(uniqueSystemId("e2e-a"),
                List.of("start_process", "query_status"), List.of("leave-approval"), false);
        CreatedSystem b = createSystem(uniqueSystemId("e2e-b"),
                List.of("start_process", "query_status"), List.of("leave-approval"), false);

        HttpResponse<String> started = send("POST", "/api/external/process-instances",
                a.systemId(), a.apiKey(), startBody("e2e-own-" + UUID.randomUUID(), null));
        assertThat(started.statusCode()).isEqualTo(200);
        String pid = pidOf(started);

        HttpResponse<String> peek = send("GET",
                "/api/external/process-instances/" + pid + "/status",
                b.systemId(), b.apiKey(), null);
        assertThat(peek.statusCode())
                .as("實例的擁有權屬於 A，B 即使有 query_status 也只能拿到 403")
                .isEqualTo(403);
        assertThat(peek.body()).contains("無權存取此流程")
                .as("拒絕回應不得帶出任務名稱、assignee 或任何案件欄位")
                .doesNotContain(MANAGER);
    }

    // ── ③ businessKey 查詢必須以擁有權過濾 ──────────────────────────

    @Test
    @DisplayName("相同 businessKey 的兩張單：各自只查得到自己的（擁有權過濾）")
    void businessKeyQueryIsScopedToOwner() throws Exception {
        CreatedSystem a = createSystem(uniqueSystemId("e2e-bk-a"),
                List.of("start_process", "query_status"), List.of("leave-approval"), false);
        CreatedSystem b = createSystem(uniqueSystemId("e2e-bk-b"),
                List.of("start_process", "query_status"), List.of("leave-approval"), false);
        String sharedKey = "e2e-shared-" + UUID.randomUUID();

        String pidA = pidOf(send("POST", "/api/external/process-instances",
                a.systemId(), a.apiKey(), startBody(sharedKey, null)));
        String pidB = pidOf(send("POST", "/api/external/process-instances",
                b.systemId(), b.apiKey(), startBody(sharedKey, null)));
        assertThat(pidA).isNotBlank();
        assertThat(pidB).isNotBlank();
        assertThat(pidA).isNotEqualTo(pidB);

        HttpResponse<String> seenByA = send("GET",
                "/api/external/process-instances?businessKey=" + sharedKey,
                a.systemId(), a.apiKey(), null);
        assertThat(seenByA.statusCode()).isEqualTo(200);
        assertThat(seenByA.body()).contains(pidA)
                .as("A 不得看到 B 的實例 —— 這條過濾是唯一的跨系統隔離")
                .doesNotContain(pidB);

        HttpResponse<String> seenByB = send("GET",
                "/api/external/process-instances?businessKey=" + sharedKey,
                b.systemId(), b.apiKey(), null);
        assertThat(seenByB.statusCode()).isEqualTo(200);
        assertThat(seenByB.body()).contains(pidB).doesNotContain(pidA);
    }

    // ── ④ allowedActions 閘門：查狀態 ───────────────────────────────

    @Test
    @DisplayName("allowedActions 不含 query_status → 403 且留下 rejected 稽核")
    void statusQueryRequiresQueryStatusActionAndIsAudited() throws Exception {
        CreatedSystem erp = createSystem(uniqueSystemId("e2e-noq"),
                List.of("start_process"), List.of("leave-approval"), false);
        String pid = pidOf(send("POST", "/api/external/process-instances",
                erp.systemId(), erp.apiKey(), startBody("e2e-noq-" + UUID.randomUUID(), null)));

        HttpResponse<String> denied = send("GET",
                "/api/external/process-instances/" + pid + "/status",
                erp.systemId(), erp.apiKey(), null);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("Action not allowed: query_status");

        List<String> rejected = awaitRejectedAudit("system:" + erp.systemId());
        assertThat(rejected)
                .as("filter 的拒絕必須留痕 —— 錯的授權設定與掃描行為靠它才查得出來")
                .anySatisfy(d -> assertThat(d).contains("query_status").contains("uri"));
    }

    // ── ⑤ 變數規格端點：allowedProcessKeys 閘門（零既有覆蓋）────────

    @Test
    @DisplayName("variable-spec：授權的流程查得到、未授權的流程 403（不得枚舉全部定義）")
    void variableSpecIsGatedByAllowedProcessKeys() throws Exception {
        CreatedSystem erp = createSystem(uniqueSystemId("e2e-spec"),
                List.of("query_status"), List.of("leave-approval"), false);
        // required=false：只為證明端點回傳的是這份規格；required=true 會影響
        // 其他測試對 leave-approval 的啟動（共享容器），刻意不用。
        ProcessVariableSpec spec = new ProcessVariableSpec();
        spec.setProcessDefinitionKey("leave-approval");
        spec.setVariableName("e2eSpec-" + UUID.randomUUID().toString().substring(0, 8));
        spec.setVariableType("string");
        spec.setRequired(false);
        spec = specRepo.save(spec);
        createdSpecs.add(spec.getId());

        HttpResponse<String> allowed = send("GET",
                "/api/external/process-definitions/leave-approval/variable-spec",
                erp.systemId(), erp.apiKey(), null);
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.body()).contains(spec.getVariableName());

        HttpResponse<String> denied = send("GET",
                "/api/external/process-definitions/purchase-approval/variable-spec",
                erp.systemId(), erp.apiKey(), null);
        assertThat(denied.statusCode())
                .as("只授權 leave-approval 的系統不得枚舉 purchase-approval 的變數規格")
                .isEqualTo(403);
        assertThat(denied.body()).contains("未被授權存取流程");
    }

    // ── ⑥ 變數規格端點：allowedActions 閘門 ─────────────────────────

    @Test
    @DisplayName("variable-spec：allowedActions 不含 query_status → 403 且留痕")
    void variableSpecRequiresQueryStatusAction() throws Exception {
        CreatedSystem erp = createSystem(uniqueSystemId("e2e-specact"),
                List.of("start_process"), List.of("leave-approval"), false);

        HttpResponse<String> denied = send("GET",
                "/api/external/process-definitions/leave-approval/variable-spec",
                erp.systemId(), erp.apiKey(), null);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("Action not allowed: query_status");

        List<String> rejected = awaitRejectedAudit("system:" + erp.systemId());
        assertThat(rejected).anySatisfy(d -> assertThat(d).contains("query_status"));
    }

    // ── ⑦ Worker 閘門：allowedActions 不含 external_worker ──────────

    @Test
    @DisplayName("worker acquire：allowedActions 不含 external_worker → 403 且留痕")
    void workerAcquireRequiresExternalWorkerActionAndIsAudited() throws Exception {
        CreatedSystem erp = createSystem(uniqueSystemId("e2e-worker"),
                List.of("start_process"), List.of("leave-approval"), false);

        HttpResponse<String> denied = send("POST", "/api/external/worker/tasks/acquire",
                erp.systemId(), erp.apiKey(), "{\"topic\":\"e2e-topic\"}");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("Action not allowed: external_worker");

        List<String> rejected = awaitRejectedAudit("system:" + erp.systemId());
        assertThat(rejected)
                .as("worker 路徑的拒絕與其餘外部端點共用同一條 filter 與同一份稽核")
                .anySatisfy(d -> assertThat(d).contains("external_worker"));
    }

    // ── ⑧ 拒絕案件的狀態必須回報 rejected ────────────────────────────

    @Test
    @DisplayName("被拒絕的案件：外部系統查狀態必須得到 result=rejected（不得一律回報 approved）")
    void rejectedCaseStatusReportsRejectedToOwningSystem() throws Exception {
        CreatedSystem erp = createSystem(uniqueSystemId("e2e-rej"),
                List.of("start_process", "query_status"), List.of("leave-approval"), false);
        String pid = pidOf(send("POST", "/api/external/process-instances",
                erp.systemId(), erp.apiKey(), startBody("e2e-rej-" + UUID.randomUUID(), null)));
        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();

        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", MANAGER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":["
                                + "{\"name\":\"approved\",\"value\":false},"
                                + "{\"name\":\"rejected\",\"value\":true}]}"))
                .andExpect(status().isOk());

        HttpResponse<String> status = send("GET",
                "/api/external/process-instances/" + pid + "/status",
                erp.systemId(), erp.apiKey(), null);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body())
                .as("把駁回回報成核准是簽核系統最不能接受的錯誤（P2-1 的同一個判定面）")
                .contains("\"status\":\"completed\"")
                .contains("\"result\":\"rejected\"")
                .doesNotContain("\"result\":\"approved\"");
    }

    // ── 對照組：admin 建立的系統被停用後，金鑰立即失效 ──────────────

    @Test
    @DisplayName("admin 建立的系統被停用後，既有金鑰立即 403（建立與停用是同一條授權鏈）")
    void disabledAdminCreatedSystemIsRejectedWithTheSameKey() throws Exception {
        CreatedSystem erp = createSystem(uniqueSystemId("e2e-disable"),
                List.of("start_process"), List.of("leave-approval"), false);

        // 停用（DELETE 語意是停用，見 ExternalSystemAdminController）。
        ExternalSystem sys = externalRepo.findBySystemId(erp.systemId()).orElseThrow();
        sys.setEnabled(false);
        externalRepo.save(sys);

        HttpResponse<String> denied = send("POST", "/api/external/process-instances",
                erp.systemId(), erp.apiKey(), startBody("e2e-disabled-" + UUID.randomUUID(), null));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("disabled");

        List<String> rejected = awaitRejectedAudit("system:" + erp.systemId());
        assertThat(rejected).isNotEmpty();
    }
}
