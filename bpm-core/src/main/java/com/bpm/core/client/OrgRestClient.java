package com.bpm.core.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class OrgRestClient {

    private final RestClient restClient;

    /**
     * 逾時設定不可省略（security-audit P1-10）。
     *
     * <p>改動前完全沒有 timeout（全 repo grep {@code connect-timeout|read-timeout|
     * requestFactory} 只命中設定檔的 URL）。後果不只是「慢」：
     *
     * <ol>
     *   <li>組織／權限系統 hang 住時，呼叫執行緒<b>無限期阻塞</b>。</li>
     *   <li>這些呼叫發生在 JUEL 求值
     *       {@code ${orgService.getDirectManager(initiator)}} 時，也就是在
     *       {@code taskService.complete()} 的 <b>DB 交易之內</b> ——
     *       執行緒卡住的同時還占著一個 DB 連線與一個未提交的交易。</li>
     *   <li>而 {@code org-service-url} 預設指向<b>自己</b>
     *       （{@code http://localhost:8080/mock/org}）→ 容器內 bpm-core 對自己
     *       發同步 HTTP 並在持有交易時等待。每次任務建立占用 2 個 Tomcat
     *       執行緒 → 執行緒池飽和時<b>自我死鎖</b>，而沒有 timeout 就不會
     *       自行解開。表現為「整個 bpm-core 無回應」。</li>
     * </ol>
     *
     * <p>逾時值刻意設得短：這些呼叫在簽核的同步路徑上，使用者正在等待。
     * 寧可快速失敗（讓使用者看到錯誤並重試）也不要把執行緒與交易一起卡住。
     *
     * <h2>認證注入（#8 正式化）</h2>
     *
     * <p>{@code authToken} 非空時，以<b>設定值原樣</b>放進 {@code authHeader}
     * 指定的 header。本服務不解析、不重組、不加前綴：
     * <ul>
     *   <li>Bearer 形式的真實系統：{@code org-auth-token: "Bearer <token>"}</li>
     *   <li>Sa-Token 形式的真實系統：{@code org-auth-token: "satoken <token>"}</li>
     * </ul>
     *
     * <p>空字串（dev mock 的預設）＝<b>完全不送出該 header</b>，
     * 因此未設 token 的環境行為與加入本功能前逐位元相同。
     *
     * <p>⚠️ token 只放進 request header，<b>不得</b>寫進日誌、例外訊息或稽核。
     * 本類別的所有錯誤訊息只含服務名／路徑／狀態碼，不含 header
     * （見 {@link ExternalApiException} 的說明）。
     *
     * <h2>錯誤映射（#8 正式化）</h2>
     *
     * <p>非 2xx 與連線／逾時都映射成 {@link ExternalApiException}
     * （服務名＋狀態碼＋路徑，不含 response body）。404 的
     * {@link ExternalApiException#isNotFound()} 保留「查無此人」的語意，
     * 服務層與 {@code ExternalActorGuard} 的 fail-closed 行為不變。
     */
    public OrgRestClient(
            @Value("${bpm.external.org-service-url}") String baseUrl,
            @Value("${bpm.external.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${bpm.external.read-timeout-ms:3000}") long readTimeoutMs,
            @Value("${bpm.external.auth-header:Authorization}") String authHeader,
            @Value("${bpm.external.org-auth-token:}") String authToken) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(java.time.Duration.ofMillis(readTimeoutMs));
        var builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultStatusHandler(ExternalApiErrors.statusHandler(ExternalApiException.Service.ORG))
                .requestInterceptor(ExternalApiErrors.connectionInterceptor(ExternalApiException.Service.ORG));
        if (authToken != null && !authToken.isEmpty()) {
            builder.defaultHeader(authHeader, authToken);
        }
        this.restClient = builder.build();
    }

    public Map<String, Object> getUser(String userId) {
        return restClient.get().uri("/api/users/{userId}", userId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    /**
     * 直屬主管的 id；{@code null} 代表「此人存在但沒有主管」（鏈頂）。
     *
     * <p>⚠️ 查無此人<b>不是</b> {@code null}：外部系統回 404，
     * client 拋 {@link ExternalApiException}（{@code isNotFound()==true}），
     * 服務層讓它往外傳。把 404 當成 null 會讓 {@code ExternalActorGuard}
     * 放行一個沒有人能簽的身分 —— 見該類別註解。
     */
    public String getManager(String userId) {
        Map<String, Object> result = restClient.get().uri("/api/users/{userId}/manager", userId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        return result != null ? (String) result.get("managerId") : null;
    }

    public List<String> getManagerChain(String userId, int levels) {
        return restClient.get().uri("/api/users/{userId}/manager-chain?levels={levels}", userId, levels)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public String getDepartment(String userId) {
        Map<String, Object> result = restClient.get().uri("/api/users/{userId}/department", userId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        return result != null ? (String) result.get("deptId") : null;
    }

    public String getSubstitute(String userId) {
        Map<String, Object> result = restClient.get().uri("/api/users/{userId}/substitute", userId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        return result != null ? (String) result.get("substituteId") : null;
    }

    public List<String> getDeptMembers(String deptId) {
        return restClient.get().uri("/api/departments/{deptId}/members", deptId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }
}
