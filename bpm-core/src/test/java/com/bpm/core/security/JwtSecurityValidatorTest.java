package com.bpm.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JwtSecurityValidator} 的單元測試：啟動時的身分來源驗證。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>身分來源設錯的服務<b>能提供服務</b>，就代表它正在接受偽造的身分，
 * 而且看起來一切正常 —— 所有授權檢查都會照著錯誤的身分正確執行。
 * 資料一旦按偽造的身分簽核完成，事後無法只靠重設設定修正。
 *
 * <p>因此每一條「危險組合」的契約都是<b>啟動失敗</b>（{@link IllegalStateException}），
 * 而不是警告。這組測試把每個組合逐一釘死，避免日後有人把驗證放寬成 log。
 */
class JwtSecurityValidatorTest {

    private static Environment environment(boolean prod) {
        Environment env = mock(Environment.class);
        when(env.matchesProfiles("prod")).thenReturn(prod);
        return env;
    }

    private static JwtSecurityValidator validator(boolean prod, String issuer, String devSecret,
                                                   boolean gatewayEnabled, String gatewaySecret) {
        return new JwtSecurityValidator(environment(prod), issuer, devSecret,
                gatewayEnabled, gatewaySecret);
    }

    @Nested
    @DisplayName("prod profile")
    class Prod {

        @Test
        @DisplayName("prod 設定 dev-secret → 啟動失敗（任何知道密鑰的人都能簽發有效身分）")
        void devSecretIsForbiddenInProd() {
            assertThatThrownBy(() -> validator(true, "https://idp.example", "dev-secret",
                    false, "").validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev-secret");
        }

        @Test
        @DisplayName("prod 沒有 issuer-uri → 啟動失敗（身分來源沒有合理的預設值）")
        void issuerIsRequiredInProd() {
            assertThatThrownBy(() -> validator(true, "", "", false, "").validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("issuer-uri");
        }

        @Test
        @DisplayName("prod 有 issuer、無 dev-secret → 通過")
        void properProdConfigPasses() {
            assertThatCode(() -> validator(true, "https://idp.example", "", false, "").validate())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("prod 閘道未啟用 → 不擋（不是每個部署都需要 server 之間的路徑）")
        void gatewayDisabledIsAllowedInProd() {
            assertThatCode(() -> validator(true, "https://idp.example", "", false, "").validate())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("dev-secret 與缺少 issuer 同時成立時，先報 dev-secret（兩者都是啟動阻擋）")
        void devSecretErrorTakesPrecedence() {
            assertThatThrownBy(() -> validator(true, "", "dev-secret", false, "").validate())
                    .hasMessageContaining("dev-secret");
        }
    }

    @Nested
    @DisplayName("非 prod profile")
    class NonProd {

        @Test
        @DisplayName("issuer 與 dev-secret 皆未設定 → 啟動失敗（不知道要用什麼驗）")
        void atLeastOneSourceIsRequired() {
            assertThatThrownBy(() -> validator(false, "", "", false, "").validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("issuer-uri")
                    .hasMessageContaining("dev-secret");
        }

        @Test
        @DisplayName("dev-secret 可用（dev／test 的本機簽發路徑）")
        void devSecretIsAllowedOutsideProd() {
            assertThatCode(() -> validator(false, "", "dev-secret", false, "").validate())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("issuer-uri 可用")
        void issuerIsAllowedOutsideProd() {
            assertThatCode(() -> validator(false, "https://idp.example", "", false, "").validate())
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("閘道設定")
    class Gateway {

        @Test
        @DisplayName("閘道啟用但沒有共享密鑰 → 啟動失敗（那正是「只看標頭」的狀態）")
        void enabledWithoutSecretFails() {
            assertThatThrownBy(() -> validator(false, "", "dev-secret", true, "").validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("shared-secret");
            assertThatThrownBy(() -> validator(false, "", "dev-secret", true, null).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("shared-secret");
        }

        @Test
        @DisplayName("閘道啟用且有密鑰 → 通過")
        void enabledWithSecretPasses() {
            assertThatCode(() -> validator(false, "", "dev-secret", true, "secret").validate())
                    .doesNotThrowAnyException();
        }
    }
}
