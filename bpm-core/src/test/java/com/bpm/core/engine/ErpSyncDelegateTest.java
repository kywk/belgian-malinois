package com.bpm.core.engine;

import com.bpm.core.webhook.WebhookUrlPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * #46 {@link ErpSyncDelegate} 的呼叫規則。
 *
 * <h2>為什麼用真的 HTTP server 而不是 mock {@code RestClient}</h2>
 *
 * <p>要釘住的幾條規則都跟「請求有沒有真的出去」有關：SSRF 政策拒絕時
 * <b>不得</b>發請求、payload 非 JSON 時不得發請求、非 2xx／逾時要變
 * BpmnError、2xx 的回應 body 要寫進流程變數。用 mock 的 fluent API 只能
 * 證明「有呼叫某個方法」，而 {@code HttpServer} 可以從<b>另一端</b>數
 * 請求 —— 這是唯一能區分「送出了」與「看起來送出了」的證據。
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
class ErpSyncDelegateTest {

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

    private DelegateExecution execution;

    @BeforeEach
    void setUp() throws IOException {
        hits.set(0);
        redirectTargetHits.set(0);
        lastMethod.set(null);
        lastBody.set(null);

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
            if (path.contains("/empty")) {
                // 204：成功但沒有 body（驗 resultVariable 會被設成空字串）
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (path.contains("/fail")) {
                // 刻意帶一個 marker：BpmnError 訊息不得把它帶出去。
                byte[] failure = "{\"secret\":\"erp-internal\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, failure.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(failure);
                }
                return;
            }
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        execution = Mockito.mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("pid-1");
        when(execution.getCurrentActivityId()).thenReturn("syncErp");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        redirectTarget.stop(0);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 允許 localhost（loopback 的明確例外），逾時寬鬆。 */
    private ErpSyncDelegate delegateAllowingLocalhost() {
        return new ErpSyncDelegate(new WebhookUrlPolicy("localhost"), new ObjectMapper(), 2000, 2000);
    }

    /** 空清單：loopback／內網全拒。 */
    private ErpSyncDelegate delegateRejectingAll() {
        return new ErpSyncDelegate(new WebhookUrlPolicy(""), new ObjectMapper(), 2000, 2000);
    }

    private static ServiceTask serviceTask(FieldExtension... fields) {
        ServiceTask task = new ServiceTask();
        task.setId("syncErp");
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

    private static BpmnError callAndCatch(ErpSyncDelegate delegate,
                                          DelegateExecution execution) {
        BpmnError error = catchThrowableOfType(() -> delegate.call(execution), BpmnError.class);
        assertThat(error).as("應該丟 BpmnError").isNotNull();
        return error;
    }

    // ── 正向 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("method 欄位缺席 → 預設 POST；payload 經 ${var} 替換後原字串送出、回應寫入 resultVariable")
    void defaultMethodIsPostAndPayloadIsSubstituted() {
        when(execution.getVariable("caseNo")).thenReturn("C-1");
        when(execution.getVariable("days")).thenReturn(3);
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("payload", "{\"caseNo\":\"${caseNo}\",\"days\":${days}}"),
                field("resultVariable", "erpResult")));

        delegateAllowingLocalhost().call(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastBody.get()).isEqualTo("{\"caseNo\":\"C-1\",\"days\":3}");
        verify(execution).setVariable("erpResult", "{\"ok\":true}");
    }

    @Test
    @DisplayName("url 支援 ${var} 替換")
    void urlSubstitutesVariables() {
        when(execution.getVariable("caseNo")).thenReturn("C-1");
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/case/${caseNo}"),
                field("payload", "{\"x\":1}")));

        delegateAllowingLocalhost().call(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("POST");
    }

    @Test
    @DisplayName("PUT 是允許的 method，payload 以原字串送出")
    void putIsAllowed() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("method", "PUT"),
                field("payload", "{\"x\":1}")));

        delegateAllowingLocalhost().call(execution);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("PUT");
        assertThat(lastBody.get()).isEqualTo("{\"x\":1}");
    }

    @Test
    @DisplayName("2xx 但 body 為空（204 等）→ resultVariable 設為空字串（不是不設）")
    void emptyResponseBodyStillSetsResultVariable() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/empty"),
                field("payload", "{\"x\":1}"),
                field("resultVariable", "erpResult")));

        delegateAllowingLocalhost().call(execution);

        verify(execution).setVariable("erpResult", "");
    }

    @Test
    @DisplayName("沒有 resultVariable 時不得偷偷寫任何變數")
    void noResultVariableMeansNoVariableWrite() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("payload", "{\"x\":1}")));

        delegateAllowingLocalhost().call(execution);

        verify(execution, never()).setVariable(anyString(), any());
    }

    // ── SSRF 政策：拒絕時不得發請求 ─────────────────────────────────

    @Test
    @DisplayName("🔴 policy 拒絕（127.0.0.1 不在清單）→ ERP_SYNC_BLOCKED，且請求數為 0")
    void policyRejectionThrowsBlockedWithoutSendingRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/ok"),
                field("payload", "{\"x\":1}"),
                field("resultVariable", "erpResult")));

        BpmnError error = callAndCatch(delegateRejectingAll(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_BLOCKED);
        assertThat(error.getMessage()).contains("loopback");
        assertThat(hits.get())
                .as("政策拒絕是非暫時性的；連嘗試都不能有")
                .isZero();
        verify(execution, never()).setVariable(anyString(), any());
    }

    @Test
    @DisplayName("🔴 policy 在 payload 驗證之前：被拒目標＋非 JSON payload → BLOCKED，不是 FAILED")
    void policyRunsBeforePayloadValidation() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/ok"),
                field("payload", "not-json")));

        BpmnError error = callAndCatch(delegateRejectingAll(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_BLOCKED);
        assertThat(hits.get()).isZero();
    }

    // ── 設定錯誤：不發請求 ──────────────────────────────────────────

    @Test
    @DisplayName("payload 非 JSON → ERP_SYNC_FAILED，不發請求（不送對方一定解不開的 body）")
    void nonJsonPayloadFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("payload", "not-json")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("不是合法 JSON");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("payload 開頭是 JSON 但尾隨垃圾 → 一樣不合法，不發請求")
    void payloadWithTrailingGarbageFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("payload", "{\"x\":1} 垃圾")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("不是合法 JSON");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("payload 欄位缺席 → ERP_SYNC_FAILED，不發請求")
    void missingPayloadFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("未設定 payload");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("payload 替換後塌成空白 → 與未設定同一條路徑，不發請求")
    void blankPayloadAfterSubstitutionFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("payload", "${missing}")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("未設定 payload");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("url 缺失 → ERP_SYNC_FAILED（設定錯誤），不發請求")
    void missingUrlFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("payload", "{\"x\":1}")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("未設定 url");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("掛在非 ServiceTask 上（讀不到欄位）→ 與 url 缺失同一條路徑")
    void nonServiceTaskFailsAsMissingUrl() {
        when(execution.getCurrentFlowElement()).thenReturn(new UserTask());

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("GET 不允許（payload 必填、GET body 不可互通）→ 不發請求")
    void getIsRejectedWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("method", "GET"),
                field("payload", "{\"x\":1}")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("GET");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("不支援的 method（DELETE）→ ERP_SYNC_FAILED，不發請求")
    void invalidMethodFailsWithoutRequest() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/ok"),
                field("method", "DELETE"),
                field("payload", "{\"x\":1}")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("DELETE");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("parseMethod：空白＝POST、不分大小寫、只收 POST／PUT／PATCH")
    void parseMethodRules() {
        assertThat(ErpSyncDelegate.parseMethod(null))
                .isEqualTo(org.springframework.http.HttpMethod.POST);
        assertThat(ErpSyncDelegate.parseMethod("  "))
                .isEqualTo(org.springframework.http.HttpMethod.POST);
        assertThat(ErpSyncDelegate.parseMethod("post"))
                .isEqualTo(org.springframework.http.HttpMethod.POST);
        assertThat(ErpSyncDelegate.parseMethod("Put "))
                .isEqualTo(org.springframework.http.HttpMethod.PUT);
        assertThat(ErpSyncDelegate.parseMethod("PATCH"))
                .isEqualTo(org.springframework.http.HttpMethod.PATCH);
        assertThat(ErpSyncDelegate.parseMethod("GET")).isNull();
        assertThat(ErpSyncDelegate.parseMethod("DELETE")).isNull();
        assertThat(ErpSyncDelegate.parseMethod("BREW")).isNull();
    }

    // ── 失敗：非 2xx／逾時／連線失敗 ────────────────────────────────

    @Test
    @DisplayName("非 2xx（503）→ ERP_SYNC_FAILED，訊息帶狀態碼、不含 response body；請求確實有出去")
    void non2xxFailsWithStatusAndWithoutResponseBody() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/fail"),
                field("payload", "{\"x\":1}")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("HTTP 503");
        assertThat(error.getMessage())
                .as("response body 可能含 ERP 端敏感資料，不得進 log／歷史")
                .doesNotContain("erp-internal");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 3xx 重導不得跟隨：被允許的主機無法用 Location 把請求帶去打 loopback")
    void redirectIsNotFollowed() {
        // 政策只看原始 URL 的 host。若 client 跟隨重導，allowed host 就能
        // 302 到 http://127.0.0.1:.../ —— SSRF 閘門形同虛設（#97）。
        // 這條同時釘住 ErpSyncDelegate 用的是 SafeRestClients 而不是裸
        // RestClient.create()。
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/redirect"),
                field("payload", "{\"x\":1}")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("HTTP 302");
        assertThat(redirectTargetHits.get())
                .as("跟隨重導的話 127.0.0.1 的端點會被請求 —— 那正是 SSRF 繞過")
                .isZero();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("讀取逾時（100ms vs 睡 1s）→ ERP_SYNC_FAILED，不是無限等待")
    void readTimeoutFailsAsBpmnError() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", baseUrl + "/slow"),
                field("payload", "{\"x\":1}")));
        ErpSyncDelegate delegate =
                new ErpSyncDelegate(new WebhookUrlPolicy("localhost"), new ObjectMapper(), 2000, 100);

        BpmnError error = callAndCatch(delegate, execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("連線或逾時失敗");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("連線失敗（關掉的 port）→ ERP_SYNC_FAILED")
    void connectionRefusedFailsAsBpmnError() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("url", "http://localhost:" + closedPort + "/nope"),
                field("payload", "{\"x\":1}")));

        BpmnError error = callAndCatch(delegateAllowingLocalhost(), execution);

        assertThat(error.getErrorCode()).isEqualTo(ErpSyncDelegate.ERROR_CODE_FAILED);
        assertThat(error.getMessage()).contains("連線或逾時失敗");
    }
}
