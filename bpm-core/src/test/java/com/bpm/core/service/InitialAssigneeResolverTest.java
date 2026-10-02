package com.bpm.core.service;

import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第一個任務的受理人判斷（security-audit P2-7 施作時發現的缺陷）。
 *
 * <p>三種情況各自有明確的正確答案，而改動前只有一種是對的：
 * <ol>
 *   <li>人工發起 → 發起人的直屬主管。原本正確。</li>
 *   <li>外部系統指定受理人 → 就是那個人，<b>且不得查組織系統</b>。
 *       原本會對 {@code system:erp} 查主管（查不到，正式環境必然失敗），
 *       只因 mock 捏造 mgr001 而恰好等於預期值。</li>
 *   <li>外部系統只給候選群組 → <b>不指派</b>，留給群組成員認領。
 *       原本會指派給捏造出來的 mgr001，群組成員永遠認領不到。</li>
 * </ol>
 */
class InitialAssigneeResolverTest {

    private final OrgService orgService = mock(OrgService.class);
    private final InitialAssigneeResolver resolver = new InitialAssigneeResolver(orgService);

    /**
     * {@code resolveEffective} 的預設答案＝本人（沒有代理人）。
     *
     * <p>用 {@code doAnswer} 而不是 {@code when(...)}：{@code when} 的 stub
     * 呼叫本身會被 Mockito 記成一次互動，下面「只給候選群組不得查代理人」
     * 的 {@code verify(never())} 就會誤紅。個別測試要模擬代理人時，
     * 在測試方法內再 {@code when(...)} 覆寫（後註冊的 stub 勝出）。
     */
    @BeforeEach
    void defaultNoSubstitute() {
        doAnswer(inv -> inv.getArgument(0)).when(orgService).resolveEffective(anyString());
    }

    /**
     * 用 stub 的 {@code DelegateExecution} 呼叫真正的 resolve。
     *
     * <p>{@code null} 代表該變數<b>不存在</b>（人工發起就是這樣）——
     * 這正是第一版設計壞掉的地方，所以測試必須涵蓋變數缺席的情況。
     */
    private String resolve(String initiator, String firstAssignee, String firstGroups) {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable("initiator")).thenReturn(initiator);
        when(execution.getVariable(InitialAssigneeResolver.FIRST_ASSIGNEE_VAR)).thenReturn(firstAssignee);
        when(execution.getVariable(InitialAssigneeResolver.FIRST_GROUPS_VAR)).thenReturn(firstGroups);
        return resolver.resolve(execution);
    }

    @Test
    @DisplayName("人工發起 → 查發起人的直屬主管")
    void humanInitiatorUsesOrgLookup() {
        when(orgService.getDirectManager("user001")).thenReturn("mgr001");

        assertThat(resolve("user001", null, null)).isEqualTo("mgr001");
    }

    @Test
    @DisplayName("明確指定受理人時不得查組織系統")
    void explicitAssigneeSkipsOrgLookupEntirely() {
        // 「不得查」是這個案例的重點，不只是「回傳對的人」：
        // initiator 是 system:erp，查組織系統必然失敗（正式環境是 404）。
        assertThat(resolve("system:erp", "mgr001", null)).isEqualTo("mgr001");

        verify(orgService, never()).getDirectManager(anyString());
    }

    @Test
    @DisplayName("只給候選群組 → 回 null（不指派），讓群組成員認領")
    void candidateGroupsOnlyLeavesTaskUnassigned() {
        assertThat(resolve("system:erp", null, "finance,hr"))
                .as("回傳任何人都會把群組派工變成單人指派，群組成員就認領不到了")
                .isNull();

        verify(orgService, never()).getDirectManager(anyString());
    }

    @Test
    @DisplayName("同時給受理人與候選群組 → 受理人優先")
    void explicitAssigneeWinsOverGroups() {
        assertThat(resolve("system:erp", "mgr002", "finance")).isEqualTo("mgr002");
    }

    @Test
    @DisplayName("組織系統查不到發起人時必須拋錯，不得靜默回 null")
    void orgLookupFailurePropagates() {
        // 靜默回 null 會產生一個沒有受理人、也沒有候選群組的任務 ——
        // 流程看起來啟動成功，實際上停在沒有人看得到的地方。
        when(orgService.getDirectManager("E0912345"))
                .thenThrow(new IllegalStateException("組織系統查無此人"));

        assertThatThrownBy(() -> resolve("E0912345", null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("空字串與空白也算「未指定」")
    void blankValuesAreTreatedAsUnspecified() {
        // 呼叫端送 "" 或 " " 的意圖是「沒有指定」，不是「指派給一個空名字」。
        when(orgService.getDirectManager("user001")).thenReturn("mgr001");

        assertThat(resolve("user001", "", "")).isEqualTo("mgr001");
        assertThat(resolve("user001", "   ", "  ")).isEqualTo("mgr001");
    }

    // ── #5：代理人代換 ────────────────────────────────────────────

    @Test
    @DisplayName("#5 人工發起：主管有代理人 → 第一關派給代理人")
    void managerSubstituteIsUsed() {
        when(orgService.getDirectManager("user001")).thenReturn("mgr001");
        when(orgService.resolveEffective("mgr001")).thenReturn("user002");

        assertThat(resolve("user001", null, null))
                .as("主管休假時任務必須改派給代理人，不能停在休假期間的收件匣")
                .isEqualTo("user002");
    }

    @Test
    @DisplayName("#5 外部明確受理人有代理人 → 也代換（存在性驗證在呼叫端、代換之前）")
    void explicitAssigneeSubstituteIsUsed() {
        // ExternalActorGuard.requireKnownPerson 驗的是原始 firstTaskAssignee，
        // 而它排在流程啟動之前；本方法只做代換，不繞過那個驗證。
        when(orgService.resolveEffective("mgr001")).thenReturn("user002");

        assertThat(resolve("system:erp", "mgr001", null)).isEqualTo("user002");
    }

    @Test
    @DisplayName("#5 只給候選群組 → 不查代理人，仍回 null")
    void candidateGroupsOnlyDoesNotResolveSubstitute() {
        // 這一格沒有指名任何人，也就沒有「這個人休假」的對象。
        // 回傳任何代理人都會把群組派工變成單人指派。
        assertThat(resolve("system:erp", null, "finance,hr")).isNull();

        verify(orgService, never()).resolveEffective(anyString());
    }

    @Test
    @DisplayName("#5 鏈頂人員沒有主管 → 不查代理人（null 不是人）")
    void chainTopManagerIsNotResolved() {
        when(orgService.getDirectManager("user001")).thenReturn(null);

        assertThat(resolve("user001", null, null)).isNull();
        verify(orgService, never()).resolveEffective(anyString());
    }

    @Test
    @DisplayName("#5 effectiveAssignee(null) 原樣回 null，不查組織系統")
    void effectiveAssigneeNullPassesThrough() {
        assertThat(resolver.effectiveAssignee(null)).isNull();

        verify(orgService, never()).resolveEffective(anyString());
    }

    @Test
    @DisplayName("putIfPresent 不得寫入 null —— 那會讓變數以 null 存在而非缺席")
    void putIfPresentOnlyWritesGivenValues() {
        var vars = new HashMap<String, Object>();
        InitialAssigneeResolver.putIfPresent(vars, null, null);
        assertThat(vars).as("沒有指定時不該產生任何變數").isEmpty();

        var given = new HashMap<String, Object>();
        InitialAssigneeResolver.putIfPresent(given, "mgr001", "finance");
        assertThat(given)
                .containsEntry(InitialAssigneeResolver.FIRST_ASSIGNEE_VAR, "mgr001")
                .containsEntry(InitialAssigneeResolver.FIRST_GROUPS_VAR, "finance");
    }
}
