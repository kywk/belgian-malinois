package com.bpm.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code dynamicAssignee} 的挑人規則（#47）。
 *
 * <h2>本測試釘住的是「最後一哩」，不是底層服務的規則</h2>
 *
 * <p>走鏈、挑第一位可受理、權限判定都在 {@link OrgService}／
 * {@link BpmPermissionService}／{@link BpmQueryService} 裡，各自有單元測試。
 * 這裡只釘住本類別新增的三條：
 * <ol>
 *   <li><b>代理人代換</b>：解析出的人休假時回代理人，不是本人。</li>
 *   <li><b>找不到人時拋例外</b>：不回 null —— 回 null 會在 BPMN 建立
 *       一個對所有人都不可見的任務。</li>
 *   <li><b>系統身分不是人</b>：{@code system:*} 沒有人能簽，當場失敗。</li>
 * </ol>
 */
class DynamicAssigneeResolverTest {

    private OrgService orgService;
    private BpmPermissionService permService;
    private BpmQueryService bpmQueryService;
    private DynamicAssigneeResolver resolver;

    @BeforeEach
    void setUp() {
        orgService = Mockito.mock(OrgService.class);
        permService = Mockito.mock(BpmPermissionService.class);
        bpmQueryService = Mockito.mock(BpmQueryService.class);
        resolver = new DynamicAssigneeResolver(orgService, permService, bpmQueryService);
    }

    // ── managerAtLevel：第 N 階主管＋代理人 ─────────────────────────

    @Test
    @DisplayName("第 N 階主管有代理人 → 回代理人，不是休假中的本人")
    void managerAtLevelGoesToSubstitute() {
        when(orgService.getManagerAtLevel("user001", 2)).thenReturn("dir001");
        when(orgService.resolveEffective("dir001")).thenReturn("sub001");

        assertThat(resolver.managerAtLevel("user001", 2))
                .as("回本人會讓案件停在休假中的主管收件匣，代理人明明就在")
                .isEqualTo("sub001");
    }

    @Test
    @DisplayName("第 N 階主管沒有代理人 → 回本人")
    void managerAtLevelReturnsManagerWithoutSubstitute() {
        when(orgService.getManagerAtLevel("user001", 1)).thenReturn("mgr001");
        when(orgService.resolveEffective("mgr001")).thenReturn("mgr001");

        assertThat(resolver.managerAtLevel("user001", 1)).isEqualTo("mgr001");
    }

    @Test
    @DisplayName("完全沒有主管 → 例外往外丟，不回 null")
    void managerAtLevelWithoutManagerPropagates() {
        when(orgService.getManagerAtLevel("dir001", 1))
                .thenThrow(new IllegalStateException("dir001 在組織系統中沒有任何主管"));

        assertThatThrownBy(() -> resolver.managerAtLevel("dir001", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("沒有任何主管");
        verify(orgService, never()).resolveEffective(any());
    }

    @Test
    @DisplayName("解析結果是系統身分 → 例外，不派給一個沒有人能簽的身分")
    void managerAtLevelSystemActorFails() {
        when(orgService.getManagerAtLevel("user001", 1)).thenReturn("system:erp");
        when(orgService.resolveEffective("system:erp")).thenReturn("system:erp");

        assertThatThrownBy(() -> resolver.managerAtLevel("user001", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system:erp")
                .hasMessageContaining("沒有人能簽");
    }

    // ── firstAvailable：權限持有人第一位可受理 ──────────────────────

    @Test
    @DisplayName("有人在 → 回挑出來的那一位（挑人規則在 BpmPermissionService）")
    void firstAvailableReturnsPickedUser() {
        when(permService.getFirstAvailableUser("finance:payment:approve")).thenReturn("mgr001");

        assertThat(resolver.firstAvailable("finance:payment:approve")).isEqualTo("mgr001");
    }

    @Test
    @DisplayName("沒有任何持有人 → 例外，訊息指名權限碼與替代寫法")
    void firstAvailableWithoutHoldersFails() {
        when(permService.getFirstAvailableUser("no:such:code")).thenReturn(null);

        assertThatThrownBy(() -> resolver.firstAvailable("no:such:code"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no:such:code")
                .hasMessageContaining("沒有任何持有人")
                .hasMessageContaining("candidateUsers");
    }

    @Test
    @DisplayName("持有人清單回傳系統身分 → 例外，不派給一個沒有人能簽的身分")
    void firstAvailableSystemActorFails() {
        when(permService.getFirstAvailableUser("bpm:external:revision")).thenReturn("system:erp");

        assertThatThrownBy(() -> resolver.firstAvailable("bpm:external:revision"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system:erp")
                .hasMessageContaining("沒有人能簽");
    }

    // ── managerWithPermission：主管鏈＋權限 ─────────────────────────

    @Test
    @DisplayName("鏈上有人持有權限碼且有代理人 → 回代理人的")
    void managerWithPermissionGoesToSubstitute() {
        when(bpmQueryService.getManagerWithPermission("user001", "legal:contract:review"))
                .thenReturn("dir001");
        when(orgService.resolveEffective("dir001")).thenReturn("sub001");

        assertThat(resolver.managerWithPermission("user001", "legal:contract:review"))
                .isEqualTo("sub001");
    }

    @Test
    @DisplayName("鏈上沒有人持有權限碼 → 例外，訊息指名權限碼（不回 null）")
    void managerWithPermissionWithoutMatchFails() {
        when(bpmQueryService.getManagerWithPermission("user001", "no:such:code"))
                .thenReturn(null);

        assertThatThrownBy(() -> resolver.managerWithPermission("user001", "no:such:code"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no:such:code")
                .hasMessageContaining("沒有人持有");
        verify(orgService, never()).resolveEffective(any());
    }

    @Test
    @DisplayName("鏈上的人有代理人但代理人是系統身分 → 例外")
    void managerWithPermissionSystemSubstituteFails() {
        when(bpmQueryService.getManagerWithPermission("user001", "legal:contract:review"))
                .thenReturn("dir001");
        when(orgService.resolveEffective("dir001")).thenReturn("system:erp");

        assertThatThrownBy(() -> resolver.managerWithPermission("user001", "legal:contract:review"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system:erp")
                .hasMessageContaining("沒有人能簽");
    }
}
