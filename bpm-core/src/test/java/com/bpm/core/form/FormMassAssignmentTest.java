package com.bpm.core.form;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDataRepository;
import com.bpm.core.form.repository.FormDefinitionRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import com.bpm.core.support.UniformTestDsl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * mass-assignment：用 JPA entity／body id 當「新增」的請求（security-audit P0-4）。
 *
 * <p>Spring Data 的 {@code save()} 以 {@code id == null} 判斷 isNew，
 * {@code id} 非 null 時走 {@code em.merge()} → 變成 <b>UPDATE</b>。
 * 因此只要在建立請求的 body 裡夾帶別人的 {@code id}，
 * 「新增」就會變成「覆寫任意資料列」。
 *
 * <p>受害端點兩個：
 * <ul>
 *   <li>{@code POST /api/forms}：改寫已發布表單的 schemaJson，且 create()
 *       會強制把 status 設回 draft → 繞過「只有 draft 能改」的守衛。</li>
 *   <li><b>表單資料</b>：uni-form 重構後 {@code POST /api/form-data} 與
 *       {@code PUT /api/form-data/{id}} 已移除，唯一的寫入路徑是
 *       {@code PUT /api/tasks/{id}} 完成任務時附帶 {@code formData}。
 *       該 payload 的形狀是 {@code {dataJson}} —— <b>沒有 id 欄位</b>；
 *       下面的測試把「body 夾帶 id 也不得覆寫既有列」釘死。</li>
 * </ul>
 *
 * <p>⚠️ 2026-09-29 起 {@code POST /api/forms} 另外需要權限碼
 * {@code bpm:form:design}（或 {@code ROLE_ADMIN}）。這組測試因此顯式宣告
 * {@link #DESIGNER}，並在每個測試裡斷言請求<b>確實被受理</b> ——
 * 否則守衛一旦擋下請求，「資料沒有被改動」這個斷言會在缺陷完全存在時
 * 照樣成立（vacuous），也就是測試對缺陷失感。
 */
class FormMassAssignmentTest extends IntegrationTestBase {

    @Autowired
    private FormDefinitionRepository defRepo;

    @Autowired
    private FormDataRepository dataRepo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 持有 {@code bpm:form:design} 的身分（MockPermController 的 dev fixture）。
     *
     * <p>⚠️ 2026-09-29 起 {@code POST /api/forms} 需要這個權限碼，
     * 而 {@code TestGatewayMockMvcCustomizer} 的預設身分是 user001 ——
     * 刻意選了一個沒有任何權限的人，避免斷言變成空斷言。
     */
    private static final String DESIGNER = "mgr001";

    @Test
    @DisplayName("POST /api/forms 不得以 body 的 id 覆寫既有（尤其是已發布的）表單")
    void createFormCannotOverwriteExisting() throws Exception {
        FormDefinition victim = new FormDefinition();
        victim.setFormKey("victim-form");
        victim.setName("原始表單");
        victim.setVersion(7);
        victim.setStatus("published");
        victim.setSchemaJson(UniformTestDsl.minimal("victim-form"));
        victim = defRepo.save(victim);
        String victimId = victim.getId();

        var res = mockMvc.perform(post("/api/forms")
                .header("X-User-Id", DESIGNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + victimId + "\","
                        + "\"formKey\":\"attacker-form\","
                        + "\"name\":\"被改掉的表單\","
                        + "\"schemaJson\":\"" + UniformTestDsl.embedded(
                                UniformTestDsl.withKeys("attacker-form", "injected")) + "\"}"))
                .andReturn();

        // ⚠️ 非空驗證：先確認請求真的進到了 create()。
        // 少了這一步，下面那些「沒有被改動」的斷言會在守衛把整個請求擋掉時
        // 也一樣成立 —— 測試就再也無法分辨缺陷修好了還是功能被關掉了。
        assertThat(res.getResponse().getStatus())
                .as("前置條件：具備 bpm:form:design 且 DSL 合法時請求應被受理，"
                        + "否則下面的斷言對 mass-assignment 缺陷無感；body=%s",
                        res.getResponse().getContentAsString())
                .isEqualTo(200);

        FormDefinition after = defRepo.findById(victimId).orElseThrow();
        assertThat(after.getSchemaJson())
                .as("既有表單的 schemaJson 不得被建立請求改寫")
                .contains("victim-form").doesNotContain("injected");
        assertThat(after.getStatus())
                .as("已發布的狀態不得被打回 draft —— 那會繞過『只有 draft 能改』的守衛")
                .isEqualTo("published");
        assertThat(after.getVersion()).as("版本不得被重設").isEqualTo(7);
        assertThat(after.getName()).as("名稱不得被改寫").isEqualTo("原始表單");

        // 攻擊者自己的表單必須真的被建立（而不是覆寫）
        assertThat(defRepo.findAll())
                .as("body 的 id 被歸零後應新增一列，attacker-form 必須真的存在")
                .anySatisfy(d -> {
                    assertThat(d.getFormKey()).isEqualTo("attacker-form");
                    assertThat(d.getId()).isNotEqualTo(victimId);
                });
    }

    @Test
    @DisplayName("PUT /api/tasks 的 formData 不得以 body 的 id 覆寫他人已送出的表單資料")
    void taskFormDataCannotOverwriteExisting() throws Exception {
        // 以 formData 啟動一個真實案件：victim 列是「已經送出的原始內容」。
        String pid = startWithFormData("user001", "原始事由");
        FormData victim = dataRepo.findByProcessInstanceIdOrderBySubmittedAtDesc(pid).get(0);
        String victimId = victim.getId();

        // 主管退回 → 補件任務指派給 user001（申請人本人）。
        Task mgr = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(mgr.getId(), Map.of("approved", false, "rejected", false));
        Task revision = taskService.createTaskQuery().processInstanceId(pid).singleResult();

        // ⚠️ 攻擊者刻意用「自己是持有人」的任務：擋下它的必須是 payload 形狀，
        // 不能只是授權（否則這條測試對 mass-assignment 無感）。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "complete");
        payload.put("formData", Map.of(
                "id", victimId,
                "dataJson", "{\"reason\":\"篡改\"}"));

        var res = putJson("/api/tasks/" + revision.getId(), "user001",
                objectMapper.writeValueAsString(payload));

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);

        FormData after = dataRepo.findById(victimId).orElseThrow();
        assertThat(after.getDataJson())
                .as("他人已送出的表單資料不得被任務請求覆寫")
                .contains("原始事由").doesNotContain("篡改");
        assertThat(dataRepo.findByProcessInstanceIdOrderBySubmittedAtDesc(pid))
                .as("語意必須是版本化新增（原始列保留＋補件列），不是就地覆寫")
                .hasSize(2);
    }

    @Test
    @DisplayName("正常建立仍須可用，且伺服器自行產生 id")
    void normalCreateStillWorks() throws Exception {
        String formKey = "brand-new-" + System.nanoTime();
        long before = defRepo.count();
        mockMvc.perform(post("/api/forms")
                        .header("X-User-Id", DESIGNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formKey\":\"" + formKey + "\",\"name\":\"新表單\","
                                + "\"schemaJson\":\"" + UniformTestDsl.minimalEmbedded(formKey) + "\"}"))
                .andExpect(status().isOk());
        assertThat(defRepo.count()).as("應新增一筆").isEqualTo(before + 1);
        assertThat(defRepo.findAll())
                .anySatisfy(d -> assertThat(d.getFormKey()).isEqualTo(formKey));
    }

    // ── 小工具 ──────────────────────────────────────────────────────

    private String startWithFormData(String initiator, String reason) throws Exception {
        Map<String, Object> payload = applicationStartPayload("leave-approval", initiator, objectMapper.writeValueAsString(Map.of(
                        "leaveType", "annual",
                        "dateRange", Map.of("start", "2026-10-01", "end", "2026-10-03"),
                        "reason", reason)));
        var res = mockMvc.perform(post("/api/process-instances")
                        .header("X-User-Id", initiator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"processInstanceId\":\"([^\"]*)\"").matcher(res);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> putJson(String path, String userId, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
