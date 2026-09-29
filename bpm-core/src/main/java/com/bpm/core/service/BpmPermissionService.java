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

    /**
     * 全域範圍的權限持有者 key。
     *
     * <h2>⚠️ 為什麼部門範圍的 key 要換成另一個 namespace（security-audit P2-8）</h2>
     *
     * <p>本專案的權限碼是階層式命名，<b>含冒號</b>（{@code hr:leave:approve}）。
     * 改動前兩種查詢共用同一個前綴：
     * <pre>
     *   getUsersByPermission("hr:leave:approve")            → perm:users:hr:leave:approve
     *   getUsersByPermissionAndDept("hr:leave", "approve")  → perm:users:hr:leave:approve
     * </pre>
     * 同一個 key，兩種不同語意的資料 —— 互相覆蓋。
     *
     * <p>但真正會踩到的是失效那一側。{@code invalidateCache} 用
     * {@code keys("perm:users:" + code + ":*")} 清部門層級的 key，而 Redis 的
     * glob 是<b>整個 key 比對</b>，所以失效 {@code hr:leave} 會連帶命中
     * {@code perm:users:hr:leave:approve} —— 清掉一個<b>不相關權限</b>的全域快取。
     * 反方向則相反：{@code hr:leave:approve} 的部門 key 也可能被別的碼清掉。
     * 兩者都不會報錯，只會讓命中率莫名下降或讀到別人的資料。
     *
     * <p>解法是讓兩個家族不再有前綴關係：部門範圍改用
     * {@code perm:users-by-dept:{deptId}:{code}}，deptId 放<b>前面</b>。
     * 這樣失效模式 {@code perm:users-by-dept:*:hr:leave} 因為兩端錨定，
     * 不會命中 {@code ...:hr:leave:approve}。
     */
    private static String globalKey(String permCode) {
        return "perm:users:" + permCode;
    }

    /** deptId 必須放在 code 之前，理由見 {@link #globalKey}。 */
    private static String deptKey(String permCode, String deptId) {
        return "perm:users-by-dept:" + deptId + ":" + permCode;
    }

    /**
     * 持有指定權限的使用者。
     *
     * <p>空清單也快取：一個還沒指派任何人的權限碼，不快取就等於每次挑簽核人
     * 都打外部系統。同時改用 {@link #splitCsv} —— 原本的
     * {@code cached.split(",")} 對空字串會回傳 {@code [""]}，
     * 也就是「有一個名字為空字串的簽核人」。
     */
    public List<String> getUsersByPermission(String permCode) {
        return cachedUsers(globalKey(permCode),
                () -> permRestClient.getUsersByPermission(permCode),
                Duration.ofMinutes(5));
    }

    public List<String> getUsersByPermissionAndDept(String permCode, String deptId) {
        return cachedUsers(deptKey(permCode, deptId),
                () -> permRestClient.getUsersByPermissionAndDept(permCode, deptId),
                Duration.ofMinutes(10));
    }

    private List<String> cachedUsers(String key, java.util.function.Supplier<List<String>> loader,
                                      Duration ttl) {
        String cached = cacheGet(key);
        if (cached != null) return splitCsv(cached);
        List<String> users = loader.get();
        List<String> result = users != null ? List.copyOf(users) : List.of();
        cachePut(key, String.join(",", result), ttl);
        return result;
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

    /**
     * 使用者持有的權限碼。
     *
     * <p>公開是為了讓 {@code AuthorityResolver} 把它們轉成 Spring Security 的
     * authorities —— 授權規則因此能用權限中心的同一套詞彙
     * （{@code hasAuthority("audit:log:read")}），不必再維護一份角色映射表。
     */
    public List<String> getUserPermissions(String userId) {
        return userPermissions(userId);
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

    /**
     * 挑第一個有空的權限持有者。
     *
     * <h2>全部不在時：派給第一位的代理人（2026-09-29 決策）</h2>
     *
     * <p>「不在」在這裡的定義就是「設了代理人」（{@code isUserAvailable} 由
     * {@code getSubstitute} 推導）。所以全部不在時，每位持有人都已指定了誰來代理 ——
     * 派給第一位的代理人，正是代理設定要處理的情況。
     *
     * <p>改動前是派給第一位持有人<b>本人</b>：案件會在休假中的人的收件匣裡
     * 等到他回來，而代理人明明就在。回 null 更糟 —— 任務沒有受理人，
     * 停在沒有人看得到的地方（會被 {@code UnreachableTaskListener} 告警，但仍然卡住）。
     *
     * <p>⚠️ 已知限制：
     * <ul>
     *   <li><b>代理人未必持有該權限碼。</b>代理是組織層的委託（「我不在時他代我簽」），
     *       不經過權限中心。這與 {@code resolveEffective} 在其他指派路徑上的語意一致。</li>
     *   <li><b>只解一層。</b>代理人自己也不在時不會再往下找 —— 代理鏈可能成環，
     *       而且「代理人的代理人」已經超出原持有人的委託意圖。</li>
     * </ul>
     * 仍記一筆 warn：若某個權限碼經常出現，代表持有人太少，那是組織設定問題。
     */
    public String getFirstAvailableUser(String permCode) {
        List<String> users = getUsersByPermission(permCode);
        if (users.isEmpty()) return null;

        return users.stream()
                .filter(orgService::isUserAvailable)
                .findFirst()
                .orElseGet(() -> {
                    String first = users.getFirst();
                    String substitute = orgService.resolveEffective(first);
                    log.warn("權限 {} 的持有者 {} 全部不在，改派第一位 {} 的代理人 {}",
                            permCode, users, first, substitute);
                    return substitute;
                });
    }

    public void invalidateCache(List<String> userIds, List<String> permCodes) {
        if (userIds != null) {
            userIds.forEach(uid -> cacheEvict("perm:user:" + uid));
        }
        if (permCodes != null) {
            permCodes.forEach(code -> {
                cacheEvict(globalKey(code));
                evictDeptScoped(code);
            });
        }
    }

    /**
     * 清掉某個權限碼在所有部門範圍下的快取。
     *
     * <h2>改用 SCAN 而非 KEYS（CLAUDE.md 已知技術債 #7）</h2>
     *
     * <p>{@code KEYS} 會一次掃完整個 keyspace 並<b>阻塞 Redis</b> ——
     * 期間所有指令排隊，而 Redis 是單執行緒。這個專案把 Redis 當作
     * 組織／權限的快取層，阻塞它等於讓所有簽核路徑的解析一起停下來。
     * 而且觸發點是「組織系統推送權限異動」，那正是流量不可預期的時候。
     *
     * <p>{@code SCAN} 以游標分批返回，每批之間讓其他指令有機會執行。
     * 代價是可能重複或漏掉在掃描期間新增的 key —— 對快取失效而言可接受：
     * 漏掉的那個 key 最多活到 TTL 到期（5～10 分鐘）。
     *
     * <p>失效失敗只記錄、不拋出：讓舊值活到 TTL 到期，遠好過讓呼叫端的
     * 請求失敗。
     */
    private void evictDeptScoped(String permCode) {
        // ⚠️ 模式必須以 permCode 結尾且不加 *。Redis 的 glob 是整個 key 比對，
        // 所以這樣才不會命中 permCode 是前綴的其他權限碼
        // （例如 hr:leave 不會誤中 hr:leave:approve）。見 deptKey 的註解。
        var options = org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match("perm:users-by-dept:*:" + permCode)
                .count(200)
                .build();
        try (var cursor = redis.scan(options)) {
            var batch = new java.util.ArrayList<String>(200);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 200) {
                    redis.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) redis.delete(batch);
        } catch (Exception e) {
            log.warn("清除部門層級權限快取失敗（code={}），舊值將活到 TTL 到期: {}",
                    permCode, e.toString());
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
