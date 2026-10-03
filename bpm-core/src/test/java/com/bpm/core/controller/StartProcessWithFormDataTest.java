package com.bpm.core.controller;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #60：流程啟動完整流程（前端提交 → form-data 儲存 → variables 設定 →
 * 流程啟動 → formVersion 鎖定）。
 *
 * <h2>這個測試組在防什麼</h2>
 *
 * <p>改動前前端只送 {@code variables}，表單資料完全沒有落地：案件跑得起來，
 * 但 {@code bpm_form_data} 永遠是空的，審結後「當時填了什麼」只剩流程變數
 * （結案時被 Flowable 清除）。本測試組釘住新契約的四個後果：
 *
 * <ol>
 *   <li><b>200 且回應同時帶 {@code processInstanceId} 與 {@code formDataId}</b>。</li>
 *   <li><b>form DB 真的有一列</b>，且 {@code process_instance_id}／
 *       {@code form_definition_id}／{@code submitted_by} 都正確
 *       —— 只驗狀態碼證明不了落地。</li>
 *   <li><b>表單欄位推導成流程變數</b>（spec §8.5：欄位 id == 變數名），
 *       {@code initiator} 仍是登入者。</li>
 *   <li><b>{@code _formVersions} 已由 FormVersionLocker 鎖定</b>。</li>
 * </ol>
 *
 * <p>拒絕路徑一律驗「零副作用」：400 時案件數、表單列數、稽核筆數全部不變。
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>與 {@code ProcessStartIdentityTest} 同一理由：MockMvc 不做 error dispatch，
 * {@code ResponseStatusException} 的狀態碼是容器轉出來的；用 MockMvc 寫，
 * {@code SecurityConfig} 的 ERROR dispatch 規則壞掉時測試照樣全綠。
 *
 * <h2>⚠️ formDefinitionId 的 id／formKey 解析（與設計說明的一處差異）</h2>
 *
 * <p>設計說明裡前端送的是 {@code 'leave-request'}，而
 * {@code FormData.formDefinitionId} 的語意是<b>定義資料列的 id</b>（seed 用
 * {@code NEWID()}，是 UUID）。若把 formKey 直接當 id：validator 的
 * {@code findById} 查不到 → 依 #55 的已知缺口<b>整包 dataJson 靜默略過驗證</b>，
 * 落地的那一列也不指向任何定義（封存守衛等以 id 為條件的查詢全部落空）。
 * 因此 {@code ProcessController} 先當 id 查、查不到再當 formKey 查最新
 * published（見 {@code FormService.findDefinitionByIdOrKey}）：
 * {@link #formKeyFromFrontendIsResolvedToDefinitionId()} 就是這條路徑的守門測試。
 * 若 PM 要嚴格照原設計（不解析、接受靜默略過），拿掉解析後這條會紅。
 */
class StartProcessWithFormDataTest extends IntegrationTestBase {

    private static final String USER = "user001";
    private static final String LEAVE = "leave-approval";
    private static final String LEAVE_FORM_KEY = "leave-request";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private FormDefinitionRepository defRepo;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

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

    /** 組出「啟動 leave-approval + 帶 formData」的請求 body。 */
    private String startBody(String formDefinitionId, Map<String, Object> values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("processDefinitionKey", LEAVE);
        payload.put("formData", Map.of(
                "formDefinitionId", formDefinitionId,
                "dataJson", objectMapper.writeValueAsString(values)));
        return objectMapper.writeValueAsString(payload);
    }

    private static Map<String, Object> leaveValues(String reason) {
        return Map.of("leaveType", "annual",
                "dateRange", "2026-10-01~2026-10-03",
                "reason", reason);
    }

    private FormDefinition leaveRequest() {
        return defRepo.findLatestPublished(LEAVE_FORM_KEY)
                .orElseThrow(() -> new AssertionError("seed 的 leave-request 定義不存在"));
    }

    private String field(String json, String name) {
        var node = objectMapper.readTree(json).path(name);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private long countRunning(String processDefinitionKey) {
        return runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(processDefinitionKey).count();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> lockedFormVersions(String pid) {
        Object v = runtimeService.getVariable(pid, "_formVersions");
        assertThat(v).as("實例啟動時必須由 FormVersionLocker 寫入 _formVersions")
                .isInstanceOf(Map.class);
        return (Map<String, Integer>) v;
    }

    /** 某定義 id 在 form DB 的列數（拒絕路徑的「零副作用」計數）。 */
    private int formRowCount(String formDefinitionId) {
        int[] rows = {-1};
        withFormConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_form_data WHERE form_definition_id = ?")) {
                ps.setString(1, formDefinitionId);
                var rs = ps.executeQuery();
                rs.next();
                rows[0] = rs.getInt(1);
            }
        });
        return rows[0];
    }

    /** 一列表單資料的 [processInstanceId, formDefinitionId, submittedBy, dataJson]；查不到回 null 元素。 */
    private String[] formRow(String formDataId) {
        String[] out = new String[4];
        withFormConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT process_instance_id, form_definition_id, submitted_by, data_json "
                            + "FROM bpm_form_data WHERE id = ?")) {
                ps.setString(1, formDataId);
                var rs = ps.executeQuery();
                if (rs.next()) {
                    out[0] = rs.getString(1);
                    out[1] = rs.getString(2);
                    out[2] = rs.getString(3);
                    out[3] = rs.getString(4);
                }
            }
        });
        return out;
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

    // ── ① 正常路徑：全部落地 ──────────────────────────────────────

    @Test
    @DisplayName("#60：帶 formData 啟動 → 200、formDataId、表單落地、變數推導、_formVersions 鎖定")
    void formDataIsPersistedAndDerivedIntoVariables() throws Exception {
        FormDefinition def = leaveRequest();
        String marker = "請假理由-" + UUID.randomUUID();
        long before = countRunning(LEAVE);

        var res = post(startBody(def.getId(), leaveValues(marker)));

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        String pid = field(res.body(), "processInstanceId");
        String formDataId = field(res.body(), "formDataId");
        assertThat(pid).isNotBlank();
        assertThat(formDataId).as("帶 formData 時回應必須附 formDataId").isNotBlank();

        // 表單欄位推導成流程變數（spec §8.5：欄位 id == 變數名）。
        assertThat(runtimeService.getVariable(pid, "leaveType")).isEqualTo("annual");
        assertThat(runtimeService.getVariable(pid, "dateRange")).isEqualTo("2026-10-01~2026-10-03");
        assertThat(runtimeService.getVariable(pid, "reason")).isEqualTo(marker);
        assertThat(runtimeService.getVariable(pid, "initiator"))
                .as("表單推導不得蓋掉 server 寫入的 initiator").isEqualTo(USER);

        assertThat(lockedFormVersions(pid)).containsEntry(LEAVE_FORM_KEY, def.getVersion());

        // form DB 真的有一列，四個關鍵欄位都正確。
        String[] row = formRow(formDataId);
        assertThat(row[0]).as("processInstanceId 必須是新案件").isEqualTo(pid);
        assertThat(row[1]).as("formDefinitionId 必須指向真正的定義 id").isEqualTo(def.getId());
        assertThat(row[2]).as("submittedBy 由 @CallerId 決定").isEqualTo(USER);
        assertThat(row[3]).contains(marker);

        assertThat(countRunning(LEAVE)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("#60：前端只送 formKey（'leave-request'）→ 解析成定義 id 後驗證與落地")
    void formKeyFromFrontendIsResolvedToDefinitionId() throws Exception {
        FormDefinition def = leaveRequest();

        var res = post(startBody(LEAVE_FORM_KEY, leaveValues("前端只送 formKey")));

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        String formDataId = field(res.body(), "formDataId");
        assertThat(formRow(formDataId)[1])
                .as("form_definition_id 必須是解析後的定義 id，而不是 'leave-request'"
                        + "（後者會讓 schema 驗證與封存守衛全部落空）")
                .isEqualTo(def.getId());
    }

    // ── ② 拒絕路徑：400 且零副作用 ────────────────────────────────

    @Test
    @DisplayName("#60：schema 違規（缺必填／型別錯）→ 400 指名欄位，零副作用")
    void schemaViolationIsRejectedWithoutSideEffects() throws Exception {
        FormDefinition def = leaveRequest();
        long instancesBefore = countRunning(LEAVE);
        long auditBefore = auditRowCount();
        int rowsBefore = formRowCount(def.getId());

        // (a) 必填缺漏：前端未填事由時送的就是空字串。
        var missing = post(startBody(def.getId(),
                Map.of("leaveType", "annual", "dateRange", "2026-10-01~2026-10-03", "reason", "")));
        assertThat(missing.statusCode()).as("body=%s", missing.body()).isEqualTo(400);
        assertThat(missing.body()).contains("reason", "必填");

        // (b) 型別不符。
        var wrongType = post(startBody(def.getId(),
                Map.of("leaveType", 123, "dateRange", "2026-10-01~2026-10-03", "reason", "x")));
        assertThat(wrongType.statusCode()).as("body=%s", wrongType.body()).isEqualTo(400);
        assertThat(wrongType.body()).contains("leaveType", "select");

        assertThat(countRunning(LEAVE)).as("被拒的請求不得留下案件").isEqualTo(instancesBefore);
        assertThat(formRowCount(def.getId())).as("被拒的請求不得留下表單資料").isEqualTo(rowsBefore);
        assertThat(auditRowCount()).as("被拒的請求不得留下稽核").isEqualTo(auditBefore);
    }

    @Test
    @DisplayName("#60：同一欄位同時出現在 variables 與 formData → 400，不得靜默選一邊")
    void overlappingVariablesAndFormDataAreRejected() throws Exception {
        FormDefinition def = leaveRequest();
        long before = countRunning(LEAVE);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("processDefinitionKey", LEAVE);
        payload.put("variables", Map.of("leaveType", "personal"));
        payload.put("formData", Map.of("formDefinitionId", def.getId(),
                "dataJson", objectMapper.writeValueAsString(leaveValues("重疊"))));

        var res = post(objectMapper.writeValueAsString(payload));

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(400);
        assertThat(res.body()).contains("leaveType");
        assertThat(countRunning(LEAVE)).as("被拒的請求不得留下案件").isEqualTo(before);
    }

    @Test
    @DisplayName("#60：formData 夾帶 initiator／onBehalfOf／_ 前綴 → 400 指名變數，零副作用")
    void protectedNamesInFormDataAreRejected() throws Exception {
        long before = countRunning(LEAVE);
        // ⚠️ 用查不到的 formDefinitionId：schema 驗證會依 #55 的已知缺口略過，
        // 因此這條測的是「推導變數的保護名單」本身。若用真實定義，未知欄位
        // 會先被 schema 擋下，保護名單就沒有被單獨證明到。
        String bogus = "no-such-definition-" + UUID.randomUUID();

        for (String name : List.of("initiator", "onBehalfOf", "_formVersions", "_externalSystemId")) {
            var res = post(startBody(bogus, Map.of(name, "injected")));

            assertThat(res.statusCode())
                    .as("formData 夾帶 %s 必須 400；body=%s", name, res.body()).isEqualTo(400);
            assertThat(res.body()).contains(name);
            assertThat(countRunning(LEAVE))
                    .as("夾帶 %s 被拒時不得留下案件", name).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("#60：formData 形狀不完整（缺 dataJson）→ 400，零副作用")
    void incompleteFormDataIsRejected() throws Exception {
        long before = countRunning(LEAVE);

        var res = post("{\"processDefinitionKey\":\"" + LEAVE + "\","
                + "\"formData\":{\"formDefinitionId\":\"" + LEAVE_FORM_KEY + "\"}}");

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(400);
        assertThat(res.body()).contains("formData");
        assertThat(countRunning(LEAVE)).as("被拒的請求不得留下案件").isEqualTo(before);
    }

    // ── ③ 既有呼叫端不受影響 ──────────────────────────────────────

    @Test
    @DisplayName("#60：只帶 variables 的既有呼叫端行為不變，回應不含 formDataId")
    void variablesOnlyCallersAreUnaffected() throws Exception {
        var res = post("{\"processDefinitionKey\":\"" + LEAVE + "\","
                + "\"businessKey\":\"legacy-" + UUID.randomUUID() + "\","
                + "\"variables\":{\"leaveType\":\"personal\",\"days\":2}}");

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        assertThat(res.body()).as("沒有 formData 時回應不該出現 formDataId")
                .doesNotContain("formDataId");
        String pid = field(res.body(), "processInstanceId");
        assertThat(runtimeService.getVariable(pid, "leaveType")).isEqualTo("personal");
        assertThat(runtimeService.getVariable(pid, "initiator")).isEqualTo(USER);
    }
}
