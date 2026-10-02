package com.bpm.core.service;

import com.bpm.core.external.ExternalActorIdentity;
import com.bpm.core.security.ProcessAccessGuard;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「這張單的自然人申請人是誰」——催辦授權與完成通知收件人的唯一判定（#96）。
 *
 * <h2>為什麼要從 {@code TaskController} 抽出來</h2>
 *
 * <p>這條規則原本是 {@code TaskController.applicantOf(pid)} 的 private 方法，
 * 只有 HTTP 完成路徑與催辦在用。改用全域 Flowable listener 發通知之後，
 * listener 也需要同一條規則（通知的收件人是申請人）—— 而 listener
 * 拿不到 controller 的 private 方法。若不抽出來，唯一能做的就是在 listener
 * 複製一份「onBehalfOf 優先、其次 initiator、系統身分不算」的判定，
 * 那正是本 repo 反覆記載的缺陷成因（同一條規則兩套形狀：只要有人改了
 * 其中一處，就會出現「催辦找得到申請人、通知卻寄給 system:erp」那種
 * 組合型式的差異）。
 *
 * <h2>規則（順序即語意）</h2>
 *
 * <ol>
 *   <li><b>{@code onBehalfOf} 有值 → 那位員工。</b>外部系統代發時
 *       {@code initiator} 仍是 {@code system:<id>}，不先取 {@code onBehalfOf}
 *       就會把通知寄給系統身分。</li>
 *   <li><b>{@code initiator} 是人 → {@code initiator}。</b>人工發起的既有行為。</li>
 *   <li><b>兩者都不是人（或不存在）→ {@code null}。</b>寄給
 *       {@code system:erp} 沒有意義；{@link com.bpm.core.notify.NotifyPublisher}
 *       對 {@code null} 申請人直接略過通知。</li>
 * </ol>
 *
 * <p>這正是 {@code ApplicantResolver} 前兩段的同一條規則（#83/#68c）。
 * <b>第三段不同</b>：{@code ApplicantResolver} 的第三段是「補件關卡派給哪個
 * 系統受理人」，那是「這張單沒有自然人申請人時誰來承辦」的答案；
 * 通知與催辦問的是「誰是申請人」，查不到自然人就是沒有。
 * 兩者共用前兩段、不共用第三段，不是同一件事。
 *
 * <h2>為什麼不放在 {@link ProcessAccessGuard}</h2>
 *
 * <p>該類別的職責是「這個呼叫者能不能碰這個案件」（授權），而這裡回答的是
 * 「這張單的申請人是誰」（身分解析）。混在一起會讓授權元件的介面帶上
 * 通知語意，且 listener 只需要這一條查詢，不必連整個守衛一起拿。
 *
 * <h2>為什麼不放在 {@link OnBehalfOfLookup}</h2>
 *
 * <p>那個類別只做「{@code onBehalfOf} 變數的一次批次查詢」，是資料存取；
 * 本類別才是政策（fallback 到 {@code initiator}、濾掉系統身分）。
 * 把政策塞進查詢類別會讓它同時回答兩個問題，而 fallback 還需要
 * {@code ProcessAccessGuard.initiatorOf} 的 runtime／歷史兩段查法。
 */
@Service
public class ApplicantIdentityLookup {

    private final OnBehalfOfLookup onBehalfOfLookup;
    private final ProcessAccessGuard accessGuard;

    public ApplicantIdentityLookup(OnBehalfOfLookup onBehalfOfLookup,
                                   ProcessAccessGuard accessGuard) {
        this.onBehalfOfLookup = onBehalfOfLookup;
        this.accessGuard = accessGuard;
    }

    /**
     * 這張單的自然人申請人：{@code onBehalfOf} 優先，其次 {@code initiator}；
     * 兩者都不是人（{@code system:<id>}）或不存在時回 {@code null}。
     *
     * @param processInstanceId 案件 id。{@code null}／空白直接回 {@code null}
     *                          （standalone 加簽子任務沒有案件，見
     *                          {@code CompletionNotifyListener}）。
     */
    public String applicantOf(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) return null;
        // #68b／#68c：代發時 initiator 是 system:<id>，必須先取 onBehalfOf。
        String onBehalfOf = onBehalfOfLookup.byProcessInstances(List.of(processInstanceId))
                .get(processInstanceId);
        if (onBehalfOf != null) return onBehalfOf;
        String initiator = accessGuard.initiatorOf(processInstanceId);
        // 系統身分不是人：通知寄不出去，催辦也不該被 system:<id> 通過。
        if (initiator == null || ExternalActorIdentity.isSystemActor(initiator)) return null;
        return initiator;
    }
}
