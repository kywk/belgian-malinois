package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #73：錯誤回應看得到「刻意丟出的」訊息，看不到非預期例外。
 *
 * <h2>缺陷</h2>
 *
 * <p>本 repo 未設 {@code server.error.include-message}，Boot 3 的預設是
 * {@code never}，而 {@code DefaultErrorAttributes} 會照著那個選項把
 * {@code message} 從回應中刪掉（{@code ErrorAttributeOptions.retainIncluded}
 * 逐一移除未被包含的鍵，已用 javap 確認）。於是 30 處精心寫的
 * {@code ResponseStatusException(...)} 理由只存在於伺服器端日誌，
 * 呼叫端只看到 {@code "error":"Bad Request"}。
 *
 * <p>這讓 #66 選定的政策失去實務意義：#66 的重點就是「明確 400 拒絕，
 * 並在訊息裡說清楚要改什麼」。
 *
 * <h2>為什麼兩個方向都要斷言</h2>
 *
 * <p>只測「看得見」等於把 {@code include-message=always} 也判為通過 ——
 * 那會把 NPE 的 {@code Cannot invoke ... because ... is null}、
 * SQL 例外裡的表名與欄位值、連線字串裡的主機與帳號全部送出去。
 * 這是資訊外洩，不是錯誤訊息改善。
 *
 * <h2>⚠️ 走真實 HTTP</h2>
 *
 * <p>錯誤訊息是在 ERROR dispatch 之後由 {@code /error} 組出來的。
 * MockMvc 不做 error dispatch，所以用 MockMvc 寫的測試在缺陷存在時照樣全綠。
 */
class ErrorMessageDisclosureTest extends IntegrationTestBase {

    /**
     * 一支必定在啟動時拋出非預期例外的流程。
     *
     * <p>{@code orgService.getAuthorizedManager} 是 security-audit P1-7
     * <b>刻意留成會拋例外</b>的 stub（「金額分級核決尚未實作」——
     * 明確失敗比靜默錯誤安全）。用它來製造真實的非預期例外，
     * 比在測試裡塞一個會拋 NPE 的 controller 誠實：那個 controller
     * 會改變所有整合測試共用的 context，而且它測的是「測試自己寫的東西」。
     * 這裡測的是<b>既有的一條真實失敗路徑</b>如何被轉成 HTTP 回應。
     */
    private static final String BOOM_FLOW = "error-message-boom-flow";

    @Autowired
    private RepositoryService repositoryService;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String userId, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
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

    private void deployBoomFlow() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="test">
                  <process id="%s" name="必定失敗" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="boom"/>
                    <userTask id="boom" name="壞掉的金額級距"
                              flowable:assignee="${orgService.getAuthorizedManager(initiator, 100)}"/>
                    <sequenceFlow id="f1" sourceRef="boom" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(BOOM_FLOW);
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(BOOM_FLOW).count() == 0) {
            repositoryService.createDeployment()
                    .addString(BOOM_FLOW + ".bpmn20.xml", xml).name(BOOM_FLOW).deploy();
        }
    }

    // ── 方向一：刻意丟出的訊息必須看得見 ──────────────────────────

    @Test
    @DisplayName("#73：ResponseStatusException 的 reason 必須出現在回應的 message")
    void deliberateReasonIsVisibleToTheCaller() throws Exception {
        // #66 的那一句：呼叫端若看不到它，只會知道「400」而不知道要改哪裡。
        var res = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"leave-approval\",\"initiator\":\"user002\"}");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body())
                .as("改動前：回應只有 \"error\":\"Bad Request\"，#66 的「明確拒絕」"
                        + "在實務上等於沒有訊息")
                .contains("\"message\"")
                .contains("initiator")
                .contains("user001");
    }

    @Test
    @DisplayName("#73：404 / 409 等其他刻意狀態的訊息同樣可見")
    void otherDeliberateStatusesAlsoExposeTheirReason() throws Exception {
        var res = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"no-such-process-key-at-all\"}");

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body())
                .as("「流程定義不存在: xxx」是排查時唯一有用的線索")
                .contains("no-such-process-key-at-all");
    }

    // ── 方向二：非預期例外維持不外洩，且仍是 500 ───────────────────

    @Test
    @DisplayName("#73：非預期例外不得洩漏訊息，而且必須維持 500")
    void unexpectedExceptionStaysOpaqueAndIsStillFiveHundred() throws Exception {
        deployBoomFlow();

        var res = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"" + BOOM_FLOW + "\"}");

        assertThat(res.statusCode())
                .as("把 message 加上來不得改變狀態碼 —— 非預期例外仍然是 500")
                .isEqualTo(500);
        assertThat(res.body())
                .as("UnsupportedOperationException 的訊息裡有內部實作細節，"
                        + "不得送到呼叫端")
                .doesNotContain("UnsupportedOperationException")
                .doesNotContain("getAuthorizedManager")
                .doesNotContain("金額分級核決");
    }

    @Test
    @DisplayName("#73：非預期例外仍不得洩漏 stack trace 或 exception 類別名")
    void unexpectedExceptionLeaksNothing() throws Exception {
        deployBoomFlow();

        var res = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"" + BOOM_FLOW + "\"}");

        assertThat(res.statusCode()).isEqualTo(500);
        assertThat(res.body()).doesNotContain("trace").doesNotContain("exception\"");
    }

    @Test
    @DisplayName("#73：不帶 reason 的 ResponseStatusException 不得 NPE，也不得生出空訊息")
    void reasonlessResponseStatusExceptionIsHandled() throws Exception {
        // 附件下載對不存在的 id 拋的是 new ResponseStatusException(NOT_FOUND)，
        // 沒有 reason。Spring 走的是 sendError(status) 而不帶訊息，
        // error message 屬性因而缺席 —— 若實作直接把它 put 進回應，
        // 呼叫端會拿到 "message": null，
        // 前端 http.js 的 `data?.message || data?.error` 會印出 undefined。
        var res = get("/api/attachments/no-such-attachment/download", "user001");

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).doesNotContain("\"message\":null");
    }

    @Test
    @DisplayName("#73：外洩範圍被釘住 —— 只外洩 sendError 的 reason，永不外洩 Throwable 的 message")
    void onlyDeliberateReasonsAreExposed() throws Exception {
        // 這條測試把 DeliberateErrorMessageAttributes 的<b>實際</b>範圍寫下來。
        //
        // 背景：javap 確認 sendError(status, reason) 有兩個呼叫端 ——
        // ResponseStatusExceptionResolver（本 repo 刻意丟出的訊息）與
        // DefaultHandlerExceptionResolver.handleErrorResponse（Spring 自己的
        // ErrorResponse 理由字串，例如 NoResourceFoundException）。
        // 兩者在 /error 的屬性上無法區分。
        //
        // 因此真正的保證是：<b>永不外洩 Throwable.getMessage()</b>，
        // 而那正是 NPE 的「Cannot invoke … because … is null」與
        // SQL 例外裡表名、欄位值的來源。要連框架的理由字串一起擋掉，
        // 就得新增全域的例外解析器（#69 已決定不採用的路線）。
        // /api/** 之下（authenticated() 涵蓋），因此會走到 DispatcherServlet 的
        // 「沒有 handler」路徑 → NoResourceFoundException。刻意不放在 /api 之外：
        // 那裡會落到 anyRequest().denyAll() 回 403，測不到這條規則。
        var notFound = get("/api/no-such-endpoint", "user001");
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body())
                .as("框架的診斷字串可見（低敏），但它絕對不是例外訊息")
                .doesNotContain("Exception")
                .doesNotContain("at com.bpm.core");

        deployBoomFlow();
        var unexpected = post("/api/process-instances", "user001",
                "{\"processDefinitionKey\":\"" + BOOM_FLOW + "\"}");
        assertThat(unexpected.statusCode()).isEqualTo(500);
        assertThat(unexpected.body())
                .as("不變量的核心：例外本身的訊息永遠不外洩")
                .doesNotContain("com.bpm.core")
                .doesNotContain("at org.flowable");
    }
}
