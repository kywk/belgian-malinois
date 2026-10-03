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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #58：{@code PUT /api/form-data/{id}} 的授權、退回狀態與版本化。
 *
 * <h2>裁決（使用者 2026-10-03）</h2>
 *
 * <ol>
 *   <li><b>授權：</b>只有該列的送件人（{@code existing.getSubmittedBy()}）
 *       可以修改；非送件人即使參與者也是 404（與「不存在」不可分辨）。</li>
 *   <li><b>狀態：</b>案件必須停在指派給呼叫者的補件關卡；否則 409。
 *       判定重用 {@code NotifyPublisher.isRevisionTask}（規則只有一份）。</li>
 *   <li><b>版本化：</b>修改新增一列（新 {@code dataJson}／{@code submittedAt}），
 *       舊列保留。{@code FORM_UPDATE} 稽核同時記新列與被取代的舊列 id。</li>
 * </ol>
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>與 {@code FormDataAuthorizationTest} 同一理由：MockMvc 不做 error
 * dispatch，{@code ResponseStatusException} 的狀態碼是容器轉出來的。
 * 用 MockMvc 寫，守衛沒接線時這些測試照樣全綠。
 *
 * <h2>每個拒絕測試都驗「資料真的沒變」</h2>
 *
 * <p>回 404／409 卻仍改了資料，比沒有守衛更危險 —— 所以除了狀態碼，
 * 每一條都直接回 {@link FormDataRepository} 與表單 DB 驗證後果
 * （列數、內容、稽核）。
 *
 * <h2>⚠️ 這個類別沒有覆蓋的事</h2>
 *
 * <ul>
 *   <li><b>schema 驗證</b>：{@code PUT} 仍然不驗 {@code dataJson} 是否符合
 *       {@code schemaJson}（#58 未納入，見 {@code FormDataController.submit}
 *       的已知落差說明）。本類別沿用既有測試的 {@code leave-form-v1}
 *       （查不到定義 → 略過驗證），因此對這條落差<b>沒有</b>證明力。</li>
 *   <li><b>前端</b>：目前沒有元件呼叫 {@code updateFormData}（見
 *       {@code bpm-frontend/src/services/formApi.js}）；補件畫面走的是
 *       {@code updateTask} 的 variables 路徑。多列的影響因此不在畫面端。</li>
 *   <li><b>併發</b>：狀態檢查與寫入之間沒有鎖，補件任務在同一瞬間被完成時，
 *       仍可能寫入一列「完成後才到」的版本。</li>
 * </ul>
 */
class FormDataVersioningTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private FormDataRepository dataRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    private static final String ORIGINAL = "{\"salary\":\"95000\"}";
    private static final String REVISED = "{\"salary\":\"88000\"}";

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

    // ── 情境小工具 ──────────────────────────────────────────────────

    private String startCase(String initiator) {
        return runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", initiator, "leaveType", "annual", "days", 1)).getId();
    }

    /** 以 HTTP 送出一筆表單資料（走完整守衛），回傳記錄 id。 */
    private String submitFormData(String pid, String userId) throws Exception {
        String body = "{\"formDefinitionId\":\"leave-form-v1\","
                + "\"processInstanceId\":\"" + pid + "\","
                + "\"dataJson\":\"" + ORIGINAL.replace("\"", "\\\"") + "\"}";
        var res = post("/api/form-data", userId, body);
        assertThat(res.statusCode()).as("前置條件：送出表單資料應成功，實際回 " + res.body())
                .isEqualTo(200);
        return field(res.body(), "id");
    }

    /** 主管退回 → 補件任務指派給申請人本人（assignee = 申請人）。 */
    private Task returnToApplicant(String pid) {
        Task mgr = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(mgr.getId(), Map.of("approved", false, "rejected", false));
        Task revision = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(revision.getName()).as("前置條件：退回後應停在補件關卡").contains("補件");
        return revision;
    }

    private static String reviseBody(String dataJson) {
        return "{\"dataJson\":\"" + dataJson.replace("\"", "\\\"") + "\"}";
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

    /**
     * 等一筆含 {@code formDataId} 的 {@code FORM_UPDATE} detail。
     *
     * <p>稽核寫入掛在交易的 beforeCommit，正常在回應之前就完成；
     * 保留輪詢只是與既有測試（{@code FormDataAuthorizationTest}）同一形狀，
     * 避免時序抖動造成假紅。
     */
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

    // ── a) 送件人＋退回狀態 → 版本化成功 ────────────────────────────

    @Test
    @DisplayName("#58：送件人在退回狀態修改 → 200、新列生效、舊列保留")
    void submitterCanReviseOnReturnedCaseAndHistoryIsKept() throws Exception {
        String pid = startCase("user001");
        String originalId = submitFormData(pid, "user001");
        Task revision = returnToApplicant(pid);
        assertThat(revision.getAssignee())
                .as("補件關卡指派給申請人本人（${applicantResolver}）")
                .isEqualTo("user001");

        var res = put("/api/form-data/" + originalId, "user001", reviseBody(REVISED));

        assertThat(res.statusCode()).as("實際回 " + res.body()).isEqualTo(200);
        String newId = field(res.body(), "id");
        assertThat(newId)
                .as("回傳的必須是新列，不是被覆寫的舊列")
                .isNotEqualTo(originalId);
        assertThat(dataJsonOf(newId)).isEqualTo(REVISED);
        assertThat(dataRepo.findById(newId).orElseThrow().getSubmittedBy())
                .as("新列沿用送件人，body 不是身分來源")
                .isEqualTo("user001");
        assertThat(dataJsonOf(originalId))
                .as("原始送件必須完整保留 —— 這是版本化的全部意義")
                .isEqualTo(ORIGINAL);
        assertThat(rowCountOf(pid)).as("同案件應有兩列：原始＋修改版").isEqualTo(2);

        var list = get("/api/form-data/" + pid, "user001");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(originalId).contains(newId);
        assertThat(list.body().indexOf(newId))
                .as("getByProcess 依 submittedAt 遞減：最新版本排最前面")
                .isLessThan(list.body().indexOf(originalId));
    }

    // ── b) 非送件人的參與者 → 404 ───────────────────────────────────

    @Test
    @DisplayName("#58：非送件人的參與者修改 → 404，資料與列數不變")
    void participantWhoIsNotSubmitterIsNotFound() throws Exception {
        String pid = startCase("user001");
        String originalId = submitFormData(pid, "user001");
        returnToApplicant(pid);
        // mgr001 剛完成 managerReview，是這個案件的參與者（歷史任務）；
        // 但送件人是 user001。參與者身分不再是充分條件。
        int rowsBefore = rowCountOf(pid);

        var res = put("/api/form-data/" + originalId, "mgr001", reviseBody(REVISED));

        assertThat(res.statusCode())
                .as("非送件人（即使參與者）與『記錄不存在』不可分辨，一律 404")
                .isEqualTo(404);
        assertThat(dataJsonOf(originalId)).isEqualTo(ORIGINAL);
        assertThat(rowCountOf(pid)).as("被拒的修改不得新增任何列").isEqualTo(rowsBefore);
    }

    // ── c) 非退回狀態 → 409 ─────────────────────────────────────────

    @Test
    @DisplayName("#58：審核中（非退回）送件人修改 → 409，資料不變")
    void notReturnedCaseIsConflict() throws Exception {
        String pid = startCase("user001");
        String originalId = submitFormData(pid, "user001");
        // 案件停在 managerReview（assignee mgr001）：user001 是送件人與發起人，
        // 通過授權，但沒有指派給他的補件任務 → 狀態衝突。
        int rowsBefore = rowCountOf(pid);

        var res = put("/api/form-data/" + originalId, "user001", reviseBody(REVISED));

        assertThat(res.statusCode())
                .as("你是這筆資料的主人，但案件現在不是退回狀態 —— 是狀態衝突，不是權限問題")
                .isEqualTo(409);
        assertThat(dataJsonOf(originalId)).isEqualTo(ORIGINAL);
        assertThat(rowCountOf(pid)).isEqualTo(rowsBefore);
    }

    // ── d) 已結束案件 → 409（自選並說明）────────────────────────────

    @Test
    @DisplayName("#58：已結束案件修改 → 409（送件人仍通過授權，差別在狀態）")
    void finishedCaseIsConflict() throws Exception {
        String pid = startCase("user001");
        String originalId = submitFormData(pid, "user001");
        Task mgr = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(mgr.getId(), Map.of("approved", true, "rejected", false));
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count())
                .as("前置條件：案件應已結束").isZero();

        var res = put("/api/form-data/" + originalId, "user001", reviseBody(REVISED));

        // 刻意選 409 而不是 404：user001 仍是發起人（歷史變數）與送件人，
        // 授權成立；「案件結束了」是狀態問題。404 會把「已結束」與
        // 「不是你的」塌成同一個回應，而這兩種情況該做的事正好相反。
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(dataJsonOf(originalId)).isEqualTo(ORIGINAL);
        assertThat(rowCountOf(pid)).isEqualTo(1);
    }

    // ── e) 稽核串起新舊列 ───────────────────────────────────────────

    @Test
    @DisplayName("#58：FORM_UPDATE 稽核同時含新列與被取代的舊列 id")
    void auditLinksNewAndSupersededRows() throws Exception {
        String pid = startCase("user001");
        String originalId = submitFormData(pid, "user001");
        returnToApplicant(pid);

        var res = put("/api/form-data/" + originalId, "user001", reviseBody(REVISED));
        assertThat(res.statusCode()).isEqualTo(200);
        String newId = field(res.body(), "id");

        String detail = awaitFormUpdateDetail(newId);
        assertThat(detail)
                .as("只記新列會讓『改動前是什麼』無跡可尋；只記舊列則對不到生效版本")
                .isNotNull()
                .contains(newId)
                .contains(originalId);
    }

    // ── f) 被拒零副作用 ─────────────────────────────────────────────

    @Test
    @DisplayName("#58：被拒的修改（409 與 404）不留資料列、不寫 FORM_UPDATE 稽核")
    void rejectedUpdateLeavesNoSideEffects() throws Exception {
        String pid = startCase("user001");
        String originalId = submitFormData(pid, "user001");
        int rowsBefore = rowCountOf(pid);
        int userAuditBefore = auditCountOf("user001", "FORM_UPDATE");
        int mgrAuditBefore = auditCountOf("mgr001", "FORM_UPDATE");

        // (1) 送件人，但案件還在審核中 → 409
        assertThat(put("/api/form-data/" + originalId, "user001", reviseBody(REVISED)).statusCode())
                .isEqualTo(409);

        // (2) 案件退回後，非送件人的參與者 → 404
        returnToApplicant(pid);
        assertThat(put("/api/form-data/" + originalId, "mgr001", reviseBody(REVISED)).statusCode())
                .isEqualTo(404);

        assertThat(dataJsonOf(originalId)).isEqualTo(ORIGINAL);
        assertThat(rowCountOf(pid)).as("兩次拒絕都不得留下任何列").isEqualTo(rowsBefore);
        assertThat(auditCountOf("user001", "FORM_UPDATE")).isEqualTo(userAuditBefore);
        assertThat(auditCountOf("mgr001", "FORM_UPDATE")).isEqualTo(mgrAuditBefore);
    }
}
