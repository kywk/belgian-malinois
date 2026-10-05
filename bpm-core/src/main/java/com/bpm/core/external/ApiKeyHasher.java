package com.bpm.core.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * API key 的雜湊與驗證（R-25），唯一一份。
 *
 * <h2>為什麼從單輪 SHA-256 改成 HMAC-SHA256(server secret, key)</h2>
 *
 * <p>單輪 SHA-256 無 salt 的具體問題不是「離線暴力破解」——
 * {@code UUID.randomUUID()} 有 122 bits 熵，那條路本來就不通。
 * 真正的問題是<b>可預計算的等值比對</b>：
 * <ul>
 *   <li>相同明文金鑰在資料庫裡永遠是同一個雜湊 → DB 外洩時可跨系統、
 *       跨環境比對出「有人重用同一把金鑰」。</li>
 *   <li>雜湊函式公開且無 secret → 拿到 DB 的人可以對候選明文直接驗證，
 *       不需要伺服器。</li>
 * </ul>
 *
 * <p>HMAC 把 kernel 換成 server secret，同時保留「可查詢性」：
 * 驗證端能拿明文重算落庫值，資料庫不需要存明文，也不必逐列比對。
 * 沒有 secret 的一方，即使拿到整個 DB 也無法離線驗證候選金鑰。
 *
 * <h2>為什麼 fail-fast、連 dev 都不給預設值</h2>
 *
 * <p>沒有 secret 就無法產生或驗證 v2 雜湊。若允許空值啟動，
 * 系統只剩 legacy 路徑 —— <b>新的保護靜默不存在，卻看起來一切正常</b>。
 * 這種「安全機制默默沒生效」的失效型態在本 repo 反覆出現
 * （見 {@code WebhookHmacSecretValidator} 的註解），所以這裡選擇啟動失敗，
 * 錯誤訊息直接指出環境變數名稱。dev／test 的值由 compose 與 test profile 注入。
 *
 * <h2>雙讀：legacy 驗證不走 HMAC</h2>
 *
 * <p>既有 DB 的金鑰是無前綴的 SHA-256。驗證時看落庫值的
 * {@link ApiKeyUtil#V2_PREFIX} 決定演算法：有前綴 → HMAC；沒有 → SHA-256。
 * 因此<b>舊金鑰在改版當天不會失效</b>；第一次通過驗證後由 filter
 * 透明升級成 v2（見 {@code ExternalApiAuthFilter}）。
 *
 * <p>比對一律用 {@link MessageDigest#isEqual}（constant-time），
 * 不讓回應時間洩漏「前幾個字元對了」。對 hex 字串比對而言這是低成本、
 * 無副作用的強化；比對對象是雜湊值而非原始金鑰，所以強度不是關鍵，
 * 但沒有理由留著一般的 {@code equals}。
 */
@Component
public class ApiKeyHasher {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyHasher.class);

    /**
     * HMAC secret 的最小長度。32 bytes 是 HMAC-SHA256 的區塊／輸出長度，
     * 低於它的 secret 讓暴力搜尋 secret 的空間縮小到不可接受。
     * 以 UTF-8 位元組數計算，不是字元數。
     */
    static final int MIN_SECRET_BYTES = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public ApiKeyHasher(ExternalSecurityProperties properties) {
        String raw = properties.getApiKey().getHmacSecret();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "bpm.external.security.api-key.hmac-secret 未設定（空白）。"
                            + "這個 secret 是 API key v2 雜湊的 HMAC 密鑰：沒有它，"
                            + "新格式無法產生也無法驗證，資料庫外洩時的金鑰保護不存在。"
                            + "請由部署環境提供（BPM_API_KEY_HMAC_SECRET）；"
                            + "dev／test 的值由 docker-compose.dev.yml／application-test.yml 注入。");
        }
        this.secret = raw.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "bpm.external.security.api-key.hmac-secret 太短（"
                            + this.secret.length + " bytes，至少需 " + MIN_SECRET_BYTES
                            + " bytes＝HMAC-SHA256 的區塊長度）。"
                            + "secret 太短會讓離線暴力搜尋可行，等於失去 HMAC 的保護。");
        }
        log.info("API key v2 雜湊已啟用（HMAC-SHA256，secret {} bytes）", this.secret.length);
    }

    /**
     * 產生 v2 落庫值：{@code v2:} ＋ HMAC-SHA256(secret, plainKey) 小寫 hex。
     *
     * <p>前綴讓「這筆資料該用哪個演算法驗證」在 DB 裡直接可辨，
     * 不需要另一張表或另一欄記錄版本。
     */
    public String hash(String plainKey) {
        return ApiKeyUtil.V2_PREFIX + hmacHex(plainKey);
    }

    /**
     * 明文是否對應這個落庫值。v2 與 legacy 都支援 —— 這就是「雙讀」。
     *
     * @param plainKey   呼叫端送來的明文；null → false
     * @param storedHash 資料庫的值（{@code v2:...} 或舊 SHA-256 hex）；null → false
     */
    public boolean matches(String plainKey, String storedHash) {
        if (plainKey == null || storedHash == null) return false;
        if (ApiKeyUtil.isV2(storedHash)) {
            return constantTimeEquals(
                    hmacHex(plainKey),
                    storedHash.substring(ApiKeyUtil.V2_PREFIX.length()));
        }
        // 舊格式：單輪 SHA-256。這條路徑是為了讓既有 DB 的金鑰在改版當天
        // 仍可用；filter 在成功驗證後會把它升級成 v2。
        return constantTimeEquals(ApiKeyUtil.hash(plainKey), storedHash);
    }

    private String hmacHex(String plainKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(
                    mac.doFinal(plainKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // HmacSHA256 一定存在、key 也一定合法；走到這裡代表 JVM 環境壞了，
            // 包成 unchecked 讓它大聲爆掉，不要靜默回傳一個可預測的值。
            throw new IllegalStateException("HMAC-SHA256 計算失敗", e);
        }
    }

    /**
     * constant-time 比對兩個 hex 字串。
     *
     * <p>先統一轉小寫：legacy 時代的落庫值若曾由其他工具以大小寫混合寫入，
     * MSSQL 的 CI 定序查詢曾是放行的（舊 {@code findBySystemIdAndApiKey} 的語意），
     * Java 端比對必須維持同樣的寬容度，否則升級後那些系統會突然 401。
     * 大小寫正規化與 secret 無關，不破壞 constant-time 的性質。
     */
    private static boolean constantTimeEquals(String computed, String stored) {
        byte[] a = computed.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        byte[] b = stored.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, b);
    }
}
