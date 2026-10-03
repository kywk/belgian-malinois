package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExternalSystemAccessGuard} 的單元測試：外部系統請求的共同授權閘門。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>這個類別是 API key 與 HMAC 兩條認證路徑<b>共用的</b>授權判定
 * （停用／IP 白名單／allowedActions）。共用是刻意的（規則只有一份），
 * 但共用也代表這裡改壞會同時放行兩條路徑。三個風險點：
 * <ul>
 *   <li>{@code enabled} 為 null 時必須視為停用（{@code Boolean.TRUE.equals}），
 *       而不是 NPE 或「非 false 即放行」。</li>
 *   <li>停用必須優先於 IP 判定 —— 否則停用的系統可以從白名單 IP 探測。</li>
 *   <li>拒絕原因必須指名參數（IP／action），維運才看得出要改什麼。</li>
 * </ul>
 *
 * <p>字串解析（四態語意、trim、子串）由 {@code ExternalSystemPolicyTest}
 * 負責，這裡只驗「閘門有沒有把問題交給 policy 並正確回應」。
 */
class ExternalSystemAccessGuardTest {

    private final ExternalSystemPolicy policy = mock(ExternalSystemPolicy.class);
    private final ExternalSystemAccessGuard guard = new ExternalSystemAccessGuard(policy);

    private static ExternalSystem system(Boolean enabled) {
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId("erp");
        sys.setEnabled(enabled);
        return sys;
    }

    @Nested
    @DisplayName("rejectSystemOrIp：停用與 IP 白名單")
    class SystemOrIp {

        @Test
        @DisplayName("啟用且 IP 允許 → 放行")
        void enabledAndAllowedIpPasses() {
            ExternalSystem sys = system(true);
            when(policy.isIpAllowed(sys, "10.0.0.1")).thenReturn(true);

            assertThat(guard.rejectSystemOrIp(sys, "10.0.0.1")).isEmpty();
        }

        @Test
        @DisplayName("停用 → 403，且不問 IP（停用的系統不該從白名單 IP 探測到更多）")
        void disabledIsRejectedWithoutIpCheck() {
            ExternalSystem sys = system(false);

            var rejection = guard.rejectSystemOrIp(sys, "10.0.0.1");

            assertThat(rejection).isPresent();
            assertThat(rejection.get().status()).isEqualTo(403);
            assertThat(rejection.get().reason()).isEqualTo("System is disabled");
            verify(policy, never()).isIpAllowed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("enabled 為 null → 視為停用（fail-closed，不是 NPE 也不是放行）")
        void nullEnabledIsDisabled() {
            var rejection = guard.rejectSystemOrIp(system(null), "10.0.0.1");

            assertThat(rejection).isPresent();
            assertThat(rejection.get().reason()).isEqualTo("System is disabled");
        }

        @Test
        @DisplayName("IP 不在白名單 → 403，訊息帶上來源 IP（維運要看得出是哪個 IP）")
        void deniedIpIsRejectedWithIpInMessage() {
            ExternalSystem sys = system(true);
            when(policy.isIpAllowed(sys, "203.0.113.9")).thenReturn(false);

            var rejection = guard.rejectSystemOrIp(sys, "203.0.113.9");

            assertThat(rejection).isPresent();
            assertThat(rejection.get().status()).isEqualTo(403);
            assertThat(rejection.get().reason()).isEqualTo("IP not in whitelist: 203.0.113.9");
        }
    }

    @Nested
    @DisplayName("rejectAction：allowedActions")
    class Action {

        @Test
        @DisplayName("policy 允許 → 放行")
        void allowedActionPasses() {
            ExternalSystem sys = system(true);
            when(policy.isActionAllowed(sys, "start_process")).thenReturn(true);

            assertThat(guard.rejectAction(sys, "start_process")).isEmpty();
        }

        @Test
        @DisplayName("不允許 → 403，訊息帶上 action（管理員才知道白名單要補什麼）")
        void deniedActionIsRejectedWithActionInMessage() {
            ExternalSystem sys = system(true);
            when(policy.isActionAllowed(sys, "external_worker")).thenReturn(false);

            var rejection = guard.rejectAction(sys, "external_worker");

            assertThat(rejection).isPresent();
            assertThat(rejection.get().status()).isEqualTo(403);
            assertThat(rejection.get().reason()).isEqualTo("Action not allowed: external_worker");
        }

        @Test
        @DisplayName("停用的系統即使 action 允許，也在第一關就被擋（順序不變）")
        void disabledWinsOverAllowedAction() {
            ExternalSystem sys = system(false);
            when(policy.isActionAllowed(sys, "start_process")).thenReturn(true);

            assertThat(guard.rejectSystemOrIp(sys, "10.0.0.1")).isPresent();
        }
    }
}
