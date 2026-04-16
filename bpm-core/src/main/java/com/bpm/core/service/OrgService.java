package com.bpm.core.service;

import com.bpm.core.client.OrgRestClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@Service("orgService")
public class OrgService {

    private final OrgRestClient orgRestClient;
    private final StringRedisTemplate redis;

    public OrgService(OrgRestClient orgRestClient, StringRedisTemplate redis) {
        this.orgRestClient = orgRestClient;
        this.redis = redis;
    }

    public String getDirectManager(String userId) {
        String key = "org:manager:" + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return cached;
        String manager = orgRestClient.getManager(userId);
        if (manager != null) redis.opsForValue().set(key, manager, Duration.ofMinutes(60));
        return manager;
    }

    public String getAuthorizedManager(String userId, BigDecimal amount) {
        // Delegate to getDirectManager; amount-based logic can be extended later
        return getDirectManager(userId);
    }

    public String resolveEffective(String userId) {
        String key = "org:substitute:" + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return cached.isEmpty() ? userId : cached;
        String substitute = orgRestClient.getSubstitute(userId);
        redis.opsForValue().set(key, substitute != null ? substitute : "", Duration.ofMinutes(1));
        return substitute != null ? substitute : userId;
    }

    public String getDeptGroup(String userId) {
        return getDeptId(userId);
    }

    public List<String> getManagerChain(String userId, int levels) {
        String key = "org:manager-chain:" + userId + ":" + levels;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return List.of(cached.split(","));
        List<String> chain = orgRestClient.getManagerChain(userId, levels);
        if (chain != null && !chain.isEmpty()) {
            redis.opsForValue().set(key, String.join(",", chain), Duration.ofMinutes(60));
        }
        return chain;
    }

    public String getDeptId(String userId) {
        String key = "org:dept:" + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return cached;
        String deptId = orgRestClient.getDepartment(userId);
        if (deptId != null) redis.opsForValue().set(key, deptId, Duration.ofMinutes(60));
        return deptId;
    }

    public boolean isUserAvailable(String userId) {
        String key = "org:available:" + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return "true".equals(cached);
        // Default: available if substitute is null (user is not on leave)
        String substitute = orgRestClient.getSubstitute(userId);
        boolean available = substitute == null;
        redis.opsForValue().set(key, String.valueOf(available), Duration.ofMinutes(5));
        return available;
    }

    public List<String> getDeptMembers(String deptId) {
        String key = "org:dept-members:" + deptId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return List.of(cached.split(","));
        List<String> members = orgRestClient.getDeptMembers(deptId);
        if (members != null && !members.isEmpty()) {
            redis.opsForValue().set(key, String.join(",", members), Duration.ofMinutes(30));
        }
        return members;
    }

    public void invalidateCache(List<String> userIds, String type) {
        if (userIds == null) return;
        for (String userId : userIds) {
            redis.delete("org:manager:" + userId);
            redis.delete("org:substitute:" + userId);
            redis.delete("org:available:" + userId);
            redis.delete("org:dept:" + userId);
            // Delete manager-chain with common levels
            for (int i = 1; i <= 5; i++) {
                redis.delete("org:manager-chain:" + userId + ":" + i);
            }
        }
    }
}
