package com.bpm.core.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ApiKeyHasher} 的單元測試：v2（HMAC）與 legacy（SHA-256）雙讀。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>R-25 把落庫格式從「無 salt 單輪 SHA-256」換成
 * 「{@code v2:} ＋ HMAC-SHA256(server secret, key)」。格式切換的兩個失敗面
 * 都不是「破解」而是「全滅」：
 * <ul>
 *   <li>驗證端只認新格式 → 既有 DB 的金鑰在部署當天全部 401。</li>
 *   <li>驗證端把兩代混在一起（例如對 v2 值再雜湊一次）→ 新金鑰永遠驗不過。</li>
 * </ul>
 * 所以這裡把「v2 怎麼算」「legacy 怎麼驗」「兩者互不混淆」逐一釘死。
 *
 * <p>secret 本身不在生產 DB 裡，是 fail-fast 的：空白與過短都在建構子就
 * 拒絕啟動 —— 測試把這兩個拒絕也釘住，避免哪天有人「順手」給預設值，
 * 讓保護靜默消失。
 */
class ApiKeyHasherTest {

    private static final String SECRET =
            "unit-test-api-key-hmac-secret-at-least-32-bytes";
    private static final String OTHER_SECRET =
            "another-unit-test-hmac-secret-at-least-32-bytes";

    private static ApiKeyHasher hasher(String secret) {
        ExternalSecurityProperties props = new ExternalSecurityProperties();
        props.getApiKey().setHmacSecret(secret);
        return new ApiKeyHasher(props);
    }

    @Nested
    @DisplayName("v2 雜湊")
    class V2Hash {

        @Test
        @DisplayName("輸出是 v2: 前綴＋64 字元小寫 hex，且具決定性、對輸入敏感")
        void shapeAndDeterminism() {
            ApiKeyHasher h = hasher(SECRET);
            String hash = h.hash("sk-abc");

            assertThat(hash).startsWith(ApiKeyUtil.V2_PREFIX).hasSize(3 + 64);
            assertThat(hash).isEqualTo(h.hash("sk-abc"));
            assertThat(hash).isNotEqualTo(h.hash("sk-abd"));
            assertThat(ApiKeyUtil.isV2(hash)).isTrue();
        }

        @Test
        @DisplayName("secret 不同 → 同一把明文金鑰的落庫值不同、互不通過驗證")
        void secretIsPartOfTheHash() {
            String stored = hasher(SECRET).hash("sk-shared");

            assertThat(hasher(OTHER_SECRET).hash("sk-shared"))
                    .as("相同明文在不同 secret 下必須得到不同落庫值 —— 否則 DB 外洩時"
                            + "仍可跨環境比對出重用的金鑰")
                    .isNotEqualTo(stored);
            assertThat(hasher(OTHER_SECRET).matches("sk-shared", stored))
                    .as("秘密不同就不該驗得過")
                    .isFalse();
        }

        @Test
        @DisplayName("明文 → v2 落庫值 → 以明文驗證通過；錯的明文不通過")
        void roundTrip() {
            ApiKeyHasher h = hasher(SECRET);
            assertThat(h.matches("sk-round", h.hash("sk-round"))).isTrue();
            assertThat(h.matches("sk-wrong", h.hash("sk-round"))).isFalse();
        }

        @Test
        @DisplayName("v2 值不會同時以 legacy SHA-256 通過（兩代不得混淆）")
        void v2IsNotSha256() {
            ApiKeyHasher h = hasher(SECRET);
            String plain = "sk-cross-format";

            assertThat(h.hash(plain)).isNotEqualTo(ApiKeyUtil.hash(plain));
            // 若驗證邏輯錯成「v2 也用 SHA-256 比對」，落庫值與 SHA-256 不同，
            // matches 會回 false —— 這裡從另一個方向證明它。
            assertThat(h.matches(plain, ApiKeyUtil.hash(plain)))
                    .as("legacy 值走 legacy 路徑，這是刻意的雙讀")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("legacy 雙讀與邊界")
    class LegacyAndEdges {

        @Test
        @DisplayName("無前綴的既有 SHA-256 值仍可驗證（部署當天舊金鑰不失效）")
        void legacyHashStillVerifies() {
            ApiKeyHasher h = hasher(SECRET);
            String legacy = ApiKeyUtil.hash("sk-legacy");

            assertThat(ApiKeyUtil.isV2(legacy)).isFalse();
            assertThat(h.matches("sk-legacy", legacy)).isTrue();
            assertThat(h.matches("sk-other", legacy)).isFalse();
        }

        @Test
        @DisplayName("legacy hex 大小寫不敏感（舊查詢是 CI 定序，語意必須一致）")
        void legacyHexIsCaseInsensitive() {
            ApiKeyHasher h = hasher(SECRET);
            String upper = ApiKeyUtil.hash("sk-case").toUpperCase(java.util.Locale.ROOT);
            assertThat(h.matches("sk-case", upper)).isTrue();
        }

        @Test
        @DisplayName("null 與空字串一律不通過，不拋例外")
        void nullSafe() {
            ApiKeyHasher h = hasher(SECRET);
            assertThat(h.matches(null, h.hash("x"))).isFalse();
            assertThat(h.matches("x", null)).isFalse();
            assertThat(h.matches(null, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("fail-fast 的 secret 驗證")
    class SecretValidation {

        @Test
        @DisplayName("空白 secret → 拒絕啟動，訊息指出環境變數")
        void blankSecretFailsFast() {
            assertThatThrownBy(() -> hasher(""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BPM_API_KEY_HMAC_SECRET");
            assertThatThrownBy(() -> hasher("   "))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> hasher(null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("過短 secret → 拒絕啟動（短於 HMAC-SHA256 的 32 bytes）")
        void shortSecretFailsFast() {
            assertThatThrownBy(() -> hasher("too-short"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32");
        }
    }
}
