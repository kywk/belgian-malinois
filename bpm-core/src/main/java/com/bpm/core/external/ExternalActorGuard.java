package com.bpm.core.external;

import com.bpm.core.service.OrgService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 外部系統指名的身分，必須是組織系統認識的人（#88）。
 *
 * <h2>它修的缺陷是什麼</h2>
 *
 * <p>{@code POST /api/external/process-instances} 接受
 * {@code firstTaskAssignee}（<b>request body 裡自由指定的字串</b>）與
 * {@code onBehalfOf}。改動前只有後者有驗證，而前者<b>完全沒有</b> ——
 * 後端只檢查「{@code firstTaskAssignee}／{@code firstTaskCandidateGroups}
 * 至少有一個」，<b>不檢查那個值是不是人</b>。
 *
 * <p>於是外部系統可以送：
 * <pre>{"firstTaskAssignee": "system:evil", ...}</pre>
 * 而第一個人工任務的 assignee 就變成 {@code system:evil}。
 * 那不是人：沒有人能以它登入，因此
 * {@link com.bpm.core.security.TaskHolderGuard} 的四個條件
 * （assignee／owner／candidateUser／候選群組）全部不命中 ——
 *
 * <blockquote><b>沒有任何人看得到、沒有任何人能簽，案件從第一關就卡住，
 * 而且沒有任何錯誤訊息。</b></blockquote>
 *
 * <p>這與 #83 是<b>同一個缺陷的兩個入口</b>：#83 修的是補件關卡（較晚），
 * 本工項是第一關（較早）。第一關更早，使用者更可能以為是系統故障。
 * #83 補上的 {@code UnreachableTaskListener} 只能讓這種情況<b>至少會告警</b>，
 * 沒有根治 —— 根治的位置是輸入端，也就是本類別。
 *
 * <h2>⚠️ 為什麼「問組織系統」是主要規則，而不只是擋 {@code system:} 前綴</h2>
 *
 * <p>{@link ExternalActorIdentity} 的 {@code isSystemActor} 已經能擋掉
 * {@code system:} 前綴（不區分大小寫），而它的類別註解還主張
 * 「能用純字串判斷的就不該換成一次網路往返」。那條主張在
 * <b>{@code initiator} 上成立，在這裡不成立</b>，理由是那個命名空間的
 * 擁有者不同：
 *
 * <ul>
 *   <li>{@code initiator} 由 server 鑄造（R-20），呼叫端<b>不得</b>指定，
 *       所以「這個命名空間只會出現 server 鑄造的值」是<b>寫入端保證</b>的事實，
 *       前綴比對因此夠用。</li>
 *   <li>{@code firstTaskAssignee} 由<b>呼叫端自由指定</b>，沒有任何保證。
 *       這個欄位可以放進任何字串，而前綴比對只能擋其中一種<b>形狀</b>。</li>
 * </ul>
 *
 * <p>更一般地說，這個缺陷的實質是「<b>任何組織系統不認識的字串</b>」，
 * 不是「{@code system:} 這個形狀」。除了拼錯的員工編號、全形字元
 * （{@code ｓｙｓｔｅｍ：x} 不會被前綴比對命中）、空白與前後空白之外，
 * 還有別的字串同樣沒有人能持有。<b>只擋前綴等於把攻擊面縮到一種形狀，
 * 而缺陷的定義本來就不是那個形狀。</b>
 *
 * <p>所以本類別<b>兩層都做</b>，而且順序刻意是「便宜的先做」：
 * <ol>
 *   <li><b>空白</b> → 直接拒絕（不打網路）。</li>
 *   <li><b>{@code system:} 前綴</b> → 直接拒絕（不打網路）。
 *       這一層<b>不是</b>為了抓攻擊，而是為了給出一句
 *       <b>指得準的</b>錯誤訊息：「這是系統身分，沒有人能簽」比
 *       「查無此人」有用得多 —— 呼叫端一看就知道要換成真人 id。</li>
 *   <li><b>其他一律問組織系統</b> → 這才是擋住整個攻擊面的那一層。</li>
 * </ol>
 *
 * <h2>⚠️ 為什麼第 2 層不能刪（它是獨立的一層，不是多餘）</h2>
 *
 * <p>真實的組織系統<b>可能 fail-open</b>：對不認識的 id 回一個預設值。
 * 本專案自己的 {@code MockOrgController} 就是這樣整整兩輪
 * （P2-7 之前對不認識的 userId 一律回 {@code mgr001}），
 * 而正是那個 fail-open 讓 #83 的缺陷長達兩輪都沒被任何測試抓到 ——
 * {@code MockOrgController} 的類別註解把這段稱為「<b>綠燈反而掩蓋了問題</b>」。
 *
 * <p>換句話說：<b>「組織系統說這個人存在」不是可信的證據</b>，
 * 前提之一是組織系統必須 fail-closed（查不到就報錯）。
 * 前綴比對不依賴那個前提，所以保留它。
 *
 * <h2>⚠️ fail-closed：組織系統不可用時一律拒絕（政策）</h2>
 *
 * <p>本方法的 {@code catch} 捕捉<b>所有</b>例外，包含連線逾時、5xx、
 * 以及「查無此人」的 404 —— 三者的結果都是 400 拒絕。方向是<b>刻意</b>的：
 * 放行（fail-open）等於回到缺陷本身（指派給一個沒有人能持有的身分），
 * 而這是簽核系統裡最難察覺的失敗型態。稽核那條線也是同一個方向
 * （fail-closed 是本專案的既定原則）。
 *
 * <p>但代價必須寫清楚：<b>組織系統掛掉時，外部系統發起流程會全部被拒</b>，
 * 而且回的是 400（呼叫端通常<b>不會</b>重試）。若 PM 認為應該改回 503
 * 讓批次重試，那是<b>狀態碼語意</b>的政策決定（且會改變 {@code onBehalfOf}
 * 現有的行為），不該由實作端默默決定。見本次工項報告。
 *
 * <p>因為 400 同時涵蓋「payload 不對」與「我們查不到」，錯誤訊息必須
 * 把兩者都點名 —— 否則營運看到「不是組織系統認識的人員」會去查
 * 呼叫端的參數，而真正的故障在基礎設施。{@code log.warn} 是同一個目的：
 * 讓運維不必靠讀錯誤訊息來分辨。
 *
 * <h2>為什麼是 400 而不是 403／404</h2>
 *
 * <p>400 = 「請求本身不完整或不被允許，該改的是 payload」，與
 * {@code allowedProcessKeys} 的 403（授權）與流程定義不存在的 404
 * 必須能分辨。呼叫端拿到 400 就會去改參數，而「把員工編號換成對的」
 * 正是它能自己做的事。
 *
 * <h2>⚠️ 交易內的外部呼叫（security-audit P1-10）</h2>
 *
 * <p>{@link OrgService} 底下是對 bpm-core 自己的同步 HTTP
 * （{@code application.yml} 的 {@code org-service-url} 指向自己）。
 * 本呼叫發生在 {@code startProcess} 的 {@code @Transactional} 之內，
 * 與同一個方法既有的 {@code repositoryService} 查詢同級；
 * 風險由 {@code OrgService} 既有的逾時設定（connect 2s／read 3s）
 * 與 Redis 快取（60 分鐘 TTL）控住，<b>本類別不新增這個風險</b> ——
 * {@code onBehalfOf} 的驗證本來就在同一個交易內做同一件事。
 *
 * <h2>為什麼把 {@code onBehalfOf} 的既有檢查也收進來</h2>
 *
 * <p>「<b>規則只能有一份</b>」是本專案的硬規則（見 {@code ProcessAccessGuard}
 * 與 {@code TaskHolderGuard} 的類別註解：同一條規則有兩套形狀，
 * 正是 #84／#86／#87 這類缺陷的成因）。
 * {@code onBehalfOf} 的驗證原本是 {@code startProcess} 裡的一段 inline
 * try/catch。若只為 {@code firstTaskAssignee} 再寫一份，
 * 就會出現「{@code onBehalfOf} 擋掉系統身分（靠組織系統查不到）、
 * {@code firstTaskAssignee} 擋掉同一個形狀（靠另一段程式碼）」這種
 * 兩套形狀 —— 而且兩邊的錯誤訊息會不一致。
 * 改動只是把既有那段換成本類別，<b>行為不變</b>
 * （未知員工仍然是 400），多出來的是空白與前綴兩層以及統一的訊息。
 */
@Component
public class ExternalActorGuard {

    private static final Logger log = LoggerFactory.getLogger(ExternalActorGuard.class);

    private final OrgService orgService;

    public ExternalActorGuard(OrgService orgService) {
        this.orgService = orgService;
    }

    /**
     * 這個欄位指名的身分必須是組織系統認識的人，否則 400。
     *
     * <p>{@code userId} 為 {@code null} 時<b>直接放行</b>：
     * 「沒有指名」是呼叫端合法的選擇（改用候選群組或代發），
     * 由呼叫端自己的「至少有一個」規則處理，不是這條規則的事。
     *
     * @param field  body 欄位名，放進錯誤訊息讓呼叫端知道要改哪一個
     * @throws ResponseStatusException 400，理由有三種（見類別註解）
     */
    public void requireKnownPerson(String field, String userId) {
        if (userId == null) return;

        // ── 1. 空白 ────────────────────────────────────────────────────
        //
        // ⚠️ 空白不等於「沒有指名」，這是本方法最容易漏掉的一格。
        // `firstTaskAssignee: ""` 會通過 startProcess 的「至少有一個」檢查
        // （非 null），然後被 InitialAssigneeResolver 視為未指定，
        // 流程啟動時就去查 `system:<id>` 的主管 —— 組織系統查不到 → 500。
        //
        // 而同時帶 `firstTaskCandidateGroups` 時更糟：
        // ExternalApiController 會對第一關呼叫 setAssignee(taskId, "")，
        // assignee 變成空字串（不是 null）。Flowable 的候選群組查詢帶著
        // `RES.ASSIGNEE_ is null`（Task.xml 的 selectTaskByCandidateGroup*），
        // 所以**群組成員看不到這個任務** —— 案件從第一關就卡死，
        // 而 UnreachableTaskListener 也不告警（它把「有候選人」當成
        // 「有人看得到」，見該類別第 138 行）。
        //
        // 為什麼是拒絕而不是「當成沒有指名」：後者會讓呼叫端以為
        // 「留給候選群組認領」成功送出，而實際上 payload 有一個它看不見的
        // 缺陷。400 讓它看到問題。
        if (userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 不可為空白。空白不會被當成「留給候選群組認領」，"
                            + "而會讓流程啟動時去查 " + ExternalActorIdentity.PREFIX
                            + "<systemId> 的主管而失敗。"
                            + "請填實際受理人的員工編號，或改用 firstTaskCandidateGroups。");
        }

        // ── 2. 伺服器鑄造的系統身分 ──────────────────────────────────────
        //
        // 不打網路，而且這一層的意義是「訊息」而不是「攔截」——
        // 見類別註解「為什麼第 2 層不能刪」。
        // 大小寫不敏感由 ExternalActorIdentity.isSystemActor 負責（它也
        // 是 UnreachableTaskListener 與 ApplicantResolver 判定
        // 「這不是人」的唯一來源，規則只有一份）。
        if (ExternalActorIdentity.isSystemActor(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + "=" + userId + " 是伺服器鑄造的系統身分（"
                            + ExternalActorIdentity.PREFIX + " 前綴），"
                            + "沒有人能以它登入，因此沒有任何人能簽第一關。"
                            + "請改填實際受理人的員工編號，或改用 firstTaskCandidateGroups。");
        }

        // ── 3. 問組織系統：擋掉其餘所有組織系統不認識的字串 ──────────────
        //
        // 用 getDirectManager 當存在性檢查，這是 onBehalfOf 沿用的形狀。
        // ⚠️ 它的回傳值（直屬主管）不是重點，**有沒有拋例外**才是：
        // 位於組織鏈頂的人（dir001、admin001）沒有主管，mock 回 {} 而
        // getDirectManager 回 null —— 那是「他存在但沒有主管」的事實，
        // 不是查不到。所以絕不可寫成
        //     if (orgService.getDirectManager(x) == null) → 拒絕
        // 那會擋掉總監本人（見 InitialAssigneeResolverTest 那個
        // 「捏造的答案恰好等於預期值」的老教訓）。
        //
        // catch 全部例外是刻意的 fail-closed，見類別註解。
        try {
            orgService.getDirectManager(userId);
        } catch (Exception e) {
            log.warn("外部系統指定的 {}={} 未通過組織系統查核，拒絕發起流程: {}",
                    field, userId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + "=" + userId + " 不是組織系統認識的人員，無法指派給他。"
                            + "請確認 id 拼寫（大小寫、前後空白都會影響比對）。"
                            + "若組織系統目前無法連線，本系統一律拒絕發起（fail-closed），"
                            + "請稍後重試；若該人員確實存在，請改用 onBehalfOf 代員工發起。",
                    e);
        }
    }
}
