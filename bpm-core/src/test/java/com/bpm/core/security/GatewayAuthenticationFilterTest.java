package com.bpm.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GatewayAuthenticationFilter} 的單元測試：server 之間的身分注入。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>這條路徑的輸入（{@code X-User-Id}／{@code X-System-Id}）是<b>可自由填寫的
 * 標頭</b>，所以「密鑰比對」與「來源白名單」的每一個邊界都直接等於
 * 「能不能偽造身分」。整合測試（{@code AuthenticationTest}）驗的是
 * 「端點有接上這條鏈」；這裡補的是只有單元層看得到的形狀：
 * 密鑰未設定、白名單是 {@code [""]}（{@code @Value} 綁定 List 的陷阱）、
 * 前綴比對、以及「JWT 已認證時不得被覆蓋」。
 *
 * <p>純 servlet mock，不起容器 —— 這些分支在整合測試裡要湊齊成本很高，
 * 而每一條都對應一個真實的偽造路徑。
 */
class GatewayAuthenticationFilterTest {

    private static final String SECRET = "test-gateway-secret";

    private final AuthorityResolver authorityResolver = mock(AuthorityResolver.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private GatewayAuthenticationFilter filter(boolean enabled, String secret, List<String> proxies) {
        return new GatewayAuthenticationFilter(enabled, secret, proxies, authorityResolver);
    }

    private static MockHttpServletRequest request(String secret, String user, String system, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
        if (secret != null) request.addHeader(GatewayAuthenticationFilter.SECRET_HEADER, secret);
        if (user != null) request.addHeader(GatewayAuthenticationFilter.USER_HEADER, user);
        if (system != null) request.addHeader(GatewayAuthenticationFilter.SYSTEM_HEADER, system);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    private static String callerId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    @Nested
    @DisplayName("啟用開關與密鑰比對")
    class Secret {

        @Test
        @DisplayName("未啟用 → 不注入身分（預設關閉，漏設的結果是路徑不可用而不是人人可偽造）")
        void disabledDoesNothing() throws Exception {
            filter(false, SECRET, List.of()).doFilter(
                    request(SECRET, "user001", null, "10.0.0.1"), response(), new MockFilterChain());

            assertThat(callerId()).isNull();
            verifyNoInteractions(authorityResolver);
        }

        @Test
        @DisplayName("未帶密鑰標頭 → 不注入身分，且不查權限中心")
        void missingSecretIsNotAnIdentity() throws Exception {
            filter(true, SECRET, List.of()).doFilter(
                    request(null, "admin001", null, "10.0.0.1"), response(), new MockFilterChain());

            assertThat(callerId()).isNull();
            verifyNoInteractions(authorityResolver);
        }

        @Test
        @DisplayName("密鑰錯誤 → 不注入身分（密鑰錯與沒帶對外表現相同，不洩漏接近程度）")
        void wrongSecretIsNotAnIdentity() throws Exception {
            filter(true, SECRET, List.of()).doFilter(
                    request("wrong", "admin001", null, "10.0.0.1"), response(), new MockFilterChain());

            assertThat(callerId()).isNull();
        }

        @Test
        @DisplayName("未設定共享密鑰（null）→ 一律不通過，即使呼叫端也送空字串")
        void unsetSecretNeverMatches() throws Exception {
            filter(true, null, List.of()).doFilter(
                    request("", "admin001", null, "10.0.0.1"), response(), new MockFilterChain());
            assertThat(callerId()).isNull();

            // 空白密鑰（"   "）由 JwtSecurityValidator 在啟動時擋掉；
            // 這裡不測它 —— 測了等於把「啟動時不可能出現的設定」當成契約。
        }
    }

    @Nested
    @DisplayName("身分來源標頭與 authorities")
    class Identity {

        @Test
        @DisplayName("X-User-Id → 成為 principal，並取得 ROLE_GATEWAY 與權限中心的權限")
        void userHeaderBecomesIdentity() throws Exception {
            when(authorityResolver.fromPermissionCentre("dir001"))
                    .thenReturn(List.of(new SimpleGrantedAuthority("audit:log:read")));

            filter(true, SECRET, List.of()).doFilter(
                    request(SECRET, "dir001", null, "10.0.0.1"), response(), new MockFilterChain());

            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.getName()).isEqualTo("dir001");
            assertThat(auth.getAuthorities()).extracting("authority")
                    .containsExactlyInAnyOrder(GatewayAuthenticationFilter.ROLE_GATEWAY, "audit:log:read");
            assertThat(auth.getDetails()).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("X-User-Id 空白時退回 X-System-Id（系統自身執行）")
        void systemHeaderIsFallback() throws Exception {
            filter(true, SECRET, List.of()).doFilter(
                    request(SECRET, "  ", "org-sync", "10.0.0.1"), response(), new MockFilterChain());

            assertThat(callerId()).isEqualTo("org-sync");
        }

        @Test
        @DisplayName("X-User-Id 優先於 X-System-Id（代某人執行時是那個人的權限）")
        void userHeaderWinsOverSystemHeader() throws Exception {
            filter(true, SECRET, List.of()).doFilter(
                    request(SECRET, "user001", "org-sync", "10.0.0.1"), response(), new MockFilterChain());

            assertThat(callerId()).isEqualTo("user001");
        }

        @Test
        @DisplayName("兩個身分標頭都缺或都空白 → 不注入身分")
        void noIdentityHeaderMeansAnonymous() throws Exception {
            filter(true, SECRET, List.of()).doFilter(
                    request(SECRET, null, null, "10.0.0.1"), response(), new MockFilterChain());
            assertThat(callerId()).isNull();

            filter(true, SECRET, List.of()).doFilter(
                    request(SECRET, "  ", "  ", "10.0.0.1"), response(), new MockFilterChain());
            assertThat(callerId()).isNull();
        }

        @Test
        @DisplayName("已經有 JWT 認證時不覆蓋 —— 能碰到閘道密鑰的人不得冒用任何人")
        void existingAuthenticationIsNeverOverwritten() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("jwt-user", null, List.of()));

            filter(true, SECRET, List.of()).doFilter(
                    request(SECRET, "admin001", null, "10.0.0.1"), response(), new MockFilterChain());

            assertThat(callerId()).isEqualTo("jwt-user");
            verifyNoInteractions(authorityResolver);
        }
    }

    @Nested
    @DisplayName("來源白名單：未設定＝不限制，設定後只接受完整位址或前綴")
    class TrustedProxies {

        @Test
        @DisplayName("未設定白名單 → 不限制來源（密鑰仍是必要條件）")
        void unsetProxyListAllowsAnySource() throws Exception {
            filter(true, SECRET, null).doFilter(
                    request(SECRET, "user001", null, "203.0.113.9"), response(), new MockFilterChain());
            assertThat(callerId()).isEqualTo("user001");
        }

        @Test
        @DisplayName("白名單是 [\"\"]（@Value 未設定的陷阱）→ 濾掉空白後視為不限制，而不是拒絕全部")
        void blankProxyEntriesAreFiltered() throws Exception {
            filter(true, SECRET, List.of("", "  ")).doFilter(
                    request(SECRET, "user001", null, "203.0.113.9"), response(), new MockFilterChain());
            assertThat(callerId()).isEqualTo("user001");
        }

        @Test
        @DisplayName("完整位址不符 → 不注入身分")
        void exactMismatchIsRejected() throws Exception {
            filter(true, SECRET, List.of("10.0.0.1")).doFilter(
                    request(SECRET, "user001", null, "10.0.0.2"), response(), new MockFilterChain());
            assertThat(callerId()).isNull();
        }

        @Test
        @DisplayName("結尾 . 是前綴比對：同網段放行、相鄰網段拒絕")
        void prefixMatching() throws Exception {
            filter(true, SECRET, List.of("10.0.0.")).doFilter(
                    request(SECRET, "user001", null, "10.0.0.55"), response(), new MockFilterChain());
            assertThat(callerId()).isEqualTo("user001");

            SecurityContextHolder.clearContext();
            filter(true, SECRET, List.of("10.0.0.")).doFilter(
                    request(SECRET, "user001", null, "10.0.1.55"), response(), new MockFilterChain());
            assertThat(callerId()).isNull();
        }

        @Test
        @DisplayName("來源位址為 null 且有白名單 → 拒絕（不得因無法比對而放行）")
        void nullRemoteAddrIsRejectedWhenListConfigured() throws Exception {
            filter(true, SECRET, List.of("10.0.0.1")).doFilter(
                    request(SECRET, "user001", null, null), response(), new MockFilterChain());
            assertThat(callerId()).isNull();
        }
    }
}
