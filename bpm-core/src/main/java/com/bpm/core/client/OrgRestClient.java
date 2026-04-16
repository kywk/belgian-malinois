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

    public OrgRestClient(@Value("${bpm.external.org-service-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public Map<String, Object> getUser(String userId) {
        return restClient.get().uri("/api/users/{userId}", userId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

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
