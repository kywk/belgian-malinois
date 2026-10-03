package com.bpm.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CallerIdArgumentResolver} 的單元測試：身分只來自 SecurityContext。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>最危險的退化是「SecurityContext 沒有身分時退回讀 {@code X-User-Id}」——
 * 那會讓整套認證變成裝飾：任何一條沒被授權規則涵蓋的路徑，身分就退回
 * 可偽造的標頭，而且完全沒有痕跡。因此未認證的契約是回 {@code null}
 * （稽核記 unknown），而不是任何形式的 fallback。
 *
 * <p>另一條是 {@code anonymousUser}：Spring Security 對未認證請求的 principal
 * 名稱看起來就像一個使用者 ID，若不過濾，稽核裡會出現一個假身分。
 */
class CallerIdArgumentResolverTest {

    private final CallerIdArgumentResolver resolver = new CallerIdArgumentResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, null, java.util.List.of()));
    }

    @Test
    @DisplayName("未認證（SecurityContext 為空）→ null，不得退回任何標頭")
    void noAuthenticationYieldsNull() {
        assertThat(CallerIdArgumentResolver.currentCallerId()).isNull();
        assertThat(CallerIdArgumentResolver.currentCallerIdOrUnknown())
                .isEqualTo(CallerIdArgumentResolver.UNKNOWN);
    }

    @Test
    @DisplayName("已建立但未認證的 token → null")
    void unauthenticatedTokenYieldsNull() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user001", "credentials"));

        assertThat(CallerIdArgumentResolver.currentCallerId()).isNull();
    }

    @Test
    @DisplayName("anonymousUser 不是身分 —— 稽核裡不得出現這個假 ID")
    void anonymousUserIsNotAnIdentity() {
        authenticate("anonymousUser");

        assertThat(CallerIdArgumentResolver.currentCallerId()).isNull();
        assertThat(CallerIdArgumentResolver.currentCallerIdOrUnknown())
                .isEqualTo(CallerIdArgumentResolver.UNKNOWN);
    }

    @Test
    @DisplayName("正常已認證 principal → 回傳其名稱")
    void authenticatedPrincipalIsReturned() {
        authenticate("user001");

        assertThat(CallerIdArgumentResolver.currentCallerId()).isEqualTo("user001");
        assertThat(CallerIdArgumentResolver.currentCallerIdOrUnknown()).isEqualTo("user001");
    }

    @Test
    @DisplayName("空白 principal 名稱 → null（不是一個空字串身分）")
    void blankNameYieldsNull() {
        authenticate("  ");

        assertThat(CallerIdArgumentResolver.currentCallerId()).isNull();
    }

    @Test
    @DisplayName("supportsParameter：只認 @CallerId 修飾的 String 參數")
    void supportsOnlyAnnotatedStringParameters() throws Exception {
        assertThat(resolver.supportsParameter(parameter("withCaller", String.class))).isTrue();
        assertThat(resolver.supportsParameter(parameter("withoutCaller", String.class))).isFalse();
        assertThat(resolver.supportsParameter(parameter("wrongType", Integer.class))).isFalse();
    }

    @Test
    @DisplayName("resolveArgument 回傳目前呼叫者（與 currentCallerId 同一份規則）")
    void resolveArgumentUsesSecurityContext() throws Exception {
        authenticate("mgr001");

        assertThat(resolver.resolveArgument(parameter("withCaller", String.class), null, null, null))
                .isEqualTo("mgr001");
    }

    private static MethodParameter parameter(String methodName, Class<?> type) throws Exception {
        Method method = Sample.class.getDeclaredMethod(methodName, type);
        return new MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    private static class Sample {
        void withCaller(@CallerId String callerId) {
        }

        void withoutCaller(String callerId) {
        }

        void wrongType(@CallerId Integer callerId) {
        }
    }
}
