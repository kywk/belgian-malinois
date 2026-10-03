package com.bpm.core.form;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import tools.jackson.databind.ObjectMapper;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #55：{@code POST /api/form-data} 的 schema 驗證接線（真實 DB／真實 HTTP）。
 *
 * <h2>這個測試組在防什麼</h2>
 *
 * <p>規則本身由 {@code FormSchemaValidatorTest}（純單元）逐條釘住；這裡只驗
 * 整合測試才能證明的事：
 * <ol>
 *   <li>違規真的回 400，且訊息指名欄位（走 ERROR dispatch 後仍在，
 *       因此用真實 HTTP —— 理由與 {@code ErrorDispatchTest} 相同）。</li>
 *   <li><b>零副作用</b>：違規時沒有資料列、沒有 {@code FORM_SUBMIT} 稽核。
 *       只驗狀態碼證明不了這件事 —— 回 400 卻照樣寫入是比沒有守衛更糟的狀態。</li>
 *   <li>合法資料仍可送出（可用性那一半），且稽核真的落地。</li>
 * </ol>
 *
 * <h2>⚠️ 每個測試用自己的 formKey／案件</h2>
 *
 * <p>容器共用（見 {@link IntegrationTestBase}），因此 formKey 帶 UUID、
 * 副作用計數一律以本次的 processInstanceId 為範圍。
 */
class FormSchemaValidationTest extends IntegrationTestBase {

    /** 送出者與案件發起人 —— leave-approval 的參與者。 */
    private static final String USER = "user001";

    private static final String SCHEMA = """
            {"formKey":"schema-validation-test","version":1,"mode":"edit","fields":[
              {"id":"itemName","type":"text","required":true},
              {"id":"quantity","type":"number","required":true},
              {"id":"reason","type":"textarea","required":true}
            ]}""";

    @Autowired
    private FormDefinitionRepository defRepo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    // ── 前置工具 ──────────────────────────────────────────────────

    private FormDefinition publishedDefinition(String schemaJson) {
        FormDefinition def = new FormDefinition();
        def.setFormKey("schema-validation-" + UUID.randomUUID());
        def.setName("Schema 驗證測試表");
        def.setVersion(1);
        def.setStatus("published");
        def.setCreatedBy(USER);
        def.setSchemaJson(schemaJson);
        return defRepo.save(def);
    }

    private String startCase() {
        return runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", USER, "leaveType", "annual", "days", 1)).getId();
    }

    /** 走真實 HTTP 送出（見類別註解：MockMvc 不做 ERROR dispatch）。 */
    private HttpResponse<String> submit(String formDefinitionId, String pid, String dataJson)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formDefinitionId", formDefinitionId);
        payload.put("processInstanceId", pid);
        payload.put("dataJson", dataJson);
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/form-data"))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", USER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private int rowCount(String pid) {
        int[] rows = {-1};
        withFormConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_form_data WHERE process_instance_id = ?")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                rs.next();
                rows[0] = rs.getInt(1);
            }
        });
        return rows[0];
    }

    private int formSubmitAuditCount(String pid) {
        int[] count = {-1};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log "
                    + "WHERE operation_type = 'FORM_SUBMIT' AND process_instance_id = ?")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                rs.next();
                count[0] = rs.getInt(1);
            }
        });
        return count[0];
    }

    /** 稽核寫入掛在交易的 beforeCommit，容許短暫延遲（同 FormDataAuthorizationTest）。 */
    private int awaitFormSubmitAuditCount(String pid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        int count = 0;
        while (System.currentTimeMillis() < deadline && count == 0) {
            count = formSubmitAuditCount(pid);
            if (count == 0) Thread.sleep(100);
        }
        return count;
    }

    /** 違規的共同後果：400／500、零資料列、零 FORM_SUBMIT 稽核。 */
    private void assertRejectedWithoutSideEffects(HttpResponse<String> res, String pid,
                                                  int expectedStatus, String... bodyFragments) {
        assertThat(res.statusCode())
                .as("預期 %d，實際 %d；body=%s", expectedStatus, res.statusCode(), res.body())
                .isEqualTo(expectedStatus);
        assertThat(res.body()).contains(bodyFragments);
        assertThat(rowCount(pid)).as("違規時不得留下資料列").isZero();
        assertThat(formSubmitAuditCount(pid)).as("違規時不得寫 FORM_SUBMIT 稽核").isZero();
    }

    // ── ① 合法資料放行（可用性）───────────────────────────────────

    @Test
    @DisplayName("#55：合法資料 → 200，資料列與 FORM_SUBMIT 稽核都落地")
    void validDataIsAcceptedAndPersisted() throws Exception {
        FormDefinition def = publishedDefinition(SCHEMA);
        String pid = startCase();

        var res = submit(def.getId(), pid,
                "{\"itemName\":\"筆電\",\"quantity\":2,\"reason\":\"汰換\"}");

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        assertThat(rowCount(pid)).isEqualTo(1);
        assertThat(awaitFormSubmitAuditCount(pid))
                .as("通過驗證的送出必須照常寫稽核（驗證不是靜默丟棄）")
                .isEqualTo(1);
    }

    // ── ② 違規 → 400，指名欄位，零副作用 ─────────────────────────

    @Test
    @DisplayName("#55：required 缺漏 → 400 且訊息指名欄位，零副作用")
    void missingRequiredFieldIsRejectedWithFieldName() throws Exception {
        FormDefinition def = publishedDefinition(SCHEMA);
        String pid = startCase();

        var res = submit(def.getId(), pid, "{\"quantity\":2,\"reason\":\"汰換\"}");

        assertRejectedWithoutSideEffects(res, pid, 400, "itemName", "必填");
    }

    @Test
    @DisplayName("#55：型別不符 → 400 且訊息指名欄位，零副作用")
    void typeMismatchIsRejectedWithFieldName() throws Exception {
        FormDefinition def = publishedDefinition(SCHEMA);
        String pid = startCase();

        var res = submit(def.getId(), pid,
                "{\"itemName\":\"筆電\",\"quantity\":\"兩台\",\"reason\":\"汰換\"}");

        assertRejectedWithoutSideEffects(res, pid, 400, "quantity", "number");
    }

    @Test
    @DisplayName("#55：未知欄位 → 400 且訊息指名欄位，零副作用")
    void unknownFieldIsRejectedWithFieldName() throws Exception {
        FormDefinition def = publishedDefinition(SCHEMA);
        String pid = startCase();

        // 欄位 id == 流程變數名（spec §8.5）：salary 不在 schema，不得被接受。
        var res = submit(def.getId(), pid,
                "{\"itemName\":\"筆電\",\"quantity\":2,\"reason\":\"汰換\",\"salary\":\"95000\"}");

        assertRejectedWithoutSideEffects(res, pid, 400, "salary", "不在表單 schema");
    }

    @Test
    @DisplayName("#55：多個違規一次回報，每個欄位都被指名")
    void multipleViolationsAreAllNamed() throws Exception {
        FormDefinition def = publishedDefinition(SCHEMA);
        String pid = startCase();

        var res = submit(def.getId(), pid, "{\"salary\":\"95000\"}");

        assertRejectedWithoutSideEffects(res, pid, 400,
                "itemName", "quantity", "reason", "salary");
    }

    // ── ③ schemaJson 毀損 → 500（fail-closed）─────────────────────

    @Test
    @DisplayName("#55：schemaJson 無法解析 → 500，零副作用（不寫入未經驗證的資料）")
    void unreadableSchemaFailsClosed() throws Exception {
        FormDefinition def = publishedDefinition("{not json");
        String pid = startCase();

        var res = submit(def.getId(), pid,
                "{\"itemName\":\"筆電\",\"quantity\":2,\"reason\":\"汰換\"}");

        assertRejectedWithoutSideEffects(res, pid, 500, "schemaJson");
    }

    // ── ④ 查不到定義 → 略過（已知缺口，刻意釘住現況）──────────────

    @Test
    @DisplayName("#55：formDefinitionId 查不到定義 → 略過驗證（已知缺口）")
    void unresolvedFormDefinitionSkipsValidationKnownGap() throws Exception {
        String pid = startCase();

        var res = submit("no-such-definition-id", pid,
                "{\"anything\":\"goes\",\"quantity\":\"not-a-number\"}");

        // 這條測試記錄的是「目前」的行為，不是理想行為：不存在的 id 讓整包
        // dataJson 跳過驗證（見 FormSchemaValidator 類別註解第 3 點）。
        // 未來若在 submit 路徑要求 formDefinitionId 必須指向真實定義，
        // 這條會變紅，強迫重新裁決 —— 那正是它存在的目的。
        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        assertThat(rowCount(pid)).isEqualTo(1);
    }
}
