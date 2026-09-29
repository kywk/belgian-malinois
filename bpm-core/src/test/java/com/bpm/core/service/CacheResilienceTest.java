package com.bpm.core.service;

import com.bpm.core.client.OrgRestClient;
import com.bpm.core.client.PermRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Redis 故障時的失效模式（security-audit P1-10 的快取部分）。
 *
 * <p><b>問題。</b>{@code OrgService} 與 {@code BpmPermissionService} 的每個方法
 * 第一件事就是 {@code redis.opsForValue().get(key)}，而且<b>沒有 try/catch</b>。
 * Redis 不可用時，連「直接去問組織系統」的退路都走不到 ——
 * 所有 assignee 解析在讀快取那一行就爆，任務建立整批失敗。
 *
 * <p>也就是說<b>快取層變成了比被快取的系統更關鍵的單點</b>。
 * 快取的用途是加速；它掛掉應該退化成「每次都問來源」，
 * 而不是讓整個功能不可用。
 */
class CacheResilienceTest {

    private OrgRestClient orgClient;
    private PermRestClient permClient;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;

    private OrgService orgService;
    private BpmPermissionService permService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orgClient = Mockito.mock(OrgRestClient.class);
        permClient = Mockito.mock(PermRestClient.class);
        redis = Mockito.mock(StringRedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);

        when(redis.opsForValue()).thenReturn(ops);
        // Redis 全面故障：讀、寫、刪都拋
        when(ops.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        Mockito.doThrow(new RedisConnectionFailureException("redis down"))
                .when(ops).set(anyString(), anyString(), any(java.time.Duration.class));
        when(redis.delete(anyString()))
                .thenThrow(new QueryTimeoutException("redis down"));

        orgService = new OrgService(orgClient, redis);
        permService = new BpmPermissionService(permClient, redis, orgService);
    }

    @Test
    @DisplayName("Redis 掛掉時，取直屬主管必須退化為直接查組織系統")
    void directManagerFallsBackToSource() {
        when(orgClient.getManager("user001")).thenReturn("mgr001");

        assertThat(orgService.getDirectManager("user001"))
                .as("快取故障不得讓 assignee 解析失敗 —— 否則任務建立整批失敗")
                .isEqualTo("mgr001");
    }

    @Test
    @DisplayName("Redis 掛掉時，主管鏈與部門仍必須可用")
    void chainAndDeptFallBackToSource() {
        when(orgClient.getManagerChain("user001", 5)).thenReturn(List.of("mgr001", "dir001"));
        when(orgClient.getDepartment("user001")).thenReturn("dept001");
        when(orgClient.getSubstitute("user001")).thenReturn(null);

        assertThat(orgService.getManagerChain("user001", 5)).containsExactly("mgr001", "dir001");
        assertThat(orgService.getDeptId("user001")).isEqualTo("dept001");
        assertThat(orgService.resolveEffective("user001")).isEqualTo("user001");
        assertThat(orgService.isUserAvailable("user001")).isTrue();
    }

    @Test
    @DisplayName("Redis 掛掉時，權限判定仍必須可用")
    void permissionsFallBackToSource() {
        when(permClient.getUserPermissions("mgr001"))
                .thenReturn(List.of("finance:payment:approve"));
        when(permClient.getUsersByPermission("finance:payment:approve"))
                .thenReturn(List.of("mgr001", "dir001"));

        assertThat(permService.hasPermission("mgr001", "finance:payment:approve")).isTrue();
        assertThat(permService.hasPermission("mgr001", "hr:leave:approve")).isFalse();
        assertThat(permService.getUsersByPermission("finance:payment:approve"))
                .containsExactly("mgr001", "dir001");
    }

    @Test
    @DisplayName("Redis 掛掉時，快取失效呼叫不得拋例外")
    void invalidationDoesNotThrow() {
        // 失效失敗只會讓舊值活到 TTL 到期，不應該讓呼叫端的請求失敗。
        //
        // ⚠️ type 原本傳的是 "org"。那個值不在任何範圍定義裡 ——
        // 當時 type 參數完全沒有被讀取，所以送什麼都沒差（security-audit P2-8）。
        // 這一行本身就是那個缺陷的證據：測試作者以為它有意義。
        // "org" 與端點路徑 /cache-invalidate/org 重複，語意上不是範圍值。
        assertThatCode(() -> orgService.invalidateCache(List.of("user001"), "all"))
                .doesNotThrowAnyException();
        assertThatCode(() -> orgService.invalidateDeptMembers(List.of("dept001")))
                .doesNotThrowAnyException();
        assertThatCode(() -> permService.invalidateCache(
                List.of("user001"), List.of("finance:payment:approve")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 掛掉時，未知的 type 仍必須被拒絕（驗證發生在碰 Redis 之前）")
    void unknownTypeIsRejectedEvenWhenRedisIsDown() {
        // 參數驗證不該依賴 Redis 是否健康 —— 設定錯誤要在同一個地方以同一種
        // 方式回報，不論基礎設施狀態如何。
        assertThatCode(() -> orgService.invalidateCache(List.of("user001"), "org"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
