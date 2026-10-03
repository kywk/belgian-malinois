package com.bpm.core.acceptance;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 請假流程的完整生命週期（#60 formData 啟動 → 審核 → 結案）端到端整合測試。
 *
 * <h2>為什麼需要這個類別</h2>
 *
 * <p>本 repo 對請假流程的每一段都有獨立測試，但<b>沒有一條</b>把整條路走完並
 * 同時驗證三層結果：
 * <ul>
 *   <li>{@code StartProcessWithFormDataTest}：只到「啟動＋表單落地＋變數推導」。</li>
 *   <li>{@code ReturnToInitiatorTest}／{@code FormDataVersioningTest}：
 *       只驗退回與補件的當下狀態，沒有走完重送後的核准。</li>
 *   <li>{@code ProcessResultReportingTest}：直接呼叫引擎完成任務，只驗 listener
 *       的結果判定，不經 HTTP、不驗表單與稽核。</li>
 * </ul>
 *
 * <p>分段綠燈不能保證串起來是對的 —— 這一類缺陷正是本專案反覆記載的型態：
 * 每個零件都對，接線錯了（例如補件重送的稽核型別、結案後的歷史變數、
 * 表單版本鎖在第二次送審時失效）。因此本類別一律在<b>同一個測試內</b>走完
 * 一條完整路徑，並斷言：
 * <ol>
 *   <li><b>引擎狀態</b>：實例真的結束、停在正確的 end event、歷史變數是最終值。</li>
 *   <li><b>DB 落地</b>：{@code bpm_form_db} 的列數與內容（補件後應有兩列）。</li>
 *   <li><b>稽核</b>：該案件在 {@code bpm_audit_log} 的完整操作鏈，含 operator
 *       與 detail（結案結果）。</li>
 * </ol>
 *
 * <p>斷言只認 {@code process_instance_id} 屬於本測試的案件，因此與共用容器上
 * 其他測試的資料絕緣。每個測試的 businessKey 與表單內容都帶 UUID，不重複。
 */
class LeaveFlowLifecycleTest extends IntegrationTestBase {

    private static final String APPLICANT = "user001";
    private static final String MANAGER = "mgr001";
    private static final String LEAVE = "leave-approval";
    private static final String LEAVE_FORM_KEY = "leave-request";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        // 稽核斷言是「該案件的精確操作鏈」；清掉其他測試留下的列只是為了
        // 讓失敗訊息可讀，真正的隔離來自 process_instance_id 條件。
        truncateAuditLog();
    }

    // ── 情境工具 ────────────────────────────────────────────────────

    private record Started(String pid, String formDataId) {}

    private static Map<String, Object> leaveValues(String reason) {
        return Map.of("leaveType", "annual",
                "dateRange", "2026-10-01~2026-10-03",
                "reason", reason);
    }

    /** 以 #60 的 formData 契約啟動請假流程（formKey 由伺服器解析成定義 id）。 */
    private Started startWithFormData(String reason) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "processDefinitionKey", LEAVE,
                "businessKey", "leave-e2e-" + UUID.randomUUID(),
                "formData", Map.of(
                        "formDefinitionId", LEAVE_FORM_KEY,
                        "dataJson", objectMapper.writeValueAsString(leaveValues(reason)))));
        String response = mockMvc.perform(post("/api/process-instances")
                        .header("X-User-Id", APPLICANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pid = field(response, "processInstanceId");
        String formDataId = field(response, "formDataId");
        assertThat(pid).as("啟動回應必須帶 processInstanceId").isNotBlank();
        assertThat(formDataId).as("帶 formData 啟動必須回傳 formDataId").isNotBlank();
        return new Started(pid, formDataId);
    }

    /** 以 variables 啟動（#60 之前的既有呼叫端形狀）。 */
    private String startWithVariablesOnly() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "processDefinitionKey", LEAVE,
                "businessKey", "leave-legacy-" + UUID.randomUUID(),
                "variables", Map.of("leaveType", "personal", "days", 2)));
        String response = mockMvc.perform(post("/api/process-instances")
                        .header("X-User-Id", APPLICANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return field(response, "processInstanceId");
    }

    private Task currentTask(String pid) {
        return taskService.createTaskQuery().processInstanceId(pid).singleResult();
    }

    /** 以 HTTP 完成任務；body 直接是 JSON 字串（變數清單形狀見 TaskController）。 */
    private void completeTask(String taskId, String user, String body) throws Exception {
        mockMvc.perform(put("/api/tasks/{id}", taskId)
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private static String completeWith(String... nameValuePairs) {
        StringBuilder vars = new StringBuilder();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            if (vars.length() > 0) vars.append(',');
            vars.append("{\"name\":\"").append(nameValuePairs[i]).append("\",\"value\":")
                    .append(nameValuePairs[i + 1]).append('}');
        }
        return "{\"action\":\"complete\",\"variables\":[" + vars + "]}";
    }

    private boolean ended(String pid) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(pid).count() == 0;
    }

    private HistoricProcessInstance historic(String pid) {
        HistoricProcessInstance h = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        assertThat(h).as("案件必須存在於歷史（連同已結束的）").isNotNull();
        return h;
    }

    private Object historicVar(String pid, String name) {
        var v = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(pid).variableName(name).singleResult();
        return v == null ? null : v.getValue();
    }

    // ── 稽核與表單的直接 DB 查詢 ────────────────────────────────────

    /** 該案件的稽核列：[operation_type, operator_id, detail]，依 id。 */
    private static List<String[]> auditRows(String pid) {
        List<String[]> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operation_type, operator_id, detail FROM bpm_audit_log "
                            + "WHERE process_instance_id = ? ORDER BY id")) {
                ps.setString(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                    }
                }
            }
        });
        return out;
    }

    /**
     * 等到至少 n 列稽核出現。
     *
     * <p>業務稽核掛在交易 beforeCommit、結案稽核由引擎 listener 在
     * complete 交易內發出 —— 正常都在 HTTP 回應前落地。輪詢只是沿用 repo
     * 既有形狀（{@code AuditCoverageTest}），把時序抖動排除在斷言之外。
     */
    private static List<String[]> awaitAuditRows(String pid, int atLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<String[]> rows = List.of();
        while (System.currentTimeMillis() < deadline) {
            rows = auditRows(pid);
            if (rows.size() >= atLeast) return rows;
            Thread.sleep(100);
        }
        return rows;
    }

    private static List<String> opAndOperator(List<String[]> rows) {
        return rows.stream().map(r -> r[0] + "|" + r[1]).toList();
    }

    /** 該案件的表單列：[id, data_json]，依 submitted_at。 */
    private static List<String[]> formRows(String pid) {
        List<String[]> out = new ArrayList<>();
        withFormConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, data_json FROM bpm_form_data "
                            + "WHERE process_instance_id = ? ORDER BY submitted_at")) {
                ps.setString(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
        });
        return out;
    }

    private String field(String json, String name) {
        var node = objectMapper.readTree(json).path(name);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    // ── ① 核准：formData 啟動 → 主管核准 → 結案 ─────────────────────

    @Test
    @DisplayName("formData 啟動 → 主管核准 → 結案：引擎結束、最終變數、表單與稽核鏈都對")
    void formDataStartApproveEndsCaseWithFullAuditChain() throws Exception {
        String marker = "特休-核准路徑-" + UUID.randomUUID();
        Started s = startWithFormData(marker);

        // 啟動後：案件在跑、表單一列、推導出的變數在引擎裡。
        assertThat(ended(s.pid())).isFalse();
        assertThat(formRows(s.pid())).as("formData 啟動必須留下一列表單").hasSize(1);
        assertThat(runtimeService.getVariable(s.pid(), "reason")).isEqualTo(marker);

        completeTask(currentTask(s.pid()).getId(), MANAGER,
                completeWith("approved", "true", "rejected", "false",
                        "approverComment", "\"同意\""));

        // ── 引擎層：實例真的結束在核准的 end event ───────────────────
        assertThat(ended(s.pid())).as("核准後案件必須結束").isTrue();
        HistoricProcessInstance h = historic(s.pid());
        assertThat(h.getEndActivityId()).as("必須停在 endApproved").isEqualTo("endApproved");
        assertThat(historicVar(s.pid(), "approved")).isEqualTo(true);
        assertThat(historicVar(s.pid(), "reason"))
                .as("結案後歷史變數仍必須是最終值（結案稽核的 result 靠它）")
                .isEqualTo(marker);

        // ── 稽核層：該案件恰好三筆，型別與操作者都對 ─────────────────
        List<String[]> rows = awaitAuditRows(s.pid(), 3);
        assertThat(opAndOperator(rows))
                .as("結案案件的稽核鏈必須恰為 啟動／核准／結案 各一筆")
                .containsExactlyInAnyOrder(
                        "PROCESS_START|" + APPLICANT,
                        "TASK_APPROVE|" + MANAGER,
                        "PROCESS_COMPLETE|system");
        assertThat(rows.get(0)[0]).as("第一筆必須是啟動").isEqualTo("PROCESS_START");
        assertThat(rows.stream().filter(r -> "PROCESS_COMPLETE".equals(r[0])).findFirst()
                .orElseThrow()[2])
                .as("結案稽核必須記下最終結果與變數是否解析成功")
                .contains("\"result\":\"approved\"").contains("finalVariablesResolved");

        // ── 表單層：結案後申請人仍讀得到自己填的內容 ─────────────────
        String formResponse = mockMvc.perform(get("/api/form-data/{pid}", s.pid())
                        .header("X-User-Id", APPLICANT))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(formResponse)
                .as("表單列是持久資料，結案後不該消失（runtime 變數會被清除）")
                .contains(marker);
    }

    // ── ② 拒絕：申請人看得到結果、表單保留 ─────────────────────────

    @Test
    @DisplayName("formData 啟動 → 主管拒絕 → 結案：result=rejected 且申請人查得到已結束案件")
    void rejectEndsCaseWithRejectedResultVisibleToApplicant() throws Exception {
        Started s = startWithFormData("事由-拒絕路徑-" + UUID.randomUUID());

        completeTask(currentTask(s.pid()).getId(), MANAGER,
                completeWith("approved", "false", "rejected", "true",
                        "rejectReason", "\"人力不足\""));

        assertThat(ended(s.pid())).isTrue();
        assertThat(historic(s.pid()).getEndActivityId()).isEqualTo("endRejected");
        assertThat(historicVar(s.pid(), "rejected")).isEqualTo(true);

        List<String[]> rows = awaitAuditRows(s.pid(), 3);
        assertThat(opAndOperator(rows)).containsExactlyInAnyOrder(
                "PROCESS_START|" + APPLICANT,
                "TASK_REJECT|" + MANAGER,
                "PROCESS_COMPLETE|system");
        assertThat(rows.stream().filter(r -> "PROCESS_COMPLETE".equals(r[0])).findFirst()
                .orElseThrow()[2])
                .as("駁回被通報成 approved 是簽核系統最不能接受的錯誤（P2-1）")
                .contains("\"result\":\"rejected\"")
                .doesNotContain("\"result\":\"approved\"");

        // 申請人的「我的申請」：已結束、狀態 completed。
        String mine = mockMvc.perform(get("/api/history/process-instances")
                        .header("X-User-Id", APPLICANT)
                        .param("initiator", APPLICANT)
                        .param("finished", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mine).as("申請人必須在自己的清單看到這張已結束的單").contains(s.pid());
    }

    // ── ③ 退回補件：退回 → 補件 → 重送 → 核准 ──────────────────────

    @Test
    @DisplayName("退回 → 補件（PUT form-data）→ 重送 → 核准：表單兩列、稽核含 FORM_UPDATE 與 TASK_RESUBMIT")
    void returnReviseResubmitThenApproveCompletesTheCase() throws Exception {
        String original = "原始事由-" + UUID.randomUUID();
        String revised = "補件後事由-" + UUID.randomUUID();
        Started s = startWithFormData(original);

        // 主管退回（approved=false、無 returnTo → TASK_RETURN，路由到 applicantRevision）。
        completeTask(currentTask(s.pid()).getId(), MANAGER,
                completeWith("approved", "false", "rejected", "false"));

        Task revision = currentTask(s.pid());
        assertThat(revision.getTaskDefinitionKey())
                .as("退回後必須停在申請人補件關卡").isEqualTo("applicantRevision");
        assertThat(revision.getAssignee()).isEqualTo(APPLICANT);

        // 申請人補件：PUT /api/form-data/{id} 產生新版本（#58）。
        String reviseBody = objectMapper.writeValueAsString(Map.of(
                "dataJson", objectMapper.writeValueAsString(leaveValues(revised))));
        mockMvc.perform(put("/api/form-data/{id}", s.formDataId())
                        .header("X-User-Id", APPLICANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody))
                .andExpect(status().isOk());

        // 重送（完成補件任務）→ 回到主管 → 核准。
        completeTask(revision.getId(), APPLICANT, "{\"action\":\"complete\"}");
        Task secondReview = currentTask(s.pid());
        assertThat(secondReview.getTaskDefinitionKey())
                .as("重送後必須回到主管關卡").isEqualTo("managerReview");
        completeTask(secondReview.getId(), MANAGER,
                completeWith("approved", "true", "rejected", "false"));

        // ── 引擎與表單層 ─────────────────────────────────────────────
        assertThat(ended(s.pid())).isTrue();
        assertThat(historic(s.pid()).getEndActivityId()).isEqualTo("endApproved");
        List<String[]> forms = formRows(s.pid());
        assertThat(forms).as("原始送件＋補件版必須各留一列（版本化，不覆寫）").hasSize(2);
        assertThat(forms.get(0)[1]).as("第一列必須是原始內容").contains(original);
        assertThat(forms.get(1)[1]).as("第二列必須是補件內容").contains(revised);

        // ── 稽核層：補件路徑的每一種型別都必須出現，且各恰好一次 ─────
        List<String[]> rows = awaitAuditRows(s.pid(), 6);
        assertThat(opAndOperator(rows)).containsExactlyInAnyOrder(
                "PROCESS_START|" + APPLICANT,
                "TASK_RETURN|" + MANAGER,
                "FORM_UPDATE|" + APPLICANT,
                "TASK_RESUBMIT|" + APPLICANT,
                "TASK_APPROVE|" + MANAGER,
                "PROCESS_COMPLETE|system");
        assertThat(rows.stream().filter(r -> "FORM_UPDATE".equals(r[0])).findFirst()
                .orElseThrow()[2])
                .as("FORM_UPDATE 必須同時記新列與被取代的舊列，版本鏈才接得起來")
                .contains(s.formDataId());

        // 結案後申請人讀表單：最新版排最前面。
        String formResponse = mockMvc.perform(get("/api/form-data/{pid}", s.pid())
                        .header("X-User-Id", APPLICANT))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(formResponse).contains(revised);
        assertThat(formResponse.indexOf(revised))
                .as("getByProcess 依 submittedAt 遞減：補件版必須排在原始版前面")
                .isLessThan(formResponse.indexOf(original));
    }

    // ── ④ 補件後被拒絕：重送不是只能走向核准 ────────────────────────

    @Test
    @DisplayName("退回 → 重送 → 拒絕：第二輪拒絕必須正常結案且只結案一次")
    void resubmittedCaseCanStillBeRejectedExactlyOnce() throws Exception {
        Started s = startWithFormData("重送後被拒-" + UUID.randomUUID());

        completeTask(currentTask(s.pid()).getId(), MANAGER,
                completeWith("approved", "false", "rejected", "false"));
        Task revision = currentTask(s.pid());
        completeTask(revision.getId(), APPLICANT, "{\"action\":\"complete\"}");

        Task secondReview = currentTask(s.pid());
        completeTask(secondReview.getId(), MANAGER,
                completeWith("approved", "false", "rejected", "true",
                        "rejectReason", "\"預算刪減\""));

        assertThat(ended(s.pid())).isTrue();
        assertThat(historic(s.pid()).getEndActivityId()).isEqualTo("endRejected");
        assertThat(historicVar(s.pid(), "rejected")).isEqualTo(true);

        List<String[]> rows = awaitAuditRows(s.pid(), 5);
        assertThat(rows.stream().filter(r -> "PROCESS_COMPLETE".equals(r[0])).count())
                .as("結案稽核必須恰好一筆 —— 重送流程若有殘留 listener 會雙發")
                .isEqualTo(1);
        assertThat(opAndOperator(rows)).containsExactlyInAnyOrder(
                "PROCESS_START|" + APPLICANT,
                "TASK_RETURN|" + MANAGER,
                "TASK_RESUBMIT|" + APPLICANT,
                "TASK_REJECT|" + MANAGER,
                "PROCESS_COMPLETE|system");
    }

    // ── ⑤ 既有呼叫端：只帶 variables 也要能走完整條路 ───────────────

    @Test
    @DisplayName("非回歸：只帶 variables 的既有啟動路徑也能核准結案（#60 不得只修 formData 那條）")
    void variablesOnlyStartCanStillCompleteTheWholeLifecycle() throws Exception {
        String pid = startWithVariablesOnly();

        completeTask(currentTask(pid).getId(), MANAGER,
                completeWith("approved", "true", "rejected", "false"));

        assertThat(ended(pid)).isTrue();
        assertThat(historicVar(pid, "days")).isEqualTo(2);
        assertThat(historicVar(pid, "leaveType")).isEqualTo("personal");
        assertThat(opAndOperator(awaitAuditRows(pid, 3))).containsExactlyInAnyOrder(
                "PROCESS_START|" + APPLICANT,
                "TASK_APPROVE|" + MANAGER,
                "PROCESS_COMPLETE|system");
        assertThat(formRows(pid)).as("沒有 formData 的案件不該有表單列").isEmpty();
    }
}
