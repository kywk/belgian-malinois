package com.bpm.core.audit.anomaly;

/**
 * 異常操作偵測的模式（#41）。
 *
 * <p>每一種模式有獨立的 enabled／threshold／window 設定（見
 * {@link AnomalyProperties}），因為它們的正常基準完全不同：一個審批者
 * 10 分鐘簽 50 件（批次消化待辦）是可能的，而 10 分鐘被拒絕存取 20 次
 * 不是。混用同一個門檻會讓其中一邊不是太吵就是太鈍。
 *
 * <p>{@link #name()} 會寫進稽核 detail 的 {@code mode} 欄位與告警信主旨
 * —— 它是查詢與告警過濾的穩定鍵，不要改動既有值的名稱。
 * {@link #label()} 只給人看的訊息使用。
 */
public enum AnomalyMode {

    /** 同一 operator 在窗口內完成大量審批（TASK_APPROVE／TASK_REJECT）。 */
    MASS_APPROVAL("短時間大量審批"),

    /** 同一 operator 在窗口內有大量被拒絕的存取（DATA_ACCESS denied）。 */
    DENIED_ACCESS("異常存取（多次被拒絕）");

    private final String label;

    AnomalyMode(String label) {
        this.label = label;
    }

    /** 繁中說明，只用於 ERROR log 與告警信。 */
    public String label() {
        return label;
    }
}
