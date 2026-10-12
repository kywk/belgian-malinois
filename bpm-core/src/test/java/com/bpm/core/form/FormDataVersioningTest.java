package com.bpm.core.form;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 補件提交的版本化：{@code PUT /api/tasks/{id}} 帶 {@code formData}
 * （uni-form 重構 §五 A5；改寫自 #58 的 {@code PUT /api/form-data/{id}} 版本）。
 *
 * <h2>版本化語意不變，入口變了</h2>
 *
 * <p>「修改＝新增一列，舊列保留」是 #58 的裁決，重構沒有動它；
 * 變的是入口：補件不再直接 PUT 表單資料，而是<b>完成補件任務</b>時
 * 附帶提交。授權因此多了一層：能改的人必須先是該任務的持有人
 * （{@code TaskHolderGuard}），不再是「任何知道 formDataId 的送件人」。
 *
 * <h2>每個拒絕測試都驗「資料真的沒變」</h2>
 *
 * <p>回 404／400 卻仍改了資料，比沒有守衛更危險 —— 所以除了狀態碼，
 * 每一條都直接回表單 DB 與稽核 DB 驗證後果（列數、內容、稽核）。
 */
class FormDataVersioningTest extends IntegrationTestBase {

    private static final String APPLICANT = "user001";
    private static final String MANAGER = "mgr001";
    private static final String LEAVE = "leave-approval";

    private static final String ORIGINAL_REASON = "原始事由";
    private static final String REVISED_REASON = "補件後事由";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private FormDataRepository dataRepo;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    // ── HTTP 小工具（走真實 HTTP，見既有測試的類別註解）──────────────

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

    // ── 情境小工具 ──────────────────────────────────────────────────

    private static String leaveValues(String reason) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "leaveType", "annual",
                "dateRange", Map.of("start", "2026-10-01", "end", "2026-10-03"),
                "reason", reason));
    }

    /** 以 #60 的 formData 契約啟動請假流程（formKey 由伺服器解析）。 */
    private String startWithFormData(String reason) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("processDefinitionKey", LEAVE);
        payload.put("formData", Map.of("formDefinitionId", "leave-request",
                "dataJson", leaveValues(reason)));
        var res = post("/api/process-instances", APPLICANT,
                objectMapper.writeValueAsString(payload));
        assertThat(res.statusCode()).as("前置條件：啟動＋表單落地應成功，實際 " + res.body())
                .isEqualTo(200);
        return field(res.body(), "processInstanceId");
    }

    /** 主管退回 → 補件任務指派給申請人本人（assignee = 申請人）。 */
    private Task returnToApplicant(String pid) {
        Task mgr = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(mgr.getId(), Map.of("approved", false, "rejected", false));
        Task revision = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(revision.getName()).as("前置條件：退回後應停在補件關卡").contains("補件");
        return revision;
    }

    private String completeBody(String dataJson) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "complete");
        payload.put("formData", Map.of("dataJson", dataJson));
        return objectMapper.writeValueAsString(payload);
    }

    private static String field(String json, String name) {
        var m = java.util.regex.Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private String latestDataJsonOf(String pid) {
        return dataRepo.findByProcessInstanceIdOrderBySubmittedAtDesc(pid).get(0).getDataJson();
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

    /** 某 operator 的某種稽核筆數（驗「被拒時不寫 FORM_UPDATE」用）。 */
    private static int auditCountOf(String operatorId, String operationType) {
        var count = new AtomicInteger(-1);
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log "
                    + "WHERE operator_id = ? AND operation_type = ?")) {
                ps.setString(1, operatorId);
                ps.setString(2, operationType);
                var rs = ps.executeQuery();
                rs.next();
                count.set(rs.getInt(1));
            }
        });
        return count.get();
    }

    private static String awaitFormUpdateDetail(String formDataId) throws InterruptedException {
        var out = new AtomicReference<String>();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && out.get() == null) {
            withAuditConnection(c -> {
                try (var ps = c.prepareStatement("SELECT TOP 1 detail FROM bpm_audit_log "
                        + "WHERE operation_type = 'FORM_UPDATE' AND detail LIKE ?")) {
                    ps.setString(1, "%" + formDataId + "%");
                    var rs = ps.executeQuery();
                    if (rs.next()) out.set(rs.getString(1));
                }
            });
            if (out.get() == null) Thread.sleep(100);
        }
        return out.get();
    }

    // ── a) 退回到補件 → 版本化成功 ──────────────────────────────────

    @Test
    @DisplayName("補件提交 → 200、新列生效、舊列保留、submittedBy 沿用申請人")
    void revisionCreatesNewRowAndKeepsHistory() throws Exception {
        String pid = startWithFormData(ORIGINAL_REASON);
        int rowsAfterStart = rowCountOf(pid);
        Task revision = returnToApplicant(pid);
        assertThat(revision.getAssignee())
                .as("補件關卡指派給申請人本人（${applicantResolver}）")
                .isEqualTo(APPLICANT);

        var res = put("/api/tasks/" + revision.getId(), APPLICANT,
                completeBody(leaveValues(REVISED_REASON)));

        assertThat(res.statusCode()).as("實際回 " + res.body()).isEqualTo(200);
        String newId = field(res.body(), "formDataId");
        assertThat(newId).as("回應必須帶新列的 id").isNotBlank();
        assertThat(latestDataJsonOf(pid)).contains(REVISED_REASON);
        assertThat(dataRepo.findById(newId).orElseThrow().getSubmittedBy())
                .as("新列沿用送件人（補件者就是申請人；誰最後改的由稽核記錄）")
                .isEqualTo(APPLICANT);
        assertThat(rowCountOf(pid))
                .as("原始送件＋補件版必須各留一列（版本化，不覆寫）")
                .isEqualTo(rowsAfterStart + 1);

        var list = get("/api/form-data/" + pid, APPLICANT);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(latestDataJsonOf(pid))
                .as("getByProcess 依 submittedAt 遞減：最新版本排最前面")
                .contains(REVISED_REASON);
        assertThat(list.body()).contains(newId);
    }

    // ── b) 非持有人 → 404 ───────────────────────────────────────────

    @Test
    @DisplayName("非持有人（即使參與者）提交補件 → 404，資料與列數不變")
    void nonHolderCannotSubmitRevision() throws Exception {
        String pid = startWithFormData(ORIGINAL_REASON);
        Task revision = returnToApplicant(pid);
        int rowsBefore = rowCountOf(pid);

        // mgr001 剛完成 managerReview，是這個案件的參與者；
        // 但補件任務的持有人是申請人 —— 任務層守衛先擋下（404）。
        var res = put("/api/tasks/" + revision.getId(), MANAGER,
                completeBody(leaveValues(REVISED_REASON)));

        assertThat(res.statusCode())
                .as("非持有人與『任務不存在』不可分辨，一律 404")
                .isEqualTo(404);
        assertThat(latestDataJsonOf(pid)).contains(ORIGINAL_REASON);
        assertThat(rowCountOf(pid)).as("被拒的提交不得新增任何列").isEqualTo(rowsBefore);
        assertThat(taskService.createTaskQuery().taskId(revision.getId()).count())
                .as("被拒的提交不得完成任務").isEqualTo(1);
    }

    // ── c) 檢視模式的任務不能提交 ───────────────────────────────────

    @Test
    @DisplayName("審核中（view 節點）提交 → 400，資料與列數不變")
    void nonFillTaskIsRejected() throws Exception {
        String pid = startWithFormData(ORIGINAL_REASON);
        Task managerTask = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        int rowsBefore = rowCountOf(pid);

        var res = put("/api/tasks/" + managerTask.getId(), MANAGER,
                completeBody(leaveValues(REVISED_REASON)));

        assertThat(res.statusCode())
                .as("主管審核是 view 節點：審核意見走 variables，不表單資料")
                .isEqualTo(400);
        assertThat(res.body()).contains("檢視模式");
        assertThat(rowCountOf(pid)).isEqualTo(rowsBefore);
        assertThat(taskService.createTaskQuery().taskId(managerTask.getId()).count()).isEqualTo(1);
    }

    // ── d) 已結束案件 → 任務不存在（404）────────────────────────────

    @Test
    @DisplayName("已結束案件的補件任務 → 404（任務已隨案件消失）")
    void finishedCaseHasNoRevisionTask() throws Exception {
        String pid = startWithFormData(ORIGINAL_REASON);
        Task revision = returnToApplicant(pid);
        // 補件完成 → 回主管 → 核准結案。
        put("/api/tasks/" + revision.getId(), APPLICANT, completeBody(leaveValues(REVISED_REASON)));
        Task managerTask = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(managerTask.getId(), Map.of("approved", true, "rejected", false));
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count())
                .as("前置條件：案件應已結束").isZero();

        var res = put("/api/tasks/" + revision.getId(), APPLICANT,
                completeBody(leaveValues("再改一次")));

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(latestDataJsonOf(pid)).contains(REVISED_REASON);
    }

    // ── e) 稽核串起新舊列 ───────────────────────────────────────────

    @Test
    @DisplayName("FORM_UPDATE 稽核同時含新列與被取代的舊列 id")
    void auditLinksNewAndSupersededRows() throws Exception {
        String pid = startWithFormData(ORIGINAL_REASON);
        String originalId = dataRepo.findByProcessInstanceIdOrderBySubmittedAtDesc(pid).get(0).getId();
        Task revision = returnToApplicant(pid);

        var res = put("/api/tasks/" + revision.getId(), APPLICANT,
                completeBody(leaveValues(REVISED_REASON)));
        assertThat(res.statusCode()).isEqualTo(200);
        String newId = field(res.body(), "formDataId");

        String detail = awaitFormUpdateDetail(newId);
        assertThat(detail)
                .as("只記新列會讓『改動前是什麼』無跡可尋；只記舊列則對不到生效版本")
                .isNotNull()
                .contains(newId)
                .contains(originalId);
    }

    // ── f) 被拒零副作用 ─────────────────────────────────────────────

    @Test
    @DisplayName("被拒的提交（readonly 篡改）不留資料列、不寫 FORM_UPDATE 稽核")
    void rejectedSubmissionLeavesNoSideEffects() throws Exception {
        String pid = startWithFormData(ORIGINAL_REASON);
        Task revision = returnToApplicant(pid);
        int rowsBefore = rowCountOf(pid);
        int userAuditBefore = auditCountOf(APPLICANT, "FORM_UPDATE");

        // leaveType 在補件節點是 readonly，篡改必須擋下。
        var res = put("/api/tasks/" + revision.getId(), APPLICANT,
                completeBody("{\"reason\":\"補件\",\"leaveType\":\"sick\"}"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(latestDataJsonOf(pid)).contains(ORIGINAL_REASON);
        assertThat(rowCountOf(pid)).as("被拒的提交不得留下任何列").isEqualTo(rowsBefore);
        assertThat(auditCountOf(APPLICANT, "FORM_UPDATE"))
                .as("沒有變更發生就不得宣稱 FORM_UPDATE")
                .isEqualTo(userAuditBefore);
        assertThat(taskService.createTaskQuery().taskId(revision.getId()).count()).isEqualTo(1);
    }
}
