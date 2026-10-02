package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 外部系統請求的<b>共同授權閘門</b>：停用、IP 白名單、allowedActions。
 *
 * <h2>為什麼要抽這一層（工項 #21）</h2>
 *
 * <p>在回呼端點出現之前，這三條檢查只存在於 {@code ExternalApiAuthFilter}。
 * 回呼需要完全相同的三條規則，但認證方式不同（HMAC 而非 API key）——
 * 若在新過濾器裡再寫一份 {@code Boolean.TRUE.equals(sys.getEnabled())} 與
 * 兩次 policy 呼叫，兩邊就會各自漂移：日後有人調整「停用的語意」或
 * 檢查順序，只會改到其中一邊，而另一邊的漏洞不會有任何測試會發現。
 *
 * <p>所以「規則只有一份」的落點是這裡：
 * <ul>
 *   <li>{@code enabled} 的判定（{@code Boolean.TRUE.equals} —— null 視為停用）。</li>
 *   <li>IP 白名單與 allowedActions 都<b>只透過</b> {@link ExternalSystemPolicy}
 *       判定，不自己解析字串。四態語意（不限制／拒絕全部／清單／格式錯誤）
 *       與空值＝不限制的規則都在那裡。</li>
 * </ul>
 *
 * <p>⚠️ 這裡只回傳「拒絕原因」，<b>不決定回應格式、也不寫稽核</b>：
 * 兩個過濾器的稽核與訊息政策不同（{@code ExternalApiAuthFilter} 會寫
 * 拒絕稽核，回呼過濾器只寫 log），把它們也收進來會改變既有外部 API 行為。
 *
 * <p>⚠️ 呼叫順序不變：{@code rejectSystemOrIp} →（外部 API 先解析 action）
 * → {@code rejectAction}。{@code ExternalApiAuthFilter} 的既有順序與訊息
 * 在抽共用後必須完全相同，否則「未知端點」與「IP 不在白名單」的訊息會對調。
 */
@Component
public class ExternalSystemAccessGuard {

    /** 一條拒絕：HTTP 狀態碼與給呼叫端的原因。狀態碼固定 403（授權層）。 */
    public record Rejection(int status, String reason) {
    }

    private final ExternalSystemPolicy policy;

    public ExternalSystemAccessGuard(ExternalSystemPolicy policy) {
        this.policy = policy;
    }

    /**
     * 停用與 IP 白名單。呼叫端必須在<b>身分已確認之後</b>呼叫 ——
     * 這兩個檢查會透露「這個系統存在且被停用／來源 IP 是什麼」。
     */
    public Optional<Rejection> rejectSystemOrIp(ExternalSystem sys, String clientIp) {
        if (!Boolean.TRUE.equals(sys.getEnabled())) {
            return Optional.of(new Rejection(403, "System is disabled"));
        }
        if (!policy.isIpAllowed(sys, clientIp)) {
            return Optional.of(new Rejection(403, "IP not in whitelist: " + clientIp));
        }
        return Optional.empty();
    }

    /** allowedActions 是否包含該 action（空值＝不限制，見 {@link ExternalSystemPolicy}）。 */
    public Optional<Rejection> rejectAction(ExternalSystem sys, String action) {
        if (!policy.isActionAllowed(sys, action)) {
            return Optional.of(new Rejection(403, "Action not allowed: " + action));
        }
        return Optional.empty();
    }
}
