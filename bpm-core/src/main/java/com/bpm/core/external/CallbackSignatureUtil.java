package com.bpm.core.external;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

/**
 * 回呼請求的 HMAC 簽章與時間戳（工項 #21）。
 *
 * <h2>為什麼簽章只放標頭，而且是對「原始 body 位元組」計算</h2>
 *
 * <p>這是 {@code WebhookConsumer} 在 outgoing 方向學到的同一課：接收端要驗章
 * 就必須能重現<b>位元完全相同的 body</b>。若簽章是對「解析後再重新序列化」的
 * JSON 計算，鍵序、空白、數字格式都會讓重算結果不同 —— 有簽章標頭卻沒有
 * 完整性保護。所以：
 *
 * <ul>
 *   <li>簽章放 {@code X-Callback-Signature: sha256=<hex>}，不塞進 body。</li>
 *   <li>過濾器讀的是請求的原始位元組（見 {@code CallbackAuthFilter} 的
 *       body 快取），不是 {@code @RequestBody} 反序列化後的 Map。</li>
 * </ul>
 *
 * <h2>為什麼時間戳兩種格式都收</h2>
 *
 * <p>規格說「ISO-8601 或 epoch」。收兩種是為了讓不同語言的外部系統用各自
 * 最自然的方式送（Java／JS 用 ISO-8601，shell／Python 常直接送 epoch），
 * 而不是逼其中一邊多做一次格式轉換。判定 epoch 秒或毫秒的方式是位數
 * （見 {@link #parseTimestamp}），不猜。
 *
 * <h2>⚠️ 驗章失敗與時間戳失敗都不可區分</h2>
 *
 * <p>{@link #verify} 只回答「通過／不通過」，呼叫端（{@code CallbackAuthFilter}）
 * 對所有 401 一律回同一個訊息 —— 否則回應內容會變成「系統存在嗎」的探測工具。
 */
public final class CallbackSignatureUtil {

    /** 簽章標頭值的固定前綴。 */
    public static final String SIGNATURE_PREFIX = "sha256=";

    /**
     * 大於此值視為 epoch 毫秒，否則視為 epoch 秒。
     *
     * <p>10<sup>11</sup> 秒約等於西元 5138 年，13 位數毫秒則始於 2001 年 ——
     * 兩者的數值範圍沒有重疊，所以位數判斷不是啟發式，是精確的。
     */
    private static final long EPOCH_MILLIS_THRESHOLD = 100_000_000_000L;

    private CallbackSignatureUtil() {
    }

    /** 對原始 body 計算 HMAC-SHA256，回傳小寫 hex（不含前綴）。 */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            // 演算法／密鑰不可能出錯（HmacSHA256 是 JDK 必備、secret 已非 null）；
            // 真出錯時也不能放行，直接讓它變成 500。
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    /**
     * 驗證 {@code X-Callback-Signature} 標頭。
     *
     * <p>比對用 {@link MessageDigest#isEqual}（常數時間）—— 與
     * {@code GatewayAuthenticationFilter} 同一條理由：一般的字串比對會在第一個
     * 不同的位元組返回，讓攻擊者能用回應時間逐位元組猜出正確簽章。
     */
    public static boolean verify(String secret, byte[] body, String header) {
        if (secret == null || header == null || !header.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        String presented = header.substring(SIGNATURE_PREFIX.length());
        String expected = sign(secret, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                presented.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 解析 {@code X-Callback-Timestamp}：ISO-8601 或 epoch 秒／毫秒。
     *
     * @return 解析出的時間點，格式無法辨識時回傳 {@code null}
     */
    public static Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();

        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // 不是 ISO-8601，改試 epoch。
        }

        try {
            long value = Long.parseLong(trimmed);
            return value < EPOCH_MILLIS_THRESHOLD
                    ? Instant.ofEpochSecond(value)
                    : Instant.ofEpochMilli(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
