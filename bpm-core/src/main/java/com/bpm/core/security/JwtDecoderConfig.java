package com.bpm.core.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT 解碼器。<b>本服務只驗證 token，不簽發</b>。
 *
 * <h2>兩種來源，依部署環境決定</h2>
 *
 * <ul>
 *   <li><b>prod</b>：{@code bpm.security.jwt.issuer-uri} 指向企業 IdP。
 *       Spring Security 會抓該 issuer 的 JWKS 並驗證簽章、issuer 與有效期。
 *       換金鑰、輪換憑證都由 IdP 負責，本服務不需要任何金鑰材料。</li>
 *   <li><b>dev</b>：對稱密鑰（HMAC）。沒有 IdP 也能開發，token 由
 *       {@code scripts/dev-token.sh} 用同一組密鑰產生 ——
 *       那個腳本在應用之外，所以「本服務不簽發 token」這件事仍然成立。</li>
 * </ul>
 *
 * <h2>⚠️ 為什麼 dev 密鑰不可能誤用到 prod</h2>
 *
 * <p>{@link JwtSecurityValidator} 在啟動時檢查：prod profile 下
 * {@code dev-secret} 若有值、或 {@code issuer-uri} 若為空，一律拒絕啟動。
 * 這與 P2-7 對 mock 組織／權限系統的處理是同一個模式 ——
 * 設錯的身分來源不該啟動成功，它能提供服務就代表它正在接受偽造的身分。
 *
 * <h2>為什麼 dev 用對稱密鑰而不是自己產一對 RSA</h2>
 *
 * <p>自產 RSA 需要把公鑰交給驗證端、私鑰交給簽發腳本，於是多出金鑰檔案的
 * 產生、掛載與 gitignore 問題 —— 而那些檔案一旦被複製進版控，
 * 就變成一個看起來很正式的後門。對稱密鑰只有一個設定值，
 * 與這個專案既有的 {@code bpm-webhook-secret} 同一個模式，
 * 且啟動驗證擋得住。
 */
@Configuration
public class JwtDecoderConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderConfig.class);

    /** HS256 要求密鑰至少 256 bits。太短的密鑰會讓 Nimbus 在執行期才拋錯。 */
    static final int MIN_DEV_SECRET_BYTES = 32;

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${bpm.security.jwt.issuer-uri:}") String issuerUri,
            @Value("${bpm.security.jwt.dev-secret:}") String devSecret) {

        if (issuerUri != null && !issuerUri.isBlank()) {
            // issuer-uri 優先：有真實 IdP 時絕不使用 dev 密鑰，
            // 即使兩者都設定了也一樣（JwtSecurityValidator 會擋下那種組合，
            // 但這裡的順序讓「萬一擋漏了」也倒向安全的那邊）。
            log.info("JWT 驗證使用 issuer: {}（JWKS 由該 issuer 提供）", issuerUri);
            return JwtDecoders.fromIssuerLocation(issuerUri);
        }

        if (devSecret == null || devSecret.getBytes(StandardCharsets.UTF_8).length < MIN_DEV_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 驗證無法設定：bpm.security.jwt.issuer-uri 未設定，而 dev-secret "
                            + (devSecret == null || devSecret.isBlank()
                                    ? "也未設定" : "不足 " + MIN_DEV_SECRET_BYTES + " bytes（HS256 的最低要求）")
                            + "。身分來源沒有合理的預設值 —— 猜錯等於接受偽造的身分，所以這裡不提供預設值。");
        }

        log.warn("╔══════════════════════════════════════════════════════════════╗");
        log.warn("║ JWT 使用開發用對稱密鑰驗證（bpm.security.jwt.dev-secret）        ║");
        log.warn("║ 任何知道該密鑰的人都能簽發有效身分，僅限開發與測試環境。            ║");
        log.warn("╚══════════════════════════════════════════════════════════════╝");

        var key = new SecretKeySpec(devSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    /**
     * dev 簽發用的 JWK source。
     *
     * <p>⚠️ 這個 bean <b>不供應用本身使用</b> —— 應用只驗證。它存在是為了讓
     * 測試能用與驗證端完全相同的密鑰簽出 token，從而測到真正的驗證路徑，
     * 而不是繞過它。prod 沒有 dev-secret，所以這個 bean 不會建立。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "bpm.security.jwt.dev-secret")
    ImmutableSecret<com.nimbusds.jose.proc.SecurityContext> devJwkSource(
            @Value("${bpm.security.jwt.dev-secret}") String devSecret) {
        return new ImmutableSecret<>(devSecret.getBytes(StandardCharsets.UTF_8));
    }
}
