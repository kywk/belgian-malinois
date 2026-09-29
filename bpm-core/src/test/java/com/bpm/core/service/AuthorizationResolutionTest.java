package com.bpm.core.service;

import com.bpm.core.client.OrgRestClient;
import com.bpm.core.client.PermRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 簽核人解析的三個問題（security-audit P1-7／P1-8／P1-9）。
 *
 * <p>這三者都決定「誰被指派來簽核」，而且三者都是<b>fail-open</b> ——
 * 外觀正常、lint 全綠、執行期無警告、稽核看起來也正常，
 * 但實際授權是錯的。這是簽核系統裡最難發現的一類缺陷。
 *
 * <p>用單元測試而非整合測試：需要精確控制快取命中／未命中、
 * 以及組織資料成環這類情境，用真實 Redis 與 mock 組織服務很難穩定重現。
 */
class AuthorizationResolutionTest {

    private PermRestClient permClient;
    private OrgRestClient orgClient;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private Map<String, String> cache;

    private OrgService orgService;
    private BpmPermissionService permService;
    private BpmQueryService queryService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        permClient = Mockito.mock(PermRestClient.class);
        orgClient = Mockito.mock(OrgRestClient.class);
        redis = Mockito.mock(StringRedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);
        cache = new HashMap<>();

        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> cache.get(inv.getArgument(0, String.class)));
        Mockito.doAnswer(inv -> {
            cache.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(java.time.Duration.class));

        orgService = new OrgService(orgClient, redis);
        permService = new BpmPermissionService(permClient, redis, orgService);
        queryService = new BpmQueryService(orgService, permService);
    }

    // ── P1-8 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P1-8 hasPermission")
    class HasPermission {

        @Test
        @DisplayName("冷快取與熱快取必須給出相同答案")
        void coldAndHotAgree() {
            when(permClient.getUserPermissions("mgr001"))
                    .thenReturn(List.of("hr:leave:approve", "finance:view"));

            // 冷（快取未命中，走精確比對）
            boolean cold = permService.hasPermission("mgr001", "hr:leave");
            // 熱（快取命中，改動前是對 "a,b,c" 做子字串比對）
            boolean hot = permService.hasPermission("mgr001", "hr:leave");

            assertThat(hot)
                    .as("同一組輸入在冷／熱快取下答案不同，授權判定就不具決定性，"
                            + "事故無法重現")
                    .isEqualTo(cold);
        }

        @Test
        @DisplayName("不得以子字串誤放行 —— 階層式權限碼特別危險")
        void substringMustNotPass() {
            when(permClient.getUserPermissions("mgr001"))
                    .thenReturn(List.of("hr:leave:approve"));

            // 先讓快取熱起來
            permService.hasPermission("mgr001", "hr:leave:approve");

            // 持有「核准」層級的碼，不代表持有「檢視」層級的碼；
            // 更不代表持有任意子字串。
            assertThat(permService.hasPermission("mgr001", "hr:leave"))
                    .as("hr:leave 是不同的權限碼，不得因為是子字串就放行").isFalse();
            assertThat(permService.hasPermission("mgr001", "approve"))
                    .as("approve 不是一個完整權限碼").isFalse();
            assertThat(permService.hasPermission("mgr001", "hr"))
                    .as("hr 不是一個完整權限碼").isFalse();
            assertThat(permService.hasPermission("mgr001", "hr:leave:approve"))
                    .as("完整相符必須放行").isTrue();
        }

        @Test
        @DisplayName("沒有任何權限的使用者，熱快取也必須回 false")
        void emptyPermissionsStayFalse() {
            when(permClient.getUserPermissions("user001")).thenReturn(List.of());
            assertThat(permService.hasPermission("user001", "hr:leave:approve")).isFalse();
            // 第二次走快取路徑；空字串快取若解析成 [""] 會出現奇怪行為
            assertThat(permService.hasPermission("user001", "hr:leave:approve")).isFalse();
            assertThat(permService.hasPermission("user001", "")).isFalse();
        }
    }

    // ── P1-9 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P1-9 主管鏈")
    class ManagerChain {

        @Test
        @DisplayName("組織資料成環時不得回傳本人（自我簽核）")
        void cyclicChainMustNotReturnSelf() {
            // MockOrgController 本身就有這個環：dir001 → mgr001 → dir001
            when(orgClient.getManagerChain("dir001", 5))
                    .thenReturn(List.of("mgr001", "dir001", "mgr001", "dir001"));
            when(permClient.getUserPermissions("mgr001")).thenReturn(List.of());
            when(permClient.getUserPermissions("dir001"))
                    .thenReturn(List.of("finance:payment:approve"));

            String approver = queryService.getManagerWithPermission(
                    "dir001", "finance:payment:approve");

            assertThat(approver)
                    .as("鏈中含本人時，findFirst 會挑到自己 → 自我簽核")
                    .isNotEqualTo("dir001");
        }

        @Test
        @DisplayName("自己是自己的主管（組織頂點的常見表示法）不得造成自我簽核")
        void selfAsOwnManagerIsExcluded() {
            when(orgClient.getManagerChain("ceo001", 5))
                    .thenReturn(List.of("ceo001", "ceo001", "ceo001"));
            when(permClient.getUserPermissions("ceo001"))
                    .thenReturn(List.of("finance:payment:approve"));

            assertThat(queryService.getManagerWithPermission("ceo001", "finance:payment:approve"))
                    .as("chain 全是本人 → 必然自我簽核，必須回 null 而不是自己")
                    .isNull();
        }

        @Test
        @DisplayName("主管鏈必須去重且保留由近而遠的順序")
        void chainIsDeduplicatedPreservingOrder() {
            when(orgClient.getManagerChain("user001", 5))
                    .thenReturn(List.of("mgr001", "dir001", "mgr001", "vp001"));

            assertThat(orgService.getManagerChain("user001", 5))
                    .as("去重後仍須由近而遠")
                    .containsExactly("mgr001", "dir001", "vp001");
        }

        @Test
        @DisplayName("沒有上級時回空鏈，不得回傳 [\"\"]")
        void emptyChainStaysEmpty() {
            when(orgClient.getManagerChain("top001", 5)).thenReturn(List.of());
            assertThat(orgService.getManagerChain("top001", 5)).isEmpty();
            // 第二次走快取路徑
            assertThat(orgService.getManagerChain("top001", 5)).isEmpty();
        }
    }

    // ── P1-7 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P1-7 金額分級／條件過濾的 fail-open stub")
    class FailOpenStubs {

        @Test
        @DisplayName("getAuthorizedManager 未實作金額分級時必須明確失敗")
        void authorizedManagerMustNotSilentlyIgnoreAmount() {
            assertThatThrownBy(() ->
                    orgService.getAuthorizedManager("user001", new BigDecimal("10000000")))
                    .as("方法簽名讓流程設計者相信有金額分級，實際卻永遠只送一階主管 —— "
                            + "留一個語意錯誤的可用實作比留一個會爆的 stub 危險得多")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("getUsersByPermissionAndCondition 未實作條件過濾時必須明確失敗")
        void conditionFilteringMustNotSilentlyIgnoreAttrs() {
            assertThatThrownBy(() -> permService.getUsersByPermissionAndCondition(
                    "finance:payment:approve", Map.of("amount", 10000000)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
