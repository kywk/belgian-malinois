package com.bpm.core.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CallbackSignatureUtil} 的單元測試：回呼請求的 HMAC 與時間戳。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>簽章是回呼端點唯一的真實性來源。兩個歷史教訓決定了它的形狀：
 * <ul>
 *   <li><b>對原始 body 位元組計算</b>：對「解析後再序列化」的 JSON 計算，
 *       鍵序／空白／數字格式都會讓重算結果不同 —— 有簽章標頭卻沒有完整性保護。
 *       所以這裡釘住「改一個 byte 就驗不過」。</li>
 *   <li><b>時間戳兩種格式都收</b>：ISO-8601 與 epoch 秒／毫秒，位數判斷
 *       必須精確（10<sup>11</sup> 是分界），猜錯會讓合法請求被當成重放。</li>
 * </ul>
 *
 * <p>驗章失敗與時間戳失敗都不可區分（呼叫端一律同一句 401），
 * 所以本類別只回答「通過／不通過」。
 */
class CallbackSignatureUtilTest {

    private static final String SECRET = "cs-test-secret";
    private static final byte[] BODY = "{\"event\":\"approved\"}".getBytes(StandardCharsets.UTF_8);

    @Nested
    @DisplayName("sign：對原始位元組計算 HMAC-SHA256")
    class Sign {

        @Test
        @DisplayName("輸出是小寫 hex，長度 64（SHA-256）")
        void hexOutput() {
            String signature = CallbackSignatureUtil.sign(SECRET, BODY);

            assertThat(signature).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("相同輸入決定性；不同密鑰或不同 body 都不同")
        void deterministicAndSensitive() {
            String a = CallbackSignatureUtil.sign(SECRET, BODY);
            assertThat(CallbackSignatureUtil.sign(SECRET, BODY)).isEqualTo(a);
            assertThat(CallbackSignatureUtil.sign("other-secret", BODY)).isNotEqualTo(a);
            assertThat(CallbackSignatureUtil.sign(SECRET, "{}".getBytes(StandardCharsets.UTF_8)))
                    .isNotEqualTo(a);
        }
    }

    @Nested
    @DisplayName("verify：標頭格式與完整性")
    class Verify {

        @Test
        @DisplayName("正確簽章通過")
        void correctSignaturePasses() {
            String header = CallbackSignatureUtil.SIGNATURE_PREFIX
                    + CallbackSignatureUtil.sign(SECRET, BODY);

            assertThat(CallbackSignatureUtil.verify(SECRET, BODY, header)).isTrue();
        }

        @Test
        @DisplayName("body 改一個 byte 就驗不過（簽章保護的是位元，不是解析後的結構）")
        void bodyTamperingFails() {
            String header = CallbackSignatureUtil.SIGNATURE_PREFIX
                    + CallbackSignatureUtil.sign(SECRET, BODY);
            byte[] tampered = "{\"event\":\"rejected\"}".getBytes(StandardCharsets.UTF_8);

            assertThat(CallbackSignatureUtil.verify(SECRET, tampered, header)).isFalse();
        }

        @Test
        @DisplayName("錯誤簽章、缺 sha256= 前綴、null 密鑰或標頭都驗不過")
        void malformedInputsFail() {
            String wrong = CallbackSignatureUtil.SIGNATURE_PREFIX + "deadbeef";

            assertThat(CallbackSignatureUtil.verify(SECRET, BODY, wrong)).isFalse();
            assertThat(CallbackSignatureUtil.verify(SECRET, BODY,
                    CallbackSignatureUtil.sign(SECRET, BODY))).isFalse();
            assertThat(CallbackSignatureUtil.verify(null, BODY, wrong)).isFalse();
            assertThat(CallbackSignatureUtil.verify(SECRET, BODY, null)).isFalse();
        }

        @Test
        @DisplayName("空 body 也是合法的簽章對象（不能因空而短路放行）")
        void emptyBodyIsSignedToo() {
            byte[] empty = new byte[0];
            String header = CallbackSignatureUtil.SIGNATURE_PREFIX
                    + CallbackSignatureUtil.sign(SECRET, empty);

            assertThat(CallbackSignatureUtil.verify(SECRET, empty, header)).isTrue();
            assertThat(CallbackSignatureUtil.verify(SECRET, BODY, header)).isFalse();
        }
    }

    @Nested
    @DisplayName("parseTimestamp：ISO-8601 或 epoch 秒／毫秒")
    class ParseTimestamp {

        @Test
        @DisplayName("ISO-8601 解析成同一時間點")
        void iso8601() {
            assertThat(CallbackSignatureUtil.parseTimestamp("2026-10-04T12:00:00Z"))
                    .isEqualTo(Instant.parse("2026-10-04T12:00:00Z"));
        }

        @Test
        @DisplayName("10 位數視為 epoch 秒")
        void epochSeconds() {
            assertThat(CallbackSignatureUtil.parseTimestamp("1700000000"))
                    .isEqualTo(Instant.ofEpochSecond(1700000000L));
        }

        @Test
        @DisplayName("13 位數視為 epoch 毫秒")
        void epochMillis() {
            assertThat(CallbackSignatureUtil.parseTimestamp("1700000000000"))
                    .isEqualTo(Instant.ofEpochMilli(1700000000000L));
        }

        @Test
        @DisplayName("位數分界精確：99999999999 是秒、100000000000 是毫秒")
        void thresholdIsExact() {
            assertThat(CallbackSignatureUtil.parseTimestamp("99999999999"))
                    .isEqualTo(Instant.ofEpochSecond(99_999_999_999L));
            assertThat(CallbackSignatureUtil.parseTimestamp("100000000000"))
                    .isEqualTo(Instant.ofEpochMilli(100_000_000_000L));
        }

        @Test
        @DisplayName("null／空白／無法辨識的字串 → null（呼叫端一律回同一句 401）")
        void invalidInputs() {
            assertThat(CallbackSignatureUtil.parseTimestamp(null)).isNull();
            assertThat(CallbackSignatureUtil.parseTimestamp("  ")).isNull();
            assertThat(CallbackSignatureUtil.parseTimestamp("not-a-time")).isNull();
            assertThat(CallbackSignatureUtil.parseTimestamp("2026-13-45T99:99:99Z")).isNull();
        }
    }
}
