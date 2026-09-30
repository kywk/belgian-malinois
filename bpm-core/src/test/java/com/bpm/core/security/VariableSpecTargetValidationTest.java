package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * #85：{@code PUT /api/admin/process-definitions/{key}/variable-spec/{id}} 的
 * {@code {key}} 守衛與稽核誠實性。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <p>{@code ProcessVariableSpecController.update} 在 {@code findById(id)} 之後
 * <b>完全沒有比對 {@code existing.getProcessDefinitionKey()}</b>，
 * 而路徑上的 {@code {key}} 只被寫進稽核紀錄。修補前實測：拿
 * {@code purchase-approval} 的那一筆 id 打在
 * {@code .../leave-approval/variable-spec/{id}} → 回 <b>200</b>、
 * {@code purchase-approval} 的規格真的被改掉（{@code required} 由 true 變 false），
 * 而稽核紀錄寫的是 {@code processDefinitionKey = "leave-approval"}。
 *
 * <p>危害不是「改到別人的資料」——端點限 ADMIN，攻擊面很小。
 * 危害是<b>稽核紀錄會說謊</b>，而稽核是這個專案的核心賣點：
 * 變數規格是外部系統的<b>輸入驗證定義</b>（{@code required} 決定
 * {@code ExternalApiController.validateVariables} 會不會擋），
 * 把 {@code required} 從 true 改成 false 就是放寬外部系統的輸入驗證，
 * 而稽核上留下的是「有人在流程 A 上改了 amount」——
 * 追查的人會去查流程 A 的設定，而不是去查真正被改的那筆。
 *
 * <h2>⚠️ 狀態碼走真實 HTTP（連「id 不存在 → 404」那條也是）</h2>
 *
 * <p>{@code ResponseStatusException} 與 {@code NoSuchElementException} 讓容器
 * 以 <b>ERROR dispatch</b> 轉到 {@code /error} 組回應，線上的狀態碼正是那條
 * 路徑決定的（{@code SecurityConfig} 的
 * {@code dispatcherTypeMatchers(ERROR).permitAll()}）。
 * MockMvc <b>不做 error dispatch</b>（見 {@link ErrorDispatchTest}），
 * 所以本類別所有狀態碼斷言都走真實 HTTP。
 * 這一條對「id 不存在」特別重要：缺陷期間那裡是
 * {@code orElseThrow()} → {@code NoSuchElementException} → <b>500</b>，
 * 而 500 與 404 的差別正是「呼叫端該重試還是該回報」。
 *
 * <h2>非空性：每一條拒絕都同時驗「資料沒變」與「稽核沒寫」</h2>
 *
 * <ul>
 *   <li>{@code required} 必須仍是 {@code true}（放寬輸入驗證是這個缺陷的實質危害），
 *       其餘欄位（{@code variableName}／{@code variableType}／{@code description}／
 *       {@code example}／{@code processDefinitionKey}）也全部逐字比對。</li>
 *   <li>稽核庫不得有指向那個 {@code targetId} 的 {@code CONFIG_CHANGE} ——
 *       一筆 {@code update} 代表「這個變更發生了」，而實際上什麼都沒發生。</li>
 *   <li>每條拒絕都配一條<b>同樣參數、只有 key 一致</b>的 200 對照，
 *       讓「守衛把所有人都擋掉」這種空通過無處藏身。</li>
 * </ul>
 */
class VariableSpecTargetValidationTest extends IntegrationTestBase {

    /** 持有通配權限 {@code *} → {@code ROLE_ADMIN}；{@code /api/admin/**} 需要它。 */
    private static final String ADMIN = "admin001";

    /**
     * 持有 {@code audit:log:read} 的稽核職能使用者（權限中心 fixture，見 MockPermController）。
     *
     * <p>⚠️ <b>不可用 {@code ROLE_ADMIN} 讀稽核。</b>2026-09-29 的政策決定是
     * 「{@code *} 通配不等於 {@code audit:log:read}」，而 {@code /api/audit-logs/**}
     * 的規則刻意不接受 {@code ROLE_ADMIN}（理由見 {@code SecurityConfig} 的註解）。
     * 所以這一條用 {@code dir001}，並順帶把「admin001 讀不到」固定下來 ——
     * 那是政策本身，不是本測試的副作用。
     */
    private static final String AUDITOR = "dir001";

    private static final String PLAIN_KEY = "sk-t85-testkey";

    @Autowired
    private ProcessVariableSpecRepository specRepo;

    @Autowired
    private ExternalSystemRepository externalSystemRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    private final List<String> createdSpecs = new ArrayList<>();
    private final List<String> createdSystems = new ArrayList<>();

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    @AfterEach
    void removeFixtures() {
        // 變數規格是外部系統的輸入驗證定義，留下 required=true 的幽靈規格會讓
        // 其他測試（例如 ExternalApiTcA04Test）的外部發起莫名其妙回 400。
        createdSpecs.forEach(id -> specRepo.findById(id).ifPresent(specRepo::delete));
        createdSystems.forEach(sid -> externalSystemRepo.findBySystemId(sid)
                .ifPresent(externalSystemRepo::delete));
        createdSpecs.clear();
        createdSystems.clear();
    }

    // ── HTTP 小工具（走真實 HTTP，見類別註解）────────────────────────

    private HttpResponse<String> send(String method, String path, String userId, String body)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putSpec(String pathKey, String id, String body) throws Exception {
        return send("PUT", "/api/admin/process-definitions/" + pathKey + "/variable-spec/" + id,
                ADMIN, body);
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── fixture ────────────────────────────────────────────────────

    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 建立一筆規格。
     *
     * <p>{@code variableName} 帶隨機後綴是為了避開唯一約束
     * {@code (processDefinitionKey, variableName)} —— 測試共用同一個資料庫。
     * 刻意用 ASCII：斷言要能一眼看出是哪個值被翻了（見 MassAssignmentTest 的說明）。
     */
    private String givenSpec(String processDefinitionKey, boolean required) {
        ProcessVariableSpec s = new ProcessVariableSpec();
        s.setProcessDefinitionKey(processDefinitionKey);
        s.setVariableName("amount_" + UUID.randomUUID().toString().substring(0, 8));
        s.setVariableType("number");
        s.setRequired(required);
        s.setDescription("ORIGINAL-DESCRIPTION");
        s.setExample("ORIGINAL-EXAMPLE");
        s = specRepo.save(s);
        createdSpecs.add(s.getId());
        return s.getId();
    }

    private void givenExternalSystem(String systemId, String allowedProcessKeys) {
        ExternalSystem sys = new ExternalSystem();
        // 刻意不設 id（見 ExternalApiTcA04Test 的說明：自行指定 id 會走 merge）。
        sys.setSystemId(systemId);
        sys.setSystemName("T85 測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys(allowedProcessKeys);
        sys.setEnabled(true);
        sys.setCreatedAt(java.time.Instant.now());
        externalSystemRepo.save(sys);
        createdSystems.add(systemId);
    }

    /** 攻擊者想寫進去的內容：把 {@code required} 由 true 翻成 false。 */
    private static String relaxRequiredBody() {
        return "{\"variableName\":\"amount_tampered\",\"variableType\":\"string\","
                + "\"required\":false,\"description\":\"TAMPERED-DESCRIPTION\","
                + "\"example\":\"TAMPERED-EXAMPLE\"}";
    }

    // ── 資料與稽核的驗證小工具 ──────────────────────────────────────

    /** 規格的完整狀態。record 的 toString 會把新舊一起印在失敗訊息裡。 */
    private record SpecState(String processDefinitionKey, String variableName, String variableType,
                             Boolean required, String description, String example) {
    }

    private SpecState stateOf(String specId) {
        ProcessVariableSpec s = specRepo.findById(specId).orElseThrow(
                () -> new AssertionError("規格 " + specId + " 不見了 —— 測試前提失效"));
        return new SpecState(s.getProcessDefinitionKey(), s.getVariableName(), s.getVariableType(),
                s.getRequired(), s.getDescription(), s.getExample());
    }

    /** 稽核庫裡 targetId 指向這個規格的 CONFIG_CHANGE 紀錄。 */
    private static List<String> configChangeDetailsOf(String targetId) {
        var out = new ArrayList<String>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement(
                    "SELECT detail FROM bpm_audit_log WHERE operation_type = 'CONFIG_CHANGE' "
                            + "AND detail LIKE ? ORDER BY id")) {
                ps.setString(1, "%\"" + targetId + "\"%");
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    /** 稽核掛在業務交易的 beforeCommit，短暫停頓只是讓「不得留下紀錄」更嚴格。 */
    private static List<String> settledConfigChangeOf(String targetId) throws InterruptedException {
        Thread.sleep(300);
        return configChangeDetailsOf(targetId);
    }

    private static List<String> awaitConfigChangeOf(String targetId) throws InterruptedException {
        List<String> found = List.of();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && found.isEmpty()) {
            found = configChangeDetailsOf(targetId);
            if (found.isEmpty()) Thread.sleep(100);
        }
        return found;
    }

    // ── ① {key} 與資料列不一致 ─────────────────────────────────────

    @Test
    @DisplayName("#85：路徑的 {key} 與資料列不符 → 404，且該筆規格一個欄位都沒被改")
    void mismatchedKeyIsRejectedAndNothingChanges() throws Exception {
        String realKey = uniqueKey("T85-victim");
        String otherKey = uniqueKey("T85-impersonated");
        String specId = givenSpec(realKey, true);
        SpecState before = stateOf(specId);

        var res = putSpec(otherKey, specId, relaxRequiredBody());

        assertThat(res.statusCode())
                .as("缺陷期間這裡回 200：spec 真的被改掉（required true → false），"
                        + "而稽核記的是路徑上的 %s", otherKey)
                .isEqualTo(404);
        assertThat(stateOf(specId))
                .as("被拒的修改不得留下任何副作用 —— required 從 true 變 false 等於"
                        + "放寬外部系統的輸入驗證，這是這個缺陷的實質危害")
                .isEqualTo(before);
        assertThat(stateOf(specId).required())
                .as("required 必須仍是 true").isTrue();
        assertThat(settledConfigChangeOf(specId))
                .as("被擋下的請求不得留下 CONFIG_CHANGE —— 那正是「稽核說這個變更發生了」"
                        + "而實際沒發生的情形")
                .isEmpty();
    }

    // ── ② {key} 一致：守衛不得過度阻擋 ──────────────────────────────

    @Test
    @DisplayName("#85：路徑的 {key} 與資料列一致 → 200，且真的改到")
    void matchingKeySucceedsAndActuallyChanges() throws Exception {
        // 這一條與 ①③④ 必須成組存在：少了它，守衛可以靠「把所有人都擋掉」達成，
        // 而管理功能（調整外部系統的輸入驗證定義）已經被打死。
        String realKey = uniqueKey("T85-happy");
        String specId = givenSpec(realKey, true);
        SpecState before = stateOf(specId);

        var res = putSpec(realKey, specId, relaxRequiredBody());

        assertThat(res.statusCode())
                .as("key 一致時必須放行 —— 否則外部系統的輸入驗證定義就永遠改不了")
                .isEqualTo(200);

        SpecState after = stateOf(specId);
        assertThat(after.required())
                .as("required 必須真的由 true 變 false，否則這條測試對「守衛擋住了一切」"
                        + "同樣是綠的")
                .isFalse();
        assertThat(after.processDefinitionKey())
                .as("路徑的 key 不得被寫進資料列 —— 資料列的 key 是它自己的身分")
                .isEqualTo(before.processDefinitionKey());
        assertThat(after.variableName()).isEqualTo("amount_tampered");
        assertThat(after.variableType()).isEqualTo("string");
        assertThat(after.description()).isEqualTo("TAMPERED-DESCRIPTION");
        assertThat(after.example()).isEqualTo("TAMPERED-EXAMPLE");
        assertThat(after).isNotEqualTo(before);
    }

    // ── ③ 稽核的誠實性 ────────────────────────────────────────────

    @Test
    @DisplayName("#85：稽核必須記「實際被改的那一筆」，且被擋下時不得留下任何紀錄")
    void auditNamesTheRowThatActuallyChanged() throws Exception {
        // 兩筆規格分屬兩個流程。decoy 存在的理由：只留一筆時，
        // 「稽核記的 key」與「資料列的 key」在通過守衛後必然相等，
        // 這個斷言就沒有任何鑑別力。放了兩筆之後才能問出真正的問題：
        // 稽核指名的流程，其底下被翻掉 required 的<b>就是</b>那筆，
        // 而另一個流程一點都沒被碰到。
        String keyA = uniqueKey("T85-audit-a");
        String keyB = uniqueKey("T85-audit-b");
        String specA = givenSpec(keyA, true);
        String specB = givenSpec(keyB, true);
        SpecState aBefore = stateOf(specA);
        SpecState bBefore = stateOf(specB);

        // (1) 冒用：拿 A 當路徑 key 去改 B
        assertThat(putSpec(keyA, specB, relaxRequiredBody()).statusCode())
                .as("前置條件：key 與資料列不符必須先被擋下").isEqualTo(404);
        assertThat(stateOf(specB)).isEqualTo(bBefore);
        assertThat(settledConfigChangeOf(specB))
                .as("缺陷期間這裡會有一筆 processDefinitionKey=" + keyA + " 的 update，"
                        + "而實際被改的是 " + keyB + " —— 稽核正在說謊")
                .isEmpty();

        // (2) 正確的 key：改 B
        assertThat(putSpec(keyB, specB, relaxRequiredBody()).statusCode())
                .as("前置條件：key 一致時必須放行").isEqualTo(200);

        var audits = awaitConfigChangeOf(specB);
        assertThat(audits)
                .as("成功的變更必須留下恰好一筆紀錄")
                .hasSize(1);
        String detail = audits.get(0);
        assertThat(detail)
                .contains("\"configType\":\"process-variable-spec\"")
                .contains("\"action\":\"update\"")
                .contains("\"processDefinitionKey\":\"" + keyB + "\"")
                .contains("required=true").contains("required=false");

        // 稽核指名的流程底下，被翻掉 required 的必須就是 B。
        // 這一句是把「稽核」與「資料」綁在一起驗，而不是只驗字串。
        var rowsOfB = specRepo.findByProcessDefinitionKeyOrderByVariableName(keyB);
        assertThat(rowsOfB).hasSize(1);
        assertThat(rowsOfB.get(0).getId()).isEqualTo(specB);
        assertThat(rowsOfB.get(0).getRequired())
                .as("稽核指名的流程底下必須真的有那一筆 required 被翻掉").isFalse();

        // 對照組：另一個流程完全沒被碰到。
        assertThat(stateOf(specA))
                .as("另一個流程的規格必須原封不動 —— 這是「只改該改的那一筆」的證據")
                .isEqualTo(aBefore);
        assertThat(settledConfigChangeOf(specA))
                .as("不得替另一個流程留下任何紀錄").isEmpty();
    }

    @Test
    @DisplayName("#85：稽核職能（audit:log:read）查得到那筆紀錄；ROLE_ADMIN 依政策查不到")
    void theChangeIsVisibleToTheAuditFunction() throws Exception {
        String realKey = uniqueKey("T85-visible");
        String specId = givenSpec(realKey, true);
        assertThat(putSpec(realKey, specId, relaxRequiredBody()).statusCode()).isEqualTo(200);
        assertThat(awaitConfigChangeOf(specId)).isNotEmpty();

        // 走真正的查詢 API（而不是只查稽核庫）—— 「有人改變了輸入驗證定義」
        // 這件事必須真的查得到，否則稽核就只是寫下來而已。
        var byAuditor = get("/api/audit-logs?operationType=CONFIG_CHANGE&size=200", AUDITOR);
        assertThat(byAuditor.statusCode())
                .as("dir001 由權限中心持有 audit:log:read").isEqualTo(200);
        assertThat(byAuditor.body())
                .as("查得到的紀錄必須指名那一筆規格")
                .contains(specId).contains("process-variable-spec").contains(realKey);

        // ⚠️ 政策斷言，不是本測試的副作用：
        // 2026-09-29 決定「能管理系統」不等於「能看全公司的簽核紀錄」，
        // 而 admin001 的權限是通配的 * → ROLE_ADMIN。
        assertThat(get("/api/audit-logs?operationType=CONFIG_CHANGE&size=200", ADMIN).statusCode())
                .as("ROLE_ADMIN 刻意不得讀稽核（AuditReadAuthorityTest 有完整涵蓋，"
                        + "這裡只是提醒不要為了讀稽核而換成 admin001）")
                .isEqualTo(403);
    }

    // ── ④ id 不存在 ───────────────────────────────────────────────

    @Test
    @DisplayName("#85：id 不存在 → 404（不是 500；缺陷期間 orElseThrow() 會回 500）")
    void unknownIdIsNotFound() throws Exception {
        // 非空性：同一個 URL 形狀、同一個呼叫者，只換 id，一致時是 200
        // （見 ②）。少了那條，這條的 404 可能來自授權層或路由，
        // 而與 findById 的 orElseThrow 毫無關係。
        String ghost = "no-such-spec-" + UUID.randomUUID();
        String realKey = uniqueKey("T85-ghost");

        var res = putSpec(realKey, ghost, relaxRequiredBody());

        assertThat(res.statusCode())
                .as("缺陷期間這裡是 orElseThrow() → NoSuchElementException → 500，"
                        + "而 500 會讓呼叫端一直重試")
                .isEqualTo(404);
        assertThat(res.statusCode())
                .as("必須是 404 而非 403（授權層）或 401（認證層）")
                .isNotIn(401, 403, 500);
        assertThat(specRepo.findById(ghost))
                .as("被拒的請求不得建立任何資料").isEmpty();
        assertThat(settledConfigChangeOf(ghost))
                .as("不存在的目標不得留下稽核").isEmpty();
    }

    // ── ⑤ 端到端：輸入驗證真的沒有被放寬 ────────────────────────────

    @Test
    @DisplayName("#85：被擋下的冒用不會放寬外部系統的輸入驗證（端到端）")
    void blockedTamperDoesNotRelaxExternalInputValidation() throws Exception {
        // 這是整個缺陷的實質危害所在，而它不在 admin API 裡：
        // required 決定 ExternalApiController.validateVariables 會不會擋。
        // 資料庫斷言證明「欄位沒被翻」，這個測試證明「行為沒被放寬」。
        //
        // 必須用已部署的流程 key（leave-approval），否則 validateVariables
        // 之後的 startProcessInstanceByKey 會先失敗，400 的來源就分辨不出來。
        String specId = givenSpec("leave-approval", true);
        String systemId = uniqueKey("t85-erp");
        givenExternalSystem(systemId, "[\"leave-approval\"]");

        // 前置：規格 required=true，外部系統少給那個變數就必須被擋。
        assertThat(startExternal(systemId))
                .as("前置條件：required=true 時缺少必填變數必須被擋")
                .isEqualTo(400);

        // 冒用：拿 purchase-approval 當路徑 key 去把 required 翻成 false
        assertThat(putSpec("purchase-approval", specId, relaxRequiredBody()).statusCode())
                .as("前置條件：key 與資料列不符必須被擋下").isEqualTo(404);

        assertThat(startExternal(systemId))
                .as("被擋下的冒用不得放寬輸入驗證 —— 這是這個缺陷真正傷害到的東西")
                .isEqualTo(400);

        // 正向對照：同樣的請求，只是路徑 key 正確 → 放行 → 驗證確實被放寬。
        // 少了這一步，前一個 400 可能來自別的原因（例如 body 少了必填欄位），
        // 那就證明不了「是那一筆規格擋的」。
        assertThat(putSpec("leave-approval", specId, relaxRequiredBody()).statusCode())
                .as("key 一致時必須放行").isEqualTo(200);
        assertThat(startExternal(systemId))
                .as("required 被翻成 false 之後，同一個請求就該通過 —— "
                        + "這證明前面的 400 確實來自這一筆規格")
                .isEqualTo(200);
    }

    /**
     * 外部系統發起 leave-approval，刻意<b>不帶</b>那一筆規格的變數。
     *
     * <p>用 MockMvc 而非真實 HTTP：這條斷言的是
     * {@code validateVariables} 的 400（既有測試 {@code ExternalApiTcA04Test}
     * 對同一個端點也是用 MockMvc），而本檔真正需要真實 HTTP 的理由是
     * ERROR dispatch —— 那是 {@code /api/admin/**} 這條路徑的問題。
     */
    private int startExternal(String systemId) throws Exception {
        return mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", systemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"leave-approval\","
                                + "\"businessKey\":\"T85-" + UUID.randomUUID() + "\","
                                + "\"firstTaskAssignee\":\"mgr001\","
                                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}"))
                .andReturn().getResponse().getStatus();
    }
}
