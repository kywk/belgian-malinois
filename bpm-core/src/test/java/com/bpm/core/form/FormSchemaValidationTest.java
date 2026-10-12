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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新流程的提交驗證：{@code PUT /api/tasks/{id}} 帶 {@code formData}
 * （uni-form 重構 §五 A5；改寫自 #55 的 {@code POST /api/form-data} 版本）。
 *
 * <h2>這個測試組在防什麼</h2>
 *
 * <ol>
 *   <li>違規真的回 400，且訊息指名欄位（走 ERROR dispatch 後仍在，
 *       因此用真實 HTTP —— 理由與 {@code ErrorDispatchTest} 相同）。</li>
 *   <li><b>零副作用</b>：違規時沒有資料列、沒有 {@code FORM_SUBMIT} 稽核、
 *       任務也<b>沒有被完成</b>。只驗狀態碼證明不了這件事 ——
 *       回 400 卻照樣完成任務是比沒有守衛更糟的狀態。</li>
 *   <li>合法資料仍可送出（可用性那一半），且稽核真的落地。</li>
 * </ol>
 *
 * <h2>為什麼用 seed 的 leave-request 而不是自造 DSL</h2>
 *
 * <p>驗證規則本身由 SDK 的 conformance 與 {@code FormEnvelopeServiceTest} 釘住；
 * 這裡要證明的是<b>接線</b>：seed 的 DSL（V7 migration）＋ BPMN 的
 * Access Profile（ADR-23）＋ {@code validateV1} 串起來之後，
 * 「必填／未知欄位／readonly 篡改」真的會被擋在寫入之前。
 * 自造 DSL 會讓「seed 與 BPMN 對不上」這件事完全測不到。
 */
class FormSchemaValidationTest extends IntegrationTestBase {

    private static final String APPLICANT = "user001";
    private static final String MANAGER = "mgr001";
    private static final String LEAVE = "leave-approval";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private FormDataRepository dataRepo;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    // ── 前置工具 ──────────────────────────────────────────────────

    /** 啟動案件 → 主管退回 → 停在申請人的補件關卡（fill／readonly＋reason editable）。 */
    private String startReturnedCase() {
        String pid = runtimeService.startProcessInstanceByKey(LEAVE,
                Map.of("initiator", APPLICANT, "leaveType", "annual",
                        "dateRange", Map.of("start", "2026-10-01", "end", "2026-10-03"),
                        "reason", "原始事由")).getId();
        completeDirectly(pid, Map.of("approved", false, "rejected", false));
        return pid;
    }

    private void completeDirectly(String pid, Map<String, Object> vars) {
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(task.getId(), vars);
    }

    private Task currentTask(String pid) {
        return taskService.createTaskQuery().processInstanceId(pid).singleResult();
    }

    /** 走真實 HTTP 完成任務並帶 formData（見類別註解：MockMvc 不做 ERROR dispatch）。 */
    private HttpResponse<String> completeWithFormData(String taskId, String dataJson) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "complete");
        payload.put("formData", Map.of("dataJson", dataJson));
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/tasks/" + taskId))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", APPLICANT)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
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

    /** 違規的共同後果：400、零資料列、零 FORM_SUBMIT 稽核、任務仍在原地。 */
    private void assertRejectedWithoutSideEffects(HttpResponse<String> res, String pid,
                                                  String taskId, String... bodyFragments) {
        assertThat(res.statusCode())
                .as("預期 400，實際 %d；body=%s", res.statusCode(), res.body())
                .isEqualTo(400);
        assertThat(res.body()).contains(bodyFragments);
        assertThat(rowCount(pid)).as("違規時不得留下資料列").isZero();
        assertThat(formSubmitAuditCount(pid)).as("違規時不得寫 FORM_SUBMIT 稽核").isZero();
        assertThat(currentTask(pid).getId())
                .as("違規時任務不得被完成 —— 只驗狀態碼證明不了零副作用")
                .isEqualTo(taskId);
    }

    // ── ① 合法資料放行（可用性）───────────────────────────────────

    @Test
    @DisplayName("合法補件資料 → 200、formDataId、資料列與 FORM_SUBMIT 稽核都落地")
    void validDataIsAcceptedAndPersisted() throws Exception {
        String pid = startReturnedCase();
        String taskId = currentTask(pid).getId();

        var res = completeWithFormData(taskId, "{\"reason\":\"補件後的事由\"}");

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        assertThat(res.body()).as("回應必須帶這次產生的 formDataId").contains("formDataId");
        assertThat(rowCount(pid)).isEqualTo(1);
        assertThat(formSubmitAuditCount(pid))
                .as("通過驗證的送出必須照常寫稽核（驗證不是靜默丟棄）")
                .isEqualTo(1);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count())
                .as("補件完成後案件應回到主管關卡（仍在執行）").isEqualTo(1);
    }

    // ── ② 違規 → 400，指名欄位，零副作用 ─────────────────────────

    @Test
    @DisplayName("required 缺漏 → 400 且訊息指名欄位，零副作用")
    void missingRequiredFieldIsRejectedWithFieldName() throws Exception {
        String pid = startReturnedCase();
        String taskId = currentTask(pid).getId();

        // reason 是補件關卡唯一可編輯的欄位，且 DSL 標了 required。
        var res = completeWithFormData(taskId, "{}");

        assertRejectedWithoutSideEffects(res, pid, taskId, "reason", "必填");
    }

    @Test
    @DisplayName("未知欄位 → 400 且訊息指名欄位，零副作用")
    void unknownFieldIsRejectedWithFieldName() throws Exception {
        String pid = startReturnedCase();
        String taskId = currentTask(pid).getId();

        // 欄位 id == 流程變數名（spec §8.5）：salary 不在 DSL，不得被接受。
        var res = completeWithFormData(taskId,
                "{\"reason\":\"補件\",\"salary\":\"95000\"}");

        assertRejectedWithoutSideEffects(res, pid, taskId, "salary");
    }

    @Test
    @DisplayName("readonly 篡改（改 leaveType）→ 400 不可編輯，零副作用")
    void readonlyTamperIsRejected() throws Exception {
        String pid = startReturnedCase();
        String taskId = currentTask(pid).getId();

        // 補件 profile 是 fill／readonly，leaveType 不可編輯。
        var res = completeWithFormData(taskId,
                "{\"reason\":\"補件\",\"leaveType\":\"sick\"}");

        assertRejectedWithoutSideEffects(res, pid, taskId, "leaveType", "不可編輯");
    }

    @Test
    @DisplayName("檢視模式的任務（主管審核）帶 formData → 400，且任務不被完成")
    void nonFillTaskRejectsFormData() throws Exception {
        String pid = runtimeService.startProcessInstanceByKey(LEAVE,
                Map.of("initiator", APPLICANT, "leaveType", "annual", "reason", "x")).getId();
        Task managerTask = currentTask(pid);
        assertThat(managerTask.getAssignee()).isEqualTo(MANAGER);

        // 主管是持有人，但 managerReview 的 profile 是 view —— 沒有可接受的提交語意。
        Map<String, Object> payload = Map.of("action", "complete",
                "formData", Map.of("dataJson", "{\"reason\":\"x\"}"));
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/tasks/" + managerTask.getId()))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", MANAGER)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(400);
        assertThat(res.body()).contains("檢視模式", "view");
        assertThat(rowCount(pid)).isZero();
        assertThat(currentTask(pid).getId()).as("任務不得被完成").isEqualTo(managerTask.getId());
    }

    // ── ③ dataJson 不是 JSON 物件 → 400 ────────────────────────────

    @Test
    @DisplayName("formData.dataJson 不是 JSON 物件 → 400，零副作用")
    void nonObjectDataJsonIsRejected() throws Exception {
        String pid = startReturnedCase();
        String taskId = currentTask(pid).getId();

        var res = completeWithFormData(taskId, "[1,2,3]");

        assertRejectedWithoutSideEffects(res, pid, taskId, "JSON");
    }

    // ── ④ 被拒後仍可補件（拒絕不是把案件鎖死）──────────────────────

    @Test
    @DisplayName("被拒後同一個任務仍可再次提交合法資料（拒絕不得留下半完成狀態）")
    void rejectedSubmissionLeavesTaskUsable() throws Exception {
        String pid = startReturnedCase();
        String taskId = currentTask(pid).getId();

        assertThat(completeWithFormData(taskId, "{}").statusCode()).isEqualTo(400);

        var res = completeWithFormData(taskId, "{\"reason\":\"第二次送出的補件\"}");
        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        assertThat(rowCount(pid)).isEqualTo(1);
        assertThat(dataRepo.findByProcessInstanceIdOrderBySubmittedAtDesc(pid).get(0).getDataJson())
                .contains("第二次送出的補件");
    }
}
