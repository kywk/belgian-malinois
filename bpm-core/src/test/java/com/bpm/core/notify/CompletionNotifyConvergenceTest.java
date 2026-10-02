package com.bpm.core.notify;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.NotifyTestSink;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #96 完成路徑通知收斂：HTTP 與外部 API 兩條完成路徑走同一份通知規則，
 * 且每個事件<b>恰好一則</b>。
 *
 * <h2>缺陷本體</h2>
 *
 * <p>改動前 {@code process_returned}／{@code process_rejected}／
 * {@code process_completed} 只在 {@code TaskController.updateTask} 的 complete
 * 分支發出，{@code ExternalApiController.completeTask} 完成任務時<b>完全不發</b>。
 * {@link #externalCompleteFinalTaskNotifiesExactlyOnce} 在缺陷期間是紅的
 * —— 這正是本工項要修的缺陷。
 *
 * <h2>為什麼每一條都要「恰好一則」</h2>
 *
 * <p>把通知搬進全域 listener 之後，最危險的失敗型態不是「沒發」而是
 * <b>雙發</b>：HTTP 路徑的直接呼叫若沒拆掉，或 {@code PROCESS_COMPLETED}
 * 用「非 null 就發」而不是「等於 process_completed 才發」，申請人就會收到
 * 兩封互相矛盾的信。{@link #assertOnlyProcessEvent} 把「process_* 事件的
 * 完整序列」寫成斷言 —— 少一則與多一則都會紅。</p>
 *
 * <h2>負向控制組（見交付報告）</h2>
 *
 * <p>把 {@code FlowableConfig} 的 {@code completionNotifyListener} 從
 * {@code setEventListeners} 移除（單一註冊點）之後：
 * {@link #externalCompleteFinalTaskNotifiesExactlyOnce}、
 * {@link #httpCompleteFinalTaskNotifiesExactlyOnce}、
 * {@link #rejectedSendsExactlyOneRejected}、
 * {@link #returnedSendsExactlyOneReturned} 四條變紅；
 * 補件（d）、中間關卡（e）、加簽（f）在缺陷期間<b>本來就會綠</b>
 * —— 它們證明的是「不該發時不發」，證明不了缺陷存在。
 * {@link NotifyPublisherTest} 的規則測試不受 listener 註冊影響，恆綠。
 */
class CompletionNotifyConvergenceTest extends IntegrationTestBase {

    private static final String API_KEY = "sk-96-completion-notify";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ExternalSystemRepository externalRepo;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        NotifyTestSink.install(rabbitTemplate, amqpAdmin);
        NotifyTestSink.reset();
        truncateAuditLog();
        // 與 ExternalCompleteTaskHardeningTest 相同的 fixture 隔離政策：
        // 容器共用同一個 DB，外部系統表逐次重建。
        externalRepo.deleteAll();
    }

    @AfterEach
    void tearDown() {
        externalRepo.deleteAll();
    }

    // ── fixture／請求工具 ───────────────────────────────────────────

    /**
     * 一張 leave-approval 的單，停在主管審核（assignee = mgr001）。
     *
     * @param initiator  案件 initiator；外部系統發起時是 {@code system:erp}
     * @param onBehalfOf 代發的員工（沒有代發時傳 null）
     */
    private record Case(String pid, String managerTaskId) {}

    private Case startLeave(String initiator, String onBehalfOf) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("initiator", initiator);
        vars.put("leaveType", "annual");
        vars.put("days", 1);
        if (onBehalfOf != null) {
            vars.put("onBehalfOf", onBehalfOf);
        }
        if (initiator.startsWith("system:")) {
            // 外部系統的擁有權標記（R-20）。正式路徑由 startProcess 寫入，
            // 這裡直接發起實例，因此自行補上。
            vars.put("_externalSystemId", "erp");
        }
        String pid = runtimeService.startProcessInstanceByKey("leave-approval", vars).getId();
        Task manager = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核的持有者必須是 mgr001")
                .isEqualTo("mgr001");
        return new Case(pid, manager.getId());
    }

    private void givenErp() {
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId("erp");
        sys.setSystemName("completion-notify-test");
        sys.setApiKey(ApiKeyUtil.hash(API_KEY));
        sys.setAllowedActions("[\"start_process\",\"complete_task\"]");
        sys.setAllowedProcessKeys("[\"leave-approval\"]");
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        externalRepo.save(sys);
    }

    private HttpResponse<String> completeAs(String taskId, String variablesJson) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/external/tasks/" + taskId))
                .header("X-API-Key", API_KEY)
                .header("X-System-Id", "erp")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"variables\":" + variablesJson + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void completeViaHttp(String taskId, String user, String variablesJson) throws Exception {
        mockMvc.perform(put("/api/tasks/{id}", taskId)
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":" + variablesJson + "}"))
                .andExpect(status().isOk());
    }

    private boolean ended(String processInstanceId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count() == 0;
    }

    // ── 斷言工具：恰好一則 ──────────────────────────────────────────

    /** 該事件恰好一則（少一則與多一則都紅）。 */
    private static Map<String, Object> exactlyOne(List<Map<String, Object>> messages, String event) {
        List<Map<String, Object>> matches = NotifyTestSink.events(messages, event);
        assertThat(matches)
                .as("事件 %s 應恰好一則；實際收到的所有事件：%s", event,
                        messages.stream().map(m -> m.get("event")).toList())
                .hasSize(1);
        return matches.get(0);
    }

    /** 所有 process_* 事件的完整序列必須等於 {@code [event]}。 */
    private static void assertOnlyProcessEvent(List<Map<String, Object>> messages, String event) {
        List<String> processEvents = messages.stream()
                .map(m -> String.valueOf(m.get("event")))
                .filter(e -> e.startsWith("process_"))
                .toList();
        assertThat(processEvents)
                .as("process_* 通知的完整序列必須恰好是 [%s]，多發與少發都算缺陷", event)
                .containsExactly(event);
    }

    /** 不得有任何 process_* 通知。 */
    private static void assertNoProcessEvent(List<Map<String, Object>> messages) {
        List<String> processEvents = messages.stream()
                .map(m -> String.valueOf(m.get("event")))
                .filter(e -> e.startsWith("process_"))
                .toList();
        assertThat(processEvents)
                .as("這一條完成路徑不該發出任何 process_* 通知")
                .isEmpty();
    }

    // ── a) 缺陷本體：外部 API 完成最後一關 ──────────────────────────

    @Test
    @DisplayName("#96 a) 外部 API 完成最後一關（onBehalfOf 自然人、approved=true）→ 恰一則 process_completed")
    void externalCompleteFinalTaskNotifiesExactlyOnce() throws Exception {
        givenErp();
        // 外部系統代 user001 發起，再把主管關卡改派給 system:erp（R-19 允許
        // 的形狀：任務明確指派給該系統時才能由它完成）。
        Case c = startLeave("system:erp", "user001");
        taskService.setAssignee(c.managerTaskId(), "system:erp");
        NotifyTestSink.reset();

        HttpResponse<String> res = completeAs(c.managerTaskId(), "{\"approved\":true}");

        assertThat(res.statusCode()).as("前置條件：erp 必須能完成自己的任務").isEqualTo(200);
        assertThat(ended(c.pid())).as("approved=true 在 leave-approval 會直接結案").isTrue();

        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        Map<String, Object> completed = exactlyOne(msgs, "process_completed");
        assertThat(completed)
                .as("收件人必須是 onBehalfOf 指到的自然人，不是 system:erp")
                .containsEntry("assignee", "user001")
                .containsEntry("processInstanceId", c.pid())
                .containsEntry("processDefinitionKey", "leave-approval")
                .containsEntry("taskId", c.managerTaskId())
                .doesNotContainValue("system:erp");
        assertOnlyProcessEvent(msgs, "process_completed");
    }

    // ── b) HTTP 路徑不得雙發 ────────────────────────────────────────

    @Test
    @DisplayName("#96 b) HTTP PUT 完成最後一關 → 恰一則 process_completed（不得與 listener 雙發）")
    void httpCompleteFinalTaskNotifiesExactlyOnce() throws Exception {
        Case c = startLeave("user001", null);
        NotifyTestSink.reset();

        completeViaHttp(c.managerTaskId(), "mgr001", "[{\"name\":\"approved\",\"value\":true}]");

        assertThat(ended(c.pid())).isTrue();
        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        Map<String, Object> completed = exactlyOne(msgs, "process_completed");
        assertThat(completed)
                .containsEntry("assignee", "user001")
                .containsEntry("processInstanceId", c.pid())
                .containsEntry("processDefinitionKey", "leave-approval")
                .containsEntry("taskId", c.managerTaskId())
                .containsEntry("taskName", "主管審核");
        assertOnlyProcessEvent(msgs, "process_completed");
    }

    // ── c) 拒絕／退回的優先序與恰好一則 ────────────────────────────

    @Test
    @DisplayName("#96 c) rejected=true → 恰一則 process_rejected、零 process_completed")
    void rejectedSendsExactlyOneRejected() throws Exception {
        Case c = startLeave("user001", null);
        NotifyTestSink.reset();

        completeViaHttp(c.managerTaskId(), "mgr001",
                "[{\"name\":\"approved\",\"value\":false},{\"name\":\"rejected\",\"value\":true}]");

        assertThat(ended(c.pid())).as("拒絕在 leave-approval 會直接結束流程").isTrue();
        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        exactlyOne(msgs, "process_rejected");
        // 拒絕的單也結束了流程；PROCESS_COMPLETED 若無條件再發一則，
        // 申請人會同時收到「已被拒絕」與「已核准完成」兩封矛盾的信。
        assertOnlyProcessEvent(msgs, "process_rejected");
    }

    @Test
    @DisplayName("#96 c) approved=false → 恰一則 process_returned（且不得被誤判成拒絕）")
    void returnedSendsExactlyOneReturned() throws Exception {
        Case c = startLeave("user001", null);
        NotifyTestSink.reset();

        completeViaHttp(c.managerTaskId(), "mgr001",
                "[{\"name\":\"approved\",\"value\":false}]");

        Task revision = taskService.createTaskQuery().processInstanceId(c.pid()).singleResult();
        assertThat(revision.getAssignee())
                .as("前置條件：退回後補件關卡派給申請人")
                .isEqualTo("user001");

        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        Map<String, Object> returned = exactlyOne(msgs, "process_returned");
        assertThat(returned)
                .containsEntry("assignee", "user001")
                .containsEntry("taskId", c.managerTaskId());
        assertOnlyProcessEvent(msgs, "process_returned");
    }

    // ── d) 補件不通知 ───────────────────────────────────────────────

    @Test
    @DisplayName("#96 d) 補件任務完成 → 零 process_* 通知（approved 預設值陷阱）")
    void revisionCompletionSendsNoProcessNotification() throws Exception {
        Case c = startLeave("user001", null);
        // 先退回，讓補件關卡出現；退回收到的 process_returned 先丟掉。
        completeViaHttp(c.managerTaskId(), "mgr001",
                "[{\"name\":\"approved\",\"value\":false}]");
        Task revision = taskService.createTaskQuery().processInstanceId(c.pid()).singleResult();
        assertThat(revision.getName()).contains("補件");
        NotifyTestSink.reset();

        // 前端補件重送的 payload：只有表單欄位，沒有 approved／rejected，
        // 而 TaskController 會補上 approved=false／rejected=false 預設值。
        // 若不排除補件任務，這裡會發出一則「您的申請已被退回」。
        completeViaHttp(revision.getId(), "user001", "[{\"name\":\"days\",\"value\":2}]");

        List<Map<String, Object>> msgs = NotifyTestSink.drain();
        assertNoProcessEvent(msgs);
        // 非空斷言：重送回主管關卡確實有 task_assigned，證明上面的空不是
        // 「因為什麼都沒送」造成的假空。
        assertThat(NotifyTestSink.events(msgs, "task_assigned")).hasSize(1);
    }

    // ── e) 核准但流程尚未結束 ───────────────────────────────────────

    @Test
    @DisplayName("#96 e) approved=true 但流程還在跑 → 當下零通知")
    void nonFinalApprovalSendsNoProcessNotification() throws Exception {
        var pi = runtimeService.startProcessInstanceByKey("purchase-approval",
                Map.of("initiator", "user001", "businessKey", "CN-" + UUID.randomUUID()));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee()).isEqualTo("mgr001");
        NotifyTestSink.reset();

        completeViaHttp(manager.getId(), "mgr001", "[{\"name\":\"approved\",\"value\":true}]");

        // 前置條件：流程真的還在跑（財務關卡已建立）。
        assertThat(ended(pi.getId())).isFalse();
        Task finance = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(finance.getName()).contains("財務");

        assertNoProcessEvent(NotifyTestSink.drain());

        // 財務關卡也核准 → 流程結束。這一步同時驗證多關卡流程只在最後
        // 一關結案時發一則，且 payload 的 taskId／taskName 是最後那一關。
        taskService.complete(finance.getId(), Map.of("approved", true));
        assertThat(ended(pi.getId())).isTrue();
        List<Map<String, Object>> finalMsgs = NotifyTestSink.drain();
        Map<String, Object> completed = exactlyOne(finalMsgs, "process_completed");
        assertThat(completed)
                .as("多關卡流程的結案通知必須帶最後一關（財務），不是上一關（主管）")
                .containsEntry("taskId", finance.getId())
                .containsEntry("taskName", "財務審核");
        assertOnlyProcessEvent(finalMsgs, "process_completed");
    }

    // ── f) standalone 加簽子任務沒有 processInstanceId ──────────────

    @Test
    @DisplayName("#96 f) 加簽子任務完成 → 零 process_* 通知")
    void countersignSubtaskCompletionSendsNoProcessNotification() throws Exception {
        Case c = startLeave("user001", null);
        mockMvc.perform(post("/api/countersign/{taskId}", c.managerTaskId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countersignUserId\":\"user003\",\"message\":\"請確認\"}"))
                .andExpect(status().isOk());
        List<Task> subtasks = taskService.getSubTasks(c.managerTaskId());
        assertThat(subtasks).as("前置條件：加簽子任務已建立").hasSize(1);
        assertThat(subtasks.get(0).getProcessInstanceId())
                .as("前置條件：standalone 加簽子任務沒有 processInstanceId")
                .isNull();
        NotifyTestSink.reset();

        // 直接把子任務完成（不需要走 HTTP 端點）：事件本身沒有案件，
        // listener 必須略過，不可查申請人、也不可發 process_*。
        taskService.complete(subtasks.get(0).getId());

        assertNoProcessEvent(NotifyTestSink.drain());
    }
}
