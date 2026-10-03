package com.bpm.core.controller;

import com.bpm.core.security.GatewayAuthenticationFilter;
import com.bpm.core.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工項 #24：Signal Event 廣播（{@code POST /api/admin/signals/{signalName}/broadcast}）。
 *
 * <h2>測什麼</h2>
 *
 * <ol>
 *   <li><b>一對多</b>：兩個實例等同一訊號 → 廣播後<b>兩個都醒</b>、變數都寫入。
 *       這是 signal 與 message 的決定性差別（message 一次只能喚一個）。</li>
 *   <li><b>0 個等待者 → 404</b>，零副作用（不寫 {@code SIGNAL_BROADCAST} 稽核）。
 *       Flowable 的全域廣播在沒有訂閱時是靜默 no-op，404 是唯一能讓
 *       「什麼都沒發生」被看見的回應。</li>
 *   <li><b>授權</b>：非 ADMIN → 403、未登入 → 401，兩者都零喚醒、零稽核。</li>
 *   <li><b>稽核</b>：operator＝呼叫者；detail 只有 signalName 與計數，
 *       <b>不含變數值</b>。</li>
 *   <li><b>邊界</b>：廣播後訂閱被消耗，第二次 → 404。</li>
 *   <li><b>形狀</b>：signalName 空／過長／含控制字元、variables 非物件 → 400，
 *       且形狀（400）先於存在性（404）。控制字元無法從 HTTP 測
 *       （servlet 防火牆先擋），由直接呼叫驗證器的單元測試覆蓋。</li>
 * </ol>
 *
 * <h2>⚠️ 測試 BPMN 的 signal 必須宣告在 definitions 層級</h2>
 *
 * <p>Flowable 的全域廣播只投遞給 global scope 的訂閱
 * （{@code SignalEventReceivedCmd}）。{@link #processScopedSignalIsNotWokenByGlobalBroadcast}
 * 用 {@code flowable:scope="processInstance"} 釘住另一半：這種訂閱<b>會</b>
 * 被 {@code waiting} 數到，但<b>不會</b>被廣播喚醒 —— 這是 Flowable 的語意，
 * 不是本端點的 bug；平台的 BPMN 規範是訊號宣告於 definitions 層級。
 *
 * <h2>負向控制組實測（2026-10-03，逐一移除防線後重跑本類別）</h2>
 *
 * <ul>
 *   <li><b>移除等待檢查</b>（{@code if (waiting == 0) throw 404} 整段註解掉）：
 *       <b>8 條中 2 紅</b> —— {@link #noWaitingSubscriptionReturns404WithoutAudit}
 *       與 {@link #secondBroadcastAfterConsumptionReturns404} 都拿到 200
 *       （0 個等待者的廣播是靜默 no-op）。其餘 6 條綠，包含一對多喚醒、
 *       稽核與形狀驗證 —— 它們不經過這條防線。</li>
 *   <li><b>改成只喚一個</b>（{@code signalEventReceived(name, 第一個 executionId,
 *       variables)}）：<b>8 條中 1 紅</b> ——
 *       {@link #broadcastWakesEveryWaitingInstanceAndAppliesVariables}
 *       在「廣播後訂閱被消耗」就紅（剩 1 個訂閱仍等待）；其餘 7 條綠。
 *       這正是 signal 與 message 的差別所在。</li>
 * </ul>
 *
 * <p><b>證明不了的事</b>：負控只證明「移除哪一行會讓哪一條紅」，
 * 不證明沒有其他繞過路徑。特別是：
 * <ul>
 *   <li>{@code waiting} 與廣播之間的競態窗口（實例在查詢後被取消／新增）
 *       無法穩定重現，只由 controller 的類別註解記載。</li>
 *   <li>稽核 fail-closed（稽核 DB 故障 → 503、廣播回滾）沒有真的停掉
 *       稽核 DB 來驗（Testcontainers 容器由所有測試共用，不能停），
 *       只由 {@code AuditEventPublisher} 的既有測試類別覆蓋。</li>
 *   <li>process-scoped 訊號的 200-no-op 是 Flowable 語意，不是防線；
 *       {@link #processScopedSignalIsNotWokenByGlobalBroadcast} 只是把它釘住。</li>
 * </ul>
 */
class SignalBroadcastTest extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROCESS_KEY = "t24-signal-wait";
    private static final String SIGNAL_NAME = "t24-broadcast-wake";
    private static final String AFTER_TASK_NAME = "廣播後關卡";

    private static final String PROCESS_SCOPED_KEY = "t24-signal-process-scoped";
    private static final String PROCESS_SCOPED_SIGNAL = "t24-process-scoped-wake";

    /**
     * 兩個實例會停在 {@code waitSignal}；訊號一到就進 {@code afterBroadcast}。
     *
     * <p>{@code <signal>} 在 definitions 層級 → global scope，全域廣播可見。
     */
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://bpm.com/signal-broadcast-test">
              <signal id="wakeSignal" name="%s"/>
              <process id="%s" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="waitSignal"/>
                <intermediateCatchEvent id="waitSignal">
                  <signalEventDefinition signalRef="wakeSignal"/>
                </intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="waitSignal" targetRef="afterBroadcast"/>
                <userTask id="afterBroadcast" name="%s" flowable:assignee="mgr001"/>
                <sequenceFlow id="f3" sourceRef="afterBroadcast" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(SIGNAL_NAME, PROCESS_KEY, AFTER_TASK_NAME);

    /** {@code flowable:scope="processInstance"}：訂閱存在但全域廣播跳過。 */
    private static final String PROCESS_SCOPED_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://bpm.com/signal-broadcast-test">
              <signal id="scopedSignal" name="%s" flowable:scope="processInstance"/>
              <process id="%s" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="waitSignal"/>
                <intermediateCatchEvent id="waitSignal">
                  <signalEventDefinition signalRef="scopedSignal"/>
                </intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="waitSignal" targetRef="afterBroadcast"/>
                <userTask id="afterBroadcast" name="%s" flowable:assignee="mgr001"/>
                <sequenceFlow id="f3" sourceRef="afterBroadcast" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(PROCESS_SCOPED_SIGNAL, PROCESS_SCOPED_KEY, AFTER_TASK_NAME);

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;

    private final List<String> processInstanceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        truncateAuditLog();
        deployOnce(PROCESS_KEY, BPMN);
        deployOnce(PROCESS_SCOPED_KEY, PROCESS_SCOPED_BPMN);
    }

    @AfterEach
    void tearDown() {
        for (String pid : processInstanceIds) {
            try {
                runtimeService.deleteProcessInstance(pid, "T24 test cleanup");
            } catch (Exception ignored) {
                // 已刪或已結束，不影響隔離。
            }
        }
        processInstanceIds.clear();
    }

    // ── fixture／工具 ──────────────────────────────────────────────

    private void deployOnce(String key, String xml) {
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count() > 0) {
            return;
        }
        repositoryService.createDeployment()
                .name("t24-signal-broadcast-test")
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
    }

    /** 啟動一個實例並停在 signal catch event。 */
    private String startWaiting(String processKey) {
        String pid = runtimeService.startProcessInstanceByKey(processKey).getId();
        processInstanceIds.add(pid);
        return pid;
    }

    private long waitingCount(String signalName) {
        return runtimeService.createExecutionQuery().signalEventSubscriptionName(signalName).count();
    }

    private List<String> taskIdsOf(String pid) {
        return taskService.createTaskQuery().processInstanceId(pid).list()
                .stream().map(org.flowable.task.api.Task::getId).toList();
    }

    /** 成功廣播（管理員身分），body 必填。 */
    private void broadcastOk(String signalName, String body) throws Exception {
        mockMvc.perform(post("/api/admin/signals/{signalName}/broadcast", signalName)
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** 稽核表裡的 SIGNAL_BROADCAST 列（依 id 排序）。 */
    private record AuditRow(String operatorId, String processInstanceId, JsonNode detail) {}

    private List<AuditRow> signalAuditRows() {
        List<AuditRow> rows = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operator_id, process_instance_id, detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'SIGNAL_BROADCAST' ORDER BY id")) {
                var rs = ps.executeQuery();
                while (rs.next()) {
                    rows.add(new AuditRow(rs.getString(1), rs.getString(2),
                            MAPPER.readTree(rs.getString(3))));
                }
            }
        });
        return rows;
    }

    // ── 1. 一對多喚醒 ─────────────────────────────────────────────

    @Test
    @DisplayName("#24 兩個實例等同一訊號 → 廣播後兩個都醒，變數寫入兩邊")
    void broadcastWakesEveryWaitingInstanceAndAppliesVariables() throws Exception {
        String pid1 = startWaiting(PROCESS_KEY);
        String pid2 = startWaiting(PROCESS_KEY);
        assertThat(waitingCount(SIGNAL_NAME))
                .as("前置條件：兩個實例都停在 signal catch event")
                .isEqualTo(2);

        mockMvc.perform(post("/api/admin/signals/{signalName}/broadcast", SIGNAL_NAME)
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"decision\":\"approved\",\"note\":\"廣播測試\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signalName").value(SIGNAL_NAME))
                .andExpect(jsonPath("$.waiting").value(2))
                .andExpect(jsonPath("$.variablesApplied").value(2));

        assertThat(waitingCount(SIGNAL_NAME)).as("廣播後訂閱被消耗").isZero();
        assertThat(taskIdsOf(pid1)).as("第一個實例必須被喚醒").hasSize(1);
        assertThat(taskIdsOf(pid2))
                .as("第二個實例也必須被喚醒 —— 這是 signal 與 message 的決定性差別")
                .hasSize(1);
        assertThat(runtimeService.getVariable(pid1, "decision")).isEqualTo("approved");
        assertThat(runtimeService.getVariable(pid2, "decision")).isEqualTo("approved");
        assertThat(runtimeService.getVariable(pid1, "note")).isEqualTo("廣播測試");
        assertThat(runtimeService.getVariable(pid2, "note")).isEqualTo("廣播測試");
    }

    // ── 2. 無等待訂閱 ─────────────────────────────────────────────

    @Test
    @DisplayName("#24 無等待訂閱 → 404，零稽核（廣播本身是 no-op）")
    void noWaitingSubscriptionReturns404WithoutAudit() throws Exception {
        assertThat(waitingCount(SIGNAL_NAME)).isZero();

        mockMvc.perform(post("/api/admin/signals/{signalName}/broadcast", SIGNAL_NAME)
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"decision\":\"approved\"}}"))
                .andExpect(status().isNotFound());

        assertThat(signalAuditRows())
                .as("沒有喚醒任何人就不該有 SIGNAL_BROADCAST 稽核")
                .isEmpty();
    }

    // ── 3. 授權 ───────────────────────────────────────────────────

    @Test
    @DisplayName("#24 非 ADMIN → 403、未登入 → 401，兩者零喚醒、零稽核")
    void onlyAdminCanBroadcast() throws Exception {
        String pid = startWaiting(PROCESS_KEY);

        mockMvc.perform(post("/api/admin/signals/{signalName}/broadcast", SIGNAL_NAME)
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"decision\":\"approved\"}}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/signals/{signalName}/broadcast", SIGNAL_NAME)
                        .header(GatewayAuthenticationFilter.SECRET_HEADER, "")
                        .header(GatewayAuthenticationFilter.USER_HEADER, "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"decision\":\"approved\"}}"))
                .andExpect(status().isUnauthorized());

        assertThat(waitingCount(SIGNAL_NAME)).as("被拒的請求不得喚醒任何實例").isEqualTo(1);
        assertThat(taskIdsOf(pid)).isEmpty();
        assertThat(signalAuditRows()).as("被拒的請求不得宣稱廣播發生").isEmpty();
    }

    // ── 4. 稽核 ───────────────────────────────────────────────────

    @Test
    @DisplayName("#24 稽核：operator＝呼叫者、detail 只記 signalName 與計數，不記變數值")
    void successfulBroadcastWritesAuditWithoutVariableValues() throws Exception {
        startWaiting(PROCESS_KEY);

        broadcastOk(SIGNAL_NAME, "{\"variables\":{\"salary\":\"12345-secret\"}}");

        List<AuditRow> rows = signalAuditRows();
        assertThat(rows).hasSize(1);
        AuditRow row = rows.get(0);
        assertThat(row.operatorId()).isEqualTo("admin001");
        assertThat(row.processInstanceId())
                .as("廣播是多案件操作，沒有單一案件可歸屬")
                .isNull();
        assertThat(row.detail()).isEqualTo(MAPPER.readTree(
                "{\"action\":\"broadcast\",\"signalName\":\"" + SIGNAL_NAME
                        + "\",\"waiting\":1,\"variables\":1}"));
        assertThat(row.detail().toString())
                .as("detail 不得出現變數值（可能是薪資等業務資料）")
                .doesNotContain("12345-secret");
    }

    // ── 5. 邊界：第二次廣播 ───────────────────────────────────────

    @Test
    @DisplayName("#24 廣播後訂閱消失 → 第二次 404，且不再寫稽核")
    void secondBroadcastAfterConsumptionReturns404() throws Exception {
        startWaiting(PROCESS_KEY);

        broadcastOk(SIGNAL_NAME, "{\"variables\":{}}");
        assertThat(waitingCount(SIGNAL_NAME)).isZero();
        assertThat(signalAuditRows()).hasSize(1);

        mockMvc.perform(post("/api/admin/signals/{signalName}/broadcast", SIGNAL_NAME)
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        assertThat(signalAuditRows())
                .as("被 404 擋下的第二次廣播不得留稽核")
                .hasSize(1);
    }

    // ── 6. 形狀 ───────────────────────────────────────────────────

    @Test
    @DisplayName("#24 形狀：signalName 空／過長、variables 非物件 → 400（先於 404）")
    void malformedRequestsAreRejectedWith400() throws Exception {
        // 全空白 signal name（%20 由 path 解碼成 " "）。
        mockMvc.perform(post("/api/admin/signals/%20/broadcast")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // 過長（上限 255 = ACT_RU_EVENT_SUBSCR.EVENT_NAME_ 的欄寬）。
        mockMvc.perform(post("/api/admin/signals/{signalName}/broadcast", "x".repeat(256))
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // variables 非物件：形狀（400）先於存在性（404）—— 此刻沒有等待者，
        // 若順序反了這條會拿到 404。
        mockMvc.perform(post("/api/admin/signals/{signalName}/broadcast", SIGNAL_NAME)
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":[1,2]}"))
                .andExpect(status().isBadRequest());

        assertThat(signalAuditRows()).isEmpty();
        assertThat(waitingCount(SIGNAL_NAME)).isZero();
    }

    @Test
    @DisplayName("#24 形狀：signalName 含控制字元 → 400（直接驗證器，不經 URL 防火牆）")
    void controlCharacterInSignalNameIsRejected() {
        // StrictHttpFirewall 會在請求進到 controller 前擋掉 URL 裡的編碼控制字元，
        // 所以這條不經 HTTP，直接呼叫 package-private 驗證器釘住分支本身。
        assertThatThrownBy(() -> SignalBroadcastController.validateSignalName("wake\u0000signal"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("控制字元");
        assertThatThrownBy(() -> SignalBroadcastController.validateSignalName("wake\nsignal"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("控制字元");
    }

    // ── 7. 已知限制：process-scoped 訊號 ──────────────────────────

    @Test
    @DisplayName("#24 已知限制：process-scoped 訊號會被 waiting 數到，但全域廣播不喚醒")
    void processScopedSignalIsNotWokenByGlobalBroadcast() throws Exception {
        String pid = startWaiting(PROCESS_SCOPED_KEY);
        assertThat(waitingCount(PROCESS_SCOPED_SIGNAL))
                .as("ExecutionQuery 不過濾 scope，所以數得到 process-scoped 訂閱")
                .isEqualTo(1);

        // 回應仍是 200（廣播指令本身沒有失敗），但實例其實沒有被喚醒 ——
        // 這是 Flowable 對 global broadcast 的定義，已在 controller 的類別註解記載。
        broadcastOk(PROCESS_SCOPED_SIGNAL, "{}");

        assertThat(taskIdsOf(pid)).as("process-scoped 訊號只能由流程內部觸發").isEmpty();
        assertThat(waitingCount(PROCESS_SCOPED_SIGNAL)).isEqualTo(1);
    }
}
