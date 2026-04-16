package com.bpm.core.service;

import com.bpm.core.client.PermRestClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service("permService")
public class BpmPermissionService {

    private final PermRestClient permRestClient;
    private final StringRedisTemplate redis;
    private final OrgService orgService;

    public BpmPermissionService(PermRestClient permRestClient, StringRedisTemplate redis, OrgService orgService) {
        this.permRestClient = permRestClient;
        this.redis = redis;
        this.orgService = orgService;
    }

    public List<String> getUsersByPermission(String permCode) {
        String key = "perm:users:" + permCode;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return List.of(cached.split(","));
        List<String> users = permRestClient.getUsersByPermission(permCode);
        if (users != null && !users.isEmpty()) {
            redis.opsForValue().set(key, String.join(",", users), Duration.ofMinutes(5));
        }
        return users;
    }

    public List<String> getUsersByPermissionAndDept(String permCode, String deptId) {
        String key = "perm:users:" + permCode + ":" + deptId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return List.of(cached.split(","));
        List<String> users = permRestClient.getUsersByPermissionAndDept(permCode, deptId);
        if (users != null && !users.isEmpty()) {
            redis.opsForValue().set(key, String.join(",", users), Duration.ofMinutes(10));
        }
        return users;
    }

    public boolean hasPermission(String userId, String permCode) {
        String key = "perm:user:" + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return cached.contains(permCode);
        // Fetch all permissions for user and cache
        List<String> perms = permRestClient.getUserPermissions(userId);
        if (perms != null) {
            redis.opsForValue().set(key, String.join(",", perms), Duration.ofMinutes(5));
            return perms.contains(permCode);
        }
        return false;
    }

    public List<String> getUsersByPermissionAndCondition(String permCode, java.util.Map<String, Object> attrs) {
        // Base implementation delegates to getUsersByPermission; extend with attrs filtering later
        return getUsersByPermission(permCode);
    }

    public String getFirstAvailableUser(String permCode) {
        List<String> users = getUsersByPermission(permCode);
        if (users == null) return null;
        return users.stream()
                .filter(orgService::isUserAvailable)
                .findFirst().orElse(users.isEmpty() ? null : users.getFirst());
    }

    public void invalidateCache(List<String> userIds, List<String> permCodes) {
        if (userIds != null) {
            userIds.forEach(uid -> redis.delete("perm:user:" + uid));
        }
        if (permCodes != null) {
            permCodes.forEach(code -> {
                redis.delete("perm:users:" + code);
                // Also delete dept-scoped keys via pattern
                var keys = redis.keys("perm:users:" + code + ":*");
                if (keys != null) redis.delete(keys);
            });
        }
    }
}
