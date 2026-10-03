package com.bpm.core.audit.anomaly;

/**
 * 聚合查詢的回傳形狀：窗口內單一 operator 的命中筆數。
 *
 * <p>用介面投影而非載入 {@code AuditLog} 實體：掃描每分鐘跑一次，
 * 它只需要「誰、幾筆」，不需要每一列的內容。聚合在 DB 端完成，
 * JVM 只拿到每個 operator 一列。
 */
public interface OperatorHitCount {

    String getOperatorId();

    long getHitCount();
}
