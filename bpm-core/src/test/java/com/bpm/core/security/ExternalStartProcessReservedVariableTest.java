package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-23 殘留：{@code POST /api/external/process-instances} 的
 * {@code variables} 可以夾帶 {@code _} 前綴的保留變數。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <pre>
 *   外部系統 erp 以 variables 夾帶保留變數啟動 leave-approval：
 *   → startProcess 對 variables 只做 validateVariables（只看 required 規格）
 *   → _ 前綴的自由 map 全部寫進流程
 *   → _formVersions 覆寫／卡住表單版本鎖（FormVersionLocker 只在解析到
 *     版本時才覆寫，解析不到時呼叫端的值留著）
 *   → _callbackUrl 在 body.callbackUrl 缺席時原樣留著
 *   →（_externalSystemId 會被 server 稍後覆寫，但「靜默忽略」讓呼叫端
 *     以為值生效了 —— 回 400 指名才是可診斷的行為）
 * </pre>
 *
 * <p>這是 R-19 在 {@code completeTask} 修掉的同一個缺陷的另一個入口。
 * R-23 的修法要求所有 variable 寫入路徑把 {@code _} 前綴列為保留字拒絕，
 * 因此這裡直接呼叫 {@code completeTask} 用的同一份
 * {@code rejectReservedVariableNames}，而不是新寫一份前綴比對
 * —— 「規則只能有一份」是本專案的硬規則。
 *
 * <h2>⚠️ 每一條拒絕都配「零副作用」斷言</h2>
 *
 * <p>只斷言 400 會被「先啟動流程、再丟 400」的實作騙過（案件已存在、
 * 只是呼叫端不知道）。因此每條拒絕都比對 {@code ACT_HI_PROCINST}／執行中
 * 實例筆數，以及 {@code EXTERNAL_API_CALL} 稽核筆數。
 *
 * <h2>⚠️ 對照組：合法啟動與 server 的 callbackUrl 路徑不得被擋</h2>
 *
 * <p>一個「把所有含 {@code _} 的請求都擋掉」的實作能讓每一條拒絕測試全綠，
 * 卻會打死合法的 {@code callbackUrl} 欄位與一般變數 —— {@link Passes}
 * 是這個方向的守門員。{@code formVersions}（沒有底線）那條同時釘住
 * 「只認前綴、不是 substring」。
 *
 * <h2>負向控制組實測（2026-10-02，拿掉 startProcess 的
 * {@code rejectReservedVariableNames} 呼叫、其餘不動）</h2>
 *
 * <p><b>7 條中 3 紅 4 綠。</b>紅的是 {@link Rejections} 全部三條：
 * 三種注入在缺陷版都是 <b>200</b>（{@code expected: 400 but was: 200}），
 * 流程真的被啟動、保留變數真的被寫入 —— 正是本工項要修的缺陷。
 *
 * <p>綠的是 3 條 {@link Passes} 與 {@link Ordering}：合法啟動與
 * 「403 優先於 400」在缺陷期間就該綠，修好之後仍必須綠 ——
 * 一個「擋掉全部啟動」的實作能讓 3 條紅的通過，卻會讓這 4 條紅。
 *
 * <p>還原方式：{@code command cp} 修好的
 * {@code ExternalApiController.java} 蓋回工作區，重跑後再移除呼叫做控制組。
 */
class ExternalStartProcessReservedVariableTest extends IntegrationTestBase {

    private static final String PLAIN_KEY = "sk-r23-external-testkey";
    private static final String PROCESS_KEY = "leave-approval";

    @Autowired
    private ExternalSystemRepository externalRepo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seedSystem() {
        // 與 ExternalRequiredVariableTest／ExternalCompleteTaskHardeningTest
        // 相同的隔離方式。
        externalRepo.deleteAll();
        givenSystem("[\"" + PROCESS_KEY + "\"]");
    }

    @AfterEach
    void removeFixtures() {
        externalRepo.deleteAll();
    }

    // ── fixture ──────────────────────────────────────────────────────

    private void givenSystem(String allowedProcessKeys) {
        ExternalSystem sys = new ExternalSystem();
        // 刻意不設 id（自行指定會走 merge，見 ExternalApiTcA04Test 的說明）。
        sys.setSystemId("erp");
        sys.setSystemName("R23 測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys(allowedProcessKeys);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        externalRepo.save(sys);
    }

    // ── 請求／斷言工具 ────────────────────────────────────────────────

    private static String body(String variablesJson) {
        return "{\"processDefinitionKey\":\"" + PROCESS_KEY + "\","
                + "\"businessKey\":\"R23-" + UUID.randomUUID() + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":" + variablesJson + "}";
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

    private long runningInstances() {
        return runtimeService.createProcessInstanceQuery().count();
    }

    private long historicInstances() {
        return historyService.createHistoricProcessInstanceQuery().count();
    }

    private long externalApiAudits() {
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
     * 斷言「被拒絕，而且什麼都沒發生」。
     *
     * <p>後三個斷言才是重點：只驗狀態碼會被「先啟動、再回 400」的實作騙過，
     * 而那個實作正是本工項要修的缺陷本身（案件已經存在）。
     */
    private void assertRejectedWithoutSideEffect(HttpResponse<String> res, String namedVariable,
                                                 long runningBefore, long historicBefore,
                                                 long auditsBefore) {
        assertThat(res.statusCode())
                .as("保留變數必須 400（payload 形狀不對，呼叫端該改參數），"
                        + "不是 403（授權）也不是 404（資源不存在）")
                .isEqualTo(400);
        assertThat(res.body())
                .as("400 的訊息必須指名是哪一個變數，呼叫端才能一次改對")
                .contains("前綴").contains(namedVariable);
        assertThat(runningInstances())
                .as("被拒的請求不得啟動流程（執行中）")
                .isEqualTo(runningBefore);
        assertThat(historicInstances())
                .as("被拒的請求不得在 ACT_HI_PROCINST 留下任何一筆")
                .isEqualTo(historicBefore);
        assertThat(externalApiAudits())
                .as("被拒的請求不得寫 EXTERNAL_API_CALL 稽核")
                .isEqualTo(auditsBefore);
    }

    // ── 缺陷本體：startProcess 的 _ 前綴注入 ─────────────────────────

    @Nested
    @DisplayName("缺陷：startProcess 的 variables 可夾帶 _ 前綴保留變數")
    class Rejections {

        @Test
        @DisplayName("R-23：_externalSystemId 注入 → 400 且指名，零副作用")
        void ownerVariableInjectionIsRejected() throws Exception {
            long running = runningInstances(), historic = historicInstances();
            long audits = externalApiAudits();

            var res = post(body("{\"leaveType\":\"annual\",\"days\":1,"
                    + "\"_externalSystemId\":\"victim\"}"));

            assertRejectedWithoutSideEffect(res, "_externalSystemId", running, historic, audits);
        }

        @Test
        @DisplayName("R-23：_formVersions 注入 → 400 且指名，零副作用")
        void formVersionsInjectionIsRejected() throws Exception {
            long running = runningInstances(), historic = historicInstances();
            long audits = externalApiAudits();

            var res = post(body("{\"leaveType\":\"annual\",\"days\":1,"
                    + "\"_formVersions\":[]}"));

            assertRejectedWithoutSideEffect(res, "_formVersions", running, historic, audits);
        }

        @Test
        @DisplayName("R-23：多個保留變數一次列出 → 400 且全部指名")
        void allReservedNamesAreNamed() throws Exception {
            long running = runningInstances(), historic = historicInstances();
            long audits = externalApiAudits();

            var res = post(body("{\"leaveType\":\"annual\",\"days\":1,"
                    + "\"_callbackUrl\":\"http://evil\",\"_x\":1}"));

            assertRejectedWithoutSideEffect(res, "_callbackUrl", running, historic, audits);
            assertThat(res.body())
                    .as("訊息必須一次列出所有保留名（排序以求穩定）")
                    .contains("_x");
        }
    }

    // ── 對照組：合法啟動不得被擋（不得「擋掉全部」）──────────────────

    @Nested
    @DisplayName("對照組：沒有保留變數的請求必須照常啟動")
    class Passes {

        @Test
        @DisplayName("R-23：一般變數 → 200，流程真的啟動並寫 EXTERNAL_API_CALL 稽核")
        void normalVariablesStillStart() throws Exception {
            long running = runningInstances(), historic = historicInstances();
            long audits = externalApiAudits();

            var res = post(body("{\"leaveType\":\"annual\",\"days\":1}"));

            assertThat(res.statusCode())
                    .as("守衛擋的是保留命名空間，不是所有啟動請求")
                    .isEqualTo(200);
            assertThat(res.body()).contains("processInstanceId");
            assertThat(runningInstances()).isEqualTo(running + 1);
            assertThat(historicInstances()).isEqualTo(historic + 1);
            assertThat(externalApiAudits()).isEqualTo(audits + 1);
        }

        @Test
        @DisplayName("R-23：只有前綴被保留 —— 名為 formVersions（無底線）的變數仍可寫入")
        void underscorePrefixIsTheOnlyReservedRule() throws Exception {
            long running = runningInstances();

            var res = post(body("{\"leaveType\":\"annual\",\"days\":1,"
                    + "\"formVersions\":\"business value\"}"));

            assertThat(res.statusCode())
                    .as("規則是『_ 前綴』，不是『名字裡有 Versions 就擋』")
                    .isEqualTo(200);
            assertThat(runningInstances()).isEqualTo(running + 1);
        }

        @Test
        @DisplayName("R-23：server 的 callbackUrl 欄位不受影響，_callbackUrl 由 server 寫入")
        void serverCallbackUrlPathStillWorks() throws Exception {
            long running = runningInstances();

            // callbackUrl 是 body 的頂層欄位（不是 variables），
            // server 在驗證變數之後才把它寫成 _callbackUrl。
            var res = post("{\"processDefinitionKey\":\"" + PROCESS_KEY + "\","
                    + "\"businessKey\":\"R23-" + UUID.randomUUID() + "\","
                    + "\"firstTaskAssignee\":\"mgr001\","
                    + "\"callbackUrl\":\"http://example.invalid/hook\","
                    + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}");

            assertThat(res.statusCode())
                    .as("保留字檢查只掃呼叫端的 variables；server 自己寫的 _callbackUrl "
                            + "是合法路徑，不能被自己的守衛擋掉")
                    .isEqualTo(200);
            assertThat(runningInstances()).isEqualTo(running + 1);
            String pid = res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
            assertThat(runtimeService.getVariable(pid, "_callbackUrl"))
                    .isEqualTo("http://example.invalid/hook");
        }
    }

    // ── 對照組：授權檢查必須先於形狀檢查（順序不可顛倒）──────────────

    @Nested
    @DisplayName("對照組：授權檢查必須先於保留變數檢查")
    class Ordering {

        @Test
        @DisplayName("R-23：未授權的流程 key ＋ _ 前綴變數 → 仍必須 403（不是 400）")
        void unauthorizedKeyWinsOverReservedVariable() throws Exception {
            // 與 ExternalRequiredVariableTest.unauthorizedKeyWinsOverBlankRequired
            // 同一個理由：若 400 的形狀檢查擺在 403 之前，一個只被授權其他流程的
            // 系統就能用 400/403 的差異探測伺服器上部署了哪些流程定義。
            externalRepo.deleteAll();
            givenSystem("[\"purchase-approval\"]");
            long running = runningInstances(), historic = historicInstances();

            var res = post(body("{\"leaveType\":\"annual\",\"days\":1,"
                    + "\"_externalSystemId\":\"x\"}"));

            assertThat(res.statusCode())
                    .as("授權檢查必須先於形狀檢查，與 startProcess 既有順序一致")
                    .isEqualTo(403);
            assertThat(runningInstances()).isEqualTo(running);
            assertThat(historicInstances()).isEqualTo(historic);
        }
    }
}
