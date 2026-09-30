package com.bpm.core.external;

/**
 * 「伺服器鑄造的系統身分」的命名規則（#83）。
 *
 * <h2>這個類別存在的理由：{@code system:} 是一條規則，不是一個字面值</h2>
 *
 * <p>外部系統發起的案件，{@code initiator} 一律是 {@code system:<systemId>}
 * （R-20，{@code ExternalApiController.startProcess} 寫入，呼叫端不得指定）。
 * 由此推導出本專案最重要的一條身分規則：
 *
 * <blockquote>
 * <b>{@code system:} 開頭的身分不是人。</b>
 * 沒有人能以這個身分登入，因此沒有任何<b>持有者</b>判定會命中它
 * （{@link com.bpm.core.security.TaskHolderGuard} 的四個條件全部不命中），
 * 案件會停在指派給它的那個關卡上，而且沒有任何錯誤訊息。
 * </blockquote>
 *
 * <p>這條規則必須<b>只有一份</b>。本工項之前它是散在多處的字串拼接
 * （{@code ExternalApiController} 七處），而「哪些地方需要判斷『這不是人』」
 * 沒有任何一處寫下來 —— 補件關卡就是漏掉的那處（#83）。
 *
 * <h2>為什麼用「前綴比對」而不是「去組織系統確認這個人存在」</h2>
 *
 * <p>看似更嚴謹的做法是問組織系統「{@code system:erp} 這個人存在嗎」，
 * 但那有三個問題：
 *
 * <ol>
 *   <li><b>它答非所問。</b>真實的組織系統未必知道哪些帳號是機器；
 *       查得到不等於它是人，查不到也不等於它不是。</li>
 *   <li><b>它在簽核交易內打 HTTP。</b>{@code flowable:assignee} 於任務建立的
 *       那一刻求值，而任務建立發生在 {@code taskService.complete()} 的
 *       交易之內 —— 也就是 security-audit P1-10 的自我呼叫死鎖面。
 *       能用純字串判斷的就不該換成一次網路往返。</li>
 *   <li><b>它擋不住真正的攻擊面。</b>{@code initiator} 只能由
 *       {@code ExternalApiController}（寫入 {@code system:<id>}）與
 *       {@code ProcessController}（寫入已認證的使用者）產生，兩條路徑都
 *       明確拒絕呼叫端指定。前綴比對之所以夠用，是因為<b>寫入端已經保證
 *       這個命名空間只會出現伺服器鑄造的值</b>，不是因為比對本身夠嚴。</li>
 * </ol>
 *
 * <h2>為什麼大小寫不敏感</h2>
 *
 * <p>伺服器產生的一律是小寫，但 {@code firstTaskAssignee} 是
 * <b>外部系統從 request body 自由指定的字串</b>。
 * 因此外部系統可能送出 {@code firstTaskAssignee: "SYSTEM:x"}，
 * 让第一關的 assignee 變成一個大小寫不同的系統身分。
 * （#88 已擋下這條輸入路徑；本方法的不區分大小寫是<b>第二層</b>，
 *  見下一節 —— BPMN 字面值與其他寫入端仍可能產生它。）
 *
 * <p>比對因此採不區分大小寫 —— 方向是刻意的：把一個系統身分誤認為人
 * （於是不告警）會重現 #83 的靜默卡死，而把一個人誤認為系統身分
 * 只會多一則告警。兩者不對稱，寧可多報不可漏報。
 *
 * <h2>⚠️ 2026-09-30（#88）：本類別<b>不是</b>唯一的防線，不要刪掉別處的組織查詢</h2>
 *
 * <p>上面「用前綴比對就好，不要問組織系統」的三個理由，
 * <b>每一個都只在這個命名空間由 server 獨佔時成立</b>
 * （理由 3 明講「寫入端已經保證這個命名空間只會出現伺服器鑄造的值」）。
 * 而 {@code firstTaskAssignee} <b>不</b>受那個保證 —— 它由呼叫端自由指定。
 *
 * <p>因此 {@link ExternalActorGuard} 的規則是<b>兩層</b>，而不是用本類別
 * 取代組織查詢：前綴比對先擋（不打網路，而且錯誤訊息指得準），
 * <b>其餘所有字串一律問組織系統</b>。理由見該類別註解
 * 「⚠️ 為什麼「問組織系統」是主要規則」。
 *
 * <p>反向的錯誤同樣要避免：<b>不要</b>因為本類別存在就放寬掉組織查詢。
 * {@code ｓｙｓｔｅｍ：x}（全形）不會被本類別命中，而它一樣沒有人能簽 ——
 * 前綴比對能擋的是<b>一種形狀</b>，缺陷的定義是「組織系統不認識的字串」。
 */
public final class ExternalActorIdentity {

    /** 系統身分的命名空間前綴。見類別註解。 */
    public static final String PREFIX = "system:";

    private ExternalActorIdentity() {
    }

    /** 系統身分 {@code system:<systemId>}。 */
    public static String of(String systemId) {
        return PREFIX + systemId;
    }

    /**
     * 這個 id 是不是伺服器鑄造的系統身分（也就是「不是人」）。
     *
     * <p>{@code null} 與空白回 {@code false} —— 那不是系統身分，
     * 只是沒有值，而「沒有值」由呼叫端各自決定怎麼處理
     * （例如 {@code InitialAssigneeResolver} 會去查組織系統）。
     */
    public static boolean isSystemActor(String userId) {
        return userId != null
                && userId.toLowerCase(java.util.Locale.ROOT).startsWith(PREFIX);
    }
}
