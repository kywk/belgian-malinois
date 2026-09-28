package com.bpm.core.service;

import com.bpm.core.client.OrgRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@Service("orgService")
public class OrgService {

    private static final Logger log = LoggerFactory.getLogger(OrgService.class);

    private final OrgRestClient orgRestClient;
    private final StringRedisTemplate redis;

    public OrgService(OrgRestClient orgRestClient, StringRedisTemplate redis) {
        this.orgRestClient = orgRestClient;
        this.redis = redis;
    }

    public String getDirectManager(String userId) {
        String key = "org:manager:" + userId;
        String cached = cacheGet(key);
        if (cached != null) return cached;
        String manager = orgRestClient.getManager(userId);
        if (manager != null) cachePut(key, manager, Duration.ofMinutes(60));
        return manager;
    }

    /**
     * 依金額取得有核決權的主管。
     *
     * <p>⚠️ <b>尚未實作，刻意拋例外</b>（security-audit P1-7）。
     *
     * <p>改動前的實作是 {@code return getDirectManager(userId)} ——
     * {@code amount} 完全未使用。業務人員寫
     * {@code ${orgService.getAuthorizedManager(initiator, amount)}}，
     * 以為一千萬的採購會往上送到有權限的層級，實際永遠只送到一階直屬主管。
     * lint 全綠、執行期無警告、稽核看起來完全正常 —— 授權已經降級而無人知道。
     *
     * <p>要真正實作需要金額級距的業務規則（誰在什麼金額以下可核決），
     * 那份規則目前不存在。在它存在之前，明確失敗比靜默錯誤安全。
     */
    public String getAuthorizedManager(String userId, BigDecimal amount) {
        throw new UnsupportedOperationException(
                "金額分級核決尚未實作（amount 會被忽略）。"
                        + "請改用 getDirectManager(userId)，或先定義金額級距規則。");
    }

    public String resolveEffective(String userId) {
        String key = "org:substitute:" + userId;
        String cached = cacheGet(key);
        if (cached != null) return cached.isEmpty() ? userId : cached;
        String substitute = orgRestClient.getSubstitute(userId);
        cachePut(key, substitute != null ? substitute : "", Duration.ofMinutes(1));
        return substitute != null ? substitute : userId;
    }

    public String getDeptGroup(String userId) {
        return getDeptId(userId);
    }

    /**
     * 主管鏈（由近而遠）。
     *
     * <p>改動前直接回傳外部系統的原始結果，沒有任何清理（security-audit P1-9）：
     *
     * <ul>
     *   <li><b>不去重</b>：組織資料成環（A 的主管是 B、B 的主管是 A）時
     *       chain 會是 {@code [B,A,B,A,B]}。</li>
     *   <li><b>不排除本人</b>：更常見的情況甚至不需要環 —— 高層自己是自己的
     *       主管（組織表常見的頂點表示法）→ chain 為 {@code [u,u,u,u,u]}
     *       → {@code getManagerWithPermission} 的 findFirst 必然回傳本人
     *       → <b>自我簽核</b>。</li>
     *   <li><b>空鏈解析錯誤</b>：{@code "".split(",")} 回傳 {@code [""]}，
     *       因此空快取會變成「有一個空字串主管」。</li>
     * </ul>
     *
     * <p>Mock 資料本身就有這個環：{@code MockOrgController.getManager("dir001")}
     * 走 default 回 {@code mgr001}，形成 {@code dir001 → mgr001 → dir001}
     * —— 正好觸發自我簽核，而且是下屬核准上司的案件。
     *
     * <p>現在在此處統一清理（去重、排除本人、保留由近而遠的順序），
     * 讓所有消費者與 BPMN EL 都拿到乾淨的鏈。
     */
    public List<String> getManagerChain(String userId, int levels) {
        String key = "org:manager-chain:" + userId + ":" + levels;
        String cached = cacheGet(key);
        if (cached != null) return clean(splitCsv(cached), userId);

        List<String> chain = orgRestClient.getManagerChain(userId, levels);
        if (chain == null) return List.of();
        List<String> cleaned = clean(chain, userId);
        if (!cleaned.isEmpty()) {
            cachePut(key, String.join(",", cleaned), Duration.ofMinutes(60));
        }
        return cleaned;
    }

    /** 去重、排除本人與空值，保留原順序（由近而遠）。 */
    private static List<String> clean(List<String> chain, String self) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String m : chain) {
            if (m == null) continue;
            String v = m.trim();
            if (v.isEmpty() || v.equals(self)) continue;
            out.add(v);
        }
        return List.copyOf(out);
    }

    /** {@code "".split(",")} 會回傳 {@code [""]}，因此不能直接 split。 */
    private static List<String> splitCsv(String csv) {
        if (csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    public String getDeptId(String userId) {
        String key = "org:dept:" + userId;
        String cached = cacheGet(key);
        if (cached != null) return cached;
        String deptId = orgRestClient.getDepartment(userId);
        if (deptId != null) cachePut(key, deptId, Duration.ofMinutes(60));
        return deptId;
    }

    public boolean isUserAvailable(String userId) {
        String key = "org:available:" + userId;
        String cached = cacheGet(key);
        if (cached != null) return "true".equals(cached);
        // Default: available if substitute is null (user is not on leave)
        String substitute = orgRestClient.getSubstitute(userId);
        boolean available = substitute == null;
        cachePut(key, String.valueOf(available), Duration.ofMinutes(5));
        return available;
    }

    public List<String> getDeptMembers(String deptId) {
        String key = "org:dept-members:" + deptId;
        String cached = cacheGet(key);
        if (cached != null) return splitCsv(cached);
        List<String> members = orgRestClient.getDeptMembers(deptId);
        if (members != null && !members.isEmpty()) {
            cachePut(key, String.join(",", members), Duration.ofMinutes(30));
        }
        return members;
    }

    public void invalidateCache(List<String> userIds, String type) {
        if (userIds == null) return;
        for (String userId : userIds) {
            cacheEvict("org:manager:" + userId);
            cacheEvict("org:substitute:" + userId);
            cacheEvict("org:available:" + userId);
            cacheEvict("org:dept:" + userId);
            // Delete manager-chain with common levels
            for (int i = 1; i <= 5; i++) {
                cacheEvict("org:manager-chain:" + userId + ":" + i);
            }
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
