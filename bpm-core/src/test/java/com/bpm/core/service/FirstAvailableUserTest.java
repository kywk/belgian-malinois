package com.bpm.core.service;

import com.bpm.core.client.PermRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@code getFirstAvailableUser} 的挑人規則（2026-09-29 決策：全部不在時派給第一位的代理人）。
 */
class FirstAvailableUserTest {

    private PermRestClient permClient;
    private OrgService orgService;
    private BpmPermissionService permService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        permClient = Mockito.mock(PermRestClient.class);
        orgService = Mockito.mock(OrgService.class);
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        // 快取一律 miss：每次都走 permClient，測的是挑人規則而不是快取。
        when(redis.opsForValue()).thenReturn(Mockito.mock(ValueOperations.class));
        permService = new BpmPermissionService(permClient, redis, orgService);
        when(permClient.getUsersByPermission("hr:leave:approve")).thenReturn(List.of("mgr001", "mgr002"));
    }

    @Test
    @DisplayName("有人在 → 派給第一個在的人")
    void picksFirstAvailable() {
        when(orgService.isUserAvailable("mgr001")).thenReturn(false);
        when(orgService.isUserAvailable("mgr002")).thenReturn(true);
        assertThat(permService.getFirstAvailableUser("hr:leave:approve")).isEqualTo("mgr002");
    }

    @Test
    @DisplayName("全部不在 → 派給第一位的代理人，而不是休假中的本人")
    void allAwayGoesToFirstHoldersSubstitute() {
        when(orgService.isUserAvailable("mgr001")).thenReturn(false);
        when(orgService.isUserAvailable("mgr002")).thenReturn(false);
        when(orgService.resolveEffective("mgr001")).thenReturn("sub001");
        assertThat(permService.getFirstAvailableUser("hr:leave:approve"))
                .as("派給休假中的本人，案件會卡到他回來；代理人明明就在")
                .isEqualTo("sub001");
    }

    @Test
    @DisplayName("沒有任何持有人 → null")
    void noHoldersReturnsNull() {
        when(permClient.getUsersByPermission("none")).thenReturn(List.of());
        assertThat(permService.getFirstAvailableUser("none")).isNull();
    }
}
