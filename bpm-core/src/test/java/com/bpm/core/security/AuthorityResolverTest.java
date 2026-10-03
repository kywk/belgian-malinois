package com.bpm.core.security;

import com.bpm.core.service.BpmPermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthorityResolver} 的單元測試：身分 → authorities 的唯一轉換點。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>兩種失敗都不會拋例外，只會讓「對的人被擋」或「錯的人被放行」：
 * <ul>
 *   <li><b>通配 {@code *} 沒轉成 {@code ROLE_ADMIN}</b> —— 產生一個名叫
 *       {@code *} 的 authority，而 {@code hasRole('ADMIN')} 不會命中它，
 *       結果是擁有全部權限的管理員被拒絕存取。開發時通常就用管理員在測，
 *       所以這個 bug 特別容易在驗收時「看起來正常」。</li>
 *   <li><b>權限中心故障時拋例外</b> —— 使用者看到 401（帳號有問題）而不是
 *       403（權限不足），排查方向完全被誤導。</li>
 * </ul>
 *
 * <p>另外，Security 7 的 {@code FACTOR_*} 認證因子會被
 * {@code isPermissionCode} 排除 —— 這條規則同時被 {@code MeController}
 * 與 {@code CandidateGroupMembership} 使用，把因子當權限碼會讓
 * 「我有哪些權限」與「我屬於哪些群組」都多出一個假項目。
 */
class AuthorityResolverTest {

    private final BpmPermissionService permissionService = mock(BpmPermissionService.class);
    private final AuthorityResolver resolver = new AuthorityResolver(permissionService);

    private static List<String> names(java.util.Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Nested
    @DisplayName("fromJwtRoles：IdP 的 roles claim 優先")
    class FromJwtRoles {

        @Test
        @DisplayName("null 或空清單 → 空集合（呼叫端據此決定要不要退回權限中心）")
        void emptyClaimYieldsEmpty() {
            assertThat(resolver.fromJwtRoles(null)).isEmpty();
            assertThat(resolver.fromJwtRoles(List.of())).isEmpty();
        }

        @Test
        @DisplayName("通配 * → ROLE_ADMIN，不可留下字面的 * authority")
        void wildcardBecomesAdminRole() {
            assertThat(names(resolver.fromJwtRoles(List.of("*"))))
                    .containsExactly(AuthorityResolver.ROLE_ADMIN);
        }

        @Test
        @DisplayName("admin（大小寫不拘）→ ROLE_ADMIN")
        void adminClaimBecomesAdminRole() {
            assertThat(names(resolver.fromJwtRoles(List.of("admin"))))
                    .containsExactly(AuthorityResolver.ROLE_ADMIN);
            assertThat(names(resolver.fromJwtRoles(List.of("ADMIN"))))
                    .containsExactly(AuthorityResolver.ROLE_ADMIN);
        }

        @Test
        @DisplayName("一般角色同時產生 ROLE_X 與原字串，讓 hasRole 與 hasAuthority 都能命中")
        void roleProducesBothForms() {
            assertThat(names(resolver.fromJwtRoles(List.of("manager"))))
                    .containsExactly("ROLE_MANAGER", "manager");
        }

        @Test
        @DisplayName("claim 已帶 ROLE_ 前綴時不重複加（否則 hasRole 找不到）")
        void rolePrefixIsNotDoubled() {
            assertThat(names(resolver.fromJwtRoles(List.of("ROLE_MANAGER"))))
                    .containsExactly("ROLE_MANAGER");
        }

        @Test
        @DisplayName("null／空白項目略過，值會 trim")
        void blankEntriesAreSkipped() {
            assertThat(names(resolver.fromJwtRoles(java.util.Arrays.asList(null, " ", "mgr"))))
                    .containsExactly("ROLE_MGR", "mgr");
        }

        @Test
        @DisplayName("重複角色只留一份，且保留原順序")
        void duplicatesAreRemoved() {
            assertThat(names(resolver.fromJwtRoles(List.of("mgr", "mgr", "dir"))))
                    .containsExactly("ROLE_MGR", "mgr", "ROLE_DIR", "dir");
        }
    }

    @Nested
    @DisplayName("fromPermissionCentre：權限中心退回路徑")
    class FromPermissionCentre {

        @Test
        @DisplayName("null／空白 userId → 空集合，且不呼叫外部服務")
        void blankUserSkipsLookup() {
            assertThat(resolver.fromPermissionCentre(null)).isEmpty();
            assertThat(resolver.fromPermissionCentre("  ")).isEmpty();
            verify(permissionService, never()).getUserPermissions(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("權限中心故障 → 空集合而非拋例外（降級為已認證但無權限，不是 401）")
        void serviceFailureDegradesToEmpty() {
            when(permissionService.getUserPermissions("user001"))
                    .thenThrow(new RuntimeException("perm service down"));

            assertThat(resolver.fromPermissionCentre("user001")).isEmpty();
        }

        @Test
        @DisplayName("通配 * → ROLE_ADMIN")
        void wildcardBecomesAdminRole() {
            when(permissionService.getUserPermissions("admin001")).thenReturn(List.of("*"));

            assertThat(names(resolver.fromPermissionCentre("admin001")))
                    .containsExactly(AuthorityResolver.ROLE_ADMIN);
        }

        @Test
        @DisplayName("權限碼逐字保留（含冒號與大小寫），不映射成粗粒度角色")
        void permissionCodesAreKeptVerbatim() {
            when(permissionService.getUserPermissions("dir001"))
                    .thenReturn(List.of("audit:log:read", "bpm:form:design"));

            assertThat(names(resolver.fromPermissionCentre("dir001")))
                    .containsExactly("audit:log:read", "bpm:form:design");
        }

        @Test
        @DisplayName("null／空白項目濾掉，重複去重")
        void blanksAndDuplicatesAreRemoved() {
            when(permissionService.getUserPermissions("user001"))
                    .thenReturn(java.util.Arrays.asList(null, " ", "hr:leave:approve", "hr:leave:approve"));

            assertThat(names(resolver.fromPermissionCentre("user001")))
                    .containsExactly("hr:leave:approve");
        }
    }

    @Nested
    @DisplayName("isPermissionCode：排除框架標記")
    class IsPermissionCode {

        @Test
        @DisplayName("一般權限碼／候選群組名 → true")
        void businessStringsAreCodes() {
            assertThat(AuthorityResolver.isPermissionCode("audit:log:read")).isTrue();
            assertThat(AuthorityResolver.isPermissionCode("dept001")).isTrue();
        }

        @Test
        @DisplayName("ROLE_ 與 FACTOR_ 前綴不是權限碼（Security 7 的認證因子會出現在 authorities 裡）")
        void frameworkMarkersAreNotCodes() {
            assertThat(AuthorityResolver.isPermissionCode("ROLE_ADMIN")).isFalse();
            assertThat(AuthorityResolver.isPermissionCode("FACTOR_BEARER")).isFalse();
        }

        @Test
        @DisplayName("null 與空白 → false，不得讓空字串變成一個權限碼")
        void nullAndBlankAreNotCodes() {
            assertThat(AuthorityResolver.isPermissionCode(null)).isFalse();
            assertThat(AuthorityResolver.isPermissionCode("  ")).isFalse();
        }
    }
}
