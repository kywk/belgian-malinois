package com.bpm.core.engine;

import com.bpm.core.http.SafeRestClients;
import com.bpm.core.webhook.WebhookUrlPolicy;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #45 {@link ESignDelegate} 的觸發規則。
 *
 * <h2>為什麼用真的 HTTP server 而不是 mock {@code RestClient}</h2>
 *
 * <p>要釘住的幾條規則都跟「請求有沒有真的出去」有關：SSRF 政策拒絕時
 * <b>不得</b>發請求、payload 非 JSON 時不得發請求、通過政策的主機 302 到
 * loopback 時不得跟隨（#97）、2xx 的回應 body 要寫進流程變數、
 * 非 2xx／逾時要變 BpmnError。用 mock 的 fluent API 只能證明「有呼叫某個
 * 方法」，而 {@code HttpServer} 可以從<b>另一端</b>數請求，這是唯一能區分
 * 「送出了」與「看起來送出了」的證據。
 *
 * <p>逾時測試刻意用短的 read timeout（100ms）＋慢端點（睡 1s），
 * 讓測試時間可預期，不依賴 TCP 的預設逾時。
 *
 * <h2>policy 的兩種設定</h2>
 *
 * <p>允許路徑用 {@code new WebhookUrlPolicy("localhost")}（把 loopback
 * 明列為例外，與 {@code application-test.yml} 的設定同一條路徑）；
 * 拒絕路徑用空清單，並以 {@code 127.0.0.1} 當目標 —— 它與
 * {@code localhost} 是不同的字面 host，而政策比對的就是字面 host。
 * 兩者都是真的 {@link WebhookUrlPolicy}，不 mock。
 */
class ESignDelegateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;

    /**
     * 302 的 Location 指向的 loopback 端點（#97 的攻擊目標）。
     * 跟隨重導的話這裡會收到請求 —— 斷言它為零就是「沒有被當跳板」的證據。
     */
    private HttpServer redirectTarget;

    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();

    private DelegateExecution execution;

    @BeforeEach
    void setUp() throws IOException {
        hits.set(0);
        redirectTargetHits.set(0);
        lastMethod.set(null);
        lastBody.set(null);
        lastContentType.set(null);

        redirectTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirectTarget.createContext("/", exchange -> {
            redirectTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        redirectTarget.start();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastMethod.set(exchange.getRequestMethod());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));

            String path = exchange.getRequestURI().getPath();
            if (path.contains("/redirect")) {
                // 被允許的主機把請求重導去打 loopback 的攻擊形狀（#97）。
                exchange.getResponseHeaders().set("Location",
                        "http://127.0.0.1:" + redirectTarget.getAddress().getPort() + "/landed");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (path.contains("/slow")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (path.contains("/fail")) {
                // 刻意放一個「敏感」字串：BpmnError 訊息不得把它帶出去。
                byte[] error = "{\"secret\":\"do-not-leak\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, error.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(error);
                }
                return;
            }
            byte[] response = "{\"requestId\":\"REQ-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        execution = Mockito.mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("pid-1");
        when(execution.getCurrentActivityId()).thenReturn("startEsign");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        redirectTarget.stop(0);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 允許 localhost（loopback 的明確例外），逾時寬鬆。 */
    private ESignDelegate delegateAllowingLocalhost() {
        return new ESignDelegate(new WebhookUrlPolicy("localhost"), objectMapper,
                SafeRestClients.create(2000, 2000));
    }

    /** 空清單：loopback／內網全拒。 */
    private ESignDelegate delegateRejectingAll() {
        return new ESignDelegate(new WebhookUrlPolicy(""), objectMapper,
                SafeRestClients.create(2000, 2000));
    }

    private static ServiceTask serviceTask(FieldExtension... fields) {
        ServiceTask task = new ServiceTask();
        task.setId("startEsign");
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

    private static BpmnError triggerAndCatch(ESignDelegate delegate,
                                             DelegateExecution execution) {
        BpmnError error = catchThrowableOfType(() -> delegate.trigger(execution), BpmnError.class);
        assertThat(error).as("應該丟 BpmnError").isNotNull();
        return error;
    }

    // ── 正向 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("2xx：payload 以 JSON POST 送出，回應 body 原字串寫入 resultVariable")
    void successPostsJsonAndWritesResponseBody() {
        when(execution.getVariable("docId")).thenReturn("DOC-7");
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/sign"),
                field("payload", "{\"docId\":\"${docId}\",\"signer\":\"alice\"}"),
                field("resultVariable", "esignResponse")));

        delegateAllowingLocalhost().trigger(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastContentType.get()).contains("application/json");
        assertThat(lastBody.get()).isEqualTo("{\"docId\":\"DOC-7\",\"signer\":\"alice\"}");
        verify(execution).setVariable("esignResponse", "{\"requestId\":\"REQ-1\"}");
    }

    @Test
    @DisplayName("JSON 陣列也算合法 payload（只驗可解析，不發明形狀契約）")
    void jsonArrayIsAccepted() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/sign"),
                field("payload", "[{\"docId\":\"D1\"}]")));

        delegateAllowingLocalhost().trigger(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastBody.get()).isEqualTo("[{\"docId\":\"D1\"}]");
    }

    @Test
    @DisplayName("沒有 resultVariable 時不得偷偷寫任何變數")
    void noResultVariableWritesNothing() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/sign"),
                field("payload", "{\"docId\":\"D1\"}")));

        delegateAllowingLocalhost().trigger(execution);

        assertThat(hits.get()).isEqualTo(1);
        verify(execution, never()).setVariable(anyString(), any());
    }

    // ── SSRF 政策：拒絕時不得發請求 ─────────────────────────────────

    @Test
    @DisplayName("🔴 policy 拒絕（127.0.0.1 不在清單）→ ESIGN_BLOCKED，且請求數為 0")
    void policyRejectionThrowsBlockedWithoutSendingRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/sign"),
                field("payload", "{\"docId\":\"D1\"}"),
                field("resultVariable", "esignResponse")));

        BpmnError error = triggerAndCatch(delegateRejectingAll(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ESignDelegate.ERROR_CODE_BLOCKED);
        assertThat(error.getMessage()).contains("電子簽章服務目標被拒絕").contains("loopback");
        assertThat(hits.get())
                .as("政策拒絕是非暫時性的；連嘗試都不能有")
                .isZero();
        verify(execution, never()).setVariable(anyString(), any());
    }

    @Test
    @DisplayName("🔴 policy 排在 payload 驗證之前：被拒的 URL ＋ 壞 payload 仍是 ESIGN_BLOCKED")
    void policyIsCheckedBeforePayloadValidation() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/sign"),
                field("payload", "not-json")));

        BpmnError error = triggerAndCatch(delegateRejectingAll(), execution);

        assertThat(error.getErrorCode())
                .as("url 是安全閘門；payload 的錯誤不該蓋掉被拒的事實")
                .isEqualTo(ESignDelegate.ERROR_CODE_BLOCKED);
        assertThat(hits.get()).isZero();
    }

    // ── 設定錯誤：不發請求 ──────────────────────────────────────────

    @Test
    @DisplayName("url 缺失 → ESIGN_FAILED，不發請求")
    void missingUrlFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("payload", "{\"docId\":\"D1\"}")));

        BpmnError error = triggerAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ESignDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("未設定 url");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("payload 缺失 → ESIGN_FAILED，不發請求")
    void missingPayloadFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/sign")));

        BpmnError error = triggerAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ESignDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("未設定 payload");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("payload 非 JSON → ESIGN_FAILED，不發請求（不把解析錯誤留給對方系統）")
    void invalidJsonPayloadFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/sign"),
                field("payload", "{\"docId\":}")));

        BpmnError error = triggerAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ESignDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("payload 不是合法 JSON");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("掛在非 ServiceTask 上（讀不到欄位）→ 與 url 缺失同一條路徑")
    void nonServiceTaskFailsAsMissingUrl() {
        when(execution.getCurrentFlowElement()).thenReturn(new UserTask());

        BpmnError error = triggerAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ESignDelegate.ERROR_CODE_FAILED);
        assertThat(hits.get()).isZero();
    }

    // ── 失敗：非 2xx／逾時／連線失敗 ────────────────────────────────

    @Test
    @DisplayName("非 2xx（503）→ ESIGN_FAILED，訊息帶狀態碼且不含 response body")
    void non2xxFailsWithStatusWithoutLeakingBody() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/fail"),
                field("payload", "{\"docId\":\"D1\"}")));

        BpmnError error = triggerAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ESignDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("HTTP 503");
        assertThat(error.getMessage())
                .as("response body 可能含對方系統的敏感資料，不得進 BpmnError 訊息")
                .doesNotContain("do-not-leak");
        assertThat(hits.get())
                .as("非 2xx 是『有送出去但對方失敗』——請求必須存在，才能與政策拒絕區分")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 3xx 重導不得跟隨：被允許的主機無法用 Location 把請求帶去打 loopback")
    void redirectIsNotFollowed() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/redirect"),
                field("payload", "{\"docId\":\"D1\"}")));

        BpmnError error = triggerAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ESignDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("HTTP 302");
        assertThat(redirectTargetHits.get())
                .as("跟隨重導的話 127.0.0.1 的端點會被請求 —— 那正是 SSRF 繞過")
                .isZero();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("讀取逾時（100ms vs 睡 1s）→ ESIGN_FAILED，不是無限等待")
    void readTimeoutFailsAsBpmnError() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/slow"),
                field("payload", "{\"docId\":\"D1\"}")));
        ESignDelegate delegate = new ESignDelegate(new WebhookUrlPolicy("localhost"),
                objectMapper, SafeRestClients.create(2000, 100));

        BpmnError error = triggerAndCatch(delegate, execution);

        assertThat(error.getErrorCode()).isEqualTo(ESignDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("連線或逾時失敗");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("連線失敗（關掉的 port）→ ESIGN_FAILED")
    void connectionRefusedFailsAsBpmnError() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", "http://localhost:" + closedPort + "/nope"),
                field("payload", "{\"docId\":\"D1\"}")));

        BpmnError error = triggerAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ESignDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("連線或逾時失敗");
    }
}
