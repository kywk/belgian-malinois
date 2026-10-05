package com.bpm.core.external;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 外部 API 認證的設定（R-25），前綴 {@code bpm.external.security}。
 *
 * <h2>為什麼 hmac-secret 沒有預設值</h2>
 *
 * <p>這個 secret 是 {@code v2:} 雜湊的 salt／pepper：它讓資料庫外洩
 * （含所有雜湊值）不足以偽造金鑰，也讓同一把明文金鑰在不同環境產生不同雜湊、
 * 不能再跨系統比對出重用的金鑰。這些保護<b>完全建立在 secret 不公開</b>的前提上。
 *
 * <p>因此這裡刻意不提供開發預設字面值（對照 {@code bpm.webhook.hmac-secret}
 * 的歷史：repo 內的預設值等於公開密鑰，最後得靠 validator 在 prod 擋）。
 * 空值＝啟動失敗，見 {@link ApiKeyHasher} 建構子的 fail-fast；
 * 各環境的注入來源：
 * <ul>
 *   <li>dev：{@code docker-compose.dev.yml} 的 {@code BPM_API_KEY_HMAC_SECRET}</li>
 *   <li>test：{@code application-test.yml}</li>
 *   <li>prod：{@code docker-compose.prod.yml} 轉注 {@code API_KEY_HMAC_SECRET}</li>
 * </ul>
 *
 * <h2>grace-period 為什麼需要</h2>
 *
 * <p>{@code rotate-key} 若讓舊金鑰立即失效，每一次輪替都是計畫性中斷，
 * 實務上的結果是「不敢輪替」。寬限期讓新舊金鑰並存到舊的到期為止，
 * 呼叫端有時間換設定。<b>0 或負值 = 立即失效</b>（維持改動前行為，
 * 供「懷疑金鑰外洩、必須立刻切斷」的情境使用）。
 *
 * <h2>throttle 的門檻語意</h2>
 *
 * <p>同一（systemId＋來源 IP）在 {@code window} 內累積 {@code maxFailures}
 * 次<b>認證失敗</b>後，後續請求暫時回 429，直到窗口滑出。
 * 門檻是「達到就擋」({@code >=})，與 {@code AnomalyProperties} 的慣例一致 ——
 * 設定 10 的意思是「10 次失敗本身已不可接受」，不是「第 11 次才算」。
 */
@Component
@ConfigurationProperties(prefix = "bpm.external.security")
public class ExternalSecurityProperties {

    private final ApiKey apiKey = new ApiKey();
    private final Throttle throttle = new Throttle();

    public ApiKey getApiKey() { return apiKey; }
    public Throttle getThrottle() { return throttle; }

    /** {@code bpm.external.security.api-key}。 */
    public static class ApiKey {

        /** HMAC-SHA256 的 server secret。空值 → 啟動失敗（fail-fast）。 */
        private String hmacSecret = "";

        /** rotate-key 後舊金鑰的寬限期。0 = 立即失效。 */
        private Duration gracePeriod = Duration.ofHours(24);

        public String getHmacSecret() { return hmacSecret; }
        public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
        public Duration getGracePeriod() { return gracePeriod; }
        public void setGracePeriod(Duration gracePeriod) { this.gracePeriod = gracePeriod; }
    }

    /** {@code bpm.external.security.throttle}。 */
    public static class Throttle {

        /** 同一（systemId＋IP）在窗口內允許的認證失敗次數，達到即 429。 */
        private int maxFailures = 10;

        /** 失敗計數的固定窗口。 */
        private Duration window = Duration.ofMinutes(1);

        public int getMaxFailures() { return maxFailures; }
        public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }
}
