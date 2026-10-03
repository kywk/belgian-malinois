package com.bpm.core.controller;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.NotifyTestSink;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工項 #5：代理人機制（新任務自動代換 ＋ 既有任務手動轉派）。
 *
 * <h2>兩個缺口、兩條路徑</h2>
 *
 * <ol>
 *   <li><b>新任務</b>：第一關受理人改走 {@code resolveEffective}。內部發起
 *       （主管）與外部 {@code firstTaskAssignee} 都要代換；沒有代理人的
 *       不受影響；只給候選群組的維持不指派。</li>
 *   <li><b>既有任務</b>：{@code POST /api/admin/tasks/forward-substitutes}
 *       掃描執行中任務，把受理人有代理人的轉派給代理人。</li>
 * </ol>
 *
 * <h2>⚠️ 為什麼用 Redis 直接寫 {@code org:substitute:*} 造代理人</h2>
 *
 * <p>{@code MockOrgController.getSubstitute} 對所有人都回 {@code {}} ——
 * fixture 裡沒有人有代理人。要造出「有代理人」的情境，只能寫那個事實的
 * 唯一來源：{@code OrgService.cachedSubstitute} 讀的 Redis key。
 * 這與 {@code CacheConsistencyTest.substituteDerivedAnswersAgree} 用的是
 * 同一個手法，而且測到的正是正式路徑（快取命中 → resolveEffective 代換）。
 * {@code @BeforeEach／@AfterEach} 都清 key，避免污染同 JVM 的其他測試。
 *
 * <h2>⚠️ 計數為什麼不是寫死 1</h2>
 *
 * <p>所有整合測試共用同一個資料庫，其他測試可能留下<b>執行中任務</b>。
 * 因此 {@code scanned} 以「呼叫前的執行中任務總數」為基準，
 * {@code forwarded} 以「呼叫前指派給該代理人的任務數」為基準 ——
 * 兩者都是實際查詢得到的精確值，而不是假設資料庫只有本測試的資料。
 *
 * <h2>決策（與 {@code SubstituteForwardController} 的註解同一份）</h2>
 *
 * <ul>
 *   <li><b>加簽、reassign、delegate 不代換</b>：它們是明確的人為選擇，
 *       不是平台自動路由。本檔以三條回歸測試釘住這個範圍。</li>
 *   <li><b>轉派後通知新受理人</b>（{@code task_assigned}）：不通知的話
 *       代理人不知道任務從別人那裡移過來了。</li>
 *   <li><b>稽核一筆總結</b>：批次轉派逐任務寫稽核會把軌跡淹沒。</li>
 * </ul>
 *
 * <h2>負向控制組（實測見交付報告）</h2>
 *
 * <p>移除 {@code InitialAssigneeResolver} 與 {@code ExternalApiController}
 * 的代換 → 新任務的自動代換測試變紅；移除端點的
 * {@code substitute.equals(assignee)} 過濾 → 手動轉派的計數與通知變紅。
 * 其餘在缺陷期間本來就會綠 —— 它們證明的是「不該變的沒變」。
 */
class SubstituteForwardTest extends IntegrationTestBase {

    private static final String LEAVE = "leave-approval";

    /** 外部測試系統（唯一 id，測試自己清理，不動別人的 fixture）。 */
    private static final String SYSTEM_ID = "t5-substitute-test";
    private static final String API_KEY = "sk-t5-substitute-testkey";

    /** fixture 裡有代理人設定的兩個角色：一般員工 user005 與主管 mgr001。 */
    private static final String AWAY_EMPLOYEE = "user005";
    private static final String AWAY_EMPLOYEE_SUBSTITUTE = "user004";
    private static final String AWAY_MANAGER = "mgr001";
    private static final String AWAY_MANAGER_SUBSTITUTE = "user002";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ExternalSystemRepository externalSystems;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> standaloneTaskIds = new ArrayList<>();
    private final List<String> processInstanceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        NotifyTestSink.install(rabbitTemplate, amqpAdmin);
        NotifyTestSink.reset();
        truncateAuditLog();
        clearSubstituteFacts();
        externalSystems.findBySystemId(SYSTEM_ID).ifPresent(externalSystems::delete);
    }

    @AfterEach
    void tearDown() {
        clearSubstituteFacts();
        externalSystems.findBySystemId(SYSTEM_ID).ifPresent(externalSystems::delete);
        for (String taskId : standaloneTaskIds) {
            try {
                taskService.deleteTask(taskId, true);
            } catch (Exception ignored) {
                // 測試已刪或已不存在，不影響隔離。
            }
        }
        for (String pid : processInstanceIds) {
            try {
                runtimeService.deleteProcessInstance(pid, "T5 test cleanup");
            } catch (Exception ignored) {
                // 同上。
            }
        }
    }

    // ── fixture／工具 ───────────────────────────────────────────────

    /** 寫入代理人事實的唯一來源（1 分鐘 TTL；測試自己清理）。 */
    private void seedSubstitute(String user, String substitute) {
        redis.opsForValue().set("org:substitute:" + user, substitute, Duration.ofMinutes(10));
    }

    private void clearSubstituteFacts() {
        List<String> keys = new ArrayList<>();
        for (String user : new String[]{"user001", "user002", "user003", "user004", "user005",
                "mgr001", "mgr002", "dir001", "admin001"}) {
            keys.add("org:substitute:" + user);
        }
        redis.delete(keys);
    }

    /** 走正式的內部啟動端點（不是直接 runtimeService），身分＝發起人。 */
    private String startLeaveAs(String user) throws Exception {
        String body = mockMvc.perform(post("/api/process-instances")
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"" + LEAVE + "\","
                                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String pid = objectMapper.readTree(body).get("processInstanceId").asText();
        processInstanceIds.add(pid);
        return pid;
    }

    private Task firstTaskOf(String pid) {
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task).as("流程 " + pid + " 必須停在第一關").isNotNull();
        return task;
    }

    /** 建立一筆 standalone 執行中任務（與加簽相同的建立方式），指定受理人。 */
    private String givenStandaloneTask(String name, String assignee) {
        Task task = taskService.newTask();
        task.setName(name);
        if (assignee != null) task.setAssignee(assignee);
        taskService.saveTask(task);
        standaloneTaskIds.add(task.getId());
        return task.getId();
    }

    private ExternalSystem givenExternalSystem() {
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId(SYSTEM_ID);
        sys.setSystemName("T5 代理人測試系統");
        sys.setApiKey(ApiKeyUtil.hash(API_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys("[\"leave-approval\"]");
        sys.setAllowOnBehalfOf(false);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return externalSystems.save(sys);
    }

    private HttpResponse<String> postExternal(String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/external/process-instances"))
                .header("X-API-Key", API_KEY)
                .header("X-System-Id", SYSTEM_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode callForwardAsAdmin() throws Exception {
        String body = mockMvc.perform(post("/api/admin/tasks/forward-substitutes")
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    /** 呼叫者的待辦清單（taskId 集合）。 */
    private List<String> inboxTaskIds(String user) throws Exception {
        String body = mockMvc.perform(get("/api/tasks")
                        .param("assignee", user)
                        .header("X-User-Id", user))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> ids = new ArrayList<>();
        for (JsonNode t : objectMapper.readTree(body)) {
            ids.add(t.path("taskId").asText());
        }
        return ids;
    }

    private String assigneeOf(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        assertThat(task).as("任務 " + taskId + " 必須仍存在").isNotNull();
        return task.getAssignee();
    }

    private record AuditRow(String operatorId, String detail) {
    }

    private static List<AuditRow> auditRows(String type) {
        List<AuditRow> rows = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operator_id, detail FROM bpm_audit_log WHERE operation_type = ?")) {
                ps.setString(1, type);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new AuditRow(rs.getString(1), rs.getString(2)));
                    }
                }
            }
        });
        return rows;
    }

    /** 稽核寫入掛在交易的 beforeCommit，容許短暫延遲（沿用既有測試的做法）。 */
    private static List<AuditRow> awaitAuditRows(String type, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        List<AuditRow> rows = List.of();
        while (System.currentTimeMillis() < deadline) {
            rows = auditRows(type);
            if (rows.size() >= expected) return rows;
            Thread.sleep(100);
        }
        return rows;
    }

    // ── 1. 新任務自動代換 ───────────────────────────────────────────

    @Test
    @DisplayName("#5 新任務（內部發起）：主管有代理人 → 第一關 assignee 是代理人")
    void internalFirstTaskGoesToManagerSubstitute() throws Exception {
        seedSubstitute(AWAY_MANAGER, AWAY_MANAGER_SUBSTITUTE);

        String pid = startLeaveAs("user001");
        Task first = firstTaskOf(pid);

        assertThat(first.getAssignee())
                .as("主管 mgr001 已設代理人；任務必須改派，不能停在休假期間的收件匣")
                .isEqualTo(AWAY_MANAGER_SUBSTITUTE);
    }

    @Test
    @DisplayName("#5 新任務（內部發起）：沒有代理人 → 受理人不變（負向對照）")
    void internalFirstTaskUnchangedWithoutSubstitute() throws Exception {
        String pid = startLeaveAs("user004"); // 主管 mgr002，未設代理人
        Task first = firstTaskOf(pid);

        assertThat(first.getAssignee())
                .as("沒有代理人時不得被代換 —— 否則代換規則會變成亂派")
                .isEqualTo("mgr002");
    }

    @Test
    @DisplayName("#5 新任務（外部 firstTaskAssignee）：有代理人 → 第一關 assignee 是代理人")
    void externalFirstTaskAssigneeGoesToSubstitute() throws Exception {
        seedSubstitute(AWAY_MANAGER, AWAY_MANAGER_SUBSTITUTE);
        givenExternalSystem();

        HttpResponse<String> res = postExternal("{\"processDefinitionKey\":\"" + LEAVE + "\","
                + "\"businessKey\":\"T5-" + UUID.randomUUID() + "\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1},"
                + "\"firstTaskAssignee\":\"" + AWAY_MANAGER + "\"}");

        assertThat(res.statusCode())
                .as("外部系統指名的是組織系統認識的人，守衛必須放行")
                .isEqualTo(200);
        String pid = res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
        processInstanceIds.add(pid);

        assertThat(firstTaskOf(pid).getAssignee())
                .as("BPMN 啟動當下已代換，而 ExternalApiController 啟動後的 "
                        + "setAssignee 不得把代理人蓋回休假者本人")
                .isEqualTo(AWAY_MANAGER_SUBSTITUTE);
    }

    @Test
    @DisplayName("#5 新任務（外部）：沒有代理人 → 維持原受理人（負向對照）")
    void externalFirstTaskAssigneeUnchangedWithoutSubstitute() throws Exception {
        givenExternalSystem();

        HttpResponse<String> res = postExternal("{\"processDefinitionKey\":\"" + LEAVE + "\","
                + "\"businessKey\":\"T5-" + UUID.randomUUID() + "\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1},"
                + "\"firstTaskAssignee\":\"mgr002\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        String pid = res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
        processInstanceIds.add(pid);

        assertThat(firstTaskOf(pid).getAssignee()).isEqualTo("mgr002");
    }

    // ── 2. 既有任務手動轉派 ─────────────────────────────────────────

    @Test
    @DisplayName("#5 手動轉派：只轉有代理人者、計數正確、稽核一筆、通知、舊待辦清空")
    void forwardSubstitutesOnlyForwardsTasksWithSubstitute() throws Exception {
        seedSubstitute(AWAY_EMPLOYEE, AWAY_EMPLOYEE_SUBSTITUTE);

        String withSubstitute = givenStandaloneTask("待轉派任務", AWAY_EMPLOYEE);
        String withoutSubstitute = givenStandaloneTask("不需轉派任務", "mgr002");
        String noAssignee = givenStandaloneTask("候選任務", null);
        String systemIdentity = givenStandaloneTask("系統身分任務", "system:legacy");

        // 以「呼叫前實際查得到的數量」為基準，不假設 DB 只有本測試的資料。
        long activeBefore = taskService.createTaskQuery().count();
        long expectedForwarded = taskService.createTaskQuery()
                .taskAssignee(AWAY_EMPLOYEE).count();
        assertThat(expectedForwarded)
                .as("前置條件：至少要有本測試建立的那一筆")
                .isGreaterThanOrEqualTo(1);

        NotifyTestSink.reset();
        JsonNode result = callForwardAsAdmin();

        assertThat(result.get("forwarded").asInt()).isEqualTo((int) expectedForwarded);
        assertThat(result.get("scanned").asInt()).isEqualTo((int) activeBefore);
        assertThat(result.get("skipped").asInt())
                .as("skipped = scanned - forwarded，三者的關係必須自洽")
                .isEqualTo((int) activeBefore - (int) expectedForwarded);

        // 只有有代理人的那一筆被換手，其餘原封不動。
        assertThat(assigneeOf(withSubstitute)).isEqualTo(AWAY_EMPLOYEE_SUBSTITUTE);
        assertThat(assigneeOf(withoutSubstitute)).isEqualTo("mgr002");
        assertThat(assigneeOf(noAssignee)).isNull();
        assertThat(assigneeOf(systemIdentity))
                .as("系統身分不是人，不可能有代理人；必須跳過且不得打組織系統（會 404 讓整批失敗）")
                .isEqualTo("system:legacy");

        // 稽核：恰一筆總結，operator＝呼叫者。
        List<AuditRow> audits = awaitAuditRows("TASK_SUBSTITUTE_FORWARD", 1);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).operatorId()).isEqualTo("admin001");
        assertThat(audits.get(0).detail())
                .contains("\"forwarded\":" + expectedForwarded)
                .contains("\"scanned\":" + activeBefore);

        // 通知：每個被轉派的任務一則 task_assigned，收件人是新受理人。
        List<Map<String, Object>> messages = NotifyTestSink.drain();
        List<Map<String, Object>> assigned = NotifyTestSink.events(messages, "task_assigned");
        assertThat(assigned).hasSize((int) expectedForwarded);
        Map<String, Object> forForwarded = assigned.stream()
                .filter(m -> withSubstitute.equals(m.get("taskId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "被轉派的任務必須通知新受理人，實際訊息: " + messages));
        assertThat(forForwarded).containsEntry("assignee", AWAY_EMPLOYEE_SUBSTITUTE);

        // 待辦：舊受理人看不到那一筆，新受理人看得到。
        assertThat(inboxTaskIds(AWAY_EMPLOYEE)).doesNotContain(withSubstitute);
        assertThat(inboxTaskIds(AWAY_EMPLOYEE_SUBSTITUTE)).contains(withSubstitute);
    }

    @Test
    @DisplayName("#5 手動轉派：第二次呼叫 no-op（forwarded=0）")
    void secondCallIsNoOp() throws Exception {
        seedSubstitute(AWAY_EMPLOYEE, AWAY_EMPLOYEE_SUBSTITUTE);
        String taskId = givenStandaloneTask("冪等任務", AWAY_EMPLOYEE);

        callForwardAsAdmin();
        assertThat(assigneeOf(taskId)).isEqualTo(AWAY_EMPLOYEE_SUBSTITUTE);

        long activeBefore = taskService.createTaskQuery().count();
        JsonNode second = callForwardAsAdmin();

        assertThat(second.get("forwarded").asInt())
                .as("已轉派的任務受理人已是代理人；代理人本人沒有代理人 → 不再轉")
                .isZero();
        assertThat(second.get("scanned").asInt()).isEqualTo((int) activeBefore);
        assertThat(assigneeOf(taskId)).isEqualTo(AWAY_EMPLOYEE_SUBSTITUTE);
    }

    // ── 3. 授權 ────────────────────────────────────────────────────

    @Test
    @DisplayName("#5 授權：非 ADMIN → 403，且不得改動任何任務")
    void nonAdminIsForbidden() throws Exception {
        seedSubstitute(AWAY_EMPLOYEE, AWAY_EMPLOYEE_SUBSTITUTE);
        String taskId = givenStandaloneTask("非管理員不得轉派", AWAY_EMPLOYEE);

        mockMvc.perform(post("/api/admin/tasks/forward-substitutes")
                        .header("X-User-Id", "mgr001"))
                .andExpect(status().isForbidden());

        assertThat(assigneeOf(taskId)).isEqualTo(AWAY_EMPLOYEE);
    }

    @Test
    @DisplayName("#5 授權：未登入 → 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/admin/tasks/forward-substitutes")
                        .header("X-Gateway-Secret", "")
                        .header("X-User-Id", ""))
                .andExpect(status().isUnauthorized());
    }

    // ── 4. 回歸：明確的人為選擇不代換 ───────────────────────────────

    @Test
    @DisplayName("#5 回歸：reassign 是明確選擇 → 不自動代換")
    void reassignIsNotSubstituted() throws Exception {
        seedSubstitute(AWAY_MANAGER, AWAY_MANAGER_SUBSTITUTE);
        String taskId = givenStandaloneTask("改派回歸", AWAY_MANAGER);

        mockMvc.perform(put("/api/tasks/{id}", taskId)
                        .header("X-User-Id", AWAY_MANAGER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"reassign\",\"assignee\":\"user003\"}"))
                .andExpect(status().isOk());

        assertThat(assigneeOf(taskId))
                .as("呼叫端明確指定 user003；代換成 user002 等於推翻按下按鈕的人的決定")
                .isEqualTo("user003");
    }

    @Test
    @DisplayName("#5 回歸：delegate 是明確選擇 → 不自動代換")
    void delegateIsNotSubstituted() throws Exception {
        seedSubstitute(AWAY_MANAGER, AWAY_MANAGER_SUBSTITUTE);
        String taskId = givenStandaloneTask("委派回歸", AWAY_MANAGER);

        mockMvc.perform(put("/api/tasks/{id}", taskId)
                        .header("X-User-Id", AWAY_MANAGER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"delegate\",\"delegateUser\":\"user003\"}"))
                .andExpect(status().isOk());

        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        assertThat(task.getAssignee())
                .as("delegatee 必須是被明確指定的人，不是原指派人的代理人")
                .isEqualTo("user003");
        assertThat(task.getOwner())
                .as("原指派人仍是 owner（resolve 才收得回來）")
                .isEqualTo(AWAY_MANAGER);
    }

    @Test
    @DisplayName("#5 回歸：加簽是明確選擇 → 不自動代換")
    void countersignIsNotSubstituted() throws Exception {
        seedSubstitute(AWAY_MANAGER, AWAY_MANAGER_SUBSTITUTE);
        String parentId = givenStandaloneTask("加簽回歸", AWAY_MANAGER);

        mockMvc.perform(post("/api/countersign/{taskId}", parentId)
                        .header("X-User-Id", AWAY_MANAGER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countersignUserId\":\"user003\",\"message\":\"請協助確認\"}"))
                .andExpect(status().isOk());

        List<Task> subtasks = taskService.getSubTasks(parentId);
        assertThat(subtasks).hasSize(1);
        subtasks.forEach(t -> standaloneTaskIds.add(t.getId()));
        assertThat(subtasks.get(0).getAssignee())
                .as("被加簽人由加簽者明確指定；代換成 user002 就是推翻他的決定")
                .isEqualTo("user003");
    }
}
