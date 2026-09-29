package com.bpm.core.security;

import com.bpm.core.repository.DocumentRequestRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #66：啟動流程時的身分冒用。
 *
 * <h2>缺陷</h2>
 *
 * <p>{@code ProcessController.startProcess} 把 body 的 {@code initiator}
 * 直接寫進流程變數，也直接當作稽核的 operatorId。任何登入的使用者
 * 因此可以<b>用別人的名義送單</b>：主管路由、補件任務的 assignee、
 * 通知信的申請人、以及被 hash chain 固定下來的稽核紀錄，全部換成受害者。
 *
 * <p>這是全 repo 唯一一個產生身分語意卻沒有用 {@code @CallerId} 的啟動路徑 ——
 * 其他 11 個 controller 都有。外部 API 那條路已由 R-20 修好
 * （{@code ExternalApiController}），這條還沒。
 *
 * <h2>⚠️ 為什麼走真實 HTTP 而不是 MockMvc</h2>
 *
 * <p>本測試要斷言「例外怎麼變成 HTTP 狀態碼」。MockMvc 不做 error dispatch ——
 * {@code ResponseStatusException} 會被容器轉成 ERROR dispatch 打到
 * {@code /error}，而 R-01 的 {@code dispatcherTypeMatchers(ERROR)} 規則
 * 正是為此存在（見 {@link ErrorDispatchTest} 的類別註解）。用 MockMvc 寫，
 * 那條規則壞掉時測試照樣全綠。所以狀態碼走真實 HTTP；
 * 「流程有沒有被啟動」「任務派給誰」「稽核記了什麼」則直接查
 * 服務端的 repository / 稽核 DB —— 那才是這個缺陷真正傷害到的東西。
 *
 * <h2>身分從哪來</h2>
 *
 * <p>以「信任閘道」的身分送出（{@code X-Gateway-Secret} + {@code X-User-Id}），
 * 與 {@link TestGatewayMockMvcCustomizer} 及正式環境走的是同一條過濾器。
 * 測試不使用 JWT 見 {@code AuthenticationTest}（那組測的是 token 驗證本身）。
 */
class ProcessStartIdentityTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private DocumentRequestRepository docRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    private static final String LEAVE = "leave-approval";

    // ── HTTP 與資料查詢小工具 ─────────────────────────────────────

    private HttpResponse<String> post(String path, String userId, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String pidOf(String json) {
        return json.replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
    }

    private long countRunning(String processDefinitionKey) {
        return runtimeService.createProcessInstanceQuery().processDefinitionKey(processDefinitionKey).count();
    }

    private long docCount() {
        return docRepo.count();
    }

    /** 稽核總筆數。用「前後筆數不變」來斷言被拒的請求沒有留下任何紀錄。 */
    private static long auditRowCount() {
        long[] out = {-1L};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log")) {
                var rs = ps.executeQuery();
                rs.next();
                out[0] = rs.getLong(1);
            }
        });
        return out[0];
    }

    /** 某個流程實例的 PROCESS_START 稽核 operatorId；查不到回 null。 */
    private static String startOperatorOf(String processInstanceId) {
        var out = new java.util.concurrent.atomic.AtomicReference<String>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT TOP 1 operator_id FROM bpm_audit_log "
                            + "WHERE operation_type = 'PROCESS_START' AND process_instance_id = ?")) {
                ps.setString(1, processInstanceId);
                var rs = ps.executeQuery();
                if (rs.next()) out.set(rs.getString(1));
            }
        });
        return out.get();
    }

    /** 稽核寫入掛在交易的 beforeCommit，容許短暫延遲。 */
    private static String awaitStartOperator(String processInstanceId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        String operator = null;
        while (System.currentTimeMillis() < deadline && operator == null) {
            operator = startOperatorOf(processInstanceId);
            if (operator == null) Thread.sleep(100);
        }
        return operator;
    }

    // ── (a) body 的 initiator 欄位 ────────────────────────────────

    @Test
    @DisplayName("#66：body 帶 initiator 必須 400，且不得啟動流程、不得留下稽核紀錄")
    void initiatorInBodyIsRejected() throws Exception {
        long auditBefore = auditRowCount();
        long instancesBefore = countRunning(LEAVE);

        var res = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"" + LEAVE + "\","
                        + "\"businessKey\":\"forged-" + UUID.randomUUID() + "\","
                        + "\"initiator\":\"user002\"}");

        assertThat(res.statusCode())
                .as("改動前：body 的 initiator 被直接採信，請求回 200 —— 受害者背上多了一張單")
                .isEqualTo(400);
        assertThat(countRunning(LEAVE))
                .as("被拒的請求不得留下流程實例（否則錯誤的 400 會讓人重送，變成兩張單）")
                .isEqualTo(instancesBefore);
        assertThat(auditRowCount())
                .as("被拒的請求不得留下稽核紀錄 —— 尤其不得把 operatorId 記成受害者")
                .isEqualTo(auditBefore);
    }

    @Test
    @DisplayName("#66：即使送的是自己的 id 也照樣拒絕（有送 initiator 就不接受）")
    void initiatorIsRejectedEvenWhenItMatchesTheCaller() throws Exception {
        // 政策：body 帶了 initiator 欄位就明確拒絕。理由是呼叫端送出這個欄位
        // 本身就表示它期待該值被採信；「送了對的」與「送了錯的」對呼叫端來說
        // 都是同一份契約，不該一個 400 一個 200。
        var res = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"" + LEAVE + "\",\"initiator\":\"user001\"}");

        assertThat(res.statusCode()).isEqualTo(400);
    }

    // ── (b)(c) variables 夾帶內部／身分變數 ───────────────────────

    @Test
    @DisplayName("#66：不得以 variables 夾帶 onBehalfOf 繞過 R-20 的系統授權")
    void onBehalfOfCannotBeSmuggledThroughVariables() throws Exception {
        // 這是實測可用的繞過：body 的 variables 是自由 map，
        // 在 onBehalfOf 授權檢查之前就被寫進流程 —— 單子會出現在 user002 名下，
        // GET /api/process-instances 還會標示 onBehalf=true。
        // 等於把「需管理員為此系統開啟的授權」變成任何登入者都能行使。
        long instancesBefore = countRunning(LEAVE);

        var res = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"" + LEAVE + "\","
                        + "\"variables\":{\"leaveType\":\"annual\",\"days\":1,"
                        + "\"onBehalfOf\":\"user002\"}}");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(countRunning(LEAVE))
                .as("被拒的請求不得留下流程實例").isEqualTo(instancesBefore);
    }

    @Test
    @DisplayName("#66：不得以 variables 夾帶 _ 前綴的伺服器內部變數")
    void internalVariablesCannotBeSmuggledThroughVariables() throws Exception {
        // _externalSystemId 是 R-09 精確比對的擁有權依據，
        // _formVersions 是表單版本鎖定的依據 —— 兩者都只該由 server 寫入。
        // 啟動路徑改動前完全沒有保護（TaskController 的 R-23 只管完成路徑）。
        for (String name : List.of("_externalSystemId", "_formVersions", "_callbackUrl",
                "initiator", "effectiveInitiator")) {
            long before = countRunning(LEAVE);

            var res = post("/api/process-instances", "user001",
                    "{\"processDefinitionKey\":\"" + LEAVE + "\","
                            + "\"variables\":{\"" + name + "\":\"injected\"}}");

            assertThat(res.statusCode())
                    .as("夾帶 " + name + " 必須被拒 —— 它改寫的是伺服器內部狀態或身分語意")
                    .isEqualTo(400);
            assertThat(countRunning(LEAVE))
                    .as("夾帶 " + name + " 被拒時不得留下流程實例").isEqualTo(before);
        }
    }

    // ── 正常路徑：身分來自登入者 ──────────────────────────────────

    @Test
    @DisplayName("#66：省略 initiator 時，流程變數的 initiator 與稽核 operatorId 都是登入者")
    void initiatorComesFromTheAuthenticatedCaller() throws Exception {
        var res = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"" + LEAVE + "\","
                        + "\"businessKey\":\"caller-" + UUID.randomUUID() + "\","
                        + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}");

        assertThat(res.statusCode()).isEqualTo(200);
        String pid = pidOf(res.body());

        assertThat(runtimeService.getVariable(pid, "initiator"))
                .as("initiator 必須是登入者。改動前 body 沒帶時 initiator 會是 null，"
                        + "下游連主管都解析不到")
                .isEqualTo("user001");
        assertThat(awaitStartOperator(pid))
                .as("稽核的 operatorId 必須是真正做事的人。改動前它記的是 body 的 initiator，"
                        + "等於把受害者寫進不可否認的 hash chain")
                .isEqualTo("user001");
    }

    @Test
    @DisplayName("#66（最重要）：第一關必須派給登入者自己的直屬主管")
    void firstTaskGoesToTheCallersOwnManager() throws Exception {
        // 這一條直接對應缺陷真正被竊取的兩個值：簽核路由與稽核。
        // leave-approval 的 managerReview 用 ${assigneeResolver.resolve(execution)}，
        // 內部讀 initiator 去查直屬主管（orgService.getDirectManager）。
        //
        // 刻意挑兩個主管不同的呼叫者（user001→mgr001、user004→mgr002）：
        // 若 initiator 其實仍可被指定，兩個案例會得到同一個答案，
        // 測試就分辨不出「路由跟著呼叫者走」與「路由跟著某個固定值走」。
        for (String[] c : new String[][]{{"user001", "mgr001"}, {"user004", "mgr002"}}) {
            String caller = c[0];
            String expectedManager = c[1];

            var res = post("/api/process-instances", caller,
                    "{\"processDefinitionKey\":\"" + LEAVE + "\","
                            + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}");
            assertThat(res.statusCode()).isEqualTo(200);
            String pid = pidOf(res.body());

            Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
            assertThat(task).as("leave-approval 啟動後應有一個主管審核任務").isNotNull();
            assertThat(task.getAssignee())
                    .as(caller + " 的單子必須派給 " + caller + " 自己的主管 " + expectedManager)
                    .isEqualTo(expectedManager);
        }
    }

    @Test
    @DisplayName("#66：一般的業務變數必須照常放行（保護名單不得擋掉正常表單）")
    void normalBusinessVariablesStillWork() throws Exception {
        // 保護的是「改寫引擎與身分語意」的變數，不是所有變數。
        // 依 spec §8.5，欄位 id == 變數名，leaveType／reason 就是流程變數。
        var res = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"" + LEAVE + "\","
                        + "\"businessKey\":\"normal-" + UUID.randomUUID() + "\","
                        + "\"variables\":{\"leaveType\":\"personal\",\"days\":3,"
                        + "\"reason\":\"家庭因素\"}}");
        assertThat(res.statusCode()).isEqualTo(200);

        String pid = pidOf(res.body());
        assertThat(runtimeService.getVariable(pid, "leaveType")).isEqualTo("personal");
        assertThat(runtimeService.getVariable(pid, "reason")).isEqualTo("家庭因素");
    }

    // ── (d) DocumentController 的 createdBy ──────────────────────

    @Test
    @DisplayName("#66：POST /api/documents 不得以 createdBy 冒用他人")
    void documentCreatedByCannotBeImpersonated() throws Exception {
        long auditBefore = auditRowCount();
        long docsBefore = docCount();

        var res = post("/api/documents", "user001",
                "{\"title\":\"冒用測試\",\"createdBy\":\"admin001\","
                        + "\"category\":\"leave-approval\"}");

        assertThat(res.statusCode())
                .as("改動前：createdBy 由 body 指定，冒用完全無阻")
                .isEqualTo(400);
        assertThat(docCount())
                .as("被拒的請求不得留下公文").isEqualTo(docsBefore);
        assertThat(auditRowCount())
                .as("被拒的請求不得留下稽核紀錄（原本會記成受害者）").isEqualTo(auditBefore);
    }

    @Test
    @DisplayName("#66：POST /api/documents 省略 createdBy 時以登入者為準（部門、initiator、稽核一致）")
    void documentUsesCallerAsCreatedBy() throws Exception {
        var res = post("/api/documents", "user001",
                "{\"title\":\"正常建立\",\"category\":\"leave-approval\"}");
        assertThat(res.statusCode()).isEqualTo(200);

        String documentNumber = res.body().replaceAll(".*\"documentNumber\":\"([^\"]*)\".*", "$1");
        String pid = res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");

        assertThat(runtimeService.getVariable(pid, "initiator"))
                .as("流程的 initiator 必須是登入者").isEqualTo("user001");
        assertThat(awaitStartOperator(pid))
                .as("稽核的 operatorId 必須是登入者").isEqualTo("user001");

        var doc = docRepo.findAll().stream()
                .filter(d -> documentNumber.equals(d.getDocumentNumber()))
                .findFirst().orElseThrow();
        assertThat(doc.getCreatedBy()).isEqualTo("user001");
        // 公文編號的部門代碼也是從 createdBy 推導的 —— 用 body 的值會讓公文
        // 掛在別人的部門下（DOC-2026-DEPT001），那本身就是身分冒用的一部分。
        assertThat(documentNumber)
                .as("user001 屬 dept001，公文編號必須反映登入者的部門")
                .startsWith("DOC-" + java.time.Year.now().getValue() + "-DEPT001");
    }
}
