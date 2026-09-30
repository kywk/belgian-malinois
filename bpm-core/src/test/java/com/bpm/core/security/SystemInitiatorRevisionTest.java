package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.service.ApplicantResolver;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #83 的端到端驗證：<b>外部系統發起、沒有自然人申請人</b>的案件，
 * 主管退回之後的補件關卡<b>真的有人能簽</b>。
 *
 * <h2>缺陷的完整樣態（這是本測試組存在的理由）</h2>
 *
 * <pre>
 *   外部系統 erp 發起 leave-approval（initiator = system:erp，firstTaskAssignee = mgr001）
 *   → mgr001 按「退回」（approved=false）
 *   → applicantRevision 建立，assignee = ${initiator} = system:erp
 *   → TaskHolderGuard 的四個條件全部不命中
 *   → 沒有任何人看得到、沒有任何人能簽
 *   → 而且沒有例外、沒有 5xx、UnreachableTaskListener 也不告警（非空白的 assignee）
 * </pre>
 *
 * <h2>⚠️ 為什麼斷言走<b>真實 HTTP</b>而不是 MockMvc</h2>
 *
 * <p>這個工項最危險的失敗型態是<b>靜默卡死</b>，也就是「請求回 200 但案子死了」。
 * MockMvc 不做 error dispatch（見 {@code ErrorDispatchTest}），
 * 而「守衛放行之後才 5xx」與「守衛擋下回 404」在 MockMvc 下長得太像。
 * 用真實 HTTP 才能確定斷言的是「權限守衛真的放行了這個人」，
 * 而不是「某個 filter 剛好讓請求過去了」。
 *
 * <h2>⚠️ 負向控制組實測（2026-09-30，把兩支 BPMN 整份還原後重跑）</h2>
 *
 * <p>7 條中 <b>5 紅 2 綠</b>。紅的全是「外部系統發起」與「代發」的形狀，
 * 綠的兩條是刻意的非回歸對照組 —— 它們防的是<b>錯誤的修法</b>，不是這個缺陷。
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅</td>
 *       <td>{@code systemInitiatedCaseIsSignableAfterReturn}<br>
 *           {@code purchaseManagerRevisionIsSignable}<br>
 *           {@code purchaseFinanceRevisionIsSignable}<br>
 *           {@code onBehalfOfCaseGoesToTheEmployeeNotTheHandler}<br>
 *           {@code shippedBpmnMustNotAssignInitiatorDirectly}</td>
 *       <td>缺陷期間三個補件關卡的 assignee 都是 {@code system:erp}：
 *           有人能看得到的只有「主管退回」那一步（回 200，看起來正常），
 *           <b>之後就沒有任何錯誤訊息了</b>。</td></tr>
 *   <tr><td>🟢 綠</td>
 *       <td>{@code humanInitiatedRevisionStillGoesToTheApplicant}<br>
 *           {@code outsiderStillCannotSignTheRevisionTask}</td>
 *       <td>它們在缺陷期間就該綠，因為缺陷只影響系統發起的案件。
 *           <b>重點是它們證明修法沒有走偏</b>：把 assignee 判定放寬來讓人簽
 *           （最容易的「修法」）會讓 {@code outsiderStill...} 變紅，
 *           把人工發起的單也改派給受理人會讓 {@code humanInitiated...} 變紅。
 *           兩條必須成組存在，否則「修好了」與「亂修」分辨不出來。</td></tr>
 * </table>
 *
 * <p>另外值得記錄：缺陷期間 {@code onBehalfOf} 那條<b>也是紅的</b>。
 * 也就是說「代員工發起」與「系統發起」走的是同一行
 * {@code flowable:assignee="${initiator}"} —— 這是 #68c 與 #83 必須共用
 * 一個決定函式的實證，不是推測。
 *
 * <p>還原方式：<b>整份還原兩支 BPMN XML</b>（不是只改那一行屬性）。
 * 理由見 round-3 handoff 第 4.4 節。
 *
 * <p>第一輪負向控制組還<b>順帶做了一件非預期的事</b>：只還原兩支 BPMN、
 * 保留修好的 listener 時，13 條裡紅了 8 條 —— 也就是說 listener 的修正
 * 單獨存在時，這些測試<b>本來就該紅</b>（因為 assignee 還是 system:erp）。
 * 這反過來證明兩件事是<b>獨立</b>的兩條防線，而不是同一條：
 * 只修路由不改告警，案件還是會卡（只是有人會知道）；
 * 只改告警不改路由，案件一樣卡住。
 *
 * <h2>每一條正向斷言都同時驗「非空」與「不是系統身分」</h2>
 *
 * <p>只斷言 {@code statusCode == 200} 是危險的：一個「什麼都不做」的實作
 * （例如把 assignee 設成 null、或讓整個動作回 200 卻沒推進流程）
 * 也會讓它通過。因此每條都額外驗證：
 * <ul>
 *   <li>任務真的被派給了<b>一個具體的人</b>（{@code getAssignee()} 非 null、
 *       非空白、且不以 {@code system:} 開頭），</li>
 *   <li><b>那個人真的簽得掉</b>（真實 HTTP PUT → 200 → 任務消失 → 流程前進到下一關），</li>
 *   <li>而且那筆 TASK_UNREACHABLE 稽核是 <b>0 筆</b>（不是靠告警補救，是真的沒卡）。</li>
 * </ul>
 */
class SystemInitiatorRevisionTest extends IntegrationTestBase {

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private ExternalSystemRepository externalSystemRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    private static final String PLAIN_KEY = "sk-b83-testkey";

    /** 開發 fixture 裡持有 {@code bpm:external:revision} 的人（見 MockPermController）。 */
    private static final String HANDLER = "dir001";

    /** 完全不存在於任何 fixture 的人：真正的局外人。 */
    private static final String OUTSIDER = "outsider001";

    @BeforeEach
    void seedExternalSystem() {
        externalSystemRepo.deleteAll();
        given("erp", "[\"start_process\"]", "[\"leave-approval\",\"purchase-approval\"]");
    }

    private void given(String systemId, String allowedActions, String allowedProcessKeys) {
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId(systemId);
        sys.setSystemName("測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions(allowedActions);
        sys.setAllowedProcessKeys(allowedProcessKeys);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        externalSystemRepo.save(sys);
    }

    private void allowOnBehalfOf() {
        ExternalSystem sys = externalSystemRepo.findBySystemId("erp").orElseThrow();
        sys.setAllowOnBehalfOf(true);
        externalSystemRepo.save(sys);
    }

    // ── HTTP 小工具（真實 HTTP，見類別註解）─────────────────────────

    private HttpResponse<String> putTask(String taskId, String userId, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/tasks/" + taskId))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String complete(boolean approved) {
        return "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":"
                + approved + "}]}";
    }

    /** 用真正的外部 API 發起流程（走 API key 驗證，不是繞過它）。 */
    private String startExternally(String body) throws Exception {
        var res = mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return res.getResponse().getContentAsString()
                .replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
    }

    /** 外部系統發起（沒有 onBehalfOf），走完主管審核並退回。回傳補件關卡的 taskId。 */
    private String returnFromManagerReview(String pid) throws Exception {
        Task manager = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(manager.getName()).contains("主管審核");
        var res = putTask(manager.getId(), "mgr001", complete(false));
        assertThat(res.statusCode())
                .as("主管按退回必須成功 —— 若這裡就 5xx，代表 ApplicantResolver 拋錯了")
                .isEqualTo(200);
        Task revision = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        return revision.getId();
    }

    /** TASK_UNREACHABLE 告警筆數。0 = 沒有靜默卡死。 */
    private int unreachableAlerts(String pid) {
        int[] n = {-1};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log "
                    + "WHERE operation_type = 'TASK_UNREACHABLE' AND process_instance_id = ?")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                rs.next();
                n[0] = rs.getInt(1);
            }
        });
        return n[0];
    }

    // ── 缺陷的原始案例 ─────────────────────────────────────────────

    @Test
    @DisplayName("#83：外部系統發起 → 主管退回 → 補件關卡有一個真人能簽")
    void systemInitiatedCaseIsSignableAfterReturn() throws Exception {
        String pid = startExternally("{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"B83-" + UUID.randomUUID() + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}");

        assertThat(runtimeService.getVariable(pid, "initiator"))
                .as("前置條件：initiator 必須是系統身分，否則本測試測不到 #83")
                .isEqualTo("system:erp");

        String revisionId = returnFromManagerReview(pid);
        Task revision = taskService.createTaskQuery().taskId(revisionId).singleResult();

        // ── 非空斷言：任務確實被派給了「某一個人」────────────────────
        assertThat(revision.getAssignee())
                .as("缺陷期間這裡是 system:erp —— 非空白，所以 UnreachableTaskListener "
                        + "也不告警，四個持有者條件全不命中，沒有人能簽")
                .isNotNull()
                .isNotBlank()
                .doesNotStartWith("system:")
                .isEqualTo(HANDLER);

        assertThat(unreachableAlerts(pid))
                .as("修好之後不該有任何「沒有人看得到」的告警 —— 這張單不是靠告警"
                        + "提醒才沒卡住，是真的有人能處理")
                .isZero();

        // ── 證明請求真的送到了 engine：那個人真的簽得掉 ──────────────
        var res = putTask(revisionId, HANDLER, complete(true));

        assertThat(res.statusCode())
                .as("被指派的人必須真的能簽 —— 否則 assignee 只是看起來對")
                .isEqualTo(200);
        assertThat(taskService.createTaskQuery().taskId(revisionId).singleResult())
                .as("任務應已完成").isNull();
        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getTaskDefinitionKey())
                .as("補件完成後應回到主管審核關，而不是停在任何奇怪的地方")
                .isEqualTo("managerReview");
    }

    @Test
    @DisplayName("#83：買購單的兩個補件關卡同樣有人能簽（主管退回）")
    void purchaseManagerRevisionIsSignable() throws Exception {
        String pid = startExternally("{\"processDefinitionKey\":\"purchase-approval\","
                + "\"businessKey\":\"B83-p-" + UUID.randomUUID() + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"amount\":5000,\"itemName\":\"測試品項\"}}");

        String revisionId = returnFromManagerReview(pid);
        Task revision = taskService.createTaskQuery().taskId(revisionId).singleResult();
        assertThat(revision.getName()).contains("補件");
        assertThat(revision.getAssignee()).isEqualTo(HANDLER).doesNotStartWith("system:");

        assertThat(putTask(revisionId, HANDLER, complete(true)).statusCode()).isEqualTo(200);
        assertThat(unreachableAlerts(pid)).isZero();
    }

    @Test
    @DisplayName("#83：財務退回的補件關卡也有人能簽（第三個 UserTask）")
    void purchaseFinanceRevisionIsSignable() throws Exception {
        String pid = startExternally("{\"processDefinitionKey\":\"purchase-approval\","
                + "\"businessKey\":\"B83-f-" + UUID.randomUUID() + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"amount\":5000,\"itemName\":\"測試品項\"}}");

        // 主管通過 → financeReview（candidateUsers）
        Task manager = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(putTask(manager.getId(), "mgr001", complete(true)).statusCode()).isEqualTo(200);

        Task finance = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(finance.getTaskDefinitionKey()).isEqualTo("financeReview");
        assertThat(putTask(finance.getId(), HANDLER, "{\"action\":\"claim\"}").statusCode())
                .isEqualTo(200);

        // 財務退回 → revisionFromFinance
        assertThat(putTask(finance.getId(), HANDLER, complete(false)).statusCode()).isEqualTo(200);
        Task revision = taskService.createTaskQuery().processInstanceId(pid).singleResult();

        assertThat(revision.getTaskDefinitionKey()).isEqualTo("revisionFromFinance");
        assertThat(revision.getAssignee())
                .as("第三個補件關卡在缺陷期間也是 system:erp")
                .isEqualTo(HANDLER)
                .doesNotStartWith("system:");
        assertThat(putTask(revision.getId(), HANDLER, complete(true)).statusCode()).isEqualTo(200);
        assertThat(unreachableAlerts(pid)).isZero();
    }

    // ── 非回歸：既有行為必須完全不變 ───────────────────────────────

    @Test
    @DisplayName("非回歸：人工發起的補件關卡仍然派給申請人本人")
    void humanInitiatedRevisionStillGoesToTheApplicant() throws Exception {
        // 這一條與 #83 無關，但它防的是最嚴重的迴歸：
        // 補件關卡在人工發起時是產品最常走的一條路，
        // 而「統一改成派給系統受理人」會讓每一張人工請假單都卡在別人手上。
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        taskService.complete(manager.getId(), Map.of("approved", false, "rejected", false));

        Task revision = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(revision.getAssignee())
                .as("人工發起時不得改派給 " + HANDLER + " —— 那會把每張人工請假單卡住")
                .isEqualTo("user001");
    }

    @Test
    @DisplayName("非回歸：代員工發起（onBehalfOf）的補件關卡派給那位員工，不是受理人")
    void onBehalfOfCaseGoesToTheEmployeeNotTheHandler() throws Exception {
        // 這是 backlog #68c 已定調的規則。之所以在 #83 的測試組裡一起釘住：
        // 兩者共用同一個決定函式，沒有任何機制防止後者蓋掉前者。
        allowOnBehalfOf();
        String pid = startExternally("{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"B83-obo-" + UUID.randomUUID() + "\","
                + "\"onBehalfOf\":\"user001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}");

        assertThat(runtimeService.getVariable(pid, "onBehalfOf")).isEqualTo("user001");
        assertThat(runtimeService.getVariable(pid, "initiator"))
                .as("代發時 initiator 仍然是系統身分 —— 這正是 #68c 與 #83 共用同一段程式碼的原因")
                .isEqualTo("system:erp");

        String revisionId = returnFromManagerReview(pid);
        Task revision = taskService.createTaskQuery().taskId(revisionId).singleResult();

        assertThat(revision.getAssignee())
                .as("代發的單必須回到那位員工手上；派給受理人是把員工的單拿給別人補")
                .isEqualTo("user001");
        assertThat(putTask(revisionId, "user001", complete(true)).statusCode()).isEqualTo(200);
    }

    // ── 授權不得為了修掉卡死而放寬 ─────────────────────────────────

    @Test
    @DisplayName("非回歸：非持有者仍然不能簽補件關卡（修 #83 不得放寬 TaskHolderGuard）")
    void outsiderStillCannotSignTheRevisionTask() throws Exception {
        // ⚠️ 這是最容易順手做壞的地方：本工項讓「補件關卡不再卡死」很容易被
        // 誤達成「把 assignee 判定放寬」。這條與上面的正向案例必須成組存在 ——
        // 只有正向時，「整條守衛失效、所有人都能簽」也會讓它通過。
        String pid = startExternally("{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"B83-outsider-" + UUID.randomUUID() + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}");
        String revisionId = returnFromManagerReview(pid);

        for (String who : List.of(OUTSIDER, "mgr001", "user001")) {
            var res = putTask(revisionId, who, complete(true));
            assertThat(res.statusCode())
                    .as("%s 不是補件關卡的持有者，不得能簽", who)
                    .isEqualTo(404);
        }
        assertThat(taskService.createTaskQuery().taskId(revisionId).singleResult())
                .as("被拒的請求不得改動任務")
                .isNotNull();
        assertThat(unreachableAlerts(pid)).isZero();
    }

    // ── BPMN 本身的結構性保護 ─────────────────────────────────────

    @Test
    @DisplayName("回歸保護：兩支 BPMN 不得再出現 ${initiator} 指派（低程式碼平台的入口）")
    void shippedBpmnMustNotAssignInitiatorDirectly() throws Exception {
        // 這是「規則只能有一份」的靜態版本。UnreachableTaskListener 現在會告警，
        // 但告警是<b>事後</b>的：case 已經卡住了。能在部署前擋住它的只有這條。
        //
        // 觸發情境不是假想的 —— bpmn-js 設計器裡輸入 ${initiator} 是一個字串，
        // 而 lint 規則 h 只檢查<b>第一個</b> UserTask，補件關卡從來不在範圍內。
        for (String name : List.of("leave-approval", "purchase-approval")) {
            String xml = new String(getClass().getClassLoader()
                    .getResourceAsStream("processes/" + name + ".bpmn20.xml").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);

            assertThat(xml)
                    .as("%s 不得用 ${initiator} 指派 UserTask —— 外部系統發起時那是 "
                            + "system:<id>，沒有人能簽（#83）", name)
                    .doesNotContain("flowable:assignee=\"${initiator}\"");
            assertThat(xml)
                    .as("%s 的補件關卡必須走 applicantResolver", name)
                    .contains("applicantResolver.resolve(execution)");
        }
        assertThat(ApplicantResolver.PERM_EXTERNAL_REVISION)
                .as("受理權限碼是本測試組與 MockPermController fixture 的契約")
                .isEqualTo("bpm:external:revision");
    }
}
