package com.bpm.core.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #71：候選群組的計算，以及 {@code processInstanceIdIn} 的參數分批。
 *
 * <p>這是純單元測試（不起容器）：兩者都是「算錯了不會立刻出事、
 * 只會在特定資料量或特定故障下才出事」的邏輯 —— 正是整合測試最難覆蓋、
 * 而出事時最難排查的兩處。
 */
class CandidateGroupMembershipTest {

    private final OrgService orgService = mock(OrgService.class);
    private final BpmPermissionService permissionService = mock(BpmPermissionService.class);
    private final CandidateGroupMembership membership =
            new CandidateGroupMembership(orgService, permissionService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user001", null,
                        java.util.Arrays.stream(authorities)
                                .map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    @DisplayName("群組 = 部門代碼 ∪ 權限碼 ∪ 非 ROLE_ 的 authority")
    void unionOfDeptPermissionsAndAuthorities() {
        when(orgService.getDeptId("user001")).thenReturn("dept001");
        when(permissionService.getUserPermissions("user001"))
                .thenReturn(List.of("hr:leave:approve", "finance:payment:approve"));
        authenticate("ROLE_USER", "hr:leave:approve");

        var groups = membership.groupsOf("user001");

        assertThat(groups).contains("dept001", "hr:leave:approve", "finance:payment:approve");
        assertThat(groups)
                .as("ROLE_ 前綴的是 Spring 的角色表示法，不是群組名稱")
                .doesNotContain("ROLE_USER");
    }

    @Test
    @DisplayName("通配權限持有者不會因此多出一個叫做 * 的群組")
    void wildcardIsNotAGroup() {
        when(orgService.getDeptId("admin001")).thenReturn("admin");
        when(permissionService.getUserPermissions("admin001")).thenReturn(List.of("*"));

        assertThat(membership.groupsOf("admin001")).containsExactly("admin");
    }

    @Test
    @DisplayName("組織系統故障時待辦仍可用（只少掉群組任務），不得整個 500")
    void orgFailureDegradesInsteadOfBreakingTheInbox() {
        // orgService.getDeptId 對 fail-closed 的 fixture 會丟例外（未知使用者）；
        // 連線失敗時也是例外。兩者都不該讓整個收件匣不可用 ——
        // 待辦的主要內容（指派給我、我是候選人）完全不依賴外部系統。
        when(orgService.getDeptId(anyString()))
                .thenThrow(new RuntimeException("org service down"));
        when(permissionService.getUserPermissions("user001"))
                .thenReturn(List.of("hr:leave:approve"));

        assertThatCode(() -> membership.groupsOf("user001")).doesNotThrowAnyException();
        assertThat(membership.groupsOf("user001")).containsExactly("hr:leave:approve");
    }

    @Test
    @DisplayName("兩個外部系統同時故障 → 空集合（端點仍回 200，不查群組）")
    void bothFailuresYieldEmptySet() {
        when(orgService.getDeptId(anyString()))
                .thenThrow(new RuntimeException("org service down"));
        when(permissionService.getUserPermissions(anyString()))
                .thenThrow(new RuntimeException("perm service down"));

        assertThat(membership.groupsOf("user001")).isEmpty();
    }

    @Test
    @DisplayName("空白或 null 的回傳值不得變成「一個空字串群組」")
    void blankValuesAreFiltered() {
        when(orgService.getDeptId("user001")).thenReturn("  ");
        when(permissionService.getUserPermissions("user001")).thenReturn(List.of("", "  "));

        assertThat(membership.groupsOf("user001")).isEmpty();
        assertThat(membership.groupsOf(null)).isEmpty();
        assertThat(membership.groupsOf("  ")).isEmpty();
    }

    // ── processInstanceIdIn 的分批 ────────────────────────────────

    @Test
    @DisplayName("id 清單必須分批：MSSQL 的參數上限 2100，超過就整個端點 500")
    void idsArePartitionedBelowTheMssqlParameterLimit() {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 2500; i++) ids.add("pid-" + i);

        var batches = ProcessInvolvementService.partition(ids);

        assertThat(batches).hasSize(3);
        assertThat(batches).allSatisfy(b -> assertThat(b).hasSizeLessThanOrEqualTo(1000));
        // 分批不能漏也不能重 —— 漏掉的案件會從「我參與的」清單裡消失，
        // 而那正是 DuplicateApprovalFilterTest 記錄過的「靜默消失」型 bug。
        assertThat(batches.stream().flatMap(List::stream).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(ids);
    }

    @Test
    @DisplayName("空集合不得產生批次：IN () 會讓 MSSQL 直接報錯")
    void emptyIdsProduceNoBatch() {
        assertThat(ProcessInvolvementService.partition(Set.of())).isEmpty();
        assertThat(ProcessInvolvementService.partition(null)).isEmpty();
    }
}
