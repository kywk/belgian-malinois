package com.bpm.core.notify;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.NotifyTestSink;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #33／#6 端到端：通知觸發與催辦。
 *
 * <h2>為什麼每一條正向斷言都要「訊息真的出現」</h2>
 *
 * <p>本工項改動前的失敗型態不是錯誤，是<b>什麼都不發生</b>：
 * 流程照跑、簽核照簽、沒有例外。所以「流程有推進」不能當成通知成功的證據
 * —— 改動前的程式碼在那些斷言下也會全綠。每條測試因此都直接看
 * {@link NotifyTestSink} 收到的訊息。
 *
 * <h2>⚠️ 收件人欄位的契約</h2>
 *
 * <p>EmailConsumer 的收件人解析是「{@code assignee} 優先，其次
 * {@code candidateUsers}」。對 process_returned／rejected／completed
 * 而言，{@code assignee} 放的是<b>申請人</b>（不是審核人）——
 * 若哪天有人「修正」成審核人，本測試組的 assignee 斷言會紅。
 *
 * <h2>催辦的授權與頻率</h2>
 *
 * <p>授權：只有申請人。測試同時涵蓋三種拒絕（參與者非申請人 403、
 * 非參與者 404、未知案件 404）與兩種「拒絕時零副作用」
 * （沒有通知、沒有消耗頻率限制 —— 後者由「拒絕後申請人仍可成功催辦」
 * 證明）。
 */
class NotifyTriggerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void setUp() {
        NotifyTestSink.install(rabbitTemplate, amqpAdmin);
        NotifyTestSink.reset();
        truncateAuditLog();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 一張 leave-approval 的單，停在主管審核（assignee = mgr001）。 */
    private record Case(String pid, String managerTaskId) {}

    private Case startLeave(String initiator) {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", initiator, "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核的持有者必須是 mgr001")
                .isEqualTo("mgr001");
        return new Case(pi.getId(), manager.getId());
    }

    private ResultActions complete(String taskId, String user, String variablesJson) throws Exception {
        return mockMvc.perform(put("/api/tasks/{id}", taskId)
                .header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"complete\",\"variables\":" + variablesJson + "}"));
    }

    private static String vars(String json) {
        return json;
    }

    private ResultActions urge(String pid, String user) throws Exception {
        return mockMvc.perform(post("/api/tasks/urge")
                .header("X-User-Id", user)
                .param("processInstanceId", pid));
    }

    /** 部署一支只有單一 UserTask 的流程（不掛任何 listener，雜訊最少）。 */
    private String deploySingleTask(String key, String userTaskAttrs) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/notify-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="審核關卡"%s/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, userTaskAttrs);
        repositoryService.createDeployment()
                .name("nt-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    private String startCustom(String key) {
        var pi = runtimeService.startProcessInstanceByKey(key, Map.of("initiator", "user001"));
        return pi.getId();
    }

    private static boolean ended(RuntimeService runtimeService, String pid) {
        return runtimeService.createProcessInstanceQuery().processInstanceId(pid).count() == 0;
    }

    private static Map<String, Object> only(List<Map<String, Object>> messages, String event) {
        List<Map<String, Object>> matches = NotifyTestSink.events(messages, event);
        assertThat(matches)
                .as("事件 %s 應該恰好一則，實際收到的所有事件：%s", event,
                        messages.stream().map(m -> m.get("event")).toList())
                .hasSize(1);
        return matches.get(0);
    }

    // ── 退回／拒絕／結案（#33）────────────────────────────────────

    @Test
    @DisplayName("#33 退回：通知申請人 process_returned，且流程真的走到補件關卡")
    void returnedNotifiesApplicant() throws Exception {
        Case c = startLeave("user001");
        NotifyTestSink.reset();

        complete(c.managerTaskId(), "mgr001",
                vars("[{\"name\":\"approved\",\"value\":false}]"))
                .andExpect(status().isOk());

        // 非空斷言 (1)：流程真的推進了
        Task revision = taskService.createTaskQuery().processInstanceId(c.pid()).singleResult();
        assertThat(revision.getAssignee())
                .as("退回後補件關卡的受理人應是申請人")
                .isEqualTo("user001");

        // 非空斷言 (2)：訊息真的出現（沒有這條，改動前的「什麼都不發生」也會綠）
        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        Map<String, Object> returned = only(msgs, "process_returned");
        assertThat(returned)
                .containsEntry("assignee", "user001")
                .containsEntry("processInstanceId", c.pid())
                .containsEntry("taskId", c.managerTaskId())
                .containsEntry("processDefinitionKey", "leave-approval");

        // P2-1：不得夾帶流程變數
        assertThat(returned)
                .doesNotContainKeys("variables", "leaveType", "days", "comment", "rejectReason");
        assertThat(NotifyTestSink.events(msgs, "process_completed")).isEmpty();
    }

    @Test
    @DisplayName("#33 拒絕：通知申請人 process_rejected，且不得同時發『已核准完成』")
    void rejectedNotifiesApplicantWithoutContradiction() throws Exception {
        Case c = startLeave("user001");
        NotifyTestSink.reset();

        complete(c.managerTaskId(), "mgr001",
                vars("[{\"name\":\"approved\",\"value\":false},{\"name\":\"rejected\",\"value\":true}]"))
                .andExpect(status().isOk());

        assertThat(ended(runtimeService, c.pid()))
                .as("拒絕在 leave-approval 會直接結束流程")
                .isTrue();

        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        Map<String, Object> rejected = only(msgs, "process_rejected");
        assertThat(rejected)
                .containsEntry("assignee", "user001")
                .containsEntry("taskId", c.managerTaskId());

        // 拒絕的單也結束了流程；若 process_completed 無條件發送，
        // 申請人會同時收到「已被拒絕」與「已核准完成」兩封矛盾的信。
        assertThat(NotifyTestSink.events(msgs, "process_completed"))
                .as("拒絕案件不得再發 process_completed")
                .isEmpty();
        assertThat(NotifyTestSink.events(msgs, "process_returned"))
                .as("rejected=true 不得被誤判成退回")
                .isEmpty();
    }

    @Test
    @DisplayName("#33 結案：核准且流程結束 → process_completed 給申請人")
    void approvedCompletionNotifiesApplicant() throws Exception {
        String key = deploySingleTask("nt-complete", " flowable:assignee=\"mgr001\"");
        String pid = startCustom(key);
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        NotifyTestSink.reset();

        complete(task.getId(), "mgr001", vars("[{\"name\":\"approved\",\"value\":true}]"))
                .andExpect(status().isOk());
        assertThat(ended(runtimeService, pid)).isTrue();

        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        Map<String, Object> completed = only(msgs, "process_completed");
        assertThat(completed)
                .containsEntry("assignee", "user001")
                .containsEntry("processInstanceId", pid)
                .containsEntry("processDefinitionKey", key);
        assertThat(NotifyTestSink.events(msgs, "process_returned")).isEmpty();
        assertThat(NotifyTestSink.events(msgs, "process_rejected")).isEmpty();
    }

    @Test
    @DisplayName("#33 中間關卡核准（流程還在跑）不得發 process_completed")
    void nonFinalApprovalDoesNotNotifyCompleted() throws Exception {
        var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "businessKey", "NT-nonfinal"));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee()).isEqualTo("mgr001");
        NotifyTestSink.reset();

        complete(manager.getId(), "mgr001", vars("[{\"name\":\"approved\",\"value\":true}]"))
                .andExpect(status().isOk());

        // 前置條件：流程真的還在跑（財務關卡已建立）
        assertThat(ended(runtimeService, pi.getId())).isFalse();
        Task finance = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(finance.getName()).contains("財務");

        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        assertThat(NotifyTestSink.events(msgs, "process_completed"))
                .as("主管核准不是結案；發了就是把『已核准完成』說早了")
                .isEmpty();
        assertThat(NotifyTestSink.events(msgs, "process_returned")).isEmpty();
        assertThat(NotifyTestSink.events(msgs, "process_rejected")).isEmpty();
    }

    @Test
    @DisplayName("#33 補件重送不得被誤判成退回（approved 預設值陷阱）")
    void resubmitDoesNotNotifyReturned() throws Exception {
        Case c = startLeave("user001");
        complete(c.managerTaskId(), "mgr001",
                vars("[{\"name\":\"approved\",\"value\":false}]"))
                .andExpect(status().isOk());
        Task revision = taskService.createTaskQuery().processInstanceId(c.pid()).singleResult();
        assertThat(revision.getName()).contains("補件");
        NotifyTestSink.reset();

        // 前端補件重送的 payload：只有表單欄位，沒有 approved／rejected，
        // 而 TaskController 會補上 approved=false／rejected=false 預設值。
        complete(revision.getId(), "user001", vars("[{\"name\":\"days\",\"value\":2}]"))
                .andExpect(status().isOk());

        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        assertThat(NotifyTestSink.events(msgs, "process_returned"))
                .as("申請人自己重送時收到『您的申請已被退回』是荒謬的；"
                        + "排除條件與既有稽核的『補件』判斷共用同一條")
                .isEmpty();
        assertThat(NotifyTestSink.events(msgs, "process_rejected")).isEmpty();
        // 非空斷言：重送回主管關卡確實有 task_assigned，證明上面的空不是
        // 「因為什麼都沒送」造成的假空。
        assertThat(NotifyTestSink.events(msgs, "task_assigned")).hasSize(1);
    }

    @Test
    @DisplayName("#33 代發案件（R-20）：退回通知給被代的員工，不是 system:erp")
    void returnedOfOnBehalfCaseNotifiesTheEmployee() throws Exception {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "system:erp", "onBehalfOf", "user001",
                        "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        // 前置條件：主管關卡依 onBehalfOf 路由（InitialAssigneeResolver）
        assertThat(manager.getAssignee()).isEqualTo("mgr001");
        NotifyTestSink.reset();

        complete(manager.getId(), "mgr001", vars("[{\"name\":\"approved\",\"value\":false}]"))
                .andExpect(status().isOk());

        Map<String, Object> returned = only(NotifyTestSink.drain(), "process_returned");
        assertThat(returned)
                .as("initiator 是 system:erp（不是人）；收件人必須取 onBehalfOf")
                .containsEntry("assignee", "user001")
                .doesNotContainValue("system:erp");
    }

    // ── 認領（#33）─────────────────────────────────────────────────

    @Test
    @DisplayName("#33 認領：通知其他候選人 task_claimed，認領者本人不得收到")
    void claimNotifiesOtherCandidates() throws Exception {
        String key = deploySingleTask("nt-claim", " flowable:candidateUsers=\"mgr001,mgr002\"");
        String pid = startCustom(key);
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).as("前置條件：候選任務沒有 assignee").isNull();
        NotifyTestSink.reset();

        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"claim\"}"))
                .andExpect(status().isOk());

        assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult().getAssignee())
                .isEqualTo("mgr001");

        Map<String, Object> claimed = only(NotifyTestSink.drain(), "task_claimed");
        assertThat(claimed)
                .containsEntry("processInstanceId", pid)
                .containsEntry("taskId", task.getId())
                .containsEntry("claimedBy", "mgr001")
                .containsEntry("processDefinitionKey", key);
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) claimed.get("candidateUsers");
        assertThat(candidates)
                .as("認領者不需要收到自己認領的通知")
                .containsExactly("mgr002");
        assertThat(claimed)
                .as("assignee 是 EmailConsumer 的收件人欄位；放了會把信寄回認領者")
                .doesNotContainKey("assignee");
    }

    // ── 加簽（#33）─────────────────────────────────────────────────

    @Test
    @DisplayName("#33 加簽：被加簽人收到 task_assigned（standalone task 沒有 BPMN listener）")
    void countersignNotifiesAssignee() throws Exception {
        Case c = startLeave("user001");
        NotifyTestSink.reset();

        mockMvc.perform(post("/api/countersign/{taskId}", c.managerTaskId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countersignUserId\":\"user003\",\"message\":\"請確認\"}"))
                .andExpect(status().isOk());

        Map<String, Object> assigned = only(NotifyTestSink.drain(), "task_assigned");
        assertThat(assigned)
                .containsEntry("assignee", "user003")
                .containsEntry("processInstanceId", c.pid())
                .containsEntry("processDefinitionKey", "leave-approval")
                .containsEntry("initiator", "user001");
        assertThat((String) assigned.get("taskName")).contains("加簽審核");
    }

    // ── 催辦（#6）：正向與授權 ──────────────────────────────────────

    @Test
    @DisplayName("#6 申請人可催辦：通知目前受理人，回傳收件人與冷卻時間")
    void applicantCanUrge() throws Exception {
        Case c = startLeave("user001");
        NotifyTestSink.reset();

        urge(c.pid(), "user001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[0]").value("mgr001"))
                .andExpect(jsonPath("$.cooldownMinutes").value(30));

        Map<String, Object> urged = only(NotifyTestSink.drain(), "task_urged");
        assertThat(urged)
                .containsEntry("assignee", "mgr001")
                .containsEntry("initiator", "user001")
                .containsEntry("taskId", c.managerTaskId())
                .containsEntry("processInstanceId", c.pid())
                .containsEntry("processDefinitionKey", "leave-approval");
    }

    @Test
    @DisplayName("#6 候選任務可以催辦：收件人是候選人")
    void candidateTaskCanBeUrged() throws Exception {
        String key = deploySingleTask("nt-urge-candidates",
                " flowable:candidateUsers=\"mgr001,mgr002\"");
        String pid = startCustom(key);
        NotifyTestSink.reset();

        urge(pid, "user001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients.length()").value(2));

        Map<String, Object> urged = only(NotifyTestSink.drain(), "task_urged");
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) urged.get("candidateUsers");
        assertThat(candidates).containsExactlyInAnyOrder("mgr001", "mgr002");
        assertThat(urged).doesNotContainKey("assignee");
    }

    // ── 催辦：#6 頻率限制 ──────────────────────────────────────────

    @Test
    @DisplayName("#6 同一案件 30 分鐘內第二次催辦回 429，且不再發通知")
    void secondUrgeWithinCooldownIs429() throws Exception {
        Case c = startLeave("user001");
        urge(c.pid(), "user001").andExpect(status().isOk());
        NotifyTestSink.drain(); // 丟掉第一則，只看第二次有沒有副作用

        urge(c.pid(), "user001")
                .andExpect(status().isTooManyRequests());

        assertThat(NotifyTestSink.drain())
                .as("被頻率限制擋下時不得再送通知")
                .isEmpty();
    }

    @Test
    @DisplayName("#6 冷卻限制是『每張單』：另一張單仍可催辦")
    void cooldownIsPerProcess() throws Exception {
        Case first = startLeave("user001");
        Case second = startLeave("user001");

        urge(first.pid(), "user001").andExpect(status().isOk());
        // key 若只用 taskId 或全域，下面這一行會被誤擋（冷卻還沒過）。
        urge(second.pid(), "user001").andExpect(status().isOk());
    }

    @Test
    @DisplayName("#6 代發案件（R-20）：被代的員工可以催辦，不是只認 initiator")
    void onBehalfApplicantCanUrge() throws Exception {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "system:erp", "onBehalfOf", "user001",
                        "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee()).isEqualTo("mgr001");
        NotifyTestSink.reset();

        // initiator 是 system:erp；能催辦的是 onBehalfOf 指到的員工。
        urge(pi.getId(), "user001").andExpect(status().isOk());

        Map<String, Object> urged = only(NotifyTestSink.drain(), "task_urged");
        assertThat(urged)
                .containsEntry("assignee", "mgr001")
                .containsEntry("initiator", "user001");
    }

    // ── 催辦：授權拒絕與零副作用 ────────────────────────────────────

    @Test
    @DisplayName("#6 參與者但不是申請人（審核人）催辦 → 403，且不消耗頻率限制")
    void participantWhoIsNotApplicantIsForbidden() throws Exception {
        Case c = startLeave("user001");
        NotifyTestSink.reset();

        urge(c.pid(), "mgr001")
                .andExpect(status().isForbidden());

        assertThat(NotifyTestSink.drain())
                .as("被拒的催辦不得送通知")
                .isEmpty();
        assertThat(auditDetailsFor("mgr001", "DATA_ACCESS"))
                .as("被拒的授權嘗試要留痕（參與者不是探測，但仍是一筆拒絕）")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"))
                .allMatch(d -> d.contains("not the applicant"));

        // 零副作用：403 沒有取得頻率許可，申請人馬上可以催辦。
        urge(c.pid(), "user001").andExpect(status().isOk());
        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "task_urged")).hasSize(1);
    }

    @Test
    @DisplayName("#6 完全無關的人催辦 → 404（不留枚舉管道），且申請人仍可催辦")
    void unrelatedUserIsNotFound() throws Exception {
        Case c = startLeave("user001");
        NotifyTestSink.reset();

        // user002 是真實登入者但與這張單無關（#79 實測報告裡的攻擊者取樣）。
        urge(c.pid(), "user002")
                .andExpect(status().isNotFound());

        assertThat(NotifyTestSink.drain()).isEmpty();
        assertThat(auditDetailsFor("user002", "DATA_ACCESS"))
                .as("非參與者的存取嘗試必須留下 DATA_ACCESS {denied:true}")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"));

        urge(c.pid(), "user001").andExpect(status().isOk());
    }

    @Test
    @DisplayName("#6 對不存在的案件催辦 → 404")
    void urgeUnknownProcessIsNotFound() throws Exception {
        urge("no-such-process-instance", "user001")
                .andExpect(status().isNotFound());
        assertThat(NotifyTestSink.drain()).isEmpty();
    }

    @Test
    @DisplayName("#6 案件沒有可催辦的受理人 → 409，且不消耗頻率限制")
    void urgeWithoutHandlerIsConflict() throws Exception {
        String key = deploySingleTask("nt-urge-nohandler", "");
        String pid = startCustom(key);
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).as("前置條件：這個任務沒有人也沒有候選人").isNull();
        NotifyTestSink.reset();

        // 沒有人可以提醒時回 409（Conflict），不是假裝成功。
        urge(pid, "user001")
                .andExpect(status().isConflict());
        assertThat(NotifyTestSink.drain()).isEmpty();

        // 沒有取得許可：把受理人補上之後，同一個案件應能立刻催辦。
        taskService.setAssignee(task.getId(), "mgr001");
        urge(pid, "user001").andExpect(status().isOk());
        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "task_urged")).hasSize(1);
    }

    // ── 稽核小工具（沿用 CommentAuthorizationTest 的形狀）──────────

    private List<String> auditDetailsFor(String operatorId, String operationType) {
        List<String> out = new java.util.ArrayList<>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operator_id = '" + operatorId + "' AND operation_type = '"
                    + operationType + "' ORDER BY id")) {
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }
}
