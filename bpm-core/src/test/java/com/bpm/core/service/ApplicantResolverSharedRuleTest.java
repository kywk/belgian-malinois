package com.bpm.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ApplicantResolver#resolveApplicant(String, String)}（#3 催辦權的共用入口）。
 *
 * <h2>為什麼既有 {@code ApplicantResolverTest} 之外還要這一組</h2>
 *
 * <p>{@code ApplicantResolverTest} 釘住的是 BPMN 入口
 * {@link ApplicantResolver#resolve(org.flowable.engine.delegate.DelegateExecution)}
 * 的行為。催辦走的是另一個入口（呼叫端已經有兩個字串），
 * 而那個入口有一個新的出錯方式：<b>參數順序</b>。
 * {@code resolveApplicant(initiator, onBehalfOf)} 寫反的話，
 * 代發案件的 {@code initiator}（{@code system:erp}）會被當成內容值、
 * 員工反而被當成「系統身分」再查一次權限碼 —— 而回傳值可能剛好還是對的，
 * 只有部分案件受影響。兩個入口共用同一份規則，但順序要在這裡釘住。
 *
 * <p>另外兩條「第三段拋錯」的形狀（查無受理人／回傳系統身分）在催辦路徑上
 * 是 fail-closed 的來源（{@code TaskController} 據此拒絕），所以在單元
 * 層直接斷言它們，而不是只靠整合測試的副作用。
 */
class ApplicantResolverSharedRuleTest {

    private final BpmPermissionService permService = mock(BpmPermissionService.class);
    private final ApplicantResolver resolver = new ApplicantResolver(permService);

    // ── 三段順序（參數順序即政策）──────────────────────────────────

    @Test
    @DisplayName("#3：第一個參數是 onBehalfOf —— 有值時它優先，且不查權限中心")
    void onBehalfOfIsTheFirstParameterAndWins() {
        // 第二個參數刻意是另一個人：若兩個參數被調換，答案會是 user001。
        assertThat(resolver.resolveApplicant("user002", "user001")).isEqualTo("user002");
        verifyNoInteractions(permService);
    }

    @Test
    @DisplayName("#3：沒有 onBehalfOf 時用 initiator（人工發起，不查權限中心）")
    void humanInitiatorIsUsedWithoutPermissionLookup() {
        assertThat(resolver.resolveApplicant(null, "user001")).isEqualTo("user001");
        assertThat(resolver.resolveApplicant("", "user001")).isEqualTo("user001");
        verifyNoInteractions(permService);
    }

    @Test
    @DisplayName("#3：initiator 是系統身分 → 權限碼的受理人")
    void systemInitiatorResolvesToTheConfiguredHandler() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("dir001");

        assertThat(resolver.resolveApplicant(null, "system:erp")).isEqualTo("dir001");
        verify(permService).getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION);
    }

    // ── 第三段的 fail-closed 形狀（催辦的 403／503 來源）────────────

    @Test
    @DisplayName("#3：查無受理人 → IllegalStateException，不得回 null")
    void noHandlerThrows() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveApplicant(null, "system:erp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ApplicantResolver.PERM_EXTERNAL_REVISION);
    }

    @Test
    @DisplayName("#3：權限中心回系統身分 → IllegalStateException（不得回一個沒有人能當的身分）")
    void systemHandlerThrows() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("system:other");

        assertThatThrownBy(() -> resolver.resolveApplicant(null, "system:erp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system:other");
    }

    @Test
    @DisplayName("#3：onBehalfOf 是系統身分時不採用（與 resolve(execution) 同一條防禦）")
    void systemActorOnBehalfOfIsNotAccepted() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("dir001");

        assertThat(resolver.resolveApplicant("system:evil", "system:erp")).isEqualTo("dir001");
    }
}
