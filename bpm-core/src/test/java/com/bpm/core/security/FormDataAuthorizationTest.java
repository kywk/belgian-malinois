package com.bpm.core.security;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.repository.FormDataRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /api/form-data} 與任務表單提交的授權與身分
 * （uni-form 重構後改寫自 #72／#58 的 {@code POST}／{@code PUT /api/form-data} 版本）。
 *
 * <h2>入口變了，政策沒變</h2>
 *
 * <p>uni-form 重構移除了 {@code POST /api/form-data} 與
 * {@code PUT /api/form-data/{id}}：表單資料一律隨流程動作落地
 * （{@code POST /api/process-instances}／{@code PUT /api/tasks/{id}} 的
 * {@code formData}）。因此本檔測的是：
 *
 * <ul>
 *   <li><b>讀端（{@code GET /api/form-data/{pid}}）</b>：關係人 ∪
 *       {@code audit:log:read}（旁路每次留痕），非關係人 404、
 *       不認 {@code ROLE_ADMIN}。政策與 #72 逐字相同。</li>
 *   <li><b>寫端</b>：不再有「body 指定 processInstanceId／submittedBy」的欄位，
 *       而是任務層守衛（{@code TaskHolderGuard}）＋ {@code @CallerId}。
 *       本檔把「非持有人 404、身分欄位不可由 body 冒用」釘住。</li>
 * </ul>
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：{@code ResponseStatusException} 會被
 * 容器轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@code ErrorDispatchTest}）。用 MockMvc 寫，這些測試在缺陷存在時
 * 會照樣全綠。
 *
 * <h2>每個守衛測試都驗「資料真的沒變」而不只看狀態碼</h2>
 *
 * <p>被拒絕的寫入如果回 404 卻仍然改了資料，那這個修補只是把攻擊從
 * 「看得到」變成「看不到錯誤訊息」。
 */
class FormDataAuthorizationTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private FormDataRepository dataRepo;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 確定沒有出現在任何既有測試資料裡的身分。
     *
     * <p>測試共用同一組容器，所以「某某看不到這張單」可能因為<b>別的測試</b>
     * 讓他變成參與者而失效。見 {@link ReadEndpointAuthorizationTest} 的同型說明。
     */
    private static final String OUTSIDER = "outsider001";

    /** 持有 {@code audit:log:read} 的稽核職能使用者（權限中心 fixture）。 */
    private static final String AUDITOR = "dir001";

    /** 持有通配權限 {@code *} —— 依政策<b>不</b>等於有 audit:log:read。 */
    private static final String ADMIN = "admin001";

    private static final String ORIGINAL = "{\"salary\":\"95000\"}";
    private static final String TAMPERED = "{\"salary\":\"9999999\"}";

    // ── HTTP 小工具（走真實 HTTP，見類別註解）───────────────────────

    private HttpResponse<String> send(String method, String path, String userId, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        return send("GET", path, userId, null);
    }

    private HttpResponse<String> put(String path, String userId, String body) throws Exception {
        return send("PUT", path, userId, body);
    }

    private String startCase(String initiator) {
        return runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", initiator, "_formVersions", Map.of("leave-request", 1), "leaveType", "annual", "days", 1)).getId();
    }

    /** 直接種一筆表單資料（讀端測試用；寫端一律走任務完成）。 */
    private String seedFormData(String pid, String submittedBy) {
        FormData data = new FormData();
        data.setFormDefinitionId("leave-request");
        data.setProcessInstanceId(pid);
        data.setSubmittedBy(submittedBy);
        data.setDataJson(ORIGINAL);
        return dataRepo.save(data).getId();
    }

    /** 主管退回 → 補件任務（指派給申請人）。 */
    private Task returnToApplicant(String pid) {
        Task mgr = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(mgr.getId(), Map.of("approved", false, "rejected", false));
        return taskService.createTaskQuery().processInstanceId(pid).singleResult();
    }

    private String revisionBody(String dataJson) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "complete");
        payload.put("formData", Map.of("dataJson", dataJson));
        return objectMapper.writeValueAsString(payload);
    }

    private String dataJsonOf(String recordId) {
        return dataRepo.findById(recordId).orElseThrow().getDataJson();
    }

    private int rowCountOf(String pid) {
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

    private static int dataAccessCountOf(String operatorId) {
        var count = new AtomicInteger(-1);
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log "
                    + "WHERE operator_id = ? AND operation_type = 'DATA_ACCESS'")) {
                ps.setString(1, operatorId);
                var rs = ps.executeQuery();
                rs.next();
                count.set(rs.getInt(1));
            }
        });
        return count.get();
    }

    private static java.util.List<String> dataAccessDetailsOf(String operatorId) {
        var details = new ArrayList<String>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operator_id = ? AND operation_type = 'DATA_ACCESS' ORDER BY id")) {
                ps.setString(1, operatorId);
                var rs = ps.executeQuery();
                while (rs.next()) details.add(rs.getString(1));
            }
        });
        return details;
    }

    /** 稽核寫入掛在交易的 beforeCommit，容許短暫延遲。 */
    private static String awaitFormOperator(String operationType, String formDataId)
            throws InterruptedException {
        var out = new AtomicReference<String>();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && out.get() == null) {
            withAuditConnection(c -> {
                try (var ps = c.prepareStatement("SELECT TOP 1 operator_id FROM bpm_audit_log "
                        + "WHERE operation_type = ? AND detail LIKE ?")) {
                    ps.setString(1, operationType);
                    ps.setString(2, "%" + formDataId + "%");
                    var rs = ps.executeQuery();
                    if (rs.next()) out.set(rs.getString(1));
                }
            });
            if (out.get() == null) Thread.sleep(100);
        }
        return out.get();
    }

    // ── ① GET：物件層授權 ──────────────────────────────────────────

    @Test
    @DisplayName("#72：非參與者讀他人案件的表單資料 → 404（不是 403）")
    void formDataOfAnotherPersonsCaseIsNotFound() throws Exception {
        String pid = startCase("user001");
        seedFormData(pid, "user001");

        var res = get("/api/form-data/" + pid, OUTSIDER);

        assertThat(res.statusCode())
                .as("403 會確認「這個案件存在」，對可枚舉的 id 等於把枚舉管道留著")
                .isEqualTo(404);
        assertThat(res.body())
                .as("薪資等級的資料任何登入者都能讀，是這個端點最嚴重的後果")
                .doesNotContain("95000");
    }

    @Test
    @DisplayName("#72：申請人讀自己案件的表單資料 → 200 且拿得到內容")
    void initiatorCanReadOwnFormData() throws Exception {
        String pid = startCase("user001");
        String recordId = seedFormData(pid, "user001");

        var res = get("/api/form-data/" + pid, "user001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(recordId).contains("95000");
    }

    @Test
    @DisplayName("#72：稽核旁路（audit:log:read）可讀他人案件的表單資料，且每次留痕")
    void auditorCanReadFormDataAndItIsRecorded() throws Exception {
        truncateAuditLog();
        String pid = startCase("user001");
        seedFormData(pid, "user001");

        var res = get("/api/form-data/" + pid, AUDITOR);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("95000");
        assertThat(dataAccessDetailsOf(AUDITOR))
                .as("稽核旁路必須每次留痕，否則「誰調閱了哪些案件」無從追查")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"auditBypass\":true"));
    }

    @Test
    @DisplayName("#72：參與者讀自己的表單資料不產生 DATA_ACCESS（那是日常操作）")
    void participantReadIsNotRecordedAsBypass() throws Exception {
        truncateAuditLog();
        String pid = startCase("user001");
        String recordId = seedFormData(pid, "user001");
        assertThat(dataAccessCountOf("user001")).isZero();

        assertThat(get("/api/form-data/" + pid, "user001").statusCode()).isEqualTo(200);

        assertThat(dataAccessCountOf("user001"))
                .as("讀表單是熱路徑，參與者讀到的正是他自己填的值；只記旁路")
                .isZero();
        assertThat(dataJsonOf(recordId)).isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("#72：持有 ROLE_ADMIN 但沒有 audit:log:read 的人不得讀他人案件的表單資料")
    void adminWildcardIsNotAnAuditBypass() throws Exception {
        String pid = startCase("user001");
        seedFormData(pid, "user001");

        // 「能管理系統」與「能看全公司的薪資單」是不同的權責。
        // 與稽核旁路那條必須成組存在 —— 少了它，上一條可以靠
        // 「把旁路放寬給管理員」達成，而那正是繞過旁路限制的樣態。
        var res = get("/api/form-data/" + pid, ADMIN);

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).doesNotContain("95000");
    }

    // ── ② 寫端：任務層守衛（表單資料的唯一入口）─────────────────────

    @Test
    @DisplayName("#77：非持有人帶 formData 完成他人任務 → 404，資料零副作用")
    void nonHolderCannotSubmitFormData() throws Exception {
        String pid = startCase("user001");
        seedFormData(pid, "user001");
        Task revision = returnToApplicant(pid);
        int rowsBefore = rowCountOf(pid);

        var res = put("/api/tasks/" + revision.getId(), OUTSIDER,
                revisionBody("{\"reason\":\"" + TAMPERED.replace("\"", "\\\"") + "\"}"));

        assertThat(res.statusCode())
                .as("任務層守衛（TaskHolderGuard）必須先於任何表單處理")
                .isEqualTo(404);
        assertThat(rowCountOf(pid)).as("被拒的請求不得在他人案件底下留下任何一列")
                .isEqualTo(rowsBefore);
        assertThat(taskService.createTaskQuery().taskId(revision.getId()).count())
                .as("被拒的請求不得完成任務").isEqualTo(1);
    }

    @Test
    @DisplayName("#72：稽核旁路是唯讀的：持有 audit:log:read 也不能完成他人任務")
    void auditorCannotWrite() throws Exception {
        String pid = startCase("user001");
        seedFormData(pid, "user001");
        Task revision = returnToApplicant(pid);

        var res = put("/api/tasks/" + revision.getId(), AUDITOR, revisionBody("{\"reason\":\"x\"}"));

        assertThat(res.statusCode())
                .as("稽核人員的職責是查閱，不是替案件補件")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("#77：不存在的任務 id → 404（與「不是你的」不可分辨）")
    void unknownTaskIsNotFound() throws Exception {
        var res = put("/api/tasks/no-such-task-id", "user001", revisionBody("{\"reason\":\"x\"}"));

        assertThat(res.statusCode()).isEqualTo(404);
    }

    // ── ③ 身分欄位：submittedBy 一律由 @CallerId 決定 ────────────────

    @Test
    @DisplayName("formData 夾帶 submittedBy 不會冒用：落地列的送件人是登入者")
    void submittedByComesFromTheAuthenticatedCaller() throws Exception {
        String pid = startCase("user001");
        seedFormData(pid, "user001");
        Task revision = returnToApplicant(pid);

        // 新 payload 的形狀是 {dataJson} —— 沒有 submittedBy 欄位。
        // 夾帶它只是被忽略（record 沒有這個欄位），落地的送件人仍是登入者。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "complete");
        payload.put("formData", Map.of("dataJson", "{\"reason\":\"補件\"}",
                "submittedBy", ADMIN));

        var res = put("/api/tasks/" + revision.getId(), "user001",
                objectMapper.writeValueAsString(payload));

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        String newId = field(res.body(), "formDataId");
        assertThat(dataRepo.findById(newId).orElseThrow().getSubmittedBy())
                .as("送件人不得由 body 指定，也不得沿用被取代列以外的任何來源")
                .isEqualTo("user001");
        assertThat(awaitFormOperator("FORM_UPDATE", newId))
                .as("稽核 operatorId 也是登入者")
                .isEqualTo("user001");
    }

    @Test
    @DisplayName("#58：補件提交的新列沿用送件人；稽核 operatorId 是登入者")
    void revisionKeepsOriginalSubmitterAndAuditsCaller() throws Exception {
        String pid = startCase("user001");
        String originalId = seedFormData(pid, "user001");
        Task revision = returnToApplicant(pid);

        var res = put("/api/tasks/" + revision.getId(), "user001",
                revisionBody("{\"reason\":\"補件後\"}"));

        assertThat(res.statusCode()).isEqualTo(200);
        String newId = field(res.body(), "formDataId");
        assertThat(newId).isNotEqualTo(originalId);
        assertThat(dataJsonOf(originalId))
                .as("原始送件必須完整保留 —— 這是版本化的全部意義")
                .isEqualTo(ORIGINAL);
        assertThat(dataRepo.findById(originalId).orElseThrow().getSubmittedBy()).isEqualTo("user001");
        assertThat(dataRepo.findById(newId).orElseThrow().getSubmittedBy()).isEqualTo("user001");
    }

    private static String field(String json, String name) {
        var m = java.util.regex.Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
