package com.bpm.core.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class PermRestClient {

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
     */
    public PermRestClient(
            @Value("${bpm.external.perm-service-url}") String baseUrl,
            @Value("${bpm.external.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${bpm.external.read-timeout-ms:3000}") long readTimeoutMs) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(java.time.Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public List<String> getUsersByPermission(String permCode) {
        return restClient.get().uri("/api/permissions/{permCode}/users", permCode)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public List<String> getUsersByPermissionAndDept(String permCode, String deptId) {
        return restClient.get().uri("/api/permissions/{permCode}/users?deptId={deptId}", permCode, deptId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public List<String> getUserPermissions(String userId) {
        return restClient.get().uri("/api/users/{userId}/permissions", userId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public boolean hasPermission(String userId, String permCode) {
        Map<String, Object> result = restClient.get()
                .uri("/api/users/{userId}/has-permission?code={code}", userId, permCode)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        return result != null && Boolean.TRUE.equals(result.get("hasPermission"));
    }
}
