package com.bpm.core.audit;

import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 組態變更的稽核（security-audit P2-4）。
 *
 * <h2>為什麼組態變更需要稽核</h2>
 *
 * <p>簽核平台的行為由組態決定：流程變數規格限制外部系統能傳什麼、
 * 通知模板決定誰會收到簽核通知、外部系統設定決定誰能發起流程。
 *
 * <p>改動前這些端點全部沒有稽核。其中通知設定特別值得注意 ——
 * 改掉通知模板或關掉某個事件的通知，可以讓簽核活動<b>不被相關人員察覺</b>。
 * 那是一條隱藏行為的路徑，而且它不需要碰任何案件資料。
 *
 * <h2>為什麼用一個共用組件而不是各自實作</h2>
 *
 * <p>三個 controller 各寫一份的結果是三種不同的 detail 結構，
 * 之後沒有人能寫出一個查詢把「所有組態變更」找出來。
 * 統一 {@code CONFIG_CHANGE} 型別加上 {@code configType}/{@code action}
 * 這組固定欄位，查詢才可能。
 */
@Component
public class ConfigChangeAuditor {

    private final AuditEventPublisher publisher;

    public ConfigChangeAuditor(AuditEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * @param operatorId  X-User-Id 標頭。空白時記成 {@code unknown} 而非 null ——
     *                    null 在查詢時容易被誤讀成「這個欄位不適用」，
     *                    而事實是「我們不知道是誰」。那是不同的意思。
     * @param configType  組態種類，例如 {@code notify-template}。
     * @param action      {@code create} / {@code update} / {@code delete}。
     * @param targetId    被改的那一筆的識別。
     * @param extra       額外的細節，可為 null。
     */
    public void record(String operatorId, String configType, String action,
                       String targetId, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("configType", configType);
        detail.put("action", action);
        detail.put("targetId", targetId == null ? "" : targetId);
        if (extra != null) extra.forEach((k, v) -> detail.put(k, v == null ? "" : v));

        publisher.publish(new AuditEvent(
                OperationType.CONFIG_CHANGE.name(),
                operatorId != null && !operatorId.isBlank() ? operatorId : "unknown",
                null, null, detail));
    }
}
