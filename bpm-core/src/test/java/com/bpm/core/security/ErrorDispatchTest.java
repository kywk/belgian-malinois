package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 錯誤回應必須保留原本的狀態碼，不可被安全規則蓋成 403。
 *
 * <p>controller 拋出的 {@code ResponseStatusException}／{@code AuditWriteException}
 * 會讓容器以 ERROR dispatch 轉到 {@code /error}。R-01 的 {@code anyRequest().denyAll()}
 * 原本也擋下了這次 dispatch → 線上<b>所有錯誤都變成 403</b>。
 *
 * <p>⚠️ 這裡刻意走真正的 HTTP，而不是 MockMvc：MockMvc 不做 error dispatch，
 * 用它寫的測試在缺陷存在時照樣是綠的 —— 其餘 265 個測試就是這樣全綠地放過了它。
 */
class ErrorDispatchTest extends IntegrationTestBase {

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", TestGatewayMockMvcCustomizer.DEFAULT_USER)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("controller 回 404 時，真實 HTTP 回應也必須是 404 而非 403")
    void notFoundSurvivesErrorDispatch() throws Exception {
        var res = send("PUT", "/api/tasks/does-not-exist", "{\"action\":\"complete\"}");
        assertThat(res.statusCode())
                .as("ERROR dispatch 被 denyAll() 擋下時會變成 403")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("直接請求 /error（REQUEST dispatch）仍然被拒絕")
    void directErrorPathStaysDenied() throws Exception {
        assertThat(send("GET", "/error", null).statusCode()).isEqualTo(403);
    }
}
