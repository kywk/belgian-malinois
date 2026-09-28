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
        String cached = redis.opsForValue().get(key);
        if (cached != null) return splitCsv(cached);

        List<String> perms = permRestClient.getUserPermissions(userId);
        if (perms == null) return List.of();
        // 空清單也要快取，否則「沒有權限」的使用者每次都打外部系統。
        redis.opsForValue().set(key, String.join(",", perms), Duration.ofMinutes(5));
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
