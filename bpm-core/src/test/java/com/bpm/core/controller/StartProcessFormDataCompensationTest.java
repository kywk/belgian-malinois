package com.bpm.core.controller;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #60：跨 DB 啟動的失敗路徑（表單與流程不在同一個資料庫、沒有 XA）。
 *
 * <h2>兩個窗口，兩條測試</h2>
 *
 * <ol>
 *   <li><b>表單寫入失敗</b>（{@link #formSaveFailureRollsBackProcessStart()}）：
 *       表單交易自己回捲、例外往外丟、主交易也回捲 —— 案件不存在。</li>
 *   <li><b>表單已 commit、主交易 commit 才失敗</b>
 *       （{@link #primaryCommitFailureCompensatesFormData()}）：這是順序無法
 *       消除的窗口，靠 {@code afterCompletion(STATUS_ROLLED_BACK)} 補償刪除。</li>
 * </ol>
 *
 * <h2>⚠️ 為什麼用 DB 觸發器而不是 {@code @MockitoSpyBean}（與工單說明的一處差異）</h2>
 *
 * <p>工單建議用 {@code @MockitoBean}／{@code @MockitoSpyBean} 讓
 * {@code FormService.submitData} 拋例外。但 {@code IntegrationTestBase} 用
 * {@code DEFINED_PORT}：bean override 會<b>另起一個 ApplicationContext</b>，
 * 第二個 context 綁不到同一個 port 而啟動失敗
 * （{@code AuditFailClosedTest} 已把這件事記錄下來；實測本工單的第一版就是
 * 這樣紅的：spy 的 context 先綁走 port，一般 context 接著
 * {@code PortInUseException}）。
 *
 * <p>因此改用 repo 既有的等價手法 —— 在真實 DB 上掛 {@code AFTER INSERT}
 * 觸發器直接 {@code THROW}（{@code AuditFailClosedTest} 同型）：走的是與
 * 正式環境相同的例外路徑，且不需要第二個 context。
 *
 * <h2>⚠️ 為什麼補償那條必須查「孤兒列」而不是只驗狀態碼</h2>
 *
 * <p>表單交易在 {@code submitData} 回傳時就 commit 了。若補償沒做，
 * 回應仍是 5xx、案件也確實不存在 —— 只驗這兩件事，把補償整段拿掉照樣全綠。
 * 因此本測試用唯一 marker 查 {@code bpm_form_data}：補償成功時 marker
 * 一列都沒有；拿掉補償時 marker 會留下（負向控制組的紅燈）。
 */
class StartProcessFormDataCompensationTest extends IntegrationTestBase {

    private static final String USER = "user001";
    private static final String LEAVE = "leave-approval";
    private static final String LEAVE_FORM_KEY = "leave-request";

    /** 表單 DB 的測試觸發器：符合 marker 的 insert 直接失敗。 */
    private static final String FORM_FAIL_TRIGGER = "trg_test_form_fail_start60";

    /** 稽核 DB 的測試觸發器：符合 operation_type 的 insert 直接失敗。 */
    private static final String AUDIT_FAIL_TRIGGER = "trg_test_audit_fail_start60";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private FormDefinitionRepository defRepo;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void dropTriggers() {
        dropFormFailTrigger();
        dropAuditFailTrigger();
    }

    // ── 失敗注入（真實 DB 觸發器）─────────────────────────────────

    private static void failFormInsertsWhere(String marker) {
        dropFormFailTrigger();
        withFormConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TRIGGER " + FORM_FAIL_TRIGGER + " ON bpm_form_data AFTER INSERT AS "
                        + "IF EXISTS (SELECT 1 FROM inserted WHERE data_json LIKE N'%" + marker + "%') "
                        + "THROW 50000, N'模擬表單 DB 寫入失敗', 1;");
            }
        });
    }

    private static void dropFormFailTrigger() {
        withFormConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("IF OBJECT_ID('" + FORM_FAIL_TRIGGER + "', 'TR') IS NOT NULL "
                        + "DROP TRIGGER " + FORM_FAIL_TRIGGER);
            }
        });
    }

    private static void failAuditInsertsWhere(String operationType) {
        dropAuditFailTrigger();
        withAuditConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TRIGGER " + AUDIT_FAIL_TRIGGER + " ON bpm_audit_log AFTER INSERT AS "
                        + "IF EXISTS (SELECT 1 FROM inserted WHERE operation_type = '" + operationType + "') "
                        + "THROW 50000, N'模擬稽核 DB 寫入失敗', 1;");
            }
        });
    }

    private static void dropAuditFailTrigger() {
        withAuditConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("IF OBJECT_ID('" + AUDIT_FAIL_TRIGGER + "', 'TR') IS NOT NULL "
                        + "DROP TRIGGER " + AUDIT_FAIL_TRIGGER);
            }
        });
    }

    // ── HTTP／資料查詢小工具 ───────────────────────────────────────

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/process-instances"))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", USER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String startBody(String formDefinitionId, Map<String, Object> values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("processDefinitionKey", LEAVE);
        payload.put("formData", Map.of(
                "formDefinitionId", formDefinitionId,
                "dataJson", objectMapper.writeValueAsString(values)));
        return objectMapper.writeValueAsString(payload);
    }

    private FormDefinition leaveRequest() {
        return defRepo.findLatestPublished(LEAVE_FORM_KEY)
                .orElseThrow(() -> new AssertionError("seed 的 leave-request 定義不存在"));
    }

    private long countRunning(String processDefinitionKey) {
        return runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(processDefinitionKey).count();
    }

    /** 用 dataJson 裡的唯一 marker 查列數 —— 補償成功時必須是 0。 */
    private int formRowsWithMarker(String marker) {
        int[] rows = {-1};
        withFormConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_form_data WHERE data_json LIKE ?")) {
                ps.setString(1, "%" + marker + "%");
                var rs = ps.executeQuery();
                rs.next();
                rows[0] = rs.getInt(1);
            }
        });
        return rows[0];
    }

    private static long auditRowCount() {
        long[] out = {-1L};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log")) {
                var rs = ps.executeQuery();
                rs.next();
                out[0] = rs.getLong(1);
            }
        });
        return out[0];
    }

    private static Map<String, Object> leaveValues(String reason) {
        return Map.of("leaveType", "annual",
                "dateRange", "2026-10-01~2026-10-03",
                "reason", reason);
    }

    // ── ① 表單寫入失敗 → 兩邊都不留 ───────────────────────────────

    @Test
    @DisplayName("#60：表單 insert 失敗 → 500，且引擎裡沒有新案件、沒有表單列、沒有稽核")
    void formSaveFailureRollsBackProcessStart() throws Exception {
        FormDefinition def = leaveRequest();
        String marker = "form-fail-" + UUID.randomUUID();
        failFormInsertsWhere(marker);

        long instancesBefore = countRunning(LEAVE);
        long auditBefore = auditRowCount();

        var res = post(startBody(def.getId(), leaveValues(marker)));

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(500);
        assertThat(countRunning(LEAVE))
                .as("表單寫入失敗時主交易必須回捲 —— 案件不得存在")
                .isEqualTo(instancesBefore);
        assertThat(formRowsWithMarker(marker))
                .as("form 交易自己回捲，不得留下資料列").isZero();
        assertThat(auditRowCount())
                .as("主交易回捲時稽核（beforeCommit）不得落地").isEqualTo(auditBefore);
    }

    // ── ② 表單已 commit、主交易 commit 才失敗 → 補償刪除 ─────────

    @Test
    @DisplayName("#60：表單已 commit 而主交易 commit 失敗 → afterCompletion 補償刪除該列")
    void primaryCommitFailureCompensatesFormData() throws Exception {
        // 讓稽核寫入在 commit 當下失敗（beforeCommit）。這是「表單寫入成功
        // 之後主交易才失敗」的可控版本；真正的 commit 失敗（連線中斷等）
        // 無法在整合測試裡安全製造。
        // AuditWriteException 是 RuntimeException → Spring 走
        // doRollbackOnCommitException → processRollback
        // → afterCompletion(STATUS_ROLLED_BACK)（實測 Spring 7 的 processCommit）。
        failAuditInsertsWhere("PROCESS_START");

        FormDefinition def = leaveRequest();
        String marker = "compensation-" + UUID.randomUUID();
        long instancesBefore = countRunning(LEAVE);
        assertThat(formRowsWithMarker(marker)).as("前置條件：marker 還不存在").isZero();

        var res = post(startBody(def.getId(), leaveValues(marker)));

        assertThat(res.statusCode())
                .as("稽核寫入失敗是 503（fail-closed）；body=%s", res.body()).isEqualTo(503);
        assertThat(countRunning(LEAVE))
                .as("主交易失敗時案件不得存在").isEqualTo(instancesBefore);
        assertThat(formRowsWithMarker(marker))
                .as("表單交易已 commit，主交易回滾時必須由補償刪除 —— "
                        + "留下來就是「有表單、沒有案件」的孤兒列")
                .isZero();
    }
}
