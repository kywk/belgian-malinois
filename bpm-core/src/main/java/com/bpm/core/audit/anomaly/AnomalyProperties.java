package com.bpm.core.audit.anomaly;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 異常操作偵測的設定（#41），前綴 {@code bpm.audit.anomaly}。
 *
 * <h2>為什麼預設值是開啟但保守</h2>
 *
 * <p>偵測器預設 {@code enabled=true}：一個預設關閉的偵測器等於不存在，
 * 而且「預設關閉」這種事不會有人記得去打開。但門檻刻意訂得高
 * （大量審批 50 筆／10 分鐘、異常存取 20 筆／10 分鐘），讓 dev 與驗收的
 * 正常操作<b>不會</b>觸發告警 —— 會一直誤報的告警最後只會被忽略。
 *
 * <p>門檻與窗口可各自覆蓋，正式環境依實際業務量調整
 * （見 application.yml 的註解）。
 *
 * <h2>為什麼掃描間隔不在這個類別</h2>
 *
 * <p>{@code scan-interval}／{@code initial-delay} 由
 * {@code AnomalyDetector} 的 {@code @Scheduled} 以 property placeholder
 * 直接解析 —— 排程屬性放在這裡只會多一份需要同步的副本。
 */
@Component
@ConfigurationProperties(prefix = "bpm.audit.anomaly")
public class AnomalyProperties {

    /** 總開關。false 時 {@code scan()} 完全不碰稽核 DB。 */
    private boolean enabled = true;

    /**
     * 同一（模式＋operatorId）的告警冷卻時間。
     *
     * <p>沒有冷卻的話，掃描每分鐘跑一次、窗口 10 分鐘 —— 同一個命中會在
     * 10 分鐘內被重複告警約 10 次。冷卻把「一個異常事件」收斂成「一則告警」。
     */
    private Duration cooldown = Duration.ofMinutes(30);

    /** 告警信收件人（逗號分隔）。預設空＝不寄信，只留 ERROR log 與稽核。 */
    private String alertRecipients = "";

    private Mode massApproval = new Mode(true, 50, Duration.ofMinutes(10));

    private Mode deniedAccess = new Mode(true, 20, Duration.ofMinutes(10));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getCooldown() { return cooldown; }
    public void setCooldown(Duration cooldown) { this.cooldown = cooldown; }
    public String getAlertRecipients() { return alertRecipients; }
    public void setAlertRecipients(String alertRecipients) { this.alertRecipients = alertRecipients; }
    public Mode getMassApproval() { return massApproval; }
    public void setMassApproval(Mode massApproval) { this.massApproval = massApproval; }
    public Mode getDeniedAccess() { return deniedAccess; }
    public void setDeniedAccess(Mode deniedAccess) { this.deniedAccess = deniedAccess; }

    /**
     * 單一模式的門檻與窗口。
     *
     * <p>{@code threshold} 是「達到就算異常」（{@code >=}），不是「超過才算」
     * —— 門檻值的語意是「這個數量本身就是異常」，等號必須落在異常側，
     * 否則設定 50 卻要 51 筆才告警，維運解讀設定時會少算一筆。
     */
    public static class Mode {

        private boolean enabled;
        private long threshold;
        private Duration window;

        public Mode() {
        }

        public Mode(boolean enabled, long threshold, Duration window) {
            this.enabled = enabled;
            this.threshold = threshold;
            this.window = window;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getThreshold() { return threshold; }
        public void setThreshold(long threshold) { this.threshold = threshold; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }
}
