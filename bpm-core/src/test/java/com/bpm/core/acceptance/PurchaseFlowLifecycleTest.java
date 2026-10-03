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
 * 採購流程的兩關生命週期（主管 → 財務）端到端整合測試。
 *
 * <h2>為什麼需要這個類別</h2>
 *
 * <p>採購流程是本平台唯一<b>兩個人工關卡</b>的內建流程，而既有測試都只覆蓋
 * 其中一段：{@code CommentTcA02Test} 建立財務關卡只為了留言、
 * {@code ReturnToInitiatorTest} 只驗退回路由、{@code InvolvedInstancesTest}
 * 只驗關係人判定。沒有任何一條測試真的把「主管核准 → 財務核准／拒絕」走完
 * 並檢查跨層結果。
 *
 * <p>兩關流程的缺陷型態與單關不同，而且是分段測試抓不到的：
 * <ul>
 *   <li>第一關核准後財務任務的<b>候選人解析</b>（{@code permService}）失敗 →
 *       任務沒有人看得到，案件卡死但每段測試各自綠燈。</li>
 *   <li>財務關卡需要<b>先認領再完成</b>（候選任務沒有 assignee）——
 *       少一步就 404，而這是流程語意不是授權問題。</li>
 *   <li>退回後重送的<b>回歸點</b>（主管退回 → 回主管；財務退回 → 也回主管）
 *       與 {@code returnTo} 的殘留，只有在完整走完第二輪才驗得到。</li>
 * </ul>
 *
 * <p>因此每條測試都斷言：引擎最終狀態（end event＋歷史變數）、財務關卡是否
 * 真的出現／不該出現、稽核操作鏈（含認領與兩關的操作者）、以及採購表單的
 * 落地與版本化。
 */
class PurchaseFlowLifecycleTest extends IntegrationTestBase {

    private static final String APPLICANT = "user001";
    private static final String MANAGER = "mgr001";
    private static final String FINANCE = "dir001";
    private static final String PURCHASE = "purchase-approval";
    private static final String PURCHASE_FORM_KEY = "purchase-request";

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
        truncateAuditLog();
    }

    // ── 情境工具 ────────────────────────────────────────────────────

    private record Started(String pid, String formDataId) {}

    private static Map<String, Object> purchaseValues(String reason, int amount) {
        return Map.of("itemName", "測試品項-" + UUID.randomUUID().toString().substring(0, 8),
                "quantity", 2,
                "amount", amount,
                "reason", reason);
    }

    private Started startWithFormData(String reason, int amount) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "processDefinitionKey", PURCHASE,
                "businessKey", "purchase-e2e-" + UUID.randomUUID(),
                "formData", Map.of(
                        "formDefinitionId", PURCHASE_FORM_KEY,
                        "dataJson", objectMapper.writeValueAsString(purchaseValues(reason, amount)))));
        String response = mockMvc.perform(post("/api/process-instances")
                        .header("X-User-Id", APPLICANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Started(field(response, "processInstanceId"), field(response, "formDataId"));
    }

    private Task currentTask(String pid) {
        return taskService.createTaskQuery().processInstanceId(pid).singleResult();
    }

    private void completeTask(String taskId, String user, String body) throws Exception {
        mockMvc.perform(put("/api/tasks/{id}", taskId)
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void claimTask(String taskId, String user) throws Exception {
        mockMvc.perform(put("/api/tasks/{id}", taskId)
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"claim\"}"))
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

    /** 主管核准，回傳新出現的財務任務（並斷言候選人解析正確）。 */
    private Task managerApproveAndReachFinance(String pid) throws Exception {
        completeTask(currentTask(pid).getId(), MANAGER,
                completeWith("approved", "true", "rejected", "false"));
        Task finance = currentTask(pid);
        assertThat(finance.getTaskDefinitionKey())
                .as("主管核准後必須出現財務關卡").isEqualTo("financeReview");
        assertThat(taskService.getIdentityLinksForTask(finance.getId()))
                .as("財務關卡沒有 assignee，候選人必須由 permService 解析出來，否則案件沒人看得到")
                .anySatisfy(link -> assertThat(link.getUserId()).isEqualTo(FINANCE));
        return finance;
    }

    private boolean ended(String pid) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(pid).count() == 0;
    }

    private HistoricProcessInstance historic(String pid) {
        HistoricProcessInstance h = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        assertThat(h).as("案件必須存在於歷史").isNotNull();
        return h;
    }

    private Object historicVar(String pid, String name) {
        var v = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(pid).variableName(name).singleResult();
        return v == null ? null : v.getValue();
    }

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

    // ── ① 兩關都核准 ────────────────────────────────────────────────

    @Test
    @DisplayName("主管核准 → 財務認領＋核准 → 結案：endApproved、稽核含兩關與認領")
    void managerThenFinanceApproveEndsWithApprovedResult() throws Exception {
        String reason = "兩關核准-" + UUID.randomUUID();
        Started s = startWithFormData(reason, 5000);

        Task finance = managerApproveAndReachFinance(s.pid());
        claimTask(finance.getId(), FINANCE);
        completeTask(finance.getId(), FINANCE,
                completeWith("approved", "true", "rejected", "false",
                        "approverComment", "\"預算足夠\""));

        assertThat(ended(s.pid())).as("財務核准後案件必須結束").isTrue();
        assertThat(historic(s.pid()).getEndActivityId()).isEqualTo("endApproved");
        assertThat(historicVar(s.pid(), "approved")).isEqualTo(true);
        assertThat(historicVar(s.pid(), "reason")).isEqualTo(reason);
        assertThat(formRows(s.pid())).as("採購單的表單必須落地").hasSize(1);

        List<String[]> rows = awaitAuditRows(s.pid(), 5);
        assertThat(opAndOperator(rows)).containsExactlyInAnyOrder(
                "PROCESS_START|" + APPLICANT,
                "TASK_APPROVE|" + MANAGER,
                "TASK_CLAIM|" + FINANCE,
                "TASK_APPROVE|" + FINANCE,
                "PROCESS_COMPLETE|system");
        assertThat(rows.stream().filter(r -> "PROCESS_COMPLETE".equals(r[0])).findFirst()
                .orElseThrow()[2]).contains("\"result\":\"approved\"");
    }

    // ── ② 財務拒絕 ─────────────────────────────────────────────────

    @Test
    @DisplayName("主管核准 → 財務拒絕 → 結案：endRejected，財務的操作者與理由都留痕")
    void financeRejectEndsWithRejectedResult() throws Exception {
        Started s = startWithFormData("財務拒絕-" + UUID.randomUUID(), 9000);

        Task finance = managerApproveAndReachFinance(s.pid());
        claimTask(finance.getId(), FINANCE);
        completeTask(finance.getId(), FINANCE,
                completeWith("approved", "false", "rejected", "true",
                        "rejectReason", "\"超出年度預算\""));

        assertThat(ended(s.pid())).isTrue();
        assertThat(historic(s.pid()).getEndActivityId()).isEqualTo("endRejected");
        assertThat(historicVar(s.pid(), "rejected")).isEqualTo(true);

        List<String[]> rows = awaitAuditRows(s.pid(), 5);
        assertThat(opAndOperator(rows)).containsExactlyInAnyOrder(
                "PROCESS_START|" + APPLICANT,
                "TASK_APPROVE|" + MANAGER,
                "TASK_CLAIM|" + FINANCE,
                "TASK_REJECT|" + FINANCE,
                "PROCESS_COMPLETE|system");
        assertThat(rows.stream().filter(r -> "PROCESS_COMPLETE".equals(r[0])).findFirst()
                .orElseThrow()[2])
                .as("財務拒絕也必須被結案稽核正確判定")
                .contains("\"result\":\"rejected\"");
        assertThat(rows.stream().filter(r -> "TASK_REJECT".equals(r[0])).findFirst()
                .orElseThrow()[2]).contains("超出年度預算");
    }

    // ── ③ 主管拒絕：不得出現財務關卡 ────────────────────────────────

    @Test
    @DisplayName("主管拒絕 → 直接結案：財務關卡從未出現，也不得有財務的任何稽核")
    void managerRejectNeverCreatesFinanceStage() throws Exception {
        Started s = startWithFormData("主管拒絕-" + UUID.randomUUID(), 3000);

        completeTask(currentTask(s.pid()).getId(), MANAGER,
                completeWith("approved", "false", "rejected", "true",
                        "rejectReason", "\"品項規格不符\""));

        assertThat(ended(s.pid())).isTrue();
        assertThat(historic(s.pid()).getEndActivityId()).isEqualTo("endRejected");
        assertThat(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(s.pid()).list())
                .as("主管拒絕後不得有任何財務關卡的歷史任務")
                .allSatisfy(t -> assertThat(t.getTaskDefinitionKey()).isEqualTo("managerReview"));

        List<String[]> rows = awaitAuditRows(s.pid(), 3);
        assertThat(opAndOperator(rows)).containsExactlyInAnyOrder(
                "PROCESS_START|" + APPLICANT,
                "TASK_REJECT|" + MANAGER,
                "PROCESS_COMPLETE|system");
        assertThat(rows).as("財務沒有動作就不得有財務的稽核")
                .allSatisfy(r -> assertThat(r[1]).isNotEqualTo(FINANCE));
    }

    // ── ④ 財務退回 → 補件 → 重送 → 兩關再走一次 ─────────────────────

    @Test
    @DisplayName("財務退回 → 補件 → 重送回主管 → 兩關核准：回歸點正確且表單兩列")
    void financeReturnThenReviseThenResubmitCompletesAfterBothStagesAgain() throws Exception {
        String original = "財務退回前-" + UUID.randomUUID();
        String revised = "補件後-" + UUID.randomUUID();
        Started s = startWithFormData(original, 7000);

        Task finance = managerApproveAndReachFinance(s.pid());
        // 一般退回（無 returnTo）：財務 → revisionFromFinance（上一站）。
        completeTask(finance.getId(), FINANCE,
                completeWith("approved", "false", "rejected", "false",
                        "approverComment", "\"請補充報價單\""));

        Task revision = currentTask(s.pid());
        assertThat(revision.getTaskDefinitionKey())
                .as("財務的一般退回必須回上一站（財務退回補件），不是回起點")
                .isEqualTo("revisionFromFinance");
        assertThat(revision.getAssignee()).isEqualTo(APPLICANT);

        // 補件（reason 是 seed schema 中 editableOnRevision=true 的欄位）。
        String reviseBody = objectMapper.writeValueAsString(Map.of(
                "dataJson", objectMapper.writeValueAsString(purchaseValues(revised, 7000))));
        mockMvc.perform(put("/api/form-data/{id}", s.formDataId())
                        .header("X-User-Id", APPLICANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody))
                .andExpect(status().isOk());

        completeTask(revision.getId(), APPLICANT, "{\"action\":\"complete\"}");
        Task managerAgain = currentTask(s.pid());
        assertThat(managerAgain.getTaskDefinitionKey())
                .as("財務退回補件後必須回主管（BPMN flowResubmit2），不是直接回財務")
                .isEqualTo("managerReview");
        completeTask(managerAgain.getId(), MANAGER,
                completeWith("approved", "true", "rejected", "false"));

        Task financeAgain = currentTask(s.pid());
        assertThat(financeAgain.getTaskDefinitionKey()).isEqualTo("financeReview");
        claimTask(financeAgain.getId(), FINANCE);
        completeTask(financeAgain.getId(), FINANCE,
                completeWith("approved", "true", "rejected", "false"));

        assertThat(ended(s.pid())).isTrue();
        assertThat(historic(s.pid()).getEndActivityId()).isEqualTo("endApproved");
        List<String[]> forms = formRows(s.pid());
        assertThat(forms).as("原始採購單＋補件版各一列").hasSize(2);
        assertThat(forms.get(0)[1]).contains(original);
        assertThat(forms.get(1)[1]).contains(revised);

        List<String[]> rows = awaitAuditRows(s.pid(), 9);
        assertThat(opAndOperator(rows)).containsExactlyInAnyOrder(
                "PROCESS_START|" + APPLICANT,
                "TASK_APPROVE|" + MANAGER,
                "TASK_RETURN|" + FINANCE,
                "FORM_UPDATE|" + APPLICANT,
                "TASK_RESUBMIT|" + APPLICANT,
                "TASK_APPROVE|" + MANAGER,
                "TASK_CLAIM|" + FINANCE,
                "TASK_APPROVE|" + FINANCE,
                "PROCESS_COMPLETE|system");
    }

    // ── ⑤ 財務退回申請人（returnTo=initiator）後仍能走完 ─────────────

    @Test
    @DisplayName("財務 returnTo=initiator → 回起點補件 → 重送 → 兩關核准：returnTo 不得殘留")
    void financeReturnToInitiatorLoopCanStillComplete() throws Exception {
        Started s = startWithFormData("退回起點-" + UUID.randomUUID(), 4200);

        Task finance = managerApproveAndReachFinance(s.pid());
        // 財務退回申請人：approved／rejected／returnTo 由伺服器寫入。
        completeTask(finance.getId(), FINANCE, "{\"action\":\"complete\",\"returnTo\":\"initiator\"}");

        Task revision = currentTask(s.pid());
        assertThat(revision.getTaskDefinitionKey())
                .as("returnTo=initiator 必須回起點（revisionFromManager）")
                .isEqualTo("revisionFromManager");
        assertThat(revision.getAssignee()).isEqualTo(APPLICANT);

        completeTask(revision.getId(), APPLICANT, "{\"action\":\"complete\"}");
        Task managerAgain = currentTask(s.pid());
        assertThat(managerAgain.getTaskDefinitionKey()).isEqualTo("managerReview");
        completeTask(managerAgain.getId(), MANAGER,
                completeWith("approved", "true", "rejected", "false"));

        Task financeAgain = currentTask(s.pid());
        assertThat(financeAgain.getTaskDefinitionKey()).isEqualTo("financeReview");
        claimTask(financeAgain.getId(), FINANCE);
        // ⚠️ 這一輪是「一般核准」，不是退回申請人。TaskController 每次都重寫
        // returnTo（非退回時寫空字串）；若它只在退回時寫入，上一輪的
        // 'initiator' 會殘留，這裡就會被 gw2 誤路由回 revisionFromManager。
        completeTask(financeAgain.getId(), FINANCE,
                completeWith("approved", "true", "rejected", "false"));

        assertThat(ended(s.pid())).isTrue();
        assertThat(historic(s.pid()).getEndActivityId()).isEqualTo("endApproved");

        List<String[]> rows = awaitAuditRows(s.pid(), 8);
        assertThat(opAndOperator(rows)).containsExactlyInAnyOrder(
                "PROCESS_START|" + APPLICANT,
                "TASK_APPROVE|" + MANAGER,
                "TASK_RETURN_INITIATOR|" + FINANCE,
                "TASK_RESUBMIT|" + APPLICANT,
                "TASK_APPROVE|" + MANAGER,
                "TASK_CLAIM|" + FINANCE,
                "TASK_APPROVE|" + FINANCE,
                "PROCESS_COMPLETE|system");
    }

    // ── 表單存取對照：結案後申請人仍可讀 ────────────────────────────

    @Test
    @DisplayName("結案後申請人仍讀得到採購單內容（表單是持久資料）")
    void purchaseFormRemainsReadableAfterCompletion() throws Exception {
        String reason = "結案後可讀-" + UUID.randomUUID();
        Started s = startWithFormData(reason, 1500);

        Task finance = managerApproveAndReachFinance(s.pid());
        claimTask(finance.getId(), FINANCE);
        completeTask(finance.getId(), FINANCE,
                completeWith("approved", "true", "rejected", "false"));

        String formResponse = mockMvc.perform(get("/api/form-data/{pid}", s.pid())
                        .header("X-User-Id", APPLICANT))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // dataJson 在回應中是轉義過的字串欄位，解析後再比對，避免對轉義層的斷言。
        String dataJson = objectMapper.readTree(formResponse).get(0).path("dataJson").asText();
        assertThat(dataJson).contains(reason).contains("\"amount\":1500");
    }
}
