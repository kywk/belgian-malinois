package com.bpm.core.service;

import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 快取一致性（security-audit P2-8）。
 *
 * <h2>這組測試斷言的是 key 的結構，不只是回傳值</h2>
 *
 * <p>快取缺陷的共同特徵是<b>回傳值在當下是對的</b>——問題出在一段時間之後，
 * 或出在另一個呼叫路徑上。所以只斷言 {@code service.foo()} 回傳什麼抓不到它們：
 * 必須直接檢查 Redis 裡有哪些 key、值是什麼。
 *
 * <p>例如「同一個事實有兩份快取」這個缺陷，任何單次呼叫都看不出來 ——
 * 兩份快取剛寫入時內容一致，要等 TTL 較短的那份過期才會分歧。
 * 可靠的斷言是「{@code org:available:*} 這個 key 不該存在」。
 */
class CacheConsistencyTest extends IntegrationTestBase {

    @Autowired private OrgService orgService;
    @Autowired private BpmPermissionService permService;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void clearCache() {
        // 容器是跨測試共用的，必須自己清理（見 IntegrationTestBase 註解）。
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match("org:*").count(500).build())) {
            cursor.forEachRemaining(redis::delete);
        }
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match("perm:*").count(500).build())) {
            cursor.forEachRemaining(redis::delete);
        }
    }

    private List<String> keys(String pattern) {
        var out = new java.util.ArrayList<String>();
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern).count(500).build())) {
            cursor.forEachRemaining(out::add);
        }
        return out;
    }

    // ── 1. 同一個事實只能有一份快取 ────────────────────────────────

    @Test
    @DisplayName("resolveEffective 與 isUserAvailable 必須共用同一個 key")
    void substituteFactHasExactlyOneCacheEntry() {
        // 改動前：resolveEffective 寫 org:substitute:*（TTL 1 分）、
        // isUserAvailable 寫 org:available:*（TTL 5 分）。兩者由同一個
        // getSubstitute 推導，所以有人請假後會出現最長 4 分鐘的窗口，
        // 系統同時相信「他有空」與「他已委派」。
        orgService.resolveEffective("user001");
        orgService.isUserAvailable("user001");

        assertThat(keys("org:substitute:user001")).hasSize(1);
        assertThat(keys("org:available:*"))
                .as("org:available:* 不該再存在 —— 它是同一個事實的第二份快取，"
                        + "TTL 不同就會與 org:substitute:* 分歧")
                .isEmpty();
    }

    @Test
    @DisplayName("兩個方法的答案必須一致（同一個 key 推導）")
    void substituteDerivedAnswersAgree() {
        // fixture 裡沒有人有代理人，所以：有空 且 effective == 本人。
        assertThat(orgService.isUserAvailable("user001")).isTrue();
        assertThat(orgService.resolveEffective("user001")).isEqualTo("user001");

        // 直接改快取值模擬「已委派」，兩個方法必須同時翻轉 ——
        // 若各有一份快取，只會有一個變。
        redis.opsForValue().set("org:substitute:user001", "user002");

        assertThat(orgService.resolveEffective("user001")).isEqualTo("user002");
        assertThat(orgService.isUserAvailable("user001"))
                .as("substitute 已設定，這個人就不該再被當成有空的")
                .isFalse();
    }

    // ── 2. 空／null 結果也必須快取 ─────────────────────────────────

    @Test
    @DisplayName("鏈頂人員的 null 主管必須快取，否則命中率永遠是 0%")
    void nullManagerIsCached() {
        // dir001 位於組織鏈頂，getManager 回 {}（無主管）。
        // 改動前 if (manager != null) cachePut(...) → 這個查詢永遠不命中，
        // 而鏈頂正是最常出現在簽核路徑上的人。
        assertThat(orgService.getDirectManager("dir001")).isNull();

        assertThat(redis.opsForValue().get("org:manager:dir001"))
                .as("null 主管未被快取 —— 每次挑簽核人都會打外部系統")
                .isEqualTo("");

        // 第二次呼叫必須走快取且結果相同。
        assertThat(orgService.getDirectManager("dir001")).isNull();
    }

    @Test
    @DisplayName("鏈頂人員的空主管鏈必須快取")
    void emptyManagerChainIsCached() {
        assertThat(orgService.getManagerChain("dir001", 3)).isEmpty();
        assertThat(redis.opsForValue().get("org:manager-chain:dir001")).isEqualTo("");
    }

    // ── 3. manager-chain 的 key 不得隨 levels 增生 ─────────────────

    @Test
    @DisplayName("manager-chain 每個使用者只有一個 key，不隨 levels 增生")
    void managerChainUsesOneKeyPerUser() {
        // 改動前 key 帶 levels（org:manager-chain:u:3），而 invalidateCache
        // 只迴圈 1..5 —— levels=10 的 key 永遠失效不到，組織調整後會留下
        // 最長 60 分鐘的錯誤簽核路徑。
        orgService.getManagerChain("user001", 1);
        orgService.getManagerChain("user001", 3);
        orgService.getManagerChain("user001", 5);

        assertThat(keys("org:manager-chain:user001*"))
                .as("每個 levels 各留一個 key，就會有失效不到的殘留")
                .containsExactly("org:manager-chain:user001");
    }

    @Test
    @DisplayName("切片後的鏈必須與直接查詢相同深度一致")
    void truncatedChainMatchesDirectQuery() {
        // user001 → mgr001 → dir001
        assertThat(orgService.getManagerChain("user001", 1)).containsExactly("mgr001");
        assertThat(orgService.getManagerChain("user001", 5)).containsExactly("mgr001", "dir001");
        assertThat(orgService.getManagerChain("user001", 0)).isEmpty();
    }

    @Test
    @DisplayName("invalidateCache 必須清掉 manager-chain（不論當初用什麼 levels）")
    void managerChainIsInvalidated() {
        orgService.getManagerChain("user001", 3);
        assertThat(keys("org:manager-chain:user001")).hasSize(1);

        orgService.invalidateCache(List.of("user001"), "manager");

        assertThat(keys("org:manager-chain:user001*")).isEmpty();
    }

    // ── 4. type 參數必須真的有作用 ─────────────────────────────────

    @Test
    @DisplayName("type 必須限制失效範圍，而不是被忽略")
    void invalidateTypeLimitsScope() {
        // 改動前 type 完全沒有被讀取 —— 送任何值都是全部清掉。
        orgService.getDirectManager("user001");
        orgService.resolveEffective("user001");
        orgService.getDeptId("user001");

        orgService.invalidateCache(List.of("user001"), "manager");

        assertThat(keys("org:manager:user001")).as("manager 應被清掉").isEmpty();
        assertThat(keys("org:substitute:user001")).as("substitute 不該被清掉").hasSize(1);
        assertThat(keys("org:dept:user001")).as("dept 不該被清掉").hasSize(1);
    }

    @Test
    @DisplayName("type 為空或 all 時清掉全部")
    void blankTypeMeansAll() {
        orgService.getDirectManager("user001");
        orgService.resolveEffective("user001");
        orgService.getDeptId("user001");

        orgService.invalidateCache(List.of("user001"), null);

        assertThat(keys("org:*user001*")).isEmpty();
    }

    @Test
    @DisplayName("未知的 type 必須拒絕，不得靜默改做別的事")
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> orgService.invalidateCache(List.of("user001"), "manger"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manger");
    }

    // ── 5. 部門成員清單必須有失效路徑 ──────────────────────────────

    @Test
    @DisplayName("使用者異動部門時，舊部門的成員清單必須被清掉")
    void oldDepartmentMemberListIsInvalidated() {
        // 改動前完全沒有任何路徑會清 org:dept-members:* ——
        // 異動後舊部門會繼續把這個人算在內，最長 30 分鐘。
        orgService.getDeptId("user001");           // → dept001
        orgService.getDeptMembers("dept001");

        assertThat(keys("org:dept-members:dept001")).hasSize(1);

        orgService.invalidateCache(List.of("user001"), "department");

        assertThat(keys("org:dept-members:dept001"))
                .as("舊部門的成員清單沒被清掉 —— 這個人會繼續被算在裡面")
                .isEmpty();
    }

    @Test
    @DisplayName("加入新部門那一半必須由呼叫端明確指定")
    void newDepartmentMustBeInvalidatedExplicitly() {
        // 後端只能從快取裡的舊值推導出「離開的部門」，新部門無從得知。
        orgService.getDeptMembers("dept002");
        assertThat(keys("org:dept-members:dept002")).hasSize(1);

        orgService.invalidateDeptMembers(List.of("dept002"));
        assertThat(keys("org:dept-members:dept002")).isEmpty();
    }

    // ── 6. 權限碼含冒號造成的 key 交叉污染 ─────────────────────────

    @Test
    @DisplayName("失效某個權限碼不得波及以它為前綴的其他權限碼")
    void invalidatingOnePermissionCodeMustNotAffectAPrefixedOne() {
        // 這是最具體的一個：權限碼是階層式命名且含冒號。
        //
        // 改動前部門層級的 key 是 perm:users:{code}:{deptId}，失效時用
        //     keys("perm:users:" + code + ":*")
        // 而 Redis glob 是整個 key 比對 —— 失效 hr:leave 會命中
        // perm:users:hr:leave:approve，清掉一個「不相關權限」的全域快取。
        permService.getUsersByPermission("hr:leave:approve");
        assertThat(keys("perm:users:hr:leave:approve")).hasSize(1);

        permService.invalidateCache(null, List.of("hr:leave"));

        assertThat(keys("perm:users:hr:leave:approve"))
                .as("失效 hr:leave 波及了 hr:leave:approve —— 兩個 key 家族有前綴關係")
                .hasSize(1);
    }

    @Test
    @DisplayName("部門層級與全域的 key 必須在不同 namespace")
    void deptScopedKeysLiveInASeparateNamespace() {
        permService.getUsersByPermission("hr:leave:approve");
        permService.getUsersByPermissionAndDept("hr:leave:approve", "dept001");

        assertThat(keys("perm:users:hr:leave:approve")).hasSize(1);
        assertThat(keys("perm:users-by-dept:dept001:hr:leave:approve")).hasSize(1);

        // 兩者不可互為前綴，否則 SCAN 模式會交叉命中。
        assertThat("perm:users-by-dept:dept001:hr:leave:approve")
                .doesNotStartWith("perm:users:");
    }

    @Test
    @DisplayName("失效權限碼必須同時清掉全域與所有部門層級的 key")
    void invalidatingAPermissionCodeClearsBothFamilies() {
        permService.getUsersByPermission("hr:leave:approve");
        permService.getUsersByPermissionAndDept("hr:leave:approve", "dept001");
        permService.getUsersByPermissionAndDept("hr:leave:approve", "dept002");

        permService.invalidateCache(null, List.of("hr:leave:approve"));

        assertThat(keys("perm:users:hr:leave:approve")).isEmpty();
        assertThat(keys("perm:users-by-dept:*:hr:leave:approve"))
                .as("所有部門範圍的 key 都要清掉")
                .isEmpty();
    }

    @Test
    @DisplayName("空的權限持有者清單也要快取")
    void emptyPermissionHolderListIsCached() {
        // purchase:self:approve 只有 dir001；改用一個 dept 篩掉所有人的組合，
        // 讓結果確定是空的。
        var users = permService.getUsersByPermissionAndDept("purchase:self:approve", "dept002");
        assertThat(users).isEmpty();

        assertThat(redis.opsForValue().get("perm:users-by-dept:dept002:purchase:self:approve"))
                .as("空清單未快取 —— 每次挑簽核人都會打外部系統")
                .isEqualTo("");
    }
}
