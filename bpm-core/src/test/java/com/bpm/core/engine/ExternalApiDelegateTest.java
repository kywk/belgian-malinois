package com.bpm.core.engine;

import com.bpm.core.webhook.WebhookUrlPolicy;
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
 * #49 {@link ExternalApiDelegate} 的呼叫規則。
 *
 * <h2>為什麼用真的 HTTP server 而不是 mock {@code RestClient}</h2>
 *
 * <p>要釘住的幾條規則都跟「請求有沒有真的出去」有關：SSRF 政策拒絕時
 * <b>不得</b>發請求、method 不合法時不得發請求、2xx 的回應 body 要寫進
 * 流程變數、非 2xx／逾時要變 BpmnError。用 mock 的 fluent API 只能證明
 * 「有呼叫某個方法」，而 {@code HttpServer} 可以從<b>另一端</b>數請求，
 * 這是唯一能區分「送出了」與「看起來送出了」的證據。
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
class ExternalApiDelegateTest {

    private HttpServer server;
    private String baseUrl;

    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private DelegateExecution execution;

    @BeforeEach
    void setUp() throws IOException {
        hits.set(0);
        lastMethod.set(null);
        lastBody.set(null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastMethod.set(exchange.getRequestMethod());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String path = exchange.getRequestURI().getPath();
            if (path.contains("/slow")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (path.contains("/empty")) {
                // 204：成功但沒有 body（驗 resultVariable 會被設成空字串）
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            int status = path.contains("/fail") ? 503 : 200;
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        execution = Mockito.mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("pid-1");
        when(execution.getCurrentActivityId()).thenReturn("callApi");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 允許 localhost（loopback 的明確例外），逾時寬鬆。 */
    private ExternalApiDelegate delegateAllowingLocalhost() {
        return new ExternalApiDelegate(new WebhookUrlPolicy("localhost"), 2000, 2000);
    }

    /** 空清單：loopback／內網全拒。 */
    private ExternalApiDelegate delegateRejectingAll() {
        return new ExternalApiDelegate(new WebhookUrlPolicy(""), 2000, 2000);
    }

    private static ServiceTask serviceTask(FieldExtension... fields) {
        ServiceTask task = new ServiceTask();
        task.setId("callApi");
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

    private static BpmnError callAndCatch(ExternalApiDelegate delegate,
                                          DelegateExecution execution) {
        BpmnError error = catchThrowableOfType(() -> delegate.call(execution), BpmnError.class);
        assertThat(error).as("應該丟 BpmnError").isNotNull();
        return error;
    }

    // ── 正向 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET 2xx：回應 body 寫入 resultVariable，method 真的是 GET")
    void getWritesResponseBodyToResultVariable() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("method", "GET"),
                field("resultVariable", "apiResult")));

        delegateAllowingLocalhost().call(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("GET");
        verify(execution).setVariable("apiResult", "{\"ok\":true}");
    }

    @Test
    @DisplayName("POST：body 經 ${var} 替換後以原字串送出")
    void postSendsSubstitutedBody() {
        when(execution.getVariable("days")).thenReturn(3);
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/post"),
                field("method", "POST"),
                field("body", "{\"days\":${days}}")));

        delegateAllowingLocalhost().call(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastBody.get()).isEqualTo("{\"days\":3}");
        // 沒有 resultVariable 時不得偷偷寫任何變數
        verify(execution, never()).setVariable(anyString(), any());
    }

    @Test
    @DisplayName("method 欄位缺席 → 預設 GET；url 支援 ${var} 替換")
    void defaultMethodIsGetAndUrlSubstitutesVariables() {
        when(execution.getVariable("caseNo")).thenReturn("C-1");
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/case/${caseNo}")));

        delegateAllowingLocalhost().call(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("GET");
    }

    @Test
    @DisplayName("2xx 但 body 為空（204 等）→ resultVariable 設為空字串（不是不設）")
    void emptyResponseBodyStillSetsResultVariable() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/empty"),
                field("resultVariable", "apiResult")));

        delegateAllowingLocalhost().call(execution);

        verify(execution).setVariable("apiResult", "");
    }

    // ── SSRF 政策：拒絕時不得發請求 ─────────────────────────────────

    @Test
    @DisplayName("🔴 policy 拒絕（127.0.0.1 不在清單）→ EXTERNAL_API_BLOCKED，且請求數為 0")
    void policyRejectionThrowsBlockedWithoutSendingRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/ok"),
                field("resultVariable", "apiResult")));

        BpmnError error = callAndCatch(delegateRejectingAll(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ExternalApiDelegate.ERROR_CODE_BLOCKED);
        assertThat(error.getMessage()).contains("loopback");
        assertThat(hits.get())
                .as("政策拒絕是非暫時性的；連嘗試都不能有")
                .isZero();
        verify(execution, never()).setVariable(anyString(), any());
    }

    @Test
    @DisplayName("url 缺失 → EXTERNAL_API_FAILED（設定錯誤），不發請求")
    void missingUrlFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask());

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ExternalApiDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("未設定 url");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("掛在非 ServiceTask 上（讀不到欄位）→ 與 url 缺失同一條路徑")
    void nonServiceTaskFailsAsMissingUrl() {
        when(execution.getCurrentFlowElement()).thenReturn(new UserTask());

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ExternalApiDelegate.ERROR_CODE_FAILED);
        assertThat(hits.get()).isZero();
    }

    // ── method／body 驗證：不發請求 ─────────────────────────────────

    @Test
    @DisplayName("不支援的 method（DELETE）→ EXTERNAL_API_FAILED，不發請求")
    void invalidMethodFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("method", "DELETE")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ExternalApiDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("DELETE");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("GET 帶 body → 明確失敗，不發請求（不靜默丟掉 body）")
    void getWithBodyFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("method", "GET"),
                field("body", "{\"x\":1}")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ExternalApiDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("GET 不支援 body");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("parseMethod：空白＝GET、不分大小寫、其他＝null")
    void parseMethodRules() {
        assertThat(ExternalApiDelegate.parseMethod(null)).isEqualTo(org.springframework.http.HttpMethod.GET);
        assertThat(ExternalApiDelegate.parseMethod("  ")).isEqualTo(org.springframework.http.HttpMethod.GET);
        assertThat(ExternalApiDelegate.parseMethod("post")).isEqualTo(org.springframework.http.HttpMethod.POST);
        assertThat(ExternalApiDelegate.parseMethod("Put ")).isEqualTo(org.springframework.http.HttpMethod.PUT);
        assertThat(ExternalApiDelegate.parseMethod("DELETE")).isNull();
    }

    // ── 失敗：非 2xx／逾時／連線失敗 ────────────────────────────────

    @Test
    @DisplayName("非 2xx（503）→ EXTERNAL_API_FAILED，訊息帶狀態碼；請求確實有出去")
    void non2xxFailsWithStatus() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/fail")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ExternalApiDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("HTTP 503");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("讀取逾時（100ms vs 睡 1s）→ EXTERNAL_API_FAILED，不是無限等待")
    void readTimeoutFailsAsBpmnError() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/slow")));
        ExternalApiDelegate delegate =
                new ExternalApiDelegate(new WebhookUrlPolicy("localhost"), 2000, 100);

        BpmnError error = callAndCatch(delegate, execution);

        assertThat(error.getErrorCode()).isEqualTo(ExternalApiDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("連線或逾時失敗");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("連線失敗（關掉的 port）→ EXTERNAL_API_FAILED")
    void connectionRefusedFailsAsBpmnError() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", "http://localhost:" + closedPort + "/nope")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ExternalApiDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("連線或逾時失敗");
    }
}
