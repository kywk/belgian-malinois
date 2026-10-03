package com.bpm.core.notify;

import com.bpm.core.webhook.WebhookUrlPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * #44 {@link TeamsNotifyDelegate} 的發送規則。
 *
 * <h2>為什麼用真的 HTTP server 而不是 mock {@code RestClient}</h2>
 *
 * <p>要釘住的幾條規則都跟「請求有沒有真的出去」有關：SSRF 政策拒絕時
 * <b>不得</b>發請求、webhookUrl／message 為空時不得發請求、2xx 才記
 * 送達、非 2xx／逾時／連線失敗不得往外拋。用 mock 的 fluent API 只能
 * 證明「有呼叫某個方法」，而 {@link HttpServer} 可以從<b>另一端</b>數
 * 請求、檢查實際送出的 body —— 這是唯一能區分「送出了」與「看起來
 * 送出了」的證據（與 {@code ExternalApiDelegateTest} 同一條理由）。
 *
 * <p>逾時測試刻意用短的 read timeout（100ms）＋慢端點（睡 1s），讓測試
 * 時間可預期，不依賴 TCP 的預設逾時。
 *
 * <h2>policy 的兩種設定</h2>
 *
 * <p>允許路徑用 {@code new WebhookUrlPolicy("localhost")}（把 loopback
 * 明列為例外，與 {@code application-test.yml} 的設定同一條路徑）；
 * 拒絕路徑用空清單，並以 {@code 127.0.0.1} 當目標 —— 它與
 * {@code localhost} 是不同的字面 host，而政策比對的就是字面 host。
 * 兩者都是真的 {@link WebhookUrlPolicy}，不 mock。
 */
class TeamsNotifyDelegateTest {

    private HttpServer server;
    private String baseUrl;

    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private DelegateExecution execution;

    @BeforeEach
    void setUp() throws IOException {
        hits.set(0);
        lastMethod.set(null);
        lastContentType.set(null);
        lastBody.set(null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastMethod.set(exchange.getRequestMethod());
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String path = exchange.getRequestURI().getPath();
            if (path.contains("/slow")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status;
            if (path.contains("/fail")) {
                status = 503;
            } else if (path.contains("/redirect")) {
                status = 302; // 不跟隨 3xx；3xx 也不算送達
            } else {
                status = 200;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        // ⚠️ 必須是 localhost：allowed-hosts 比對的是 URL 裡的字面 host。
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        execution = Mockito.mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("pid-1");
        when(execution.getCurrentActivityId()).thenReturn("notifyTeams");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 允許 localhost（loopback 的明確例外），逾時寬鬆。 */
    private TeamsNotifyDelegate delegateAllowingLocalhost() {
        return new TeamsNotifyDelegate(new WebhookUrlPolicy("localhost"), new ObjectMapper(), 2000, 2000);
    }

    /** 空清單：loopback／內網全拒。 */
    private TeamsNotifyDelegate delegateRejectingAll() {
        return new TeamsNotifyDelegate(new WebhookUrlPolicy(""), new ObjectMapper(), 2000, 2000);
    }

    private static ServiceTask serviceTask(FieldExtension... fields) {
        ServiceTask task = new ServiceTask();
        task.setId("notifyTeams");
        for (FieldExtension field : fields) {
            task.getFieldExtensions().add(field);
        }
        return task;
    }

    private static FieldExtension field(String name, String stringValue) {
        FieldExtension field = new FieldExtension();
        field.setFieldName(name);
        field.setStringValue(stringValue);
        return field;
    }

    // ── 正向 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("title／message 原樣送出：POST JSON {\"text\":\"title\\nmessage\"}")
    void sendsTitleAndMessageAsText() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", baseUrl + "/hook"),
                field("title", "案件通知"),
                field("message", "流程已受理。")));

        delegateAllowingLocalhost().send(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastContentType.get()).contains("application/json");
        assertThat(lastBody.get()).isEqualTo("{\"text\":\"案件通知\\n流程已受理。\"}");
    }

    @Test
    @DisplayName("${var} 在 webhookUrl／title／message 都替換（三個欄位共用同一份 helper）")
    void substitutesVariablesInAllFields() {
        when(execution.getVariable("hookName")).thenReturn("echo");
        when(execution.getVariable("caseNo")).thenReturn("C-2026-001");
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", baseUrl + "/${hookName}"),
                field("title", "案件 ${caseNo}"),
                field("message", "承辦人：${approver}")));

        delegateAllowingLocalhost().send(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastBody.get())
                .as("${approver} 不存在 → 替換成空字串（BpmnFieldSupport 的既有語意）")
                .isEqualTo("{\"text\":\"案件 C-2026-001\\n承辦人：\"}");
    }

    @Test
    @DisplayName("title 缺欄位 → 只有 message；不留前導換行")
    void missingTitleSendsMessageOnly() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", baseUrl + "/hook"),
                field("message", "只有訊息")));

        delegateAllowingLocalhost().send(execution);

        assertThat(lastBody.get()).isEqualTo("{\"text\":\"只有訊息\"}");
    }

    @Test
    @DisplayName("title 全空白 → 與缺欄位相同（不送出「空白標題」）")
    void blankTitleIsTreatedAsMissing() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", baseUrl + "/hook"),
                field("title", "   "),
                field("message", "只有訊息")));

        delegateAllowingLocalhost().send(execution);

        assertThat(lastBody.get()).isEqualTo("{\"text\":\"只有訊息\"}");
    }

    @Test
    @DisplayName("JSON 跳脫由 ObjectMapper 產生：引號／反斜線／換行都原樣還原，且只有 text 一個欄位")
    void payloadEscapesJsonSpecialCharacters() throws Exception {
        String payload = delegateAllowingLocalhost().payload("標題 \"A\"", "line1\nline2\\x");

        JsonNode node = new ObjectMapper().readTree(payload);
        assertThat(node.size()).as("payload 形狀固定是單一欄位 text").isEqualTo(1);
        assertThat(node.get("text").asText()).isEqualTo("標題 \"A\"\nline1\nline2\\x");
        assertThat(payload).startsWith("{\"text\":\"").endsWith("\"}");
    }

    // ── 反向：不發請求 ──────────────────────────────────────────────

    @Test
    @DisplayName("webhookUrl 欄位不存在 → no-op（不發請求），也不拋例外")
    void missingWebhookUrlIsNoOp() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("message", "x")));

        assertThatCode(() -> delegateAllowingLocalhost().execute(execution)).doesNotThrowAnyException();

        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("webhookUrl 替換後塌成空白（${missing}）→ no-op（不發請求到空位址）")
    void blankOrCollapsedWebhookUrlIsNoOp() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", "${missing}"),
                field("message", "x")));

        assertThatCode(() -> delegateAllowingLocalhost().execute(execution)).doesNotThrowAnyException();

        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("message 欄位不存在 → no-op（不發空訊息），也不拋例外")
    void missingMessageIsNoOp() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", baseUrl + "/hook")));

        assertThatCode(() -> delegateAllowingLocalhost().execute(execution)).doesNotThrowAnyException();

        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("message 全為空白／替換後塌成空白 → no-op（不發空訊息）")
    void blankOrCollapsedMessageIsNoOp() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", baseUrl + "/hook"),
                field("message", " ${missing} ")));

        assertThatCode(() -> delegateAllowingLocalhost().execute(execution)).doesNotThrowAnyException();

        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("掛在非 ServiceTask 上（掛錯位置）→ no-op，不拋例外")
    void nonServiceTaskCurrentElementIsNoOp() {
        when(execution.getCurrentFlowElement()).thenReturn(new UserTask());

        assertThatCode(() -> delegateAllowingLocalhost().execute(execution)).doesNotThrowAnyException();

        assertThat(hits.get()).isZero();
    }

    // ── SSRF 政策：拒絕時不得發請求 ─────────────────────────────────

    @Test
    @DisplayName("🔴 policy 拒絕（127.0.0.1 不在清單）→ no-op，且請求數為 0（不丟 BpmnError）")
    void policyRejectionIsNoOpWithoutSendingRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook"),
                field("message", "x")));

        assertThatCode(() -> delegateRejectingAll().execute(execution))
                .as("通知類 fail-open：拒絕只 log warn，不能像 ExternalApiDelegate 丟 BpmnError")
                .doesNotThrowAnyException();

        assertThat(hits.get())
                .as("政策拒絕是非暫時性的；連嘗試都不能有")
                .isZero();
    }

    // ── fail-open：非 2xx／3xx／逾時／連線失敗都不往外拋 ──────────────

    @Test
    @DisplayName("非 2xx（503）→ execute() 吞掉，只記 log；請求確實有出去")
    void non2xxIsSwallowedByExecute() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", baseUrl + "/fail"),
                field("message", "x")));

        assertThatCode(() -> delegateAllowingLocalhost().execute(execution)).doesNotThrowAnyException();

        assertThat(hits.get())
                .as("非 2xx 是『有送出去但對方失敗』—— 與政策拒絕（零請求）不同")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("3xx 不算送達（不跟隨重導）：execute() 不拋例外，請求只有原始那一次")
    void redirectStatusIsNotTreatedAsDelivered() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", baseUrl + "/redirect"),
                field("message", "x")));

        assertThatCode(() -> delegateAllowingLocalhost().execute(execution)).doesNotThrowAnyException();

        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("讀取逾時（100ms vs 睡 1s）→ execute() 吞掉，不是無限等待")
    void readTimeoutIsSwallowedByExecute() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", baseUrl + "/slow"),
                field("message", "x")));
        TeamsNotifyDelegate delegate =
                new TeamsNotifyDelegate(new WebhookUrlPolicy("localhost"), new ObjectMapper(), 2000, 100);

        assertThatCode(() -> delegate.execute(execution)).doesNotThrowAnyException();

        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("連線失敗（關掉的 port）→ execute() 吞掉，流程不受影響")
    void connectionRefusedIsSwallowedByExecute() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("webhookUrl", "http://localhost:" + closedPort + "/nope"),
                field("message", "x")));

        assertThatCode(() -> delegateAllowingLocalhost().execute(execution)).doesNotThrowAnyException();
    }
}
