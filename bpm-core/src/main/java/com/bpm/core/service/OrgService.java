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

    /** 一般事實（主管、部門、部門成員）的 TTL。組織架構不常變。 */
    private static final Duration ORG_TTL = Duration.ofMinutes(60);

    /**
     * 直屬主管，{@code null} 表示位於組織鏈頂。
     *
     * <p>⚠️ {@code null} 也必須快取（security-audit P2-8）。改動前是
     * {@code if (manager != null) cachePut(...)} —— 鏈頂人員（dir001、admin001）
     * 的查詢<b>命中率永遠是 0%</b>，每次都打外部系統。而鏈頂正是最常出現在
     * 簽核路徑上的人，所以這是熱路徑。
     *
     * <p>用空字串當「沒有主管」的哨兵值，與 {@link #cachedSubstitute} 一致。
     * 現在 mock 已改為 fail-closed（P2-7），查不到的人會拋例外而不是回 null，
     * 所以 {@code null} 只代表「確實沒有主管」—— 快取這個事實是安全的。
     */
    public String getDirectManager(String userId) {
        String key = "org:manager:" + userId;
        String cached = cacheGet(key);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String manager = orgRestClient.getManager(userId);
        cachePut(key, manager != null ? manager : "", ORG_TTL);
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

    /**
     * 代理人快取的 TTL。
     *
     * <p>取兩者中較短的那個（原本 resolveEffective 是 1 分鐘、
     * isUserAvailable 是 5 分鐘）。委派生效要快 —— 有人請假時工作必須立刻改道，
     * 而「快取久一點」省下的外部呼叫遠不值得那段時間的錯誤派工。
     */
    private static final Duration SUBSTITUTE_TTL = Duration.ofMinutes(1);

    /**
     * 「這個人有沒有代理人」——{@link #resolveEffective} 與
     * {@link #isUserAvailable} 的<b>唯一</b>事實來源（security-audit P2-8）。
     *
     * <h2>改動前為什麼會自我矛盾</h2>
     *
     * <p>兩個方法都從 {@code orgRestClient.getSubstitute(userId)} 推導，
     * 卻各自用不同的 key 與 TTL 快取：{@code org:substitute:*} 1 分鐘、
     * {@code org:available:*} 5 分鐘。
     *
     * <p>於是有人請假之後會出現一段<b>最長 4 分鐘</b>的窗口，系統同時相信
     * 「這個人有空」與「這個人已委派給別人」。
     * {@code BpmPermissionService.getFirstAvailableUser} 用 isUserAvailable
     * 挑簽核人 → 任務派給一個正在休假的人。休假結束後方向相反：
     * available=false 還留著，那個人會被跳過。
     *
     * <p>同一個事實不可以有兩份快取。現在只有一個 key，
     * 兩個方法都由它推導，矛盾在結構上不可能發生。
     *
     * @return 代理人 id，或 {@code null} 表示沒有代理人
     */
    private String cachedSubstitute(String userId) {
        String key = "org:substitute:" + userId;
        String cached = cacheGet(key);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String substitute = orgRestClient.getSubstitute(userId);
        // 空字串是「沒有代理人」的哨兵值。一定要快取 ——
        // 絕大多數人沒有代理人，不快取等於每次挑簽核人都打外部系統。
        cachePut(key, substitute != null ? substitute : "", SUBSTITUTE_TTL);
        return substitute;
    }

    /** 有代理人時回代理人，否則回本人。 */
    public String resolveEffective(String userId) {
        String substitute = cachedSubstitute(userId);
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
    /**
     * 快取主管鏈的標準深度。
     *
     * <p>改動前快取 key 帶著 {@code levels}（{@code org:manager-chain:u:3}），
     * 而 {@code invalidateCache} 只迴圈 1..5 —— 有人呼叫
     * {@code getManagerChain(u, 10)} 時，那個 key <b>永遠不會被失效</b>，
     * 組織調整後會有最長 60 分鐘的錯誤簽核路徑（security-audit P2-8）。
     *
     * <p>現在只快取一份標準深度的鏈，較短的需求直接切片 ——
     * 主管鏈對 levels 是前綴關係，所以切片與重新查詢的結果相同。
     * 一個 userId 對應一個 key，失效就不可能漏。
     */
    private static final int CACHED_CHAIN_LEVELS = 5;

    public List<String> getManagerChain(String userId, int levels) {
        if (levels <= 0) return List.of();

        // 超過標準深度就不快取，直接問來源。
        // 寧可少一次快取命中，也不要為了省一次呼叫而留下失效不到的 key。
        if (levels > CACHED_CHAIN_LEVELS) {
            log.debug("主管鏈深度 {} 超過快取的標準深度 {}，本次直接查詢來源（不快取）",
                    levels, CACHED_CHAIN_LEVELS);
            List<String> chain = orgRestClient.getManagerChain(userId, levels);
            return chain == null ? List.of() : clean(chain, userId);
        }

        String key = "org:manager-chain:" + userId;
        String cached = cacheGet(key);
        if (cached != null) return truncate(clean(splitCsv(cached), userId), levels);

        List<String> chain = orgRestClient.getManagerChain(userId, CACHED_CHAIN_LEVELS);
        List<String> cleaned = chain == null ? List.<String>of() : clean(chain, userId);
        // 空鏈（鏈頂人員）也要快取，理由見 getDirectManager。
        cachePut(key, String.join(",", cleaned), ORG_TTL);
        return truncate(cleaned, levels);
    }

    /**
     * 第 {@code level} 階主管（1 = 直屬主管）。<b>永遠不回 null。</b>
     *
     * <h2>為什麼需要這個方法（security-audit P2-6）</h2>
     *
     * <p>設計器對「直屬主管（N階）」產生的是
     * {@code ${orgService.getManagerChain(initiator, 2)[1]}}，也就是對
     * {@code List} 直接做索引。而主管鏈可能<b>比要求的短</b> ——
     * 離組織頂端只差一階的人就會如此，而 {@code clean()} 的去重與排除本人
     * 還會讓它更短。
     *
     * <p>關鍵是 JUEL 對越界索引的行為：<b>它不拋例外，而是回 null</b>（已實測）。
     * 於是任務建立成功、案件存在，但 {@code assignee=null} 且沒有候選群組
     * —— <b>這個任務對所有人都不可見</b>。沒有人收到通知、沒有人能認領，
     * 案件永遠卡在引擎裡。而 {@code [1]}（階數 2）正是設計器的預設值。
     *
     * <p>簽核系統裡「靜默產生看不見的任務」比「啟動失敗」嚴重得多：
     * 後者使用者會立刻重試或回報，前者要等到有人問「我的假單怎麼還沒過」。
     *
     * <h2>鏈比要求的短時為什麼回最高階而不是拋錯</h2>
     *
     * <p>「要求第 3 階但只有 2 階」與 {@code getAuthorizedManager} 當初忽略
     * {@code amount} 的情況不同（那是把呼叫端的意圖整個丟掉）。這裡組織圖明確
     * 說了上面沒有人 —— 回最高階是「現存的最高權限」，不是「比要求的低」。
     *
     * <p>但這也可能代表組織資料不完整，所以會記一筆 warn。
     * 完全沒有主管則沒有任何說得過去的答案，拋例外。
     *
     * @throws IllegalArgumentException level 小於 1
     * @throws IllegalStateException    這個人完全沒有主管
     */
    public String getManagerAtLevel(String userId, int level) {
        if (level < 1) {
            throw new IllegalArgumentException(
                    "主管階數必須大於 0（收到 " + level + "）。1 代表直屬主管。");
        }

        List<String> chain = getManagerChain(userId, level);
        if (chain.isEmpty()) {
            throw new IllegalStateException(
                    userId + " 在組織系統中沒有任何主管，無法推導第 " + level + " 階簽核人。"
                            + "請改用明確指定的受理人，或修正組織資料。");
        }
        if (chain.size() < level) {
            log.warn("{} 的主管鏈只有 {} 階（要求第 {} 階），改用最高階主管 {}。"
                            + "若這不是預期結果，請檢查組織資料是否完整",
                    userId, chain.size(), level, chain.getLast());
            return chain.getLast();
        }
        return chain.get(level - 1);
    }

    private static List<String> truncate(List<String> chain, int levels) {
        return chain.size() <= levels ? chain : List.copyOf(chain.subList(0, levels));
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

    /** {@code null} 同樣要快取，理由見 {@link #getDirectManager}。 */
    public String getDeptId(String userId) {
        String key = "org:dept:" + userId;
        String cached = cacheGet(key);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String deptId = orgRestClient.getDepartment(userId);
        cachePut(key, deptId != null ? deptId : "", ORG_TTL);
        return deptId;
    }

    /**
     * 是否可受理工作 —— 由 {@link #cachedSubstitute} 推導，<b>不另外快取</b>。
     *
     * <p>「有代理人」等於「這個人不在」。這是同一個事實的另一種說法，
     * 所以不該有自己的 key 與 TTL（security-audit P2-8，詳見
     * {@link #cachedSubstitute} 的註解）。
     */
    public boolean isUserAvailable(String userId) {
        return cachedSubstitute(userId) == null;
    }

    /**
     * 部門成員。空清單也快取，且一律不回 {@code null}。
     *
     * <p>改動前空清單不快取（每次都打外部系統），而且直接回傳 client 的
     * {@code null} —— 呼叫端拿到 null 會 NPE，而空部門是合法狀態。
     */
    public List<String> getDeptMembers(String deptId) {
        String key = "org:dept-members:" + deptId;
        String cached = cacheGet(key);
        if (cached != null) return splitCsv(cached);
        List<String> members = orgRestClient.getDeptMembers(deptId);
        List<String> result = members != null ? List.copyOf(members) : List.of();
        cachePut(key, String.join(",", result), Duration.ofMinutes(30));
        return result;
    }

    /** {@link #invalidateCache} 接受的 type。 */
    public enum CacheType {
        /** 主管與主管鏈。 */
        MANAGER,
        /** 代理人（同時影響 isUserAvailable，因為它們是同一個事實）。 */
        SUBSTITUTE,
        /** 所屬部門，以及該部門的成員清單。 */
        DEPARTMENT,
        /** 以上全部。 */
        ALL;

        /**
         * 未知的 type 一律拒絕，<b>不要猜</b>。
         *
         * <p>改動前 {@code type} 這個參數完全沒有被讀取 —— 呼叫端送
         * {@code "manager"} 以為只清主管快取，實際上全部被清掉。
         * 那次剛好是「清太多」（安全的方向），但它建立了一個錯誤的認知：
         * 呼叫端會相信這個參數有作用，於是把它用在會出問題的方向上。
         *
         * <p>現在打錯字會拿到 400，而不是靜默地做別的事。
         */
        static CacheType parse(String raw) {
            if (raw == null || raw.isBlank()) return ALL;
            try {
                return CacheType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "未知的快取類型: " + raw + "（可用: manager, substitute, department, all）", e);
            }
        }
    }

    /**
     * 失效指定使用者的組織快取。
     *
     * <p>{@code type} 為 null 或空白時代表全部。未知的 type 會拋
     * {@link IllegalArgumentException} —— 見 {@link CacheType#parse}。
     *
     * <h2>部門成員清單的失效（security-audit P2-8）</h2>
     *
     * <p>改動前完全沒有任何路徑會清掉 {@code org:dept-members:*}：
     * 有人異動部門後，舊部門的成員清單會繼續把他算在內，最長 30 分鐘。
     * 那段時間內以部門為範圍挑簽核人，會挑到已經不在該部門的人。
     *
     * <p>這裡在清掉 {@code org:dept:{userId}} <b>之前</b>先讀出它的值，
     * 才能知道要清哪個部門的成員清單。順序反了就拿不到了。
     *
     * <p>⚠️ 這只涵蓋「離開舊部門」那一半。<b>加入新部門</b>那一半這裡無從得知
     * —— 呼叫端必須另外呼叫 {@link #invalidateDeptMembers}。
     * 組織系統推送異動時應同時送出新舊兩個部門。
     */
    public void invalidateCache(List<String> userIds, String type) {
        CacheType scope = CacheType.parse(type);
        if (userIds == null) return;

        for (String userId : userIds) {
            if (scope == CacheType.ALL || scope == CacheType.MANAGER) {
                cacheEvict("org:manager:" + userId);
                cacheEvict("org:manager-chain:" + userId);
            }
            if (scope == CacheType.ALL || scope == CacheType.SUBSTITUTE) {
                // isUserAvailable 由同一個 key 推導，所以清這一個就夠了。
                cacheEvict("org:substitute:" + userId);
            }
            if (scope == CacheType.ALL || scope == CacheType.DEPARTMENT) {
                // 必須先讀後刪：清掉之後就不知道他原本在哪個部門了。
                String oldDept = cacheGet("org:dept:" + userId);
                cacheEvict("org:dept:" + userId);
                if (oldDept != null && !oldDept.isEmpty()) {
                    cacheEvict("org:dept-members:" + oldDept);
                }
            }
        }
    }

    /**
     * 失效部門成員清單。
     *
     * <p>{@link #invalidateCache} 只能推導出使用者<b>離開</b>的那個部門。
     * 加入新部門時，呼叫端必須明確指定 —— 這個方法就是給那個用途。
     */
    public void invalidateDeptMembers(List<String> deptIds) {
        if (deptIds == null) return;
        for (String deptId : deptIds) {
            if (deptId != null && !deptId.isBlank()) {
                cacheEvict("org:dept-members:" + deptId.trim());
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
