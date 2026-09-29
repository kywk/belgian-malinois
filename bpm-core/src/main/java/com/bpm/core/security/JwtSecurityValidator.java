package com.bpm.core.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 啟動時驗證身分來源的設定，設錯就不讓它起來。
 *
 * <h2>為什麼是啟動失敗而不是警告</h2>
 *
 * <p>與 {@code ExternalSystemUrlValidator}（P2-7）同一個理由，但後果更直接：
 * 身分來源設錯的服務能提供服務，就代表它<b>正在接受偽造的身分</b>，
 * 而且看起來一切正常 —— 所有授權檢查都會照著錯誤的身分正確執行。
 *
 * <p>資料一旦按偽造的身分簽核完成，事後無法只靠重設設定修正。
 */
@Configuration
public class JwtSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtSecurityValidator.class);

    private final Environment environment;
    private final String issuerUri;
    private final String devSecret;
    private final boolean gatewayEnabled;
    private final String gatewaySecret;

    public JwtSecurityValidator(
            Environment environment,
            @Value("${bpm.security.jwt.issuer-uri:}") String issuerUri,
            @Value("${bpm.security.jwt.dev-secret:}") String devSecret,
            @Value("${bpm.security.gateway.enabled:false}") boolean gatewayEnabled,
            @Value("${bpm.security.gateway.shared-secret:}") String gatewaySecret) {
        this.environment = environment;
        this.issuerUri = issuerUri;
        this.devSecret = devSecret;
        this.gatewayEnabled = gatewayEnabled;
        this.gatewaySecret = gatewaySecret;
    }

    @PostConstruct
    void validate() {
        boolean prod = environment.matchesProfiles("prod");
        boolean hasIssuer = issuerUri != null && !issuerUri.isBlank();
        boolean hasDevSecret = devSecret != null && !devSecret.isBlank();

        if (prod && hasDevSecret) {
            throw new IllegalStateException(
                    "prod profile 下不可設定 bpm.security.jwt.dev-secret —— "
                            + "任何知道該密鑰的人都能簽發有效身分。請移除它並改用 issuer-uri。");
        }
        if (prod && !hasIssuer) {
            throw new IllegalStateException(
                    "prod profile 必須設定 bpm.security.jwt.issuer-uri（企業 IdP 的位置）。"
                            + "身分來源沒有合理的預設值 —— 猜錯等於接受偽造的身分。");
        }
        if (!hasIssuer && !hasDevSecret) {
            throw new IllegalStateException(
                    "bpm.security.jwt.issuer-uri 與 dev-secret 皆未設定，JWT 無法驗證。"
                            + "本服務只驗證 token、不簽發，所以必須知道要用什麼驗。");
        }

        // 閘道：啟用卻沒有密鑰等於「只看標頭」—— 那正是改動前的狀態。
        if (gatewayEnabled && (gatewaySecret == null || gatewaySecret.isBlank())) {
            throw new IllegalStateException(
                    "bpm.security.gateway.enabled=true 但未設定 shared-secret。"
                            + "沒有密鑰的話任何能連到應用 port 的人都能用 X-User-Id 偽造身分 —— "
                            + "那正是本次要修掉的狀態。");
        }

        if (prod && !gatewayEnabled) {
            // 不擋：不是每個部署都需要 server 之間的路徑。
            log.info("prod：server 之間的閘道認證未啟用，/api/internal/** 將一律拒絕");
        }
        if (hasDevSecret) {
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║ 身分驗證使用開發用密鑰，僅限開發與測試環境。                        ║");
            log.warn("╚══════════════════════════════════════════════════════════════╝");
        }
    }
}
