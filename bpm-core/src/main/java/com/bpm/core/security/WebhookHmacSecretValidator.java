package com.bpm.core.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 啟動時驗證 webhook 的 HMAC 密鑰設定，設錯就不讓它起來。
 *
 * <h2>這個檢查要防止的部署事故（#26/#27 收尾時發現的不對稱）</h2>
 *
 * <p>{@code bpm.webhook.hmac-secret} 在 {@code application.yml} 有一份開發用
 * 預設字面值，而 {@code WebhookConsumer} 的 {@code @Value} 又有同一個 fallback。
 * 這條路徑原本只送 log、不真的投遞，所以預設值從未被認真對待；現在它真的會把
 * 事件（含簽章）送到外部 URL —— 正式環境若忘了換密鑰，就會一直用<b>公開在
 * repo 裡</b>的密鑰簽章，而接收端無從分辨真假。
 *
 * <p>同時身分（{@link JwtSecurityValidator}）與閘道密鑰都有 prod 啟動防護，
 * 只有 webhook HMAC 沒有。缺的這一塊就是這裡補上的對稱防護。
 *
 * <h2>為什麼是啟動失敗而不是警告</h2>
 *
 * <p>與 {@code ExternalSystemUrlValidator}（P2-7）、{@link JwtSecurityValidator}
 * 同一個理由：HMAC 簽章是接收端唯一能判斷「這真的來自 BPM」的依據。
 * 用預設字面值簽章的服務啟動成功，就代表它正在送出<b>任何人都能偽造</b>的簽章，
 * 而且從日誌到健康檢查都看起來一切正常。
 */
@Configuration
public class WebhookHmacSecretValidator {

    private static final Logger log = LoggerFactory.getLogger(WebhookHmacSecretValidator.class);

    /**
     * 已知的開發預設字面值。
     *
     * <p>它同時存在於 {@code application.yml} 的 base 文件與
     * {@code WebhookConsumer} 的 {@code @Value} fallback。三處必須同步；
     * 若不一樣，這裡的防護就會漏掉真正被使用的預設值
     * （由 {@code WebhookHmacSecretValidatorTest} 的同步測試守住）。
     */
    static final String DEV_DEFAULT_SECRET = "bpm-webhook-secret";

    private final Environment environment;
    private final String hmacSecret;

    public WebhookHmacSecretValidator(
            Environment environment,
            @Value("${bpm.webhook.hmac-secret:}") String hmacSecret) {
        this.environment = environment;
        this.hmacSecret = hmacSecret;
    }

    @PostConstruct
    void validate() {
        boolean prod = environment.matchesProfiles("prod");
        boolean blank = hmacSecret == null || hmacSecret.isBlank();
        boolean devDefault = DEV_DEFAULT_SECRET.equals(hmacSecret);

        if (prod && blank) {
            throw new IllegalStateException(
                    "prod profile 下 bpm.webhook.hmac-secret 未設定（空白）。"
                            + "空密鑰簽出的簽章不具任何保護力，而這條路徑會真的把事件投遞出去。"
                            + "請由部署環境提供密鑰（BPM_WEBHOOK_HMAC_SECRET）。");
        }
        if (prod && devDefault) {
            throw new IllegalStateException(
                    "prod profile 下 bpm.webhook.hmac-secret 仍是 repo 裡的開發預設值（"
                            + DEV_DEFAULT_SECRET + "）。這個字面值公開在版本控制中，"
                            + "任何知道它的人都能對 webhook 接收端偽造簽章。"
                            + "請由部署環境提供密鑰（BPM_WEBHOOK_HMAC_SECRET）。");
        }

        if (!prod && devDefault) {
            // 就算是 dev，也要在啟動日誌留下明顯痕跡 ——
            // 萬一哪天有人把這個預設值帶上正式環境，這是唯一的線索。
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║ Webhook HMAC 使用開發用預設密鑰，僅限開發與測試環境。              ║");
            log.warn("╚══════════════════════════════════════════════════════════════╝");
        }
        if (!prod && blank) {
            // 不擋：本機可能刻意不驗簽章。但空密鑰等於沒有保護，值得留一筆。
            log.warn("bpm.webhook.hmac-secret 未設定或為空白（非 prod）——"
                    + "空密鑰簽出的簽章不具保護力，請確認這是刻意的。");
        }
    }
}
