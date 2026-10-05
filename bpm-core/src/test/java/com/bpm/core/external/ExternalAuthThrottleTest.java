package com.bpm.core.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExternalAuthThrottle} 的單元測試：固定窗口、成功重置、鍵隔離。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>節流有兩個方向都能壞：
 * <ul>
 *   <li><b>太鬆</b>：門檻沒生效，失敗猜測免費 —— R-25 要修的原始狀態。</li>
 *   <li><b>太緊</b>：正常呼叫端（甚至只是打錯一次）被鎖在門外，
 *       而且錯誤訊息不會說明發生什麼事。</li>
 * </ul>
 *
 * <p>第三個方向是鍵：只用 systemId 會讓知道 id 的人鎖死別人；只用 IP
 * 則在 nginx 後（R-22 未修）全部共用一個桶。測試把
 * 「（systemId＋IP）聯集、systemId 正規化」的鍵語意釘死 ——
 * 少了正規化，攻擊者只要輪替大小寫就能繞過節流。
 *
 * <p>時間用短窗口（毫秒級）真實驗證，不引入可注入時鐘 ——
 * 這裡的邏輯小到不值得多一層抽象，而 sleep 50ms 的成本可忽略。
 */
class ExternalAuthThrottleTest {

    private static ExternalAuthThrottle throttle(int maxFailures, Duration window) {
        ExternalSecurityProperties props = new ExternalSecurityProperties();
        props.getThrottle().setMaxFailures(maxFailures);
        props.getThrottle().setWindow(window);
        return new ExternalAuthThrottle(props);
    }

    @Test
    @DisplayName("未達門檻不擋；達到門檻（>=）立刻擋，且 Retry-After 為正")
    void blocksAtThreshold() {
        ExternalAuthThrottle t = throttle(3, Duration.ofMinutes(1));

        assertThat(t.isBlocked("erp", "10.0.0.1")).isFalse();
        t.recordFailure("erp", "10.0.0.1");
        t.recordFailure("erp", "10.0.0.1");
        assertThat(t.isBlocked("erp", "10.0.0.1"))
                .as("2 次失敗 < 門檻 3，仍可嘗試").isFalse();

        t.recordFailure("erp", "10.0.0.1");
        assertThat(t.isBlocked("erp", "10.0.0.1"))
                .as("門檻語意是「達到就算」—— 3 次失敗本身已不可接受")
                .isTrue();
        assertThat(t.retryAfterSeconds("erp", "10.0.0.1")).isPositive();
    }

    @Test
    @DisplayName("成功登入清空該桶：偶發失敗不累積成鎖死")
    void successResetsBucket() {
        ExternalAuthThrottle t = throttle(3, Duration.ofMinutes(1));

        t.recordFailure("erp", "10.0.0.1");
        t.recordFailure("erp", "10.0.0.1");
        t.recordSuccess("erp", "10.0.0.1");

        t.recordFailure("erp", "10.0.0.1");
        assertThat(t.isBlocked("erp", "10.0.0.1"))
                .as("重置後重新計數，一次失敗不該被之前的歷史推過門檻")
                .isFalse();
    }

    @Test
    @DisplayName("窗口過期後自動解除（固定窗口，不需人工清理）")
    void windowExpiryUnblocks() throws Exception {
        ExternalAuthThrottle t = throttle(1, Duration.ofMillis(50));

        t.recordFailure("erp", "10.0.0.1");
        assertThat(t.isBlocked("erp", "10.0.0.1")).isTrue();

        Thread.sleep(80);
        assertThat(t.isBlocked("erp", "10.0.0.1"))
                .as("窗口滑出後應恢復 —— 否則一次誤鎖就是永久鎖")
                .isFalse();
        assertThat(t.retryAfterSeconds("erp", "10.0.0.1")).isZero();
    }

    @Test
    @DisplayName("不同（systemId 或 IP）各自獨立：不互相鎖死")
    void keysAreIsolated() {
        ExternalAuthThrottle t = throttle(1, Duration.ofMinutes(1));
        t.recordFailure("erp", "10.0.0.1");

        assertThat(t.isBlocked("erp", "10.0.0.2")).as("不同 IP 不受牽連").isFalse();
        assertThat(t.isBlocked("hr", "10.0.0.1")).as("不同系統不受牽連").isFalse();
    }

    @Test
    @DisplayName("systemId 鍵以 trim＋小寫正規化：大小寫輪替不得繞過節流")
    void systemIdIsNormalised() {
        ExternalAuthThrottle t = throttle(1, Duration.ofMinutes(1));
        t.recordFailure("erp", "10.0.0.1");

        assertThat(t.isBlocked("ERP", "10.0.0.1"))
                .as("DB 查詢是 CI 定序，桶的鍵必須與查詢語意一致")
                .isTrue();
        assertThat(t.isBlocked(" erp ", "10.0.0.1")).isTrue();
    }

    @Test
    @DisplayName("設定錯誤 fail-fast：門檻 < 1、窗口非正數都拒絕啟動")
    void invalidConfigFailsFast() {
        assertThatThrownBy(() -> throttle(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-failures");
        assertThatThrownBy(() -> throttle(10, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> throttle(10, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
