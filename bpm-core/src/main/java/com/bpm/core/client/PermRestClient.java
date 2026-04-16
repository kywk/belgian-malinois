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

    public PermRestClient(@Value("${bpm.external.perm-service-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
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
