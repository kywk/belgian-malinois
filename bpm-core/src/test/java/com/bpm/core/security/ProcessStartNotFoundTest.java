package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #69：啟動不存在的流程 key 應回 404，不是 500。
 *
 * <h2>缺陷</h2>
 *
 * <p>{@code startProcessInstanceByKey} 對查不到的 key 會丟
 * {@code FlowableObjectNotFoundException} → 裸 500。使用者看到 500 的
 * 直覺是「再按一次」，於是<b>重送造成重複案件</b>。同一個端點的旁邊
 * 就有註解記錄過這類問題（P1-2：singleResult 讓流程已啟動後才回 500）。
 *
 * <h2>為什麼 404 而不是 400</h2>
 *
 * <p>key 為 null／空字串是<b>請求本身不完整</b>（400）；
 * key 有值但查不到任何定義是<b>指向的資源不存在</b>（404）。
 * 兩者對呼叫端的處置方式不同 —— 前者要改 payload，後者要改流程選擇。
 *
 * <h2>為什麼用真實 HTTP</h2>
 *
 * <p>{@code ResponseStatusException} 要經過容器的 ERROR dispatch 才會變成
 * 真正的狀態碼，而 MockMvc 不做那次 dispatch。若同時壞掉的是
 * {@code SecurityConfig} 裡的 {@code dispatcherTypeMatchers(ERROR)} 規則，
 * MockMvc 測試會照樣全綠而線上全部變 403。見 {@link ErrorDispatchTest}。
 *
 * <h2>為什麼不用全域 {@code @RestControllerAdvice}</h2>
 *
 * <p>那會改變所有端點的行為，而且 {@code FlowableObjectNotFoundException}
 * 在 Flowable 裡語意很廣（任務、流程實例、部署、決策表都會丟它），
 * 一刀切映射成 404 太粗暴。這個 repo 的既有慣例是在 controller 內
 * 拋 {@code ResponseStatusException}（見 ProcessController 的
 * {@code getBpmnXml}、ExternalApiController、TaskController 多處）。
 */
class ProcessStartNotFoundTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> start(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/process-instances"))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", TestGatewayMockMvcCustomizer.DEFAULT_USER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("#69：processDefinitionKey 查不到任何定義 → 404（改動前是 500）")
    void unknownProcessDefinitionKeyIsNotFound() throws Exception {
        long before = runtimeService.createProcessInstanceQuery().count();

        var res = start("{\"processDefinitionKey\":\"no-such-process-"
                + java.util.UUID.randomUUID() + "\"}");

        assertThat(res.statusCode())
                .as("改動前：FlowableObjectNotFoundException → 500，使用者會重送造成重複案件")
                .isEqualTo(404);
        assertThat(runtimeService.createProcessInstanceQuery().count())
                .as("被拒的請求不得留下流程實例").isEqualTo(before);
    }

    @Test
    @DisplayName("#69：processDefinitionKey 為 null 或空字串 → 400（請求不完整，不是找不到）")
    void missingProcessDefinitionKeyIsBadRequest() throws Exception {
        for (String body : new String[]{
                "{\"businessKey\":\"no-key\"}",
                "{\"processDefinitionKey\":null}",
                "{\"processDefinitionKey\":\"\"}",
                "{\"processDefinitionKey\":\"   \"}"}) {
            var res = start(body);
            assertThat(res.statusCode())
                    .as("body=" + body + " 應回 400 —— 缺 key 是請求不完整，"
                            + "與「key 查不到」（404）要能分辨")
                    .isEqualTo(400);
        }
    }

    @Test
    @DisplayName("#69：已部署的流程必須仍能啟動（404 的預先檢查不得擋掉正常路徑）")
    void existingProcessStillStarts() throws Exception {
        var res = start("{\"processDefinitionKey\":\"leave-approval\"}");
        assertThat(res.statusCode())
                .as("預先檢查寫錯（例如用 deploymentId 比對）會讓所有正常啟動都變 404")
                .isEqualTo(200);
        assertThat(res.body()).contains("processInstanceId");
    }
}
