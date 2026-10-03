package com.bpm.core.security;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.repository.FormDataRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #72：{@code /api/form-data} 三個端點的授權與身分。
 *
 * <h2>缺陷（三個，全部以真實 JWT 線上實測確認）</h2>
 *
 * <ol>
 *   <li><b>讀零檢查。</b>{@code GET /api/form-data/{pid}} 不看呼叫者是誰。
 *       實測 token=user001 讀 user002 的案件回 200，
 *       {@code dataJson: {"salary":"95000","reason":"機密"}}。
 *       依 spec §8.5 欄位 id == 流程變數名，所以這與
 *       {@code GET /api/process-instances/{id}/variables} 是<b>同一批資料的兩條路</b>；
 *       #71 擋下後者卻沒擋這裡，那一版的保護可被繞過。</li>
 *   <li><b>寫零擁有權檢查。</b>{@code PUT /api/form-data/{id}} 的路徑參數只有 id，
 *       {@code updateData} 內部 {@code findById} 之後完全不判斷關係。
 *       實測以 user001 改寫 user002 案件的表單回 200，之後 user002 讀到的
 *       就是 {@code {"salary":"9999999"}} —— 任何登入者都能改任何人的表單資料。</li>
 *   <li><b>{@code submittedBy} 來自 body。</b>它同時是稽核的 operatorId，
 *       所以稽核記的是冒用者，且被 hash chain 永久固定 ——
 *       與 #66 修掉的 {@code ProcessController.initiator}、
 *       {@code DocumentController.createdBy} 完全同型。</li>
 * </ol>
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：{@code ResponseStatusException} 會被
 * 容器轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@link ErrorDispatchTest}）。用 MockMvc 寫，這些測試在缺陷存在時
 * 會照樣全綠。
 *
 * <h2>每個守衛測試都驗「資料真的沒變」而不只看狀態碼</h2>
 *
 * <p>被拒絕的寫入如果回 404 卻仍然改了資料，那這個修補只是把攻擊從
 * 「看得到」變成「看不到錯誤訊息」。因此 PUT 與 POST 的每一條拒絕測試
 * 都直接回 {@link FormDataRepository} 與稽核 DB 驗證後果。
 */
class FormDataAuthorizationTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private FormDataRepository dataRepo;

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

    private HttpResponse<String> post(String path, String userId, String body) throws Exception {
        return send("POST", path, userId, body);
    }

    private HttpResponse<String> put(String path, String userId, String body) throws Exception {
        return send("PUT", path, userId, body);
    }

    private String startCase(String initiator) {
        return runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", initiator, "leaveType", "annual", "days", 1)).getId();
    }

    /** 以 HTTP 送出一筆表單資料（走完整的三道守衛），回傳記錄 id。 */
    private String submitFormData(String pid, String userId, String submittedByJson) throws Exception {
        String body = "{\"formDefinitionId\":\"leave-form-v1\","
                + "\"processInstanceId\":\"" + pid + "\","
                + submittedByJson
                + "\"dataJson\":\"" + ORIGINAL.replace("\"", "\\\"") + "\"}";
        var res = post("/api/form-data", userId, body);
        assertThat(res.statusCode()).as("前置條件：送出表單資料應成功，實際回 " + res.body()).isEqualTo(200);
        return res.body().replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");
    }

    private static String field(String json, String name) {
        var m = java.util.regex.Pattern.compile("\"" + name + "\":\"([^\"]*)\"");
        var matcher = m.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
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
        submitFormData(pid, "user001", "");

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
        String recordId = submitFormData(pid, "user001", "");

        var res = get("/api/form-data/" + pid, "user001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(recordId).contains("95000");
    }

    @Test
    @DisplayName("#72：稽核旁路（audit:log:read）可讀他人案件的表單資料，且每次留痕")
    void auditorCanReadFormDataAndItIsRecorded() throws Exception {
        truncateAuditLog();
        String pid = startCase("user001");
        submitFormData(pid, "user001", "");

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
        String recordId = submitFormData(pid, "user001", "");
        // 前置：送出本身寫的是 FORM_SUBMIT，不是 DATA_ACCESS。
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
        submitFormData(pid, "user001", "");

        // 「能管理系統」與「能看全公司的薪資單」是不同的權責。
        // 與稽核旁路那條必須成組存在 —— 少了它，上一條可以靠
        // 「把旁路放寬給管理員」達成，而那正是繞過旁路限制的樣態。
        var res = get("/api/form-data/" + pid, ADMIN);

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).doesNotContain("95000");
    }

    // ── ② PUT：擁有權 ──────────────────────────────────────────────

    @Test
    @DisplayName("#72：非參與者改寫他人案件的表單資料 → 404，且資料真的沒被改動")
    void nonParticipantCannotOverwriteOthersFormData() throws Exception {
        String pid = startCase("user001");
        String recordId = submitFormData(pid, "user001", "");
        assertThat(dataJsonOf(recordId)).isEqualTo(ORIGINAL);

        var res = put("/api/form-data/" + recordId, OUTSIDER,
                "{\"dataJson\":\"" + TAMPERED.replace("\"", "\\\"") + "\"}");

        assertThat(res.statusCode())
                .as("改動前：updateData 只做 findById，任何登入者都能改任何人的表單資料")
                .isEqualTo(404);
        assertThat(dataJsonOf(recordId))
                .as("被拒的寫入不得有任何後果 —— 回 404 卻仍改到資料，比沒有守衛更危險")
                .isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("#72＋#58：簽核人是參與者、但不是送件人 → 404，且資料真的沒被改動")
    void currentApproverIsNotTheSubmitterAndMayNotUpdate() throws Exception {
        // ⚠️ #58（2026-10-03 裁決）收窄了這裡的政策：改動前「參與者即可改」，
        // 於是簽核人能改寫申請人填的薪資數字。現在只有該列的送件人能改，
        // 參與者身分不再是充分條件 —— 這一條因此從 200 改釘 404。
        String pid = startCase("user001");
        String recordId = submitFormData(pid, "user001", "");

        var res = put("/api/form-data/" + recordId, "mgr001",
                "{\"dataJson\":\"" + TAMPERED.replace("\"", "\\\"") + "\"}");

        assertThat(res.statusCode())
                .as("mgr001 目前持有 managerReview，是參與者；但送件人是 user001")
                .isEqualTo(404);
        assertThat(dataJsonOf(recordId))
                .as("被拒的寫入不得有任何後果")
                .isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("#72：稽核旁路是唯讀的：持有 audit:log:read 也不能替別人的案件改表單")
    void auditorCannotUpdate() throws Exception {
        String pid = startCase("user001");
        String recordId = submitFormData(pid, "user001", "");

        var res = put("/api/form-data/" + recordId, AUDITOR,
                "{\"dataJson\":\"" + TAMPERED.replace("\"", "\\\"") + "\"}");

        assertThat(res.statusCode())
                .as("稽核人員的職責是查閱，不是替案件補件或改寫別人填的薪資數字")
                .isEqualTo(404);
        assertThat(dataJsonOf(recordId)).isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("#72：不存在的表單資料 id → 404，且與「不屬於你」無法分辨")
    void unknownRecordIsNotFound() throws Exception {
        var res = put("/api/form-data/no-such-form-data-id", "user001",
                "{\"dataJson\":\"{}\"}");

        assertThat(res.statusCode())
                .as("兩種拒絕同一個狀態碼，PUT 因此不能當成枚舉別人資料的探測工具")
                .isEqualTo(404);
    }

    // ── ③ POST：寫入端的參與者檢查 ─────────────────────────────────

    @Test
    @DisplayName("#72：非參與者不得把表單資料塞進他人案件")
    void nonParticipantCannotSubmitIntoAnotherPersonsCase() throws Exception {
        String pid = startCase("user001");
        int before = rowCountOf(pid);

        var res = post("/api/form-data", OUTSIDER,
                "{\"formDefinitionId\":\"leave-form-v1\",\"processInstanceId\":\"" + pid
                        + "\",\"dataJson\":\"{\\\"salary\\\":\\\"1\\\"}\"}");

        assertThat(res.statusCode())
                .as("processInstanceId 是 body 的純資料欄位，但資料會被該案的參與者讀到")
                .isEqualTo(404);
        assertThat(rowCountOf(pid))
                .as("被拒的請求不得在他人案件底下留下任何一列").isEqualTo(before);
    }

    @Test
    @DisplayName("#72：POST 缺少 processInstanceId → 400，不得變成 500")
    void missingProcessInstanceIdIsBadRequest() throws Exception {
        var res = post("/api/form-data", "user001",
                "{\"formDefinitionId\":\"leave-form-v1\",\"dataJson\":\"{}\"}");

        // processInstanceId(null) 在 Flowable 的 query 上等同「沒有這個條件」，
        // singleResult() 會變成對全公司案件取單筆 → FlowableException → 500。
        assertThat(res.statusCode()).isEqualTo(400);
    }

    // ── ④ submittedBy 的身分冒用（backlog #72，與 #66 同型）────────

    @Test
    @DisplayName("#72：POST 帶冒用的 submittedBy → 400，且不得留下資料與稽核")
    void submitCannotImpersonateSubmittedBy() throws Exception {
        String pid = startCase("user001");
        int rowsBefore = rowCountOf(pid);

        var res = post("/api/form-data", "user001",
                "{\"formDefinitionId\":\"leave-form-v1\",\"processInstanceId\":\"" + pid
                        + "\",\"submittedBy\":\"" + ADMIN + "\",\"dataJson\":\"{}\"}");

        assertThat(res.statusCode())
                .as("改動前：submittedBy 由 body 指定，冒用完全無阻")
                .isEqualTo(400);
        assertThat(res.body())
                .as("訊息要說清楚要改什麼，否則呼叫端只會看到 400（#73）")
                .contains("submittedBy");
        assertThat(rowCountOf(pid)).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("#72：POST 省略 submittedBy → 200，送件人與稽核 operatorId 都是登入者")
    void submittedByComesFromTheAuthenticatedCaller() throws Exception {
        String pid = startCase("user001");

        var res = post("/api/form-data", "user001",
                "{\"formDefinitionId\":\"leave-form-v1\",\"processInstanceId\":\"" + pid
                        + "\",\"dataJson\":\"" + ORIGINAL.replace("\"", "\\\"") + "\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        String recordId = field(res.body(), "id");
        assertThat(field(res.body(), "submittedBy"))
                .as("省略時必須寫入登入者而不是留 null —— 欄位可為 null 會讓「誰送出的」無解")
                .isEqualTo("user001");
        assertThat(awaitFormOperator("FORM_SUBMIT", recordId))
                .as("稽核要記「誰做的」。改動前它記的是 body 的 submittedBy，"
                        + "等於把冒用者寫進被 hash chain 固定的紀錄")
                .isEqualTo("user001");
    }

    @Test
    @DisplayName("#72：POST 帶與自己相同的 submittedBy 視為省略，不得拒絕")
    void matchingSubmittedByIsAccepted() throws Exception {
        // DocumentController.createdBy 的同型取捨：submittedBy 是 JPA entity 欄位，
        // Jackson 反序列化後無法分辨「沒送」與「送了 null」；而送出與自己相同的
        // 身分並不構成冒用，拒絕它只會製造無意義的破壞。
        String pid = startCase("user001");

        var res = post("/api/form-data", "user001",
                "{\"formDefinitionId\":\"leave-form-v1\",\"processInstanceId\":\"" + pid
                        + "\",\"submittedBy\":\"user001\",\"dataJson\":\"{}\"}");

        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("#72：PUT 帶冒用的 submittedBy → 400，且資料沒有被改動")
    void updateCannotImpersonateSubmittedBy() throws Exception {
        String pid = startCase("user001");
        String recordId = submitFormData(pid, "user001", "");

        var res = put("/api/form-data/" + recordId, "user001",
                "{\"submittedBy\":\"" + ADMIN + "\",\"dataJson\":\""
                        + TAMPERED.replace("\"", "\\\"") + "\"}");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(dataJsonOf(recordId))
                .as("400 必須真的擋下寫入，而不是只回一個錯誤狀態碼")
                .isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("#72＋#58：退回狀態下送件人修改 → 200，稽核 operatorId 是登入者")
    void updateOperatorIsTheAuthenticatedCaller() throws Exception {
        String pid = startCase("user001");
        String recordId = submitFormData(pid, "user001", "");
        // #58：修改必須在退回（補件）狀態。主管退回 → 補件任務指派給 user001。
        var mgrTask = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(mgrTask.getId(), Map.of("approved", false, "rejected", false));

        var res = put("/api/form-data/" + recordId, "user001",
                "{\"dataJson\":\"" + TAMPERED.replace("\"", "\\\"") + "\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        // #58 版本化：回傳的是新列，不是被改寫的舊列。
        String newId = field(res.body(), "id");
        assertThat(newId).isNotEqualTo(recordId);
        assertThat(awaitFormOperator("FORM_UPDATE", newId))
                .as("改動前：operatorId 是 body 的 submittedBy")
                .isEqualTo("user001");
        // submittedBy 刻意不覆寫：舊列原封不動，新列沿用同一位送件人。
        assertThat(dataJsonOf(recordId)).as("原始送件必須保留").isEqualTo(ORIGINAL);
        assertThat(dataRepo.findById(recordId).orElseThrow().getSubmittedBy()).isEqualTo("user001");
        assertThat(dataRepo.findById(newId).orElseThrow().getSubmittedBy()).isEqualTo("user001");
    }
}
