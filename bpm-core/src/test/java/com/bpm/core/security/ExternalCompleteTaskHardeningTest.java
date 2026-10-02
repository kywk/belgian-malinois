package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-19：{@code PUT /api/external/tasks/{taskId}} 的外部系統可自我核准
 * 與變數注入。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <pre>
 *   外部系統 erp 以 firstTaskAssignee=mgr001 啟動 leave-approval
 *   → 該實例的擁有者（_externalSystemId）是 erp
 *   → completeTask 只做 verifyRunningOwnership（實例是 erp 的 → 通過）
 *   → erp 直接 PUT 主管簽核的 taskId
 *   → 主管簽核被送出者自己完成 —— 人工審批等於不存在
 *
 *   同一個端點也接受任意 variables：
 *   → {"approved":true} 由外部系統自行填寫（覆寫審核結果）
 *   → {"_externalSystemId":"victim"} 把實例過戶
 *   → {"_formVersions":[]} 解除表單版本鎖
 *   → 也不比對該系統的 allowedProcessKeys
 * </pre>
 *
 * <h2>裁決後的語意（2026-10-02，照做未自行擴大）</h2>
 *
 * <ol>
 *   <li><b>任務層級授權</b>：只有 task 的 {@code assignee} 或
 *       {@code candidateUsers} <b>明確</b>包含 {@code system:<systemId>}
 *       才能完成；候選<b>群組</b>不納入。拒絕 → 403。</li>
 *   <li><b>變數</b>：任何 {@code _} 前綴變數名 → 400 並指名；
 *       其餘走與 startProcess 相同的 {@code validateVariables}。</li>
 *   <li><b>allowedProcessKeys</b>：任務所屬流程 key 不在授權清單 → 403。</li>
 * </ol>
 *
 * <p>狀態碼的分工：400 = 呼叫端該改 payload（保留變數名、必填規格），
 * 403 = 授權（任務不是指派給它、流程 key 沒被授權、實例不是它的）。
 *
 * <h2>⚠️ 每一條拒絕都配「零副作用」斷言</h2>
 *
 * <p>只斷言狀態碼的測試會被「先完成任務、再回錯誤」的實作完全騙過 ——
 * 而那正是本工項要修的缺陷本身。所以每一條拒絕都另外驗：任務仍在、
 * 目標變數沒被寫入、稽核筆數不變。
 *
 * <h2>⚠️ 每一組拒絕都配「同樣參數、只有持有者不同」的放行對照</h2>
 *
 * <p>一個「擋掉所有完成請求」的實作能讓每一條拒絕測試全綠 —— 而那個實作
 * 會讓外部系統完全無法完成任何任務。{@link Passes} 是這個方向的守門員。
 *
 * <h2>⚠️ 狀態碼斷言走真實 HTTP</h2>
 *
 * <p>{@code ResponseStatusException} 走 ERROR dispatch，MockMvc 不做那次
 * dispatch（見 {@code ErrorDispatchTest}）。本檔一律走真實 HTTP。
 *
 * <h2>負向控制組（2026-10-02，把 ExternalApiController 整份還原成 base 616ba8a）</h2>
 *
 * <p><b>18 條中 12 紅 6 綠。</b>紅的全部是缺陷本體，失敗原因一律是
 * {@code expected: 403/400 but was: 200} —— 也就是<b>任務真的被完成了</b>、
 * 變數真的被寫入、未授權流程真的放行，正是本工項要修的那三件事。
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅</td>
 *       <td>{@link SelfApproval} 4 條（humanAssignee／anotherSystem／
 *           candidateGroup／padded）<br>
 *           {@link Variables} 5 條（ownerVariable／formVersions／
 *           allReservedNames／missingRequired／blankRequired）<br>
 *           {@link ProcessKeyAuthorization} 3 條</td>
 *       <td>缺陷期間全部 {@code 200}：自我核准、變數注入、未授權流程
 *           全部放行，正是 R-19 的三個缺陷面。</td></tr>
 *   <tr><td>🟢 綠</td>
 *       <td>{@link Passes} 4 條<br>
 *           {@code SelfApproval.nonOwnerIsStillForbidden…}<br>
 *           {@code Variables.requiredPresentAndUndeclaredVariablePasses}</td>
 *       <td><b>不是漏抓</b>：4 條放行對照在缺陷期間就該綠，而且修好之後
 *           仍必須綠 —— 一個「擋掉全部完成請求」的實作能讓 12 條紅的通過，
 *           卻會讓這 4 條紅。擁有權那條證明既有拒絕沒有被新檢查蓋掉；
 *           undeclared 那條證明新驗證沒有把 startProcess 的既有語意
 *           （未宣告放行）換成另一套。</td></tr>
 * </table>
 *
 * <p>還原方式：{@code command cp} base 版 {@code ExternalApiController.java}
 * 蓋回工作區（整份還原，不是只註解掉某一段），跑完再蓋回修好的版本。
 */
class ExternalCompleteTaskHardeningTest extends IntegrationTestBase {

    private static final String PLAIN_KEY = "sk-r19-external-testkey";
    private static final String OTHER_KEY = "sk-r19-other-testkey";
    private static final String PROCESS_KEY = "leave-approval";

    @Autowired
    private ExternalSystemRepository externalRepo;

    @Autowired
    private ProcessVariableSpecRepository specRepo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private TaskService taskService;

    private final HttpClient http = HttpClient.newHttpClient();

    /** 本測試建立的規格 id，逐筆刪除而不是整表清空 —— 容器共用同一個資料庫。 */
    private final List<String> specIds = new ArrayList<>();

    @BeforeEach
    void seedExternalSystem() {
        externalRepo.deleteAll();
    }

    @AfterEach
    void removeFixtures() {
        // 必填規格若殘留，會讓所有以 leave-approval 發起的外部測試莫名回 400。
        specRepo.deleteAllById(specIds);
        specIds.clear();
        externalRepo.deleteAll();
    }

    // ── fixture ──────────────────────────────────────────────────────

    private ExternalSystem givenSystem(String systemId, String apiKey, String allowedActions,
                                       String allowedProcessKeys) {
        ExternalSystem sys = new ExternalSystem();
        // 刻意不設 id（@GeneratedValue(strategy = UUID)；自行指定會走 merge）。
        sys.setSystemId(systemId);
        sys.setSystemName("R-19 測試系統 " + systemId);
        sys.setApiKey(ApiKeyUtil.hash(apiKey));
        sys.setAllowedActions(allowedActions);
        sys.setAllowedProcessKeys(allowedProcessKeys);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return externalRepo.save(sys);
    }

    private ExternalSystem givenErp(String allowedProcessKeys) {
        return givenSystem("erp", PLAIN_KEY, "[\"start_process\",\"complete_task\"]",
                allowedProcessKeys);
    }

    private void givenRequiredSpec(String variableName, String variableType) {
        ProcessVariableSpec s = new ProcessVariableSpec();
        s.setProcessDefinitionKey(PROCESS_KEY);
        s.setVariableName(variableName);
        s.setVariableType(variableType);
        s.setRequired(true);
        specRepo.save(s);
        specIds.add(s.getId());
    }

    // ── 請求工具 ──────────────────────────────────────────────────────

    private static String startBody(String variablesJson) {
        return "{\"processDefinitionKey\":\"" + PROCESS_KEY + "\","
                + "\"businessKey\":\"R19-" + UUID.randomUUID() + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":" + variablesJson + "}";
    }

    private HttpResponse<String> startAs(String systemId, String apiKey, String payload)
            throws Exception {
        return send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT
                                + "/api/external/process-instances"))
                .header("X-API-Key", apiKey)
                .header("X-System-Id", systemId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build());
    }

    private HttpResponse<String> completeAs(String systemId, String apiKey, String taskId,
                                            String variablesJson) throws Exception {
        return send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT
                                + "/api/external/tasks/" + taskId))
                .header("X-API-Key", apiKey)
                .header("X-System-Id", systemId)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"variables\":" + variablesJson + "}"))
                .build());
    }

    private HttpResponse<String> complete(String taskId, String variablesJson) throws Exception {
        return completeAs("erp", PLAIN_KEY, taskId, variablesJson);
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── 狀態工具 ──────────────────────────────────────────────────────

    private record OwnedTask(String processInstanceId, String taskId) {}

    /** 以 erp 發起 leave-approval，回傳第一關（assignee=mgr001）的識別。 */
    private OwnedTask startOwnedTask() throws Exception {
        return startOwnedTask("{\"leaveType\":\"annual\",\"days\":1}");
    }

    private OwnedTask startOwnedTask(String variablesJson) throws Exception {
        var res = startAs("erp", PLAIN_KEY, startBody(variablesJson));
        assertThat(res.statusCode())
                .as("前置條件：erp 必須能發起 " + PROCESS_KEY)
                .isEqualTo(200);
        String pid = pidOf(res);
        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        return new OwnedTask(pid, task.getId());
    }

    /**
     * 直接建立一張 erp 擁有、且任務已指派給 system:erp 的 leave-approval
     * 實例（繞過 startProcess）。
     *
     * <p>用於 allowedProcessKeys 的測試：那些測試要的狀態是「授權在實例
     * 啟動後才收緊」，而 startProcess 現在會先回 403，無法用它建立。
     */
    private OwnedTask givenOwnedTaskAssignedToSystem() {
        String pid = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                "R19-" + UUID.randomUUID(),
                Map.of("_externalSystemId", "erp", "initiator", "system:erp",
                        "firstTaskAssignee", "mgr001",
                        "leaveType", "annual", "days", 1)).getId();
        String taskId = taskService.createTaskQuery().processInstanceId(pid).singleResult().getId();
        taskService.setAssignee(taskId, "system:erp");
        return new OwnedTask(pid, taskId);
    }

    private static String pidOf(HttpResponse<String> res) {
        return res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
    }

    private long audits() {
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
     * 斷言「被拒絕，而且什麼都沒發生」。
     *
     * <p>後兩個斷言才是重點：只驗狀態碼會被「先完成任務、再回錯誤」的實作
     * 騙過，而那個實作正是本工項要修的缺陷本身（簽核已經被送出者自己完成）。
     * 稽核也要不動 —— 被拒的請求不得宣稱一件沒發生的變更。
     */
    private void assertRejectedWithoutSideEffect(HttpResponse<String> res, int expectedStatus,
                                                 OwnedTask task, long auditsBefore) {
        assertThat(res.statusCode())
                .as("狀態碼必須是裁決指定的值（403 = 授權；400 = payload 形狀），"
                        + "不是 200、也不是 500")
                .isEqualTo(expectedStatus);
        assertThat(taskService.createTaskQuery().taskId(task.taskId()).singleResult())
                .as("被拒的請求不得完成任務")
                .isNotNull();
        assertThat(audits())
                .as("被拒的請求不得寫 complete_task 稽核")
                .isEqualTo(auditsBefore);
    }

    // ── 缺陷本體：自我核准與任務層級授權 ────────────────────────────

    @Nested
    @DisplayName("缺陷：系統完成自己案件上的人工簽核（自我核准）")
    class SelfApproval {

        @Test
        @DisplayName("R-19：任務 assignee 是真人 mgr001 → 403，且任務／變數／稽核都不動")
        void humanAssigneeCannotBeCompletedBySystem() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            long before = audits();

            var res = complete(t.taskId(), "{\"approved\":true}");

            assertRejectedWithoutSideEffect(res, 403, t, before);
            assertThat(res.body())
                    .as("訊息必須指名原因與正確做法（該任務的持有者是誰）")
                    .contains("未指派給本系統").contains("system:erp");
            assertThat(runtimeService.getVariable(t.processInstanceId(), "approved"))
                    .as("被拒的請求不得寫入任何變數")
                    .isNull();
        }

        @Test
        @DisplayName("R-19：任務指派給另一個系統 system:other → 403")
        void anotherSystemAssigneeCannotBeCompleted() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            taskService.setAssignee(t.taskId(), "system:other");
            long before = audits();

            assertRejectedWithoutSideEffect(complete(t.taskId(), "{\"approved\":true}"), 403, t,
                    before);
        }

        @Test
        @DisplayName("R-19：候選群組名稱就算是 system:erp 也不算持有 → 403（裁決：群組不納入）")
        void candidateGroupNamedLikeTheSystemDoesNotAuthorize() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            taskService.unclaim(t.taskId());
            taskService.addCandidateGroup(t.taskId(), "system:erp");
            long before = audits();

            var res = complete(t.taskId(), "{\"approved\":true}");

            assertRejectedWithoutSideEffect(res, 403, t, before);
            assertThat(res.body())
                    .as("訊息必須說明候選群組不納入，否則呼叫端會以為把群組名改成 "
                            + "system:erp 就能用")
                    .contains("候選群組");
        }

        @Test
        @DisplayName("R-19：前後空白的系統身分不算持有 → 403（不靜默 trim）")
        void paddedSystemIdentityDoesNotAuthorize() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            taskService.setAssignee(t.taskId(), " system:erp");
            long before = audits();

            assertRejectedWithoutSideEffect(complete(t.taskId(), "{\"approved\":true}"), 403, t,
                    before);
        }

        @Test
        @DisplayName("R-19：非擁有者仍被既有擁有權檢查拒絕（不得回歸）")
        void nonOwnerIsStillForbiddenEvenWhenTaskIsAssignedToIt() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            givenSystem("other", OTHER_KEY, "[\"start_process\",\"complete_task\"]",
                    "[\"" + PROCESS_KEY + "\"]");
            // other 發起的案件，任務卻指派給 system:erp：兩個檢查都會通過，
            // 唯一擋下請求的是擁有權 —— 它必須排在任務指派檢查之前。
            var startRes = startAs("other", OTHER_KEY, startBody("{\"leaveType\":\"annual\",\"days\":1}"));
            assertThat(startRes.statusCode()).isEqualTo(200);
            String pid = pidOf(startRes);
            String taskId = taskService.createTaskQuery().processInstanceId(pid).singleResult().getId();
            taskService.setAssignee(taskId, "system:erp");
            long before = audits();

            var res = complete(taskId, "{\"approved\":true}");

            assertThat(res.statusCode())
                    .as("別的系統的案件，即使任務剛好指派給 erp，也不能由 erp 完成")
                    .isEqualTo(403);
            assertThat(res.body()).contains("無權存取此流程");
            assertThat(taskService.createTaskQuery().taskId(taskId).singleResult()).isNotNull();
            assertThat(audits()).isEqualTo(before);
        }
    }

    // ── 對照組：明確指派給系統的任務必須仍然可以完成 ────────────────

    @Nested
    @DisplayName("對照組：明確指派給系統的任務必須可以完成（不得「擋掉全部」）")
    class Passes {

        @Test
        @DisplayName("R-19：assignee=system:erp → 200，任務完成、變數寫入、流程真的走完")
        void taskAssignedToTheSystemCanBeCompleted() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            taskService.setAssignee(t.taskId(), "system:erp");
            long before = audits();

            var res = complete(t.taskId(), "{\"approved\":true}");

            assertThat(res.statusCode())
                    .as("任務明確指派給本系統時必須放行 —— 否則「擋掉全部」的實作"
                            + "也能讓每一條拒絕測試全綠")
                    .isEqualTo(200);
            assertThat(res.body()).contains("\"status\":\"completed\"");
            assertThat(taskService.createTaskQuery().taskId(t.taskId()).singleResult())
                    .as("正向對照必須真的完成了任務").isNull();
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(t.processInstanceId()).singleResult())
                    .as("approved=true 讓流程走到 endApproved，執行中的實例應消失").isNull();
            assertThat(historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(t.processInstanceId()).variableName("approved")
                    .singleResult().getValue())
                    .as("變數必須真的寫進流程，而不只是回 200").isEqualTo(true);
            assertThat(audits())
                    .as("完成的稽核必須留下").isEqualTo(before + 1);
        }

        @Test
        @DisplayName("R-19：candidateUser=system:erp（沒有 assignee）→ 200")
        void candidateUserOfTheSystemCanComplete() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            taskService.unclaim(t.taskId());
            taskService.addCandidateUser(t.taskId(), "system:erp");

            var res = complete(t.taskId(), "{\"approved\":true}");

            assertThat(res.statusCode())
                    .as("裁決明文包含 candidateUsers，不是只認 assignee")
                    .isEqualTo(200);
            assertThat(taskService.createTaskQuery().taskId(t.taskId()).singleResult()).isNull();
        }

        @Test
        @DisplayName("R-19：大小寫不同的 SYSTEM:ERP → 200（沿用 ExternalActorIdentity 的慣例）")
        void caseInsensitiveIdentityCanComplete() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            taskService.setAssignee(t.taskId(), "SYSTEM:ERP");

            var res = complete(t.taskId(), "{\"approved\":true}");

            assertThat(res.statusCode())
                    .as("大小寫慣例照 ExternalActorIdentity.isSystemActor（不區分大小寫）；"
                            + "方向安全 —— 真人 id 不會命中 system: 命名空間")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("R-19：allowedProcessKeys 為 null → 不限制（沿用 ExternalSystemPolicy 四態語意）")
        void nullAllowedProcessKeysMeansUnrestricted() throws Exception {
            givenErp(null);
            OwnedTask t = startOwnedTask();
            taskService.setAssignee(t.taskId(), "system:erp");

            var res = complete(t.taskId(), "{\"approved\":true}");

            assertThat(res.statusCode())
                    .as("空值 = 不限制是 ExternalSystemPolicy 的既有語意（與 startProcess 一致）；"
                            + "把它當拒絕全部會讓所有未設定 allowedProcessKeys 的既有系統全滅（R-21）")
                    .isEqualTo(200);
        }
    }

    // ── 缺陷本體：變數注入 ────────────────────────────────────────────

    @Nested
    @DisplayName("缺陷：變數注入（_ 前綴與 ProcessVariableSpec）")
    class Variables {

        @Test
        @DisplayName("R-19：_externalSystemId 注入 → 400 且指名，擁有權不被覆寫")
        void ownerVariableInjectionIsRejected() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            taskService.setAssignee(t.taskId(), "system:erp");
            long before = audits();

            var res = complete(t.taskId(), "{\"approved\":true,\"_externalSystemId\":\"victim\"}");

            assertRejectedWithoutSideEffect(res, 400, t, before);
            assertThat(res.body())
                    .as("400 的訊息必須指名是哪一個變數，呼叫端才能一次改對")
                    .contains("_externalSystemId");
            assertThat(runtimeService.getVariable(t.processInstanceId(), "_externalSystemId"))
                    .as("擁有權標記必須仍是 server 寫入的 erp，不得被過戶")
                    .isEqualTo("erp");
            assertThat(runtimeService.getVariable(t.processInstanceId(), "approved"))
                    .as("同一個請求裡的其他變數也不得寫入（整筆拒絕，不是部分套用）")
                    .isNull();
        }

        @Test
        @DisplayName("R-19：_formVersions 注入 → 400 且指名，表單版本鎖不被動")
        void formVersionsInjectionIsRejected() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            taskService.setAssignee(t.taskId(), "system:erp");
            long before = audits();
            Object lockedBefore = runtimeService.getVariable(t.processInstanceId(), "_formVersions");

            var res = complete(t.taskId(), "{\"approved\":true,\"_formVersions\":[]}");

            assertRejectedWithoutSideEffect(res, 400, t, before);
            assertThat(res.body()).contains("_formVersions");
            assertThat(runtimeService.getVariable(t.processInstanceId(), "_formVersions"))
                    .as("表單版本鎖必須維持原值（可能是 null，但不得被呼叫端改寫）")
                    .isEqualTo(lockedBefore);
        }

        @Test
        @DisplayName("R-19：多個保留變數一次列出 → 400 且全部指名")
        void allReservedNamesAreNamed() throws Exception {
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask();
            taskService.setAssignee(t.taskId(), "system:erp");
            long before = audits();

            var res = complete(t.taskId(),
                    "{\"approved\":true,\"_callbackUrl\":\"http://evil\",\"_x\":1}");

            assertRejectedWithoutSideEffect(res, 400, t, before);
            assertThat(res.body()).contains("_callbackUrl").contains("_x");
        }

        @Test
        @DisplayName("R-19：必填變數未帶 → 400，訊息與 validateVariables 相同")
        void missingRequiredVariableIsRejected() throws Exception {
            givenRequiredSpec("dept", "string");
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask("{\"dept\":\"dept001\",\"leaveType\":\"annual\",\"days\":1}");
            taskService.setAssignee(t.taskId(), "system:erp");
            long before = audits();

            var res = complete(t.taskId(), "{\"approved\":true}");

            assertRejectedWithoutSideEffect(res, 400, t, before);
            assertThat(res.body())
                    .as("必須與 startProcess 走同一份 validateVariables，訊息一字不差")
                    .contains("缺少必填變數: dept");
        }

        @Test
        @DisplayName("R-19：必填變數為純空白 → 400，訊息與 validateVariables 相同")
        void blankRequiredVariableIsRejected() throws Exception {
            givenRequiredSpec("dept", "string");
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask("{\"dept\":\"dept001\",\"leaveType\":\"annual\",\"days\":1}");
            taskService.setAssignee(t.taskId(), "system:erp");
            long before = audits();

            var res = complete(t.taskId(), "{\"dept\":\"   \",\"approved\":true}");

            assertRejectedWithoutSideEffect(res, 400, t, before);
            assertThat(res.body()).contains("必填變數不可為空白: dept");
        }

        @Test
        @DisplayName("R-19：必填變數帶值＋未宣告的變數 → 200（未宣告放行，與 validateVariables 一致）")
        void requiredPresentAndUndeclaredVariablePasses() throws Exception {
            givenRequiredSpec("dept", "string");
            givenErp("[\"" + PROCESS_KEY + "\"]");
            OwnedTask t = startOwnedTask("{\"dept\":\"dept001\",\"leaveType\":\"annual\",\"days\":1}");
            taskService.setAssignee(t.taskId(), "system:erp");

            var res = complete(t.taskId(),
                    "{\"dept\":\"dept001\",\"approved\":true,\"notDeclared\":\"x\"}");

            assertThat(res.statusCode())
                    .as("validateVariables 只檢查 required=true 的宣告，未宣告的變數兩邊都放行"
                            + "（這是既有語意，R-19 沿用）")
                    .isEqualTo(200);
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(t.processInstanceId()).singleResult()).isNull();
        }
    }

    // ── 缺陷本體：allowedProcessKeys ────────────────────────────────

    @Nested
    @DisplayName("缺陷：任務所屬流程不在 allowedProcessKeys 內")
    class ProcessKeyAuthorization {

        @Test
        @DisplayName("R-19：未授權流程的任務 → 403 且指名流程 key，零副作用")
        void unauthorizedProcessKeyIsForbidden() throws Exception {
            // 只被授權 purchase-approval 的 erp，卻擁有一張 leave-approval
            // （例如授權在實例啟動後才收緊）。任務先指派給 system:erp，
            // 確保擋下請求的確實是流程 key 檢查，而不是任務層級檢查。
            givenErp("[\"purchase-approval\"]");
            OwnedTask t = givenOwnedTaskAssignedToSystem();
            long before = audits();

            var res = complete(t.taskId(), "{\"approved\":true}");

            assertThat(res.statusCode()).isEqualTo(403);
            assertThat(res.body())
                    .as("必須指名未授權的流程 key")
                    .contains("未被授權完成流程").contains(PROCESS_KEY);
            assertThat(taskService.createTaskQuery().taskId(t.taskId()).singleResult())
                    .as("被拒的請求不得完成任務").isNotNull();
            assertThat(runtimeService.getVariable(t.processInstanceId(), "approved"))
                    .as("被拒的請求不得寫入變數").isNull();
            assertThat(audits()).isEqualTo(before);
        }

        @Test
        @DisplayName("R-19：無法解析的 allowedProcessKeys → 403（fail-closed）")
        void unparseableAllowedProcessKeysFailsClosed() throws Exception {
            givenErp("[not valid json");
            OwnedTask t = givenOwnedTaskAssignedToSystem();
            long before = audits();

            assertRejectedWithoutSideEffect(complete(t.taskId(), "{\"approved\":true}"), 403, t,
                    before);
        }

        @Test
        @DisplayName("R-19：授權檢查必須先於變數檢查（403 優先於 400）")
        void authorizationWinsOverReservedVariable() throws Exception {
            givenErp("[\"purchase-approval\"]");
            OwnedTask t = givenOwnedTaskAssignedToSystem();
            long before = audits();

            var res = complete(t.taskId(), "{\"approved\":true,\"_externalSystemId\":\"x\"}");

            assertThat(res.statusCode())
                    .as("呼叫端該先知道「這件事你根本不能做」，再知道 payload 哪裡不對；"
                            + "與 startProcess 的 allowedProcessKeys → validateVariables 順序一致")
                    .isEqualTo(403);
            assertThat(taskService.createTaskQuery().taskId(t.taskId()).singleResult()).isNotNull();
            assertThat(audits()).isEqualTo(before);
        }
    }
}
