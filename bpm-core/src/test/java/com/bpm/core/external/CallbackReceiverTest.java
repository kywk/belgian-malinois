package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工項 #21：回呼接收端（{@code POST /api/callback/{type}}）。
 *
 * <h2>測什麼</h2>
 *
 * <p>認證（HMAC 簽章＋時間戳窗）、授權（allowedActions 的 {@code callback}）、
 * 形狀（400）、冪等（deliveryId）、correlation（message catch event），
 * 以及管理端 callback secret 的產生與輪換。
 *
 * <h2>為什麼簽章在測試裡自己算，不用 {@link CallbackSignatureUtil#sign}</h2>
 *
 * <p>用生產程式碼產生測試簽章，會讓「產生器與驗證器有同一個 bug」的組合
 * 全綠 —— 那正是這條 wire format 最該被獨立驗證的地方。所以這裡用 JDK 的
 * {@code Mac} 重寫一次 HMAC，連 {@code sha256=} 前綴都自己組。
 *
 * <h2>負向控制組實測（把本工項的防線逐一移除後重跑本類別）</h2>
 *
 * <p>依 repo 慣例把「移除防線」的結果記在這裡，避免日後有人以為這組測試
 * 在任何實作下都會紅：
 *
 * <ul>
 *   <li><b>移除簽章驗證</b>（{@code CallbackAuthFilter} 不呼叫
 *       {@code CallbackSignatureUtil.verify}）：<b>16 條中 3 條紅</b> ——
 *       {@link #badSignatureIsRejected}、{@link #tamperedBodyIsRejected}、
 *       {@link #rotateInvalidatesOldSecretAndActivatesNew}（輪換後的舊密鑰
 *       被放行）。其餘 13 條綠，包含所有「認證之前」的拒絕（缺標頭、
 *       查無系統、未設密鑰、時間戳超窗）。</li>
 *   <li><b>移除時間戳窗</b>（不檢查 ±5 分鐘）：<b>1 條紅</b> ——
 *       {@link #timestampOutsideWindowIsRejected}。其餘 15 條綠。</li>
 *   <li><b>移除冪等</b>（忽略 {@code setIfAbsent} 的結果）：
 *       <b>2 條紅</b> —— {@link #duplicateDeliveryIdIsIdempotent} 與
 *       {@link #concurrentDuplicatesWakeProcessOnce}（第二次落到 correlation
 *       → 404，而不是 duplicate）。其餘 14 條綠。</li>
 *   <li><b>不釋放失敗的冪等鍵</b>（移除 {@code releaseOnRollback}）：
 *       <b>1 條紅</b> —— {@link #failedCorrelationDoesNotPoisonIdempotencyKey}。
 *       其餘 15 條綠。</li>
 * </ul>
 *
 * <p><b>證明不了的事</b>：這些負控只證明「移除哪一行會讓哪一條紅」，
 * 不證明沒有其他繞過路徑。特別是 Redis 故障時的 fail-closed 503 只由
 * {@code CallbackController.acquireIdempotencyKey} 的分支保證，
 * 本組測試沒有真的停掉 Redis 來驗（Testcontainers 容器由所有測試共用，不能停）。
 */
class CallbackReceiverTest extends IntegrationTestBase {

    private static final String SYSTEM_ID = "erp";
    private static final String MESSAGE_NAME = "esign-completed";
    private static final String PROCESS_KEY = "t21-callback-flow";
    private static final String LATE_WAIT_KEY = "t21-late-wait-flow";
    private static final String PLAIN_API_KEY = "sk-t21-testkey";
    private static final String SECRET = "cs-t21-test-secret";

    /** 回呼後任務的名稱；用來證明流程真的被喚醒並續行。 */
    private static final String AFTER_TASK_NAME = "回呼後任務";

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://bpm.com/callback-test">
              <message id="esignMessage" name="esign-completed"/>
              <process id="t21-callback-flow" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="waitEsign"/>
                <intermediateCatchEvent id="waitEsign">
                  <messageEventDefinition messageRef="esignMessage"/>
                </intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="waitEsign" targetRef="afterCallback"/>
                <userTask id="afterCallback" name="回呼後任務" flowable:assignee="mgr001"/>
                <sequenceFlow id="f3" sourceRef="afterCallback" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    /**
     * 先停在 userTask，完成後才進入 message catch event。
     * 用來驗「correlation 失敗後冪等鍵被釋放」——同一個 pid 在第一次回呼時
     * 還沒有等待中的訂閱，之後才等到。
     */
    private static final String LATE_WAIT_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://bpm.com/callback-test">
              <message id="esignMessageLate" name="esign-completed"/>
              <process id="t21-late-wait-flow" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="prepare"/>
                <userTask id="prepare" name="準備" flowable:assignee="mgr001"/>
                <sequenceFlow id="f2" sourceRef="prepare" targetRef="waitEsign"/>
                <intermediateCatchEvent id="waitEsign">
                  <messageEventDefinition messageRef="esignMessageLate"/>
                </intermediateCatchEvent>
                <sequenceFlow id="f3" sourceRef="waitEsign" targetRef="afterCallback"/>
                <userTask id="afterCallback" name="回呼後任務" flowable:assignee="mgr001"/>
                <sequenceFlow id="f4" sourceRef="afterCallback" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    @Autowired private ExternalSystemRepository repo;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private RepositoryService repositoryService;
    @Autowired private StringRedisTemplate redis;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        repo.deleteAll();
        // 稽核是同步寫入（AuditEventPublisher fail-closed），清乾淨讓每條測試
        // 可以斷言「只有這一筆」。
        truncateAuditLog();
        deployOnce(PROCESS_KEY, BPMN);
        deployOnce(LATE_WAIT_KEY, LATE_WAIT_BPMN);
    }

    // ── fixture／工具 ──────────────────────────────────────────────

    private void deployOnce(String key, String xml) {
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count() > 0) {
            return;
        }
        repositoryService.createDeployment()
                .name("t21-callback-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
    }

    private ExternalSystem givenSystem(String systemId, String callbackSecret, String allowedActions) {
        ExternalSystem sys = new ExternalSystem();
        // 刻意不設 id（@GeneratedValue(strategy = UUID)；自行指定會走 merge）。
        sys.setSystemId(systemId);
        sys.setSystemName("T21 測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_API_KEY));
        sys.setAllowedActions(allowedActions);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        if (callbackSecret != null) {
            // 存密鑰本身：HMAC 驗簽需要原始值（與 apiKey 的雜湊不同）。
            sys.setCallbackSecret(callbackSecret);
        }
        return repo.save(sys);
    }

    private String startWaitingProcess(String processKey) {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                processKey, "t21-" + UUID.randomUUID());
        return pi.getId();
    }

    private List<Execution> waitingExecutions(String pid) {
        return runtimeService.createExecutionQuery()
                .processInstanceId(pid)
                .messageEventSubscriptionName(MESSAGE_NAME)
                .list();
    }

    private static String callbackBody(String pid, String deliveryId, String variablesJson) {
        return "{\"processInstanceId\":\"" + pid + "\",\"deliveryId\":\"" + deliveryId
                + "\",\"variables\":" + variablesJson + "}";
    }

    private MockHttpServletRequestBuilder callback(String type, String body, String secret, Instant ts) {
        return post("/api/callback/" + type)
                .header("X-System-Id", SYSTEM_ID)
                .header("X-Callback-Signature", "sha256=" + hmacHex(secret, body))
                .header("X-Callback-Timestamp", ts.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /**
     * 逐項決定要不要帶標頭。{@code MockHttpServletRequestBuilder.header()} 是
     * 累加而非覆寫，所以「同名設兩次」不會模擬出「缺標頭」——必須真的不設。
     */
    private static MockHttpServletRequestBuilder callbackWithHeaders(
            String systemId, String signature, String timestamp, String body) {
        var req = post("/api/callback/" + MESSAGE_NAME)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (systemId != null) req = req.header("X-System-Id", systemId);
        if (signature != null) req = req.header("X-Callback-Signature", signature);
        if (timestamp != null) req = req.header("X-Callback-Timestamp", timestamp);
        return req;
    }

    /** 測試自己實作的 HMAC（見類別註解：不用生產程式碼產生簽章）。 */
    private static String hmacHex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonString(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(json);
        assertThat(m.find()).as("回應缺少欄位 " + field + "：" + json).isTrue();
        return m.group(1);
    }

    private List<String> callbackAuditRows() {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT operator_id, process_instance_id, detail FROM bpm_audit_log "
                                 + "WHERE operation_type = 'EXTERNAL_API_CALL' ORDER BY id")) {
                while (rs.next()) {
                    out.add(rs.getString("operator_id") + "|"
                            + rs.getString("process_instance_id") + "|"
                            + rs.getString("detail"));
                }
            }
        });
        return out;
    }

    private List<String> configChangeRows() {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT operator_id, detail FROM bpm_audit_log "
                                 + "WHERE operation_type = 'CONFIG_CHANGE' ORDER BY id")) {
                while (rs.next()) {
                    out.add(rs.getString("operator_id") + "|" + rs.getString("detail"));
                }
            }
        });
        return out;
    }

    private long taskCount(String pid) {
        return taskService.createTaskQuery().processInstanceId(pid).count();
    }

    // ── 1. 管理端：建立與輪換 ─────────────────────────────────────

    @Test
    @DisplayName("#21：建立外部系統回傳 callbackSecret（明文僅一次），DB 存密鑰、GET 遮蔽")
    void createReturnsCallbackSecretOnceAndStoresSecret() throws Exception {
        String sid = "t21-create-" + UUID.randomUUID().toString().substring(0, 8);

        String body = mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"" + sid + "\",\"systemName\":\"T21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackSecret").exists())
                .andReturn().getResponse().getContentAsString();

        String plain = jsonString(body, "callbackSecret");
        assertThat(plain).as("回呼密鑰必須有可辨識的前綴").startsWith("cs-");

        ExternalSystem saved = repo.findBySystemId(sid).orElseThrow();
        assertThat(saved.getCallbackSecret())
                .as("HMAC 驗簽需要原始密鑰，DB 必須存得回它（不是雜湊）")
                .isEqualTo(plain);

        mockMvc.perform(get("/api/admin/external-systems/" + sid)
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackSecret").value("***"));
    }

    @Test
    @DisplayName("#21：rotate-callback-secret 後舊密鑰失效、新密鑰生效，且稽核不記明文")
    void rotateInvalidatesOldSecretAndActivatesNew() throws Exception {
        String oldSecret = "cs-old-" + UUID.randomUUID();
        // 回呼的 X-System-Id 固定是 SYSTEM_ID，所以受測系統就用它建立
        // （每個測試開頭都 repo.deleteAll()，不會與其他測試互相汙染）。
        givenSystem(SYSTEM_ID, oldSecret, null);

        // 輪換前：舊密鑰可用 —— 少了這一步，「輪換後失效」證明不了任何事。
        String pidBefore = startWaitingProcess(PROCESS_KEY);
        mockMvc.perform(callback(MESSAGE_NAME,
                        callbackBody(pidBefore, "d-" + UUID.randomUUID(), "{}"),
                        oldSecret, Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        String rotateBody = mockMvc.perform(post("/api/admin/external-systems/" + SYSTEM_ID
                                + "/rotate-callback-secret")
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackSecret").exists())
                .andReturn().getResponse().getContentAsString();
        String newSecret = jsonString(rotateBody, "callbackSecret");
        assertThat(newSecret).startsWith("cs-").isNotEqualTo(oldSecret);

        ExternalSystem rotated = repo.findBySystemId(SYSTEM_ID).orElseThrow();
        assertThat(rotated.getCallbackSecret())
                .as("DB 存的是可還原的新密鑰（HMAC 需要），且舊密鑰已被取代")
                .isEqualTo(newSecret)
                .isNotEqualTo(oldSecret);

        // 輪換後：舊密鑰 → 401（認證先於 correlation，所以不是「查無訂閱」的 404），
        // 而且流程必須還在等待。
        String pidAfter = startWaitingProcess(PROCESS_KEY);
        String bodyAfter = callbackBody(pidAfter, "d-" + UUID.randomUUID(), "{}");
        mockMvc.perform(callback(MESSAGE_NAME, bodyAfter, oldSecret, Instant.now()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(CallbackAuthFilter.AUTH_FAILED));
        assertThat(waitingExecutions(pidAfter)).hasSize(1);

        // 新密鑰 → 200，且流程真的被喚醒。
        mockMvc.perform(callback(MESSAGE_NAME, bodyAfter, newSecret, Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        assertThat(taskCount(pidAfter)).isEqualTo(1);

        String audit = String.join("\n", configChangeRows());
        assertThat(audit)
                .as("輪換是授權／憑證變更，必須留痕")
                .contains("rotate-callback-secret");
        assertThat(audit)
                .as("明文回呼密鑰絕不可進稽核庫")
                .doesNotContain(newSecret)
                .doesNotContain(oldSecret);
    }

    // ── 2. 正向：簽章正確 → correlation ──────────────────────────

    @Test
    @DisplayName("#21：簽章正確 → 喚醒等待中的 message catch event、變數寫入、流程續行")
    void validSignatureCorrelatesProcessAndWritesVariables() throws Exception {
        // allowedActions 留 null：空值＝不限制，沿用 ExternalSystemPolicy
        // 的同一條規則（這是正向對照，同時證明 policy 沒被繞過）。
        givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);
        assertThat(waitingExecutions(pid)).hasSize(1);

        String deliveryId = "d-" + UUID.randomUUID();
        String body = callbackBody(pid, deliveryId,
                "{\"esignResult\":\"approved\",\"esignComment\":\"ok\"}");

        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.processInstanceId").value(pid));

        assertThat(waitingExecutions(pid))
                .as("訂閱必須被消耗，否則流程會停在原地")
                .isEmpty();
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pid).list();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getName()).isEqualTo(AFTER_TASK_NAME);

        assertThat(runtimeService.getVariable(pid, "esignResult"))
                .as("回呼帶的變數必須寫進流程，否則後續關卡看不到審核結果")
                .isEqualTo("approved");
        assertThat(runtimeService.getVariable(pid, "esignComment")).isEqualTo("ok");

        assertThat(callbackAuditRows())
                .anySatisfy(row -> assertThat(row)
                        .contains("system:" + SYSTEM_ID)
                        .contains(pid)
                        .contains("callback")
                        .contains(deliveryId));
    }

    @Test
    @DisplayName("#21：epoch 秒時間戳也可接受（規格允許 ISO-8601 或 epoch）")
    void epochTimestampIsAccepted() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);
        String body = callbackBody(pid, "d-" + UUID.randomUUID(), "{}");

        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET,
                        Instant.ofEpochSecond(Instant.now().getEpochSecond())))
                .andExpect(status().isOk());
    }

    // ── 3. 認證失敗一律 401 ───────────────────────────────────────

    @Test
    @DisplayName("#21：缺任一標頭 → 401（訊息只說缺哪些標頭，不涉及系統是否存在）")
    void missingHeadersAreRejected() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);
        String body = callbackBody(pid, "d-" + UUID.randomUUID(), "{}");

        for (String missing : List.of("X-System-Id", "X-Callback-Signature", "X-Callback-Timestamp")) {
            String systemHeader = "X-System-Id".equals(missing) ? null : SYSTEM_ID;
            String signatureHeader = "X-Callback-Signature".equals(missing)
                    ? null : "sha256=" + hmacHex(SECRET, body);
            String timestampHeader = "X-Callback-Timestamp".equals(missing)
                    ? null : Instant.now().toString();

            mockMvc.perform(callbackWithHeaders(systemHeader, signatureHeader, timestampHeader, body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(
                            "Missing X-System-Id, X-Callback-Signature or X-Callback-Timestamp"));
        }
        assertThat(taskCount(pid)).as("被拒的請求不得喚醒流程").isZero();
    }

    @Test
    @DisplayName("#21：簽章錯誤 → 401（相同訊息，不洩漏系統是否存在）")
    void badSignatureIsRejected() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);
        String body = callbackBody(pid, "d-" + UUID.randomUUID(), "{}");

        mockMvc.perform(callback(MESSAGE_NAME, body, "cs-not-the-secret", Instant.now()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(CallbackAuthFilter.AUTH_FAILED));

        // 同一個 systemId、同一個時間戳，換成不存在的系統：回應必須完全一樣。
        String ghost = "{\"processInstanceId\":\"" + pid + "\",\"deliveryId\":\"d-ghost\",\"variables\":{}}";
        mockMvc.perform(post("/api/callback/" + MESSAGE_NAME)
                        .header("X-System-Id", "no-such-system")
                        .header("X-Callback-Signature", "sha256=" + hmacHex(SECRET, ghost))
                        .header("X-Callback-Timestamp", Instant.now().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ghost))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(CallbackAuthFilter.AUTH_FAILED));

        assertThat(taskCount(pid)).isZero();
        assertThat(callbackAuditRows()).as("認證失敗只寫 log，不寫成功稽核").isEmpty();
    }

    @Test
    @DisplayName("#21：簽章只涵蓋原始 body —— 竄改 body 後即使簽章格式正確也 401")
    void tamperedBodyIsRejected() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);
        String deliveryId = "d-" + UUID.randomUUID();
        String original = callbackBody(pid, deliveryId, "{\"esignResult\":\"rejected\"}");
        String tampered = callbackBody(pid, deliveryId, "{\"esignResult\":\"approved\"}");

        mockMvc.perform(post("/api/callback/" + MESSAGE_NAME)
                        .header("X-System-Id", SYSTEM_ID)
                        // 對 original 的簽章，搭配 tampered 的 body。
                        .header("X-Callback-Signature", "sha256=" + hmacHex(SECRET, original))
                        .header("X-Callback-Timestamp", Instant.now().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tampered))
                .andExpect(status().isUnauthorized());

        assertThat(waitingExecutions(pid)).as("流程必須還在等待，變數不得寫入").hasSize(1);
    }

    @Test
    @DisplayName("#21：時間戳超出 ±5 分鐘或無法解析 → 401（防重放）")
    void timestampOutsideWindowIsRejected() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);
        String body = callbackBody(pid, "d-" + UUID.randomUUID(), "{}");

        for (Instant ts : List.of(Instant.now().minusSeconds(6 * 60),
                Instant.now().plusSeconds(6 * 60))) {
            mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, ts))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(CallbackAuthFilter.AUTH_FAILED));
        }

        mockMvc.perform(post("/api/callback/" + MESSAGE_NAME)
                        .header("X-System-Id", SYSTEM_ID)
                        .header("X-Callback-Signature", "sha256=" + hmacHex(SECRET, body))
                        .header("X-Callback-Timestamp", "not-a-timestamp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(waitingExecutions(pid)).hasSize(1);
    }

    @Test
    @DisplayName("#21：系統不存在／未設 callback secret → 401")
    void unknownSystemOrMissingSecretIsRejected() throws Exception {
        String pid = startWaitingProcess(PROCESS_KEY);
        String body = callbackBody(pid, "d-" + UUID.randomUUID(), "{}");

        // 未設密鑰（V5 migration 之後的既有系統就是這個狀態）。
        givenSystem(SYSTEM_ID, null, null);
        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(CallbackAuthFilter.AUTH_FAILED));

        // 系統不存在。
        mockMvc.perform(post("/api/callback/" + MESSAGE_NAME)
                        .header("X-System-Id", "ghost-system")
                        .header("X-Callback-Signature", "sha256=" + hmacHex(SECRET, body))
                        .header("X-Callback-Timestamp", Instant.now().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(CallbackAuthFilter.AUTH_FAILED));

        assertThat(waitingExecutions(pid)).hasSize(1);
    }

    // ── 4. 授權失敗 403 ──────────────────────────────────────────

    @Test
    @DisplayName("#21：系統停用／IP 不在白名單 → 403（簽章通過後才檢查）")
    void disabledOrWrongIpIsForbidden() throws Exception {
        ExternalSystem sys = givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);
        String body = callbackBody(pid, "d-" + UUID.randomUUID(), "{}");

        sys.setEnabled(false);
        repo.save(sys);
        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("System is disabled"));

        sys.setEnabled(true);
        sys.setIpWhitelist("10.99.99.99"); // MockMvc 的 remoteAddr 是 127.0.0.1
        repo.save(sys);
        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString("IP not in whitelist")));

        assertThat(waitingExecutions(pid)).hasSize(1);
    }

    @Test
    @DisplayName("#21：allowedActions 未授權 callback → 403；空值＝不限制（同一條 policy 規則）")
    void callbackActionMustBeAuthorized() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, "[\"start_process\"]");
        String pid = startWaitingProcess(PROCESS_KEY);
        String body = callbackBody(pid, "d-" + UUID.randomUUID(), "{}");

        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Action not allowed: callback"));

        // 明確的空清單＝拒絕全部（與 null 不同）。
        ExternalSystem sys = repo.findBySystemId(SYSTEM_ID).orElseThrow();
        sys.setAllowedActions("[]");
        repo.save(sys);
        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isForbidden());

        // 空值＝不限制 → 放行。這一條是正向對照：沒有它，把 policy 改成
        // 「一律拒絕」也會讓上面兩個 403 全綠。
        sys.setAllowedActions(null);
        repo.save(sys);
        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isOk());
    }

    // ── 5. 形狀 400 ──────────────────────────────────────────────

    @Test
    @DisplayName("#21：缺 deliveryId／processInstanceId／variables 形狀錯 → 400（與重試可分）")
    void malformedBodyIsBadRequest() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);

        for (String body : List.of(
                // 缺 deliveryId（冪等鍵必填）
                "{\"processInstanceId\":\"" + pid + "\",\"variables\":{}}",
                "{\"processInstanceId\":\"" + pid + "\",\"deliveryId\":\"\",\"variables\":{}}",
                // 缺 processInstanceId
                "{\"deliveryId\":\"d-1\",\"variables\":{}}",
                // variables 不是物件
                "{\"processInstanceId\":\"" + pid + "\",\"deliveryId\":\"d-2\",\"variables\":[]}",
                // 沒有 body
                "")) {
            mockMvc.perform(post("/api/callback/" + MESSAGE_NAME)
                            .header("X-System-Id", SYSTEM_ID)
                            .header("X-Callback-Signature", "sha256=" + hmacHex(SECRET, body))
                            .header("X-Callback-Timestamp", Instant.now().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        assertThat(waitingExecutions(pid)).hasSize(1);
    }

    // ── 6. 冪等 ─────────────────────────────────────────────────

    @Test
    @DisplayName("#21：同 deliveryId 第二次 → 200 duplicate；流程只被喚醒一次、稽核只一筆")
    void duplicateDeliveryIdIsIdempotent() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);
        String deliveryId = "d-" + UUID.randomUUID();
        String body = callbackBody(pid, deliveryId, "{\"esignResult\":\"approved\"}");

        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(taskCount(pid)).as("流程只能被喚醒一次").isEqualTo(1);
        assertThat(callbackAuditRows())
                .as("duplicate 不重複寫成功稽核")
                .hasSize(1);
        assertThat(callbackAuditRows().get(0)).contains(deliveryId);

        assertThat(redis.getExpire(CallbackController.IDEMPOTENCY_KEY_PREFIX + SYSTEM_ID + ":" + deliveryId))
                .as("冪等鍵必須有 TTL，否則 Redis 會永久累積")
                .isGreaterThan(0L);
    }

    @Test
    @DisplayName("#21：並行的同 deliveryId → 恰好一次 ok、一次 duplicate，流程只被喚醒一次")
    void concurrentDuplicatesWakeProcessOnce() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);
        String pid = startWaitingProcess(PROCESS_KEY);
        String deliveryId = "d-" + UUID.randomUUID();
        String body = callbackBody(pid, deliveryId, "{\"esignResult\":\"approved\"}");

        // 真正的並行：兩條請求同時送出，讓 Redis SetIfAbsent 的競爭真的發生。
        CompletableFuture<HttpResponse<String>> f1 = CompletableFuture.supplyAsync(
                () -> sendOverRealHttp(body));
        CompletableFuture<HttpResponse<String>> f2 = CompletableFuture.supplyAsync(
                () -> sendOverRealHttp(body));
        List<HttpResponse<String>> responses = CompletableFuture
                .allOf(f1, f2)
                .thenApply(ignored -> List.of(f1.join(), f2.join()))
                .join();

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200);
        assertThat(responses).extracting(HttpResponse::body)
                .anySatisfy(b -> assertThat(b).contains("\"status\":\"ok\""))
                .anySatisfy(b -> assertThat(b).contains("\"status\":\"duplicate\""));

        assertThat(taskCount(pid)).isEqualTo(1);
        assertThat(callbackAuditRows()).hasSize(1);
    }

    private HttpResponse<String> sendOverRealHttp(String body) {
        var req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/callback/" + MESSAGE_NAME))
                .header("X-System-Id", SYSTEM_ID)
                .header("X-Callback-Signature", "sha256=" + hmacHex(SECRET, body))
                .header("X-Callback-Timestamp", Instant.now().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("真實 HTTP 回呼失敗", e);
        }
    }

    @Test
    @DisplayName("#21：correlation 失敗（404）不得毒化冪等鍵 —— 之後同 deliveryId 可重送")
    void failedCorrelationDoesNotPoisonIdempotencyKey() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);
        // 這支流程先停在 prepare，完成後才進入 message catch event。
        String pid = startWaitingProcess(LATE_WAIT_KEY);
        Task prepare = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(prepare.getName()).isEqualTo("準備");

        String deliveryId = "d-" + UUID.randomUUID();
        String body = callbackBody(pid, deliveryId, "{\"esignResult\":\"approved\"}");

        // 第一次：還沒有等待中的訂閱 → 404。
        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isNotFound());

        // 讓流程走到等待點，再送一次同一個 deliveryId。
        taskService.complete(prepare.getId());
        assertThat(waitingExecutions(pid)).hasSize(1);

        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        assertThat(taskCount(pid)).isEqualTo(1);
        assertThat(callbackAuditRows()).hasSize(1);
    }

    // ── 7. correlation 404 ──────────────────────────────────────

    @Test
    @DisplayName("#21：查無等待中的訂閱 → 404（訊息說明沒有等待中的流程）")
    void noWaitingSubscriptionIsNotFound() throws Exception {
        givenSystem(SYSTEM_ID, SECRET, null);

        // 不存在的 processInstanceId。訊息內容由 ResponseStatusException 攜帶；
        // MockMvc 不做 ERROR dispatch，所以這裡只驗狀態碼（見
        // ExternalApiProcessKeyNotFoundTest 對 ERROR dispatch 的說明）。
        String body = callbackBody("no-such-pid-" + UUID.randomUUID(), "d-" + UUID.randomUUID(), "{}");
        mockMvc.perform(callback(MESSAGE_NAME, body, SECRET, Instant.now()))
                .andExpect(status().isNotFound());

        // 流程存在、但等待的是別的 message name。
        String pid = startWaitingProcess(PROCESS_KEY);
        String wrongTypeBody = callbackBody(pid, "d-" + UUID.randomUUID(), "{}");
        mockMvc.perform(callback("some-other-message", wrongTypeBody, SECRET, Instant.now()))
                .andExpect(status().isNotFound());

        assertThat(waitingExecutions(pid)).hasSize(1);
        assertThat(callbackAuditRows()).as("404 不是成功，不寫稽核").isEmpty();
    }
}
