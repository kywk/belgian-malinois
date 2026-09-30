package com.bpm.core.service;

import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 補件關卡的受理人判斷（#83）。
 *
 * <h2>這個工項最危險的失敗型態是「靜默卡死」，所以每一條斷言都在問同一個問題</h2>
 *
 * <p>缺陷期間，外部系統發起 → 主管退回 → 補件關卡的 assignee 是
 * {@code system:erp}。那個流程<b>不會拋任何例外</b>、不會有 5xx、
 * 稽核看起來完全正常 —— 它只是永遠沒有人能簽。
 *
 * <p>因此本測試組刻意<b>不</b>只斷言「回傳某個值」，而是同時斷言：
 * <ol>
 *   <li>回傳值<b>不是</b> {@code system:*}（這是缺陷的形狀本身），而且</li>
 *   <li>在該情形下<b>根本不會去查權限中心</b> ——
 *       一個「什麼都不做」的實作（永遠回 null、或永遠回 initiator）
 *       會讓「回傳值不是 system:*」這條失敗，但不會讓「沒有呼叫」這條失敗。
 *       兩條必須成組，否則斷言分辨不出「修好了」與「整條規則壞掉」。</li>
 * </ol>
 * 端到端的版本（真的把流程跑一遍、真的用 HTTP 簽）在
 * {@code com.bpm.core.security.SystemInitiatorRevisionTest}。
 */
class ApplicantResolverTest {

    private final BpmPermissionService permService = mock(BpmPermissionService.class);
    private final ApplicantResolver resolver = new ApplicantResolver(permService);

    /**
     * 用 stub 的 {@code DelegateExecution} 呼叫真正的 resolve。
     *
     * <p>{@code null} 代表該變數<b>不存在</b> —— 人工發起時 {@code onBehalfOf}
     * 就是這樣。參數是 {@code execution} 而非三個變數正是為了讓這種情況
     * 不會炸掉（見 {@code InitialAssigneeResolverTest} 記載的第一次設計）。
     */
    private String resolve(String initiator, String onBehalfOf) {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable("initiator")).thenReturn(initiator);
        when(execution.getVariable(InitialAssigneeResolver.ON_BEHALF_OF_VAR)).thenReturn(onBehalfOf);
        return resolver.resolve(execution);
    }

    // ── 第一段：代員工發起（backlog #68c 已定調的規則）──────────────

    @Test
    @DisplayName("#68c：onBehalfOf 有值 → 派給那位員工（且不查權限中心）")
    void onBehalfOfWinsOverSystemInitiator() {
        String handler = resolve("system:erp", "user001");

        assertThat(handler).isEqualTo("user001");
        assertThat(handler)
                .as("initiator 是 system:erp；派給它就是 #83 的缺陷本身")
                .doesNotStartWith("system:");
        verifyNoInteractions(permService);
    }

    @Test
    @DisplayName("#83：onBehalfOf 必須壓過 initiator，即使 initiator 恰好是個人")
    void onBehalfOfAlsoWinsOverAHumanInitiator() {
        // 人工發起時 ProcessController 不允許夾帶 onBehalfOf，所以這個組合
        // 在正常流程裡不會出現。釘住優先序是為了讓「第一段」這個決定
        // 不會被後來的人以為是「第二段的else分支」而調換。
        assertThat(resolve("user001", "user002")).isEqualTo("user002");
        verifyNoInteractions(permService);
    }

    @Test
    @DisplayName("onBehalfOf 是系統身分時不得採用（防禦：不得用它繞過第三段）")
    void systemActorOnBehalfOfIsNotAccepted() {
        // 正常情況下不可能（ExternalApiController 會用組織系統驗證），
        // 但若哪一天新增了一條寫入路徑，這一條會擋住。
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("dir001");

        assertThat(resolve("system:erp", "system:evil"))
                .as("派給 system:evil 等於製造一個沒有人能簽的任務")
                .isEqualTo("dir001");
    }

    // ── 第二段：人工發起（既有行為，必須完全不變）────────────────────

    @Test
    @DisplayName("人工發起 → 派給 initiator 本人（且不查權限中心）")
    void humanInitiatorIsUnchanged() {
        String handler = resolve("user001", null);

        assertThat(handler).isEqualTo("user001");
        // 人工發起是補件關卡最常見的情況；每次都去查權限中心等於在簽核交易內
        // 平白多一次 HTTP self-call（security-audit P1-10）。所以「不查」
        // 本身就是這個案例要保護的東西，與回傳值同等重要。
        verifyNoInteractions(permService);
    }

    @Test
    @DisplayName("initiator 的大小寫不影響判定（SYSTEM: 開頭也算系統身分）")
    void systemPrefixIsCaseInsensitive() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("dir001");

        // firstTaskAssignee 是外部系統自由指定的字串且沒有驗證，
        // 所以大小寫不同的系統身分真的可能被寫進來（見 ExternalActorIdentity）。
        assertThat(resolve("SYSTEM:erp", null)).isEqualTo("dir001");
        verify(permService).getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION);
    }

    // ── 第三段：沒有自然人申請人 → 系統受理人（#83 的本體）──────────

    @Test
    @DisplayName("#83：發起人是 system:<id> 且無 onBehalfOf → 派給權限碼的受理人")
    void systemInitiatorFallsBackToConfiguredHandler() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("dir001");

        String handler = resolve("system:erp", null);

        assertThat(handler).isEqualTo("dir001");
        assertThat(handler).doesNotStartWith("system:");
        verify(permService).getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION);
    }

    @Test
    @DisplayName("#83：initiator 缺席或空白時同樣走第三段（不得回 null 製造無主任務）")
    void missingInitiatorAlsoFallsBack() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("dir001");

        assertThat(resolve(null, null)).isEqualTo("dir001");
        assertThat(resolve("", null)).isEqualTo("dir001");
        assertThat(resolve("   ", null)).isEqualTo("dir001");
    }

    @Test
    @DisplayName("#83：找不到受理人必須拋錯，不得靜默回 null")
    void missingHandlerMustThrowRatherThanReturnNull() {
        // 回 null 會讓 Flowable 建立一個沒有 assignee、也沒有候選人的任務 ——
        // 那是換一種方式製造同一個靜默卡死。見 ApplicantResolver 類別註解。
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn(null);

        assertThatThrownBy(() -> resolve("system:erp", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ApplicantResolver.PERM_EXTERNAL_REVISION)
                .as("訊息必須指名缺的是哪個權限碼，否則排查的人只會看到一個例外");
    }

    @Test
    @DisplayName("#83：權限碼沒有持有人時拋錯（fail-closed）")
    void emptyHandlerListMustThrow() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("");

        assertThatThrownBy(() -> resolve("system:erp", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("#83：權限中心故障必須往外傳，不得吞掉後回 null")
    void permissionServiceFailurePropagates() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenThrow(new IllegalStateException("權限中心 503"));

        // catch 成 null 等於把「查不到」變成「沒有人」，那正是靜默卡死。
        assertThatThrownBy(() -> resolve("system:erp", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("權限中心");
    }

    @Test
    @DisplayName("#83：權限中心回系統身分時必須拋錯（不得派給沒有人能持有的身分）")
    void systemActorHandlerMustThrow() {
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("system:other");

        assertThatThrownBy(() -> resolve("system:erp", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system:other");
    }

    // ── 決定順序（不是「三個獨立分支」）───────────────────────────

    @Test
    @DisplayName("onBehalfOf 有值時短路：連 initiator 都不讀（更不會碰權限中心）")
    void onBehalfOfShortCircuitsBeforeReadingInitiator() {
        // 短路不只是省一個變數讀取：它同時把「第二段」與「第三段」整組排除，
        // 因此一個日後在第二、三段加入副作用的改動不會意外影響代發的案件。
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable(InitialAssigneeResolver.ON_BEHALF_OF_VAR)).thenReturn("user001");

        assertThat(resolver.resolve(execution)).isEqualTo("user001");

        InOrder order = inOrder(execution);
        order.verify(execution).getVariable(InitialAssigneeResolver.ON_BEHALF_OF_VAR);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(permService);
    }

    @Test
    @DisplayName("決定順序：先 onBehalfOf、再 initiator，最後才碰權限中心")
    void decisionOrderIsOnBehalfOfThenInitiatorThenPermissionCentre() {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable(InitialAssigneeResolver.ON_BEHALF_OF_VAR)).thenReturn(null);
        when(execution.getVariable("initiator")).thenReturn("system:erp");
        when(permService.getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION))
                .thenReturn("dir001");

        assertThat(resolver.resolve(execution)).isEqualTo("dir001");

        // 權限中心必須是最後一步：前兩段都不碰外部系統，
        // 所以只有「系統發起且未代發」這一種案件會產生交易內的 HTTP self-call。
        InOrder order = inOrder(execution, permService);
        order.verify(execution).getVariable(InitialAssigneeResolver.ON_BEHALF_OF_VAR);
        order.verify(execution).getVariable("initiator");
        order.verify(permService).getFirstAvailableUser(ApplicantResolver.PERM_EXTERNAL_REVISION);
    }

    @Test
    @DisplayName("決定權限碼是常數且與 BPMN 无关（避免同一個碼散在兩處）")
    void permissionCodeIsASingleConstant() {
        // 這個碼同時出現在 ApplicantResolver 與 MockPermController 的 fixture。
        // 若有人只改一邊，外部系統發起的案件會在「退回」時才爆炸。
        assertThat(ApplicantResolver.PERM_EXTERNAL_REVISION).isEqualTo("bpm:external:revision");
    }
}
