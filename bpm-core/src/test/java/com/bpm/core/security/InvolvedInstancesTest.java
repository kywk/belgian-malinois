package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
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
 * #71：兩個「我參與的」新端點與候選群組的處理。
 *
 * <h2>為什麼要拆成兩個端點而不是一個</h2>
 *
 * <p>{@code GET /api/process-instances} 是「我的申請」。若把「我審過／正在審的」
 * 混進同一個列表，那個頁面會出現大量自己沒送過、而且已經審完的單 ——
 * 「我的申請」這個名稱就失效了。反過來，實務上最常見的問題恰恰是
 * 「我上個月審過那張單，現在卡在別人的關卡」，那不是我的申請，
 * 卻明確是我參與的。兩種視角因此拆成不同 API（2026-09-29 政策）。
 */
class InvolvedInstancesTest extends IntegrationTestBase {

    private static final String OUTSIDER = "outsider001";

    /** 設計器「發起人所屬單位」會產生的那個運算式。 */
    private static final String OWN_DEPT_FLOW = "own-dept-group-flow";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String startCase(String initiator) {
        return runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", initiator, "leaveType", "annual", "days", 1)).getId();
    }

    /** 完成主管關卡並「退回」，案件仍在執行中但任務已指派給申請人。 */
    private void returnToApplicant(String pid) {
        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(task.getId(), Map.of("approved", false, "rejected", false));
    }

    private void approveAndFinish(String pid) {
        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(task.getId(), Map.of("approved", true, "rejected", false));
    }

    // ── GET /api/process-instances/involved ────────────────────────

    @Test
    @DisplayName("#71：我參與的（執行中）必須包含「我審過、現在在別人手上」的案件")
    void involvedIncludesCasesHandedOverToSomeoneElse() throws Exception {
        String pid = startCase("user001");
        // mgr001 審完退回 → 任務指派給申請人 user001，案件仍在執行中。
        // 這一刻 mgr001 在 ACT_RU_TASK 裡已經沒有任何任務了 ——
        // 若以 runtime 任務為驅動表，這張單就會從他的「我參與的」消失。
        returnToApplicant(pid);

        var res = get("/api/process-instances/involved", "mgr001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("審過第一關、案子現在在申請人手上，仍然是「我參與的」")
                .contains(pid);
        assertThat(res.body()).contains("currentTask");
        assertThat(res.body()).contains("申請者補件");
    }

    @Test
    @DisplayName("#71：我參與的只回呼叫者參與的，outsider 看不到")
    void involvedIsScopedToTheCaller() throws Exception {
        String pid = startCase("user001");
        assertThat(get("/api/process-instances/involved", OUTSIDER).body())
                .doesNotContain(pid);
        assertThat(get("/api/process-instances/involved", "user001").body())
                .as("申請人自己當然看得到")
                .contains(pid);
    }

    @Test
    @DisplayName("#71：已結束的案件不得出現在執行中的「我參與的」")
    void finishedCasesAreNotInTheRunningInvolvedList() throws Exception {
        String pid = startCase("user001");
        approveAndFinish(pid);

        var res = get("/api/process-instances/involved", "mgr001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("結束的案件屬於歷史清單；放在這裡會讓「進行中」頁面出現審結的單")
                .doesNotContain(pid);
    }

    @Test
    @DisplayName("#71：沒有參與任何案件的呼叫者得到空清單而不是 500")
    void emptyResultIsNotAnError() throws Exception {
        var res = get("/api/process-instances/involved", OUTSIDER);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("[]");
    }

    // ── GET /api/history/process-instances/involved ────────────────

    @Test
    @DisplayName("#71：歷史版「我參與的」包含已結束的案件並標示狀態")
    void historicInvolvedIncludesFinishedCases() throws Exception {
        String pid = startCase("user001");
        approveAndFinish(pid);

        var res = get("/api/history/process-instances/involved", "mgr001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(pid).contains("completed");
    }

    @Test
    @DisplayName("#71：歷史版「我參與的」同時涵蓋執行中與已結束，且只回呼叫者的")
    void historicInvolvedSpansRunningAndFinished() throws Exception {
        // mgr001 參與兩張：一張已審結、一張還卡在他的主管關卡。
        String finished = startCase("user001");
        approveAndFinish(finished);
        String running = startCase("user001");
        // mgr001 完全沒碰過的一張（user004 的主管是 mgr002）。
        String theirs = startCase("user004");

        var res = get("/api/history/process-instances/involved", "mgr001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(finished).contains("completed");
        assertThat(res.body()).contains(running).contains("running");
        assertThat(res.body())
                .as("沒有參與的案件不得出現在清單裡")
                .doesNotContain(theirs);
    }

    // ── 候選群組 ───────────────────────────────────────────────────

    /**
     * 部署一支用 {@code ${orgService.getDeptId(initiator)}} 指派候選群組的流程。
     *
     * <p>這是設計器「發起人所屬單位」選項實際產生的樣子
     * （{@code bpm-frontend/src/bpmn/assigneeExpressions.js:85} 的 ownDept）。
     * 任務沒有 assignee，只有候選群組 —— 因此只有「由伺服器算出呼叫端屬於
     * 哪些群組」才能讓它出現在收件匣。
     */
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

    @Test
    @DisplayName("#71：候選群組指派的任務，省略 candidateGroups 時仍必須出現在群組成員的待辦")
    void groupAssignedTaskIsVisibleToGroupMembers() throws Exception {
        deployOwnDeptFlow();
        String pid = runtimeService.startProcessInstanceByKey(OWN_DEPT_FLOW,
                Map.of("initiator", "user002")).getId();      // user002 → dept001
        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee())
                .as("候選群組任務沒有受理人 —— 這正是它對呼叫端不可見的原因")
                .isNull();

        // user001 同屬 dept001 → 必須看得到
        var member = get("/api/tasks", "user001");
        assertThat(member.statusCode()).isEqualTo(200);
        assertThat(member.body())
                .as("省略 candidateGroups 卻完全不查群組的話，這個任務會從所有人的"
                        + "收件匣消失，案件靜默卡死")
                .contains(task.getId());

        // user004 屬 dept002 → 看不到
        assertThat(get("/api/tasks", "user004").body())
                .as("群組成員判定必須真的生效，而不是回傳全部")
                .doesNotContain(task.getId());
    }

    @Test
    @DisplayName("#71：呼叫端自稱群組 → 400（即使那正是他自己的群組）")
    void callerSuppliedGroupsAreRejected() throws Exception {
        deployOwnDeptFlow();
        String pid = runtimeService.startProcessInstanceByKey(OWN_DEPT_FLOW,
                Map.of("initiator", "user002")).getId();
        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();

        var res = get("/api/tasks?candidateGroups=dept001", "user001");

        assertThat(res.statusCode())
                .as("群組是一個集合的自稱 —— 放行它等於要求伺服器相信「我屬於這個組織」")
                .isEqualTo(400);
        assertThat(res.body())
                .as("被拒的請求不得仍然回傳資料")
                .doesNotContain(task.getId());
    }

    @Test
    @DisplayName("#71：purchase-approval 的候選人（candidateUsers）路徑不受候選群組改動影響")
    void candidateUserFlowIsUnaffected() throws Exception {
        // purchase-approval 的 financeReview 用的是
        // ${permService.getUsersByPermission('finance:payment:approve')} → candidateUsers。
        // 這條路徑與 candidateGroups 無關，必須仍能正常運作。
        String pid = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "amount", 5000, "itemName", "測試品項")).getId();
        var mgrTask = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(mgrTask.getAssignee()).isEqualTo("mgr001");
        taskService.complete(mgrTask.getId(), Map.of("approved", true, "rejected", false));

        var financeTask = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(financeTask.getAssignee()).isNull();

        var res = get("/api/tasks?candidateUser=mgr001", "mgr001");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("候選人群組的計算不能影響候選人的路徑")
                .contains(financeTask.getId());
    }
}
