package com.bpm.core.service;

import com.bpm.core.client.PermRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service("permService")
public class BpmPermissionService {

    private static final Logger log = LoggerFactory.getLogger(BpmPermissionService.class);

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
        String cached = cacheGet(key);
        if (cached != null) return List.of(cached.split(","));
        List<String> users = permRestClient.getUsersByPermission(permCode);
        if (users != null && !users.isEmpty()) {
            cachePut(key, String.join(",", users), Duration.ofMinutes(5));
        }
        return users;
    }

    public List<String> getUsersByPermissionAndDept(String permCode, String deptId) {
        String key = "perm:users:" + permCode + ":" + deptId;
        String cached = cacheGet(key);
        if (cached != null) return List.of(cached.split(","));
        List<String> users = permRestClient.getUsersByPermissionAndDept(permCode, deptId);
        if (users != null && !users.isEmpty()) {
            cachePut(key, String.join(",", users), Duration.ofMinutes(10));
        }
        return users;
    }

    /**
     * 使用者是否持有指定權限碼。
     *
     * <p>改動前有兩個獨立問題（security-audit P1-8）：
     *
     * <p><b>1. 冷熱快取答案不同。</b>快取命中時做的是
     * {@code cached.contains(permCode)} —— 對 {@code "a,b,c"} 這個字串做
     * <b>子字串</b>比對；快取未命中時走 {@code perms.contains(permCode)}
     * —— <b>集合成員</b>精確比對。同一組輸入在 5 分鐘內外會得到不同答案，
     * 授權判定因此不具決定性，事故無法重現。
     *
     * <p><b>2. 子字串誤放行。</b>持有 {@code hr:leave:approve} 的人，
     * {@code hasPermission(u, "hr:leave")} 與 {@code hasPermission(u, "approve")}
     * 全為 true。本專案的權限碼是階層式命名，
     * 「檢視層級的碼」被「核准層級的碼」誤中的機率很高。
     *
     * <p>本方法被 {@code BpmQueryService.getManagerWithPermission} 用來挑選
     * 簽核人 —— 誤放行等於讓沒有核決權的人成為簽核者。
     *
     * <p>現在兩條路徑都把權限清單解析成集合後精確比對。
     */
    public boolean hasPermission(String userId, String permCode) {
        if (permCode == null || permCode.isBlank()) return false;
        return userPermissions(userId).contains(permCode);
    }

    /** 取使用者的權限清單（快取），冷熱路徑回傳相同的資料結構。 */
    private List<String> userPermissions(String userId) {
        String key = "perm:user:" + userId;
        String cached = cacheGet(key);
        if (cached != null) return splitCsv(cached);

        List<String> perms = permRestClient.getUserPermissions(userId);
        if (perms == null) return List.of();
        // 空清單也要快取，否則「沒有權限」的使用者每次都打外部系統。
        cachePut(key, String.join(",", perms), Duration.ofMinutes(5));
        return perms;
    }

    /**
     * 解析逗號分隔的快取值。
     *
     * <p>{@code "".split(",")} 會回傳 {@code [""]}（長度 1 的陣列），
     * 因此空快取若直接 split，會變成「持有一個空字串權限」的清單 ——
     * 這也是為什麼要把空值明確過濾掉。
     */
    private static List<String> splitCsv(String csv) {
        if (csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .toList();
    }

    /**
     * 依權限碼與條件屬性取得使用者。
     *
     * <p>⚠️ <b>尚未實作，刻意拋例外</b>（security-audit P1-7）。
     *
     * <p>改動前的實作是 {@code return getUsersByPermission(permCode)} ——
     * {@code attrs} 完全未使用。但方法簽名會讓流程設計者相信有條件過濾，
     * 而這個 bean 又在 BPMN EL 的白名單上。於是業務人員寫了帶條件的運算式，
     * 實際上條件被完全忽略：<b>lint 全綠、執行期無警告、稽核看起來完全正常</b>，
     * 而授權已經降級。這是簽核系統裡最難發現的一類 fail-open。
     *
     * <p>留一個語意錯誤但「可用」的實作，比留一個會明確失敗的 stub 危險得多。
     */
    public List<String> getUsersByPermissionAndCondition(String permCode, java.util.Map<String, Object> attrs) {
        throw new UnsupportedOperationException(
                "條件式權限查詢尚未實作（attrs 會被忽略）。請改用 getUsersByPermission("
                        + permCode + ")，或先實作條件過濾再使用此方法。");
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
            userIds.forEach(uid -> cacheEvict("perm:user:" + uid));
        }
        if (permCodes != null) {
            permCodes.forEach(code -> {
                cacheEvict("perm:users:" + code);
                // Also delete dept-scoped keys via pattern.
                //
                // ⚠️ KEYS 會掃整個 keyspace 並阻塞 Redis，production 隱憂
                // （CLAUDE.md 已知技術債 #7 / backlog）。此處先包上容錯 ——
                // 失效失敗只會讓舊值活到 TTL 到期（5/10 分鐘），
                // 不應該讓呼叫端的請求失敗。改用 SCAN 是另一個題目。
                try {
                    var keys = redis.keys("perm:users:" + code + ":*");
                    if (keys != null && !keys.isEmpty()) redis.delete(keys);
                } catch (Exception e) {
                    log.warn("清除部門層級權限快取失敗（code={}）: {}", code, e.toString());
                }
            });
        }
    }

    // ── 快取容錯（security-audit P1-10）───────────────────────────────
    //
    // 改動前每個方法第一件事就是 redis.opsForValue().get(key)，且無 try/catch。
    // Redis 不可用時，連「直接去問組織系統」的退路都走不到 ——
    // 所有 assignee 解析在讀快取那一行就爆，於是任務建立整批失敗。
    //
    // 也就是說快取層變成了比被快取的系統更關鍵的單點。快取的用途是加速，
    // 它掛掉應該退化成「每次都問來源」，而不是讓整個功能不可用。

    /** 讀快取；任何 Redis 故障都視為 cache miss。 */
    private String cacheGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("讀取快取失敗，退化為直接查詢來源（key={}）: {}", key, e.toString());
            return null;
        }
    }

    /** 寫快取；失敗只記錄，不影響已取得的正確結果。 */
    private void cachePut(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("寫入快取失敗，本次結果仍然有效（key={}）: {}", key, e.toString());
        }
    }

    /** 刪快取；失敗只記錄。失效失敗會讓舊值活到 TTL 到期，但不應讓請求失敗。 */
    private void cacheEvict(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("清除快取失敗（key={}）: {}", key, e.toString());
        }
    }
}
