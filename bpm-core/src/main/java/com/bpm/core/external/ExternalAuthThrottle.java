package com.bpm.core.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 API 認證失敗的節流（R-25）。
 *
 * <h2>為什麼需要</h2>
 *
 * <p>改動前驗證失敗只寫一筆稽核，沒有限制：持有 systemId 的攻擊者可以
 * 對 {@code X-API-Key} 無限次猜測（線上字典攻擊），伺服器每一次都完整走
 * 「查 DB → 雜湊比對 → 寫稽核」。金鑰有 122 bits 熵，猜中的機率可以忽略，
 * 但猜測流量本身是 DB 與稽核庫的負擔；429 是把這個成本擋在 DB 之前。
 *
 * <h2>為什麼是記憶體計數、不是 Redis</h2>
 *
 * <p>本服務目前是單實例（compose 裡只有一個 {@code bpm-core}）；
 * 用 Redis 會引入新的外部相依與故障模式，換不到現階段需要的精確度。
 * 而且節流是「阻力」不是「授權」—— 重啟後窗口歸零是可接受的代價；
 * 真正的授權判斷永遠是每次請求都比對金鑰。
 *
 * <h2>⚠️ 桶的鍵為什麼是（systemId＋來源 IP）</h2>
 *
 * <p>只用 systemId：任何人知道某個系統的 id 就能用錯金鑰把它鎖死
 * （合法的持有者一起被 429）。只用 IP：在 R-22 修好之前
 * {@code getRemoteAddr()} 取到的是 nginx 位址，所有系統共用一個桶，
 * 一個壞鄰居會鎖死全部人。兩者相乘是現況下最小受害面的選擇：
 * 同一來源對同一系統的失敗才會互相影響。
 *
 * <p>⚠️ 也就是說 R-22 未修時，這個鍵在容器部署下會退化成「只按 systemId」
 * —— 鎖死風險仍在，只是需要攻擊者先知道 systemId。R-22 修好後，
 * 鍵中的 IP 才會恢復成真實 client IP 的隔離效果。
 *
 * <h2>⚠️ systemId 為什麼要正規化</h2>
 *
 * <p>DB 查詢的定序是 CI（{@code findBySystemId("ERP")} 找得到 {@code erp}），
 * 若桶的鍵用原始字串，攻擊者只要輪替大小寫就能讓每次猜測落在不同的桶
 * —— 等於沒有節流。因此鍵以 trim＋小寫正規化，與查詢的實際語意對齊。
 *
 * <h2>桶的記憶體上限</h2>
 *
 * <p>鍵含攻擊者可控的 systemId；不設上限就是一個記憶體放大入口。
 * 超過上限時先清掉已過期的桶，仍滿就整體清空。清空的代價是
 * 「窗口內的失敗計數歸零」，比 OOM 小得多；節流不是授權，可以承受。
 */
@Component
public class ExternalAuthThrottle {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuthThrottle.class);

    /** 桶數量的自我保護上限。見類別註解。 */
    static final int MAX_BUCKETS = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ExternalAuthThrottle(ExternalSecurityProperties properties) {
        this.maxFailures = properties.getThrottle().getMaxFailures();
        this.window = properties.getThrottle().getWindow();
        if (maxFailures < 1) {
            throw new IllegalStateException(
                    "bpm.external.security.throttle.max-failures 必須 >= 1，目前是 " + maxFailures
                            + "。0 或負數等於每個請求都被視為超限，不是關閉節流；"
                            + "要關閉請把門檻設得比合理流量高。");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalStateException(
                    "bpm.external.security.throttle.window 必須是正數時長，目前是 " + window + "。"
                            + "零窗口讓計數立刻過期，等於節流不存在。");
        }
    }

    /** 這個（systemId＋IP）目前是否已達失敗門檻。 */
    public boolean isBlocked(String systemId, String clientIp) {
        Bucket b = buckets.get(bucketKey(systemId, clientIp));
        return b != null && b.count() >= maxFailures && !expired(b);
    }

    /** 距離窗口結束還有幾秒（進位到整數秒，至少 1）；未達門檻時回 0。 */
    public long retryAfterSeconds(String systemId, String clientIp) {
        Bucket b = buckets.get(bucketKey(systemId, clientIp));
        if (b == null || b.count() < maxFailures || expired(b)) return 0;
        long remainingMillis = window.toMillis() - (System.currentTimeMillis() - b.windowStart());
        return Math.max(1, (remainingMillis + 999) / 1000);
    }

    /** 記一次認證失敗。窗口過期則重新開一個窗口。 */
    public void recordFailure(String systemId, String clientIp) {
        long now = System.currentTimeMillis();
        String key = bucketKey(systemId, clientIp);
        if (buckets.size() >= MAX_BUCKETS) {
            purgeExpired(now);
            if (buckets.size() >= MAX_BUCKETS) {
                log.warn("外部 API 認證失敗的節流桶超過 {} 個，清空重來"
                        + "（節流是阻力、不是授權；清空的代價只是窗口內計數歸零）", MAX_BUCKETS);
                buckets.clear();
            }
        }
        buckets.compute(key, (k, old) ->
                old == null || expiredAt(old, now)
                        ? new Bucket(now, 1)
                        : new Bucket(old.windowStart(), old.count() + 1));
    }

    /** 成功登入：清掉這個桶，讓下一個窗口從零開始。 */
    public void recordSuccess(String systemId, String clientIp) {
        buckets.remove(bucketKey(systemId, clientIp));
    }

    private void purgeExpired(long now) {
        buckets.entrySet().removeIf(e -> expiredAt(e.getValue(), now));
    }

    private boolean expired(Bucket b) {
        return expiredAt(b, System.currentTimeMillis());
    }

    private boolean expiredAt(Bucket b, long now) {
        return now - b.windowStart() >= window.toMillis();
    }

    private static String bucketKey(String systemId, String clientIp) {
        // 與 DB 查詢的 CI 定序對齊；IP 為 null 時仍要有穩定的鍵。
        return (systemId == null ? "" : systemId.trim().toLowerCase(Locale.ROOT))
                + "|" + (clientIp == null ? "unknown" : clientIp);
    }

    /** 固定窗口內的失敗次數。immutable，替換而非就地修改，避免跨執行緒可見性問題。 */
    private record Bucket(long windowStart, int count) {}
}
