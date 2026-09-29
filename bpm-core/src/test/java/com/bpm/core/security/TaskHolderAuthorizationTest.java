package com.bpm.core.security;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #77：{@code PUT /api/tasks/{id}} 的持有者守衛。
 *
 * <h2>缺陷（真實 JWT 線上實測）</h2>
 *
 * <p>user001 送出 leave-approval，該案主管審核任務的 assignee 是 mgr001。
 * 讓與該案毫無關係的 user002 去簽：
 * <pre>
 * PUT /api/tasks/649d8b80-… {"action":"complete","variables":[{"name":"approved","value":true}]}
 * → HTTP 200 {"status":"ok"}
 * </pre>
 * 稽核：{@code PROCESS_START | user001} → {@code TASK_APPROVE | user002}
 * → {@code PROCESS_COMPLETE | system}。
 * <b>任何登入者可以批准或拒絕系統裡的任意請假單、任意採購單。</b>
 *
 * <p>而且這條路徑正是前端實際在用的表單寫入路徑
 * （{@code DocumentDetail.vue} → {@code PUT /api/tasks/{taskId}} 帶 variables）。
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：{@code ResponseStatusException} 會被
 * 容器轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@link ErrorDispatchTest}）。用 MockMvc 寫，這些測試在缺陷存在時
 * 會照樣全綠 —— 缺陷期間回的就是 200，缺陷修好後回 404，兩者都不會
 * 被 MockMvc 翻成別的樣子。
 *
 * <h2>⚠️ 每一條都同時斷言「資料真的沒變」</h2>
 *
 * <p>只斷言狀態碼是不夠的：這個缺陷的危險之處不在於回 200，而在於
 * <b>流程真的走完了</b>（稽核留下 TASK_APPROVE、案件結案）。
 * 因此被拒的請求一律另外驗證：任務仍在、assignee／owner 未變、
 * 流程實例仍在執行、{@code approved} 變數沒有被寫入。
 *
 * <h2>「非持有者」的取樣</h2>
 *
 * <p>{@link #OUTSIDER} 是完全不存在於任何 fixture 的人（沒權限、沒部門），
 * {@code user002} 則是一個真實登入者、與該案無關 —— 後者才是實測報告裡
 * 的攻擊者，也是最危險的取樣（缺陷期間正是他簽掉了別人的單）。
 */
class TaskHolderAuthorizationTest extends IntegrationTestBase {

    /** 沒有任何流程、沒有權限、也不在組織 fixture 裡的 id（真正的局外人）。 */
    private static final String OUTSIDER = "outsider001";

    /** 設計器「發起人所屬單位」會產生的候選群組指派（與 InvolvedInstancesTest 同一支）。 */
    private static final String OWN_DEPT_FLOW = "own-dept-group-flow";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    // ── HTTP 小工具 ────────────────────────────────────────────────

    private HttpResponse<String> put(String path, String userId, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── 情境小工具 ──────────────────────────────────────────────────

    /** 以 initiator 啟動 leave-approval，回傳主管審核任務（assignee = 其直屬主管）。 */
    private Task startLeaveAndGetManagerTask(String initiator) {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", initiator, "leaveType", "annual", "days", 1));
        return taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
    }

    private Task currentTask(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).singleResult();
    }

    private static String complete(boolean approved) {
        return "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":"
                + approved + "}]}";
    }

    /** 主管退回 → 補件任務指派給申請人本人（assignee=${initiator}）。 */
    private Task returnToApplicant(String initiator) {
        Task mgr = startLeaveAndGetManagerTask(initiator);
        taskService.complete(mgr.getId(), Map.of("approved", false, "rejected", false));
        return taskService.createTaskQuery().processInstanceId(mgr.getProcessInstanceId())
                .list().get(0);
    }

    /**
     * 啟動採購流程並讓主管關卡通過，產生 {@code financeReview}
     * （{@code candidateUsers}，assignee 為 null）。
     */
    private Task financeReviewTask() {
        var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "amount", 5000, "itemName", "測試品項"));
        Task mgr = taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
        taskService.complete(mgr.getId(), Map.of("approved", true, "rejected", false));
        return taskService.createTaskQuery().processInstanceId(pi.getId()).list().get(0);
    }

    private void deployOwnDeptFlow() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="test">
                  <process id="%s" name="所屬單位審核" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="deptReview"/>
                    <userTask id="deptReview" name="單位審核"
                              flowable:candidateGroups="${orgService.getDeptId(initiator)}"/>
                    <sequenceFlow id="f1" sourceRef="deptReview" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(OWN_DEPT_FLOW);
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(OWN_DEPT_FLOW).count() == 0) {
            repositoryService.createDeployment()
                    .addString(OWN_DEPT_FLOW + ".bpmn20.xml", xml).name(OWN_DEPT_FLOW).deploy();
        }
    }

    /** 被拒之後任務必須原封不動。 */
    private void assertUntouched(Task task, String expectedAssignee) {
        Task now = currentTask(task.getId());
        assertThat(now)
                .as("被拒的請求不得改動任務 —— 這個缺陷的危險之處是流程真的走完了，"
                        + "不只是回應狀態碼不對")
                .isNotNull();
        assertThat(now.getAssignee()).isEqualTo(expectedAssignee);
        assertThat(now.getOwner()).isNull();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).count())
                .as("案件必須仍在執行中").isEqualTo(1);
        assertThat(runtimeService.getVariable(task.getProcessInstanceId(), "approved"))
                .as("流程變數不得被寫入 —— 那是核准／退回的閘條件").isNull();
    }

    // ── 五個 action：非持有者一律 404 ───────────────────────────────

    @Test
    @DisplayName("#77：無關的使用者不得 complete 別人的任務（缺陷的原始案例）")
    void strangerCannotComplete() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");
        assertThat(task.getAssignee()).isEqualTo("mgr001");

        var res = put("/api/tasks/" + task.getId(), "user002", complete(true));

        assertThat(res.statusCode())
                .as("缺陷期間這裡回 200 {\"status\":\"ok\"}，流程直接走完")
                .isEqualTo(404);
        assertUntouched(task, "mgr001");
    }

    @Test
    @DisplayName("#77：無關的使用者不得 delegate 別人的任務")
    void strangerCannotDelegate() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");

        var res = put("/api/tasks/" + task.getId(), OUTSIDER,
                "{\"action\":\"delegate\",\"delegateUser\":\"outsider001\"}");

        assertThat(res.statusCode()).isEqualTo(404);
        assertUntouched(task, "mgr001");
    }

    @Test
    @DisplayName("#77：無關的使用者不得 reassign 別人的任務（搶走並改成自己）")
    void strangerCannotReassign() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");

        var res = put("/api/tasks/" + task.getId(), OUTSIDER,
                "{\"action\":\"reassign\",\"assignee\":\"outsider001\"}");

        assertThat(res.statusCode()).isEqualTo(404);
        assertUntouched(task, "mgr001");
    }

    @Test
    @DisplayName("#77：無關的使用者不得 claim 別人已指派的任務（搶先認領）")
    void strangerCannotClaim() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");

        var res = put("/api/tasks/" + task.getId(), OUTSIDER, "{\"action\":\"claim\"}");

        assertThat(res.statusCode()).isEqualTo(404);
        assertUntouched(task, "mgr001");
    }

    @Test
    @DisplayName("#77：無關的使用者不得 resolve 別人的任務")
    void strangerCannotResolve() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");

        var res = put("/api/tasks/" + task.getId(), OUTSIDER, "{\"action\":\"resolve\"}");

        assertThat(res.statusCode()).isEqualTo(404);
        assertUntouched(task, "mgr001");
    }

    @Test
    @DisplayName("#77：非持有者不得 claim 候選任務（engine 的 claim 不檢查候選資格）")
    void strangerCannotClaimCandidateTask() throws Exception {
        Task finance = financeReviewTask();
        assertThat(finance.getAssignee())
                .as("財務審核是 candidateUsers 任務，assignee 必須為 null")
                .isNull();

        var res = put("/api/tasks/" + finance.getId(), OUTSIDER, "{\"action\":\"claim\"}");

        assertThat(res.statusCode())
                .as("Flowable 的 claim 只擋「已被他人指派」，不擋「你不是候選人」—— "
                        + "沒有這個守衛，任何登入者都能在 mgr001 之前搶走財務關卡")
                .isEqualTo(404);
        assertThat(currentTask(finance.getId()).getAssignee())
                .as("候選任務不得被不相關的人認領").isNull();
    }

    // ── 持有者本人：四種身分都要能簽 ────────────────────────────────

    @Test
    @DisplayName("#77：assignee 本人 → 200")
    void assigneeCanComplete() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");

        var res = put("/api/tasks/" + task.getId(), "mgr001", complete(true));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"ok\"");
        assertThat(currentTask(task.getId())).as("任務應已完成").isNull();
    }

    @Test
    @DisplayName("#77：candidateUser → 200（先 claim 再 complete，purchase 的 financeReview）")
    void candidateUserCanClaimAndComplete() throws Exception {
        Task finance = financeReviewTask();
        assertThat(finance.getAssignee()).isNull();
        assertThat(taskService.getIdentityLinksForTask(finance.getId()))
                .as("dir001 應為財務審核的候選人（finance:payment:approve）")
                .anySatisfy(link -> assertThat(link.getUserId()).isEqualTo("dir001"));

        assertThat(put("/api/tasks/" + finance.getId(), "dir001",
                "{\"action\":\"claim\"}").statusCode()).isEqualTo(200);
        assertThat(currentTask(finance.getId()).getAssignee()).isEqualTo("dir001");

        assertThat(put("/api/tasks/" + finance.getId(), "dir001", complete(true)).statusCode())
                .as("候選人 claim 之後必須能簽 —— 否則候選任務只會永遠卡住")
                .isEqualTo(200);
        assertThat(currentTask(finance.getId())).isNull();
    }

    @Test
    @DisplayName("#77：候選群組成員 → 200（flowable:candidateGroups 指派的任務）")
    void candidateGroupMemberCanClaimAndComplete() throws Exception {
        deployOwnDeptFlow();
        String pid = runtimeService.startProcessInstanceByKey(OWN_DEPT_FLOW,
                Map.of("initiator", "user002")).getId();          // user002 → dept001
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).as("候選群組任務沒有受理人").isNull();

        // user001 同屬 dept001
        assertThat(put("/api/tasks/" + task.getId(), "user001",
                "{\"action\":\"claim\"}").statusCode())
                .as("群組成員必須能認領 —— 少了這條，設計器的「所屬單位」流程會對所有人卡死")
                .isEqualTo(200);
        assertThat(put("/api/tasks/" + task.getId(), "user001", complete(true)).statusCode())
                .isEqualTo(200);
        assertThat(currentTask(task.getId())).isNull();
    }

    @Test
    @DisplayName("#77：申請者能簽自己的補件任務（leave-approval 的 applicantRevision）")
    void initiatorCanSignOwnRevisionTask() throws Exception {
        Task revision = returnToApplicant("user001");
        assertThat(revision.getName()).contains("補件");
        assertThat(revision.getAssignee())
                .as("補件關卡是 ${initiator} → 申請人本人")
                .isEqualTo("user001");

        var res = put("/api/tasks/" + revision.getId(), "user001", complete(true));

        assertThat(res.statusCode())
                .as("補件任務的 assignee 就是申請人，守衛不得把這條路擋掉")
                .isEqualTo(200);
        // 補件完成後回到主管關卡
        assertThat(taskService.createTaskQuery()
                .processInstanceId(revision.getProcessInstanceId()).singleResult().getAssignee())
                .isEqualTo("mgr001");
    }

    // ── delegate／resolve 生命週期（本次最大的迴歸風險）──────────────
    //
    // ⚠️ 查證結果（Flowable 7.2.0，javap + TaskHelper 位元碼）：
    //
    //  * delegateTask(id, X)：delegationState = PENDING；owner 為 null 時
    //    把原本的 assignee 寫進 owner；assignee 換成 X。
    //  * complete：TaskHelper.completeTask 對 delegationState = PENDING 的任務
    //    **直接拋 FlowableException**（"cannot be completed, but should be
    //    resolved instead"）—— 也就是說「被委派的任務不能被 complete」是
    //    引擎的規則，不是本專案的選擇。
    //  * resolveTask(id)：把 assignee 還給 owner。
    //
    // 因此委派的完整往返是：owner delegate → delegatee resolve（他做完了）
    // → owner complete。守衛必須讓前兩步都通過。

    @Test
    @DisplayName("#77：被 delegate 之後 delegatee 必須能動作（否則委派功能被守衛打死）")
    void delegateeIsNotBlockedAfterDelegation() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");

        assertThat(put("/api/tasks/" + task.getId(), "mgr001",
                "{\"action\":\"delegate\",\"delegateUser\":\"user003\"}").statusCode())
                .isEqualTo(200);

        Task delegated = currentTask(task.getId());
        assertThat(delegated.getAssignee())
                .as("Flowable 的 delegateTask 會把 assignee 換成 delegatee")
                .isEqualTo("user003");
        assertThat(delegated.getOwner())
                .as("原本的 assignee 會被寫進 owner（delegate 與 resolve 都要靠它）")
                .isEqualTo("mgr001");

        // delegatee 做完了 → resolve 把任務交還給 owner。
        // 這一條是「守衛沒有過度阻擋」的正向證據：若守衛只認原 assignee，
        // 這裡會是 404，而整個委派功能就只剩「交出去」沒有「做完了回報」。
        var res = put("/api/tasks/" + delegated.getId(), "user003", "{\"action\":\"resolve\"}");

        assertThat(res.statusCode())
                .as("delegatee 是 assignee，守衛必須放行 —— 這是本次修改最大的風險點")
                .isEqualTo(200);
        assertThat(currentTask(task.getId()).getAssignee())
                .as("resolve 之後 assignee 必須回到 owner，owner 與 delegatee 才輪得到對方")
                .isEqualTo("mgr001");
    }

    @Test
    @DisplayName("#77：原指派人（owner）必須能 resolve 把被委派的任務收回來")
    void ownerCanResolveAfterDelegation() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");
        put("/api/tasks/" + task.getId(), "mgr001",
                "{\"action\":\"delegate\",\"delegateUser\":\"user003\"}");

        var res = put("/api/tasks/" + task.getId(), "mgr001", "{\"action\":\"resolve\"}");

        assertThat(res.statusCode())
                .as("resolve 的呼叫者只可能是 owner；守衛不放行 owner 等於讓委派只有去沒有回")
                .isEqualTo(200);
        assertThat(currentTask(task.getId()).getAssignee())
                .as("resolve 之後 assignee 必須回到 owner")
                .isEqualTo("mgr001");
    }

    @Test
    @DisplayName("#77：委派往返後 owner 仍可 complete（守衛不得把流程卡在 resolve 之後）")
    void ownerCanCompleteAfterTheDelegateRoundTrip() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");
        put("/api/tasks/" + task.getId(), "mgr001",
                "{\"action\":\"delegate\",\"delegateUser\":\"user003\"}");
        put("/api/tasks/" + task.getId(), "user003", "{\"action\":\"resolve\"}");

        var res = put("/api/tasks/" + task.getId(), "mgr001", complete(true));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(currentTask(task.getId())).isNull();
    }

    @Test
    @DisplayName("#77：被委派任務的 complete 由引擎拒絕，不得被守衛說成「任務不存在」")
    void completingADelegatedTaskIsRefusedByTheEngineNotByTheGuard() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");
        put("/api/tasks/" + task.getId(), "mgr001",
                "{\"action\":\"delegate\",\"delegateUser\":\"user003\"}");

        var res = put("/api/tasks/" + task.getId(), "user003", complete(true));

        // ⚠️ 這一條<b>不是</b>守衛的負向對照（缺陷存在時它也會通過，
        // 因為引擎本來就會擋）。它固定的是兩件事：
        //
        //  1. 守衛不得把引擎的規則變成 404 —— 對 delegatee 說「這個任務不存在」
        //     是謊話，而且會把一個可診斷的錯誤藏起來。
        //  2. 無論如何任務狀態不得改變。
        //
        // 引擎目前拋的是 FlowableException → 裸 500。把「被委派的任務請用
        // resolve」變成明確的 409 屬於另一件事（與 P1-3「守門回 200」同型），
        // 不在本次範圍內 —— 前端 ActionDialog 送 delegate、DocumentDetail 送
        // complete 的組合目前就是 500，見回報。
        assertThat(res.statusCode())
                .as("被引擎拒絕，不是被授權守衛拒絕")
                .isNotEqualTo(404);
        Task now = currentTask(task.getId());
        assertThat(now)
                .as("被拒的 complete 不得改動任務").isNotNull();
        assertThat(now.getAssignee()).isEqualTo("user003");
        assertThat(runtimeService.getVariable(task.getProcessInstanceId(), "approved"))
                .isNull();
    }

    @Test
    @DisplayName("#77：非持有者不得 resolve 被委派的任務（且任務仍在 delegatee 手上）")
    void strangerCannotResolveDelegatedTask() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");
        put("/api/tasks/" + task.getId(), "mgr001",
                "{\"action\":\"delegate\",\"delegateUser\":\"user003\"}");

        var res = put("/api/tasks/" + task.getId(), OUTSIDER, "{\"action\":\"resolve\"}");

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(currentTask(task.getId()).getAssignee())
                .as("被拒的 resolve 不得把任務從 delegatee 手上收回")
                .isEqualTo("user003");
    }

    // ── 讀寫兩端必須一致 ───────────────────────────────────────────

    /**
     * 待辦清單看得到的任務，寫入端不得拒絕（反之亦然）。
     *
     * <p>探針用<b>未知 action</b>（{@code {"action":"__probe__"}}）：守衛在前、
     * switch 的 default 在後，所以
     * <b>400 = 通過了授權檢查</b>、<b>404 = 被守衛擋下</b>，
     * 而兩種情況都不會碰到任務、也不會留下稽核。
     * 這個探針必須成對使用（正向 400 ＋ 負向 404）——
     * 只有正向時，「整條守衛壞掉、所有人都是 404」也會讓它失敗；
     * 只有負向時，「守衛放行所有人」也會讓它失敗。
     */
    @Test
    @DisplayName("#77：待辦看得到的任務寫入端不得拒絕，看不到的必須拒絕")
    void inboxAndWritePathAgree() throws Exception {
        Task mine = startLeaveAndGetManagerTask("user001");     // assignee mgr001
        Task theirs = startLeaveAndGetManagerTask("user004");   // assignee mgr002

        assertThat(get("/api/tasks", "mgr001").body())
                .as("前置條件：mgr001 的待辦含自己的、看得到別人的看不到")
                .contains(mine.getId()).doesNotContain(theirs.getId());

        assertThat(put("/api/tasks/" + mine.getId(), "mgr001", "{\"action\":\"__probe__\"}")
                .statusCode())
                .as("待辦看得到 → 守衛必須放行（400 = 走到 switch 的 default）")
                .isEqualTo(400);
        assertThat(put("/api/tasks/" + theirs.getId(), "mgr001", "{\"action\":\"__probe__\"}")
                .statusCode())
                .as("待辦看不到 → 守衛必須拒絕（404）；這一條讓上一條無法靠"
                        + "「整條守衛失效」達成")
                .isEqualTo(404);

        assertThat(currentTask(mine.getId()).getAssignee()).isEqualTo("mgr001");
        assertThat(currentTask(theirs.getId()).getAssignee()).isEqualTo("mgr002");
    }

    @Test
    @DisplayName("#77：被拒的簽核必須留下 DATA_ACCESS 稽核（有人嘗試簽別人的單）")
    void refusalIsAudited() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");

        put("/api/tasks/" + task.getId(), OUTSIDER, complete(true));

        var details = new java.util.ArrayList<String>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operator_id = '" + OUTSIDER + "' AND operation_type = 'DATA_ACCESS'")) {
                var rs = ps.executeQuery();
                while (rs.next()) details.add(rs.getString(1));
            }
        });
        assertThat(details)
                .as("拒絕的存取嘗試本身值得知道；不留痕就只剩下一堆沒有來源的 404")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"));
    }

    @Test
    @DisplayName("#77：正常簽核不得產生 DATA_ACCESS 拒絕紀錄（那是日常操作）")
    void holderIsNotRecordedAsDenied() throws Exception {
        Task task = startLeaveAndGetManagerTask("user001");

        assertThat(put("/api/tasks/" + task.getId(), "mgr001", complete(true)).statusCode())
                .isEqualTo(200);

        int[] count = {-1};
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log "
                    + "WHERE operator_id = 'mgr001' AND operation_type = 'DATA_ACCESS'")) {
                var rs = ps.executeQuery();
                if (rs.next()) count[0] = rs.getInt(1);
            }
        });
        assertThat(count[0]).isZero();
    }
}
