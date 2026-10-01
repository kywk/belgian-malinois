package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.repository.ProcessVariableSpecRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #91 漏報①：{@code POST /api/external/process-instances} 對
 * {@code required=true} 的變數，收到空白值仍然放行。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <pre>
 *   spec：{@code dept} required=true
 *   外部系統 erp 送 {"dept":"  "}（或 {"dept":null}）
 *   → validateVariables 只判 {@code !variables.containsKey("dept")}
 *     → 鍵存在 → <b>通過</b>
 *   → 流程啟動，BPMN 的 {@code flowable:assignee="${dept}"} 求值為空白／null
 *   → assignee 非 null（空字串）或為 null 而無候選人
 *   → Flowable 候選群組查詢帶 {@code ASSIGNEE_ IS NULL} 不命中
 *   → 沒有任何任務查得到、沒有人能簽
 *   → 呼叫端卻拿到 <b>200 與一個 processInstanceId</b>：案件靜默卡死
 * </pre>
 *
 * <p>與 {@link ExternalFirstTaskAssigneeTest}（#88）的失效型態相同
 * （「靜默卡死」），只是入口不同：那裡是 {@code firstTaskAssignee} 自由字串，
 * 這裡是 {@code variables} 裡被宣告為必填、卻送空白的值。
 *
 * <h2>裁決後的語意（照做，未自行擴大）</h2>
 *
 * <p>{@code required=true} 的變數符合任一 → <b>400</b>：
 * <ol>
 *   <li>缺值（{@code !containsKey}）—— 既有行為，訊息不變。</li>
 *   <li>值為 {@code null}。</li>
 *   <li>值是 {@code String} 且 {@code isBlank()}。</li>
 * </ol>
 *
 * <p><b>明確不擋</b>：{@code 0}、{@code false}、空集合／空 Map、任何非空白字串。
 * 判準是<b>字串語意</b>，不是 falsy。這一組由 {@link Passes} 守著。
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>{@code ResponseStatusException} 走 ERROR dispatch，MockMvc 不做那次 dispatch
 * （見 {@code ErrorDispatchTest} 與 #80／#88 的同款說明）。若只用 MockMvc，
 * 「修好之後狀態碼其實是 500」這類錯誤不會被翻面。本類別的狀態碼斷言
 * 一律走真實 HTTP。
 *
 * <h2>⚠️ 每一條拒絕都配「被拒不得留下流程實例」的非空斷言</h2>
 *
 * <p>只斷言 400 會被「先啟動流程、再丟 400」的實作騙過 ——
 * 而那個實作正是本工項要修的缺陷本身（案件已存在、只是沒人能簽）。
 * 因此每條拒絕都同時比對 {@code ACT_HI_PROCINST} 與執行中實例的筆數。
 *
 * <h2>⚠️ 負向控制組實測（把「null／空白」那段條件停用，只留 {@code !containsKey}）</h2>
 *
 * <p><b>10 條中 3 紅 7 綠。</b>
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅</td>
 *       <td>{@code nullRequiredVariableIsBadRequest}<br>
 *           {@code emptyStringRequiredVariableIsBadRequest}<br>
 *           {@code whitespaceRequiredVariableIsBadRequest}</td>
 *       <td>缺陷期間三種空白形狀全部 <b>200</b>（{@code expected: 400 but was: 200}）
 *           —— 流程真的被啟動，正是本工項要修的「靜默卡死」。</td></tr>
 *   <tr><td>🟢 綠</td>
 *       <td>{@code missingRequiredVariableIsBadRequest}<br>
 *           {@link Passes} 全部 5 條<br>
 *           {@code unauthorizedKeyWinsOverBlankRequired}</td>
 *       <td><b>不是漏抓</b>：缺值本來就 400（既有行為）；
 *           5 條放行對照在缺陷期間就該綠，而且<b>修好之後仍必須綠</b>
 *           —— 一個「把所有 required 值都擋掉」的實作能讓 3 條紅的通過，
 *           卻會讓這 5 條紅；403 那條證明授權檢查沒有被新檢查蓋掉。
 *           （<b>懷疑但未複驗</b>：同一條 403 對照在缺陷期間綠，
 *           代表它抓不到「把空白檢查搬到 403 之前」的錯誤修法 ——
 *           因為缺陷版根本沒有那段檢查可搬。它的價值在修好之後的迴歸。）</td></tr>
 * </table>
 *
 * <p>還原方式：只把 {@code validateVariables} 的判斷式換回
 * {@code Boolean.TRUE.equals(...) && !containsKey(...)}（其餘不動），
 * 重跑後再從備份還原修好的版本。
 */
class ExternalRequiredVariableTest extends IntegrationTestBase {

    private static final String PLAIN_KEY = "sk-t91-external-testkey";
    private static final String PROCESS_KEY = "leave-approval";

    @Autowired
    private ExternalSystemRepository externalRepo;

    @Autowired
    private ProcessVariableSpecRepository specRepo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

    private final HttpClient http = HttpClient.newHttpClient();

    /** 本測試建立的規格 id，逐筆刪除而不是整表清空 —— 容器共用同一個資料庫。 */
    private final List<String> specIds = new ArrayList<>();

    @BeforeEach
    void seedSystem() {
        // 與 ExternalFirstTaskAssigneeTest／ExternalApiProcessKeyNotFoundTest 相同的隔離方式。
        externalRepo.deleteAll();
        givenSystem("[\"" + PROCESS_KEY + "\"]");
    }

    @AfterEach
    void removeFixtures() {
        // 必填規格若殘留，會讓所有以 leave-approval 發起的外部測試莫名回 400
        // （ExternalApiTcA04Test 等）—— 逐筆刪除自己的規格，不碰別人的。
        specRepo.deleteAllById(specIds);
        specIds.clear();
        externalRepo.deleteAll();
    }

    // ── fixture ──────────────────────────────────────────────────────

    private void givenSystem(String allowedProcessKeys) {
        ExternalSystem sys = new ExternalSystem();
        // 刻意不設 id（自行指定會走 merge，見 ExternalApiTcA04Test 的說明）。
        sys.setSystemId("erp");
        sys.setSystemName("T91 測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys(allowedProcessKeys);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        externalRepo.save(sys);
    }

    /** 為 {@link #PROCESS_KEY} 宣告一筆變數規格，並登記 id 以便收尾。 */
    private void givenSpec(String variableName, String variableType, boolean required) {
        ProcessVariableSpec s = new ProcessVariableSpec();
        s.setProcessDefinitionKey(PROCESS_KEY);
        s.setVariableName(variableName);
        s.setVariableType(variableType);
        s.setRequired(required);
        specRepo.save(s);
        specIds.add(s.getId());
    }

    // ── 請求／斷言工具 ────────────────────────────────────────────────

    /** JSON 字面值的變數 map 直接內嵌，讓 {@code null} 與「欄位不存在」能分開。 */
    private static String body(String variablesJson) {
        return "{\"processDefinitionKey\":\"" + PROCESS_KEY + "\","
                + "\"businessKey\":\"T91-" + UUID.randomUUID() + "\","
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

    /**
     * 斷言「被拒絕，而且資料庫裡查無流程實例」。
     *
     * <p>後兩個斷言才是重點：只驗狀態碼會被「先啟動、再回 400」的實作騙過。
     * 執行中與歷史兩張表都比對，因為若交易沒有如預期回滾，
     * 歷史表仍會留下一筆（Flowable 的歷史寫入與執行期分屬不同儲存）。
     */
    private void assertRejectedWithoutInstance(HttpResponse<String> res,
                                               long runningBefore, long historicBefore) {
        assertThat(res.statusCode())
                .as("必填變數為 null／空白必須 400（payload 形狀不對，呼叫端該改參數），"
                        + "不是 403（授權）也不是 404（資源不存在）")
                .isEqualTo(400);
        assertThat(runningInstances())
                .as("被拒的請求不得啟動流程（執行中）")
                .isEqualTo(runningBefore);
        assertThat(historicInstances())
                .as("被拒的請求不得在 ACT_HI_PROCINST 留下任何一筆")
                .isEqualTo(historicBefore);
    }

    // ── 缺陷本體：required=true 收到空白值 ───────────────────────────

    @Nested
    @DisplayName("缺陷：required=true 收到 null／空字串／純空白")
    class Rejections {

        @Test
        @DisplayName("#91：required=true 但 variables 完全沒帶該鍵 → 400（既有行為，訊息不變）")
        void missingRequiredVariableIsBadRequest() throws Exception {
            givenSpec("dept", "string", true);
            long running = runningInstances(), historic = historicInstances();

            var res = post(body("{\"leaveType\":\"annual\",\"days\":1}"));

            assertRejectedWithoutInstance(res, running, historic);
            assertThat(res.body())
                    .as("缺值的訊息是既有契約，本工項刻意不動它")
                    .contains("缺少必填變數: dept");
        }

        @Test
        @DisplayName("#91：required=true 帶 null → 400 且不啟動流程（缺陷期間是 200）")
        void nullRequiredVariableIsBadRequest() throws Exception {
            givenSpec("dept", "string", true);
            long running = runningInstances(), historic = historicInstances();

            var res = post(body("{\"dept\":null}"));

            assertRejectedWithoutInstance(res, running, historic);
            assertThat(res.body())
                    .as("null 與缺值都是 400，但訊息必須能分辨 —— 呼叫端要改的地方不同")
                    .contains("dept")
                    .doesNotContain("缺少必填變數");
        }

        @Test
        @DisplayName("#91：required=true 帶空字串 → 400 且不啟動流程（缺陷期間是 200）")
        void emptyStringRequiredVariableIsBadRequest() throws Exception {
            givenSpec("dept", "string", true);
            long running = runningInstances(), historic = historicInstances();

            var res = post(body("{\"dept\":\"\"}"));

            assertRejectedWithoutInstance(res, running, historic);
            assertThat(res.body()).contains("必填變數不可為空白: dept");
        }

        @Test
        @DisplayName("#91：required=true 帶純空白（含 tab）→ 400 且不啟動流程")
        void whitespaceRequiredVariableIsBadRequest() throws Exception {
            givenSpec("dept", "string", true);
            long running = runningInstances(), historic = historicInstances();

            for (String v : new String[]{"\"   \"", "\"\\t\"", "\" \\t \\n \""}) {
                var res = post(body("{\"dept\":" + v + "}"));
                assertThat(res.statusCode())
                        .as("純空白（isBlank）必須等同沒有值: " + v).isEqualTo(400);
                assertThat(runningInstances())
                        .as("被拒的請求不得啟動流程: " + v).isEqualTo(running);
            }
            assertThat(historicInstances())
                    .as("整組被拒的請求都不得在 ACT_HI_PROCINST 留下任何一筆")
                    .isEqualTo(historic);
        }
    }

    // ── 對照組：合法值必須仍然放行（不得「擋掉全部」）────────────────

    @Nested
    @DisplayName("對照組：非字串的 falsy 值是合法必填值，必須仍啟動")
    class Passes {

        @Test
        @DisplayName("#91：required=true 的合法字串 → 200，且流程真的啟動")
        void legalStringStarts() throws Exception {
            givenSpec("dept", "string", true);
            long running = runningInstances(), historic = historicInstances();

            var res = post(body("{\"dept\":\"dept001\"}"));

            assertThat(res.statusCode())
                    .as("守衛必須擋的是空白，不是所有值")
                    .isEqualTo(200);
            assertThat(res.body()).contains("processInstanceId");
            assertThat(runningInstances())
                    .as("正向對照必須真的啟動了流程")
                    .isEqualTo(running + 1);
            assertThat(historicInstances()).isEqualTo(historic + 1);
        }

        @Test
        @DisplayName("#91：數字 0 是合法必填值（判準是字串，不是 falsy）→ 200")
        void zeroIsNotBlank() throws Exception {
            givenSpec("days", "number", true);
            long running = runningInstances();

            var res = post(body("{\"days\":0}"));

            assertThat(res.statusCode())
                    .as("0 是語意明確的必填值；把它當空白會換來『合法的 0 填不進來』的新缺陷")
                    .isEqualTo(200);
            assertThat(runningInstances()).isEqualTo(running + 1);
        }

        @Test
        @DisplayName("#91：布林 false 是合法必填值 → 200")
        void falseIsNotBlank() throws Exception {
            givenSpec("urgent", "boolean", true);
            long running = runningInstances();

            var res = post(body("{\"urgent\":false}"));

            assertThat(res.statusCode())
                    .as("false 是明確回答，不是沒回答")
                    .isEqualTo(200);
            assertThat(runningInstances()).isEqualTo(running + 1);
        }

        @Test
        @DisplayName("#91：空集合／空 Map 不是「空白字串」→ 200")
        void emptyCollectionAndMapAreNotBlank() throws Exception {
            givenSpec("tags", "string", true);
            long running = runningInstances();

            for (String v : new String[]{"[]", "{}"}) {
                var res = post(body("{\"tags\":" + v + "}"));
                assertThat(res.statusCode())
                        .as("空集合／空 Map 是合法值，非 String 不套用空白判準: " + v)
                        .isEqualTo(200);
            }
            assertThat(runningInstances()).isEqualTo(running + 2);
        }

        @Test
        @DisplayName("#91：required=false 的空白值完全不受影響 → 200")
        void optionalBlankIsNotBlocked() throws Exception {
            givenSpec("note", "string", false);
            long running = runningInstances();

            var res = post(body("{\"note\":\"   \"}"));

            assertThat(res.statusCode())
                    .as("本方法只處理 required=true；required=false 不是它的守備範圍")
                    .isEqualTo(200);
            assertThat(runningInstances()).isEqualTo(running + 1);
        }
    }

    // ── 對照組：授權先於形狀（順序不可顛倒）──────────────────────────

    @Nested
    @DisplayName("對照組：授權檢查必須先於必填變數檢查")
    class Ordering {

        @Test
        @DisplayName("#91：未授權的流程 key ＋ 空白 required → 仍必須 403（不是 400）")
        void unauthorizedKeyWinsOverBlankRequired() throws Exception {
            // 為什麼重要：若把 400 的形狀檢查擺在 403 之前，一個只被授權
            // purchase-approval 的系統就能用「400 變 403」探測出伺服器上
            // 部署了哪些流程定義 —— 授權檢查會變成 enumeration 工具
            // （與 #80 的 404 預檢、#88 的 400 身分檢查同一個理由）。
            externalRepo.deleteAll();
            givenSystem("[\"purchase-approval\"]");
            givenSpec("dept", "string", true);
            long running = runningInstances(), historic = historicInstances();

            // key 是 leave-approval（未在此系統的 allowedProcessKeys 內），
            // 而 dept 對它而言是 required=true 卻送空白。
            var res = post(body("{\"dept\":\"   \"}"));

            assertThat(res.statusCode())
                    .as("授權檢查必須先於形狀檢查，否則 400/403 的差異成了部署清單的探測工具")
                    .isEqualTo(403);
            assertThat(runningInstances())
                    .as("被拒的請求不得啟動流程（執行中）").isEqualTo(running);
            assertThat(historicInstances())
                    .as("被拒的請求不得在 ACT_HI_PROCINST 留下任何一筆").isEqualTo(historic);
        }
    }
}
