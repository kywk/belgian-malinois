package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.service.OrgService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * <h2>⚠️ 故障與拒絕必須分開：查無此人 → 400，組織系統故障 → 503</h2>
 *
 * <p>本方法的 {@code catch} 捕捉<b>所有</b>例外，但<b>它們不是同一件事</b>，
 * 而回應必須分開（2026-09-30 使用者裁決）:
 *
 * <table border="1">
 *   <caption>組織查詢失敗的兩種語意</caption>
 *   <tr><th>情形</th><th>判準</th><th>回應</th><th>呼叫端該做什麼</th></tr>
 *   <tr><td>組織系統<b>明確回答「查無此人」</b></td>
 *       <td>HTTP 404（{@code MockOrgController} 對 fixture 外的 id 就是這樣）</td>
 *       <td><b>400</b></td><td>改 payload（換成對的員工編號）</td></tr>
 *   <tr><td>組織系統<b>故障</b></td>
 *       <td>連線逾時／無法連線／5xx／其他 4xx／回應無法解析</td>
 *       <td><b>503</b></td><td>稍後重試，payload 不用改</td></tr>
 * </table>
 *
 * <p><b>為什麼故障要回 503 而不是 400</b>：守衛在
 * {@code startProcessInstanceByKey} <b>之前</b>，被拒時沒有任何東西被建立 ——
 * 所以重試是<b>安全的</b>，不會產生重複流程。用 400 會讓批次不重試，
 * 組織恢復後要人工重跑。
 *
 * <p><b>為什麼仍要 fail-closed（不放行）</b>：方向不變。放行等於回到缺陷本身
 * （指派給一個沒有人能持有的身分），而這是簽核系統裡最難察覺的失敗型態。
 * 裁決改的是<b>狀態碼語意</b>（要不要重試），不是<b>要不要拒絕</b>。
 * 兩者常被混為一談 —— fail-open 是安全問題，400/503 是可用性問題。
 *
 * <h2>⚠️ 分界線是「HTTP 404」，而且為什麼它剛好等於「查無此人」</h2>
 *
 * <p>判準是「<b>組織系統有沒有給出一個明確的拒絕</b>」，在 HTTP 上就是
 * 404（{@code HttpClientErrorException.NotFound}）：
 * <ul>
 *   <li>本專案的 {@code MockOrgController} <b>刻意 fail-closed</b>
 *       （P2-7）—— 對 fixture 外的 id 丟 404，訊息寫明「fixture 裡沒有這個」。
 *       改動前它對任何 id 都捏造 {@code mgr001}，而那正是 #83 的缺陷
 *       兩輪都沒被任何測試抓到的原因。所以 <b>404 = 明確拒絕</b>。</li>
 *   <li>而鏈頂人員（{@code dir001}／{@code admin001}）<b>不是</b> 404：
 *       mock 對他們回 {@code {}}（存在但沒有主管），這是本 repo
 *       <b>刻意保留</b>的區分 —— 一個實在的組織系統也必須這樣回答，
 *       否則「查得到但沒有主管」與「查不到」就無法分辨。</li>
 * </ul>
 *
 * <p><b>為什麼其他 4xx 不算「查無此人」</b>：組織系統回 401／403 代表的是
 * 「<b>我們</b>沒被允許查」（憑證或權限設定錯了），不是「這個人不存在」。
 * 回 400 會告訴呼叫端「改你的 payload」，而正確的動作是修我們自己的設定 ——
 * 那正是「400 讓批次不重試」這個後果最貴的一種。
 *
 * <h2>⚠️ 分不出來的情形：200 但內容不足</h2>
 *
 * <p>若組織系統對未知 id 回 <b>200 + 空的內容</b>（既不捏造也不報錯），
 * 本方法會把它當成「他存在但沒有主管」而放行。這是本類別註解早就記錄過的
 * 前提：<b>「組織系統說這個人存在」只有在它 fail-closed 時才是可信證據。</b>
 * 不為此多打一次網路（那要一個「這個人存不存在」的 API，而
 * {@code getDirectManager} 的語意已經被整個 repo 的 BPMN 運算式依賴）。
 *
 * <p>因為 400 與 503 分開了，錯誤訊息也<b>分成兩句</b>：400 那句講「換 id」，
 * 503 那句講「我們查不到、請稍後重試」。運維不必靠讀錯誤訊息來分辨故障在哪一側。
 *
 * <h2>為什麼是 400／503 而不是 403／404</h2>
 *
 * <p>403 = 授權（那是 {@code allowedProcessKeys} 與候選群組白名單的位置），
 * 404 = 資源不存在（流程定義不存在的位置）。這兩者都不能重複使用：
 * 403 會確認物件存在，對可枚舉的 id 等於留枚舉管道。
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
 * 拋 {@code ResponseStatusException} 與拋任何其他例外一樣會 rollback 交易，
 * 而此時沒有任何資料被寫入，所以 rollback 正是想要的行為。
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
 *
 * <h2>候選群組白名單為什麼也在這個類別裡（#88 政策 B）</h2>
 *
 * <p>「外部系統指名的東西必須是被授權的」是同一個主題的兩面：
 * {@code firstTaskAssignee} 那面是「必須是人」，{@code firstTaskCandidateGroups}
 * 那面是「必須是被授權的群組」。放在一起的理由不是方便，而是
 * <b>兩者都必須擋在 {@code startProcessInstanceByKey} 之前</b> ——
 * 放錯位置就會留下一個已經存在、卻沒有人能簽的案件。
 */
@Component
public class ExternalActorGuard {

    private static final Logger log = LoggerFactory.getLogger(ExternalActorGuard.class);

    /** 候選群組白名單是授權設定，規則由 ExternalSystemPolicy 唯一負責。 */
    private final OrgService orgService;
    private final ExternalSystemPolicy policy;

    public ExternalActorGuard(OrgService orgService, ExternalSystemPolicy policy) {
        this.orgService = orgService;
        this.policy = policy;
    }

    /**
     * 這個欄位指名的身分必須是組織系統認識的人。
     *
     * <p>{@code userId} 為 {@code null} 時<b>直接放行</b>：
     * 「沒有指名」是呼叫端合法的選擇（改用候選群組或代發），
     * 由呼叫端自己的「至少有一個」規則處理，不是這條規則的事。
     *
     * <p>⚠️ <b>呼叫端不是都「發起流程」</b>。本方法在 #93a 之後有五個呼叫點，
     * 其中四個（reassign／delegate／countersign，以及外部 API 的 onBehalfOf）
     * <b>不會發起流程</b>。錯誤訊息若寫死「未發起流程」「請改用
     * {@code onBehalfOf}」，就是在對改派／委派／加簽的呼叫端說一件他們的
     * 請求裡根本不存在的事 —— <b>診斷訊息指向錯誤的欄位比沒有訊息更糟</b>。
     * 所以 action 由呼叫端傳入。
     *
     * @see #requireKnownPerson(String, String, String)
     */
    public void requireKnownPerson(String field, String userId) {
        requireKnownPerson(field, userId, DEFAULT_ACTION);
    }

    /** 未指定 action 時的預設值 —— 只有外部 API 那個呼叫點適用。 */
    private static final String DEFAULT_ACTION = "發起流程";

    /**
     * 這個欄位指名的身分必須是組織系統認識的人。
     *
     * <p>{@code userId} 為 {@code null} 時<b>直接放行</b>：
     * 「沒有指名」是呼叫端合法的選擇（改用候選群組或代發），
     * 由呼叫端自己的「至少有一個」規則處理，不是這條規則的事。
     *
     * @param field   body 欄位名，放進錯誤訊息讓呼叫端知道要改哪一個
     * @param userId  要指派的人員 id；{@code null} = 未指名（放行）
     * @param action  這個呼叫點「做了什麼」，放進錯誤訊息。⚠️ **它不是裝飾**：
     *                五個呼叫點裡只有一個真的會發起流程，見類別註解與
     *                {@link #requireKnownPerson(String, String)} 的說明
     * @throws ResponseStatusException 400（查無此人）或 503（組織系統故障），
     *         分界線見類別註解「故障與拒絕必須分開」
     */
    public void requireKnownPerson(String field, String userId, String action) {
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
        // catch 全部例外是刻意的 fail-closed（不論故障還是查無此人都不放行），
        // 但**狀態碼分開** —— 見類別註解「故障與拒絕必須分開」。
        try {
            orgService.getDirectManager(userId);
        } catch (Exception e) {
            if (isDefinitiveRejection(e)) {
                // 組織系統明確回答「查無此人」：呼叫端該改 payload。
                log.warn("指定的 {}={} 不是組織系統認識的人員，拒絕（{}）: {}",
                        field, userId, action, e.toString());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + "=" + userId + " 不是組織系統認識的人員，無法指派給他。"
                                + "請確認 id 拼寫（大小寫、前後空白都會影響比對）。"
                                + "若該人員確實存在，請改用確實存在的員工編號。",
                        e);
            }
            // 我們沒拿到答案（連線逾時／無法連線／5xx／其他 4xx／無法解析）。
            // log.error 而非 warn：這一筆是基礎設施故障，需要有人被通知。
            log.error("組織系統查詢失敗（{}={}），無法判定該人員是否存在，"
                    + "回 503 讓呼叫端稍後重試（{}）: {}", field, userId, action, e.toString(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "組織系統目前無法查詢（" + e.getClass().getSimpleName() + "），"
                            + "無法確認 " + field + "=" + userId + " 是否為有效人員，因此未執行 " + action + "。"
                            + "這是暫時性問題，請稍後以相同的參數重試 —— "
                            + "本次請求未做任何變更，重試不會產生重複案件。",
                    e);
        }
    }

    /**
     * 組織系統的回應是否是<b>明確的拒絕</b>（查無此人），而不是故障。
     *
     * <p>判準是 HTTP 404。理由見類別註解「分界線是 HTTP 404」。
     *
     * <h2>為什麼只認 404，其他 4xx 一律當故障</h2>
     *
     * <p>401／403 描述的是<b>我們</b>的問題（憑證或權限設定錯了），
     * 不是「這個人不存在」。把它們當成 400 會讓呼叫端去改 payload，
     * 而它改了也一樣失敗 —— 那是把「我們壞了」說成「你送錯了」。
     *
     * <p>⚠️ 刻意<b>不</b>用 {@code e instanceof RestClientResponseException}
     * 搭配「4xx 都算拒絕」：那會讓一個壞掉的權限設定看起來像
     * 「查無此人」，而症狀會變成批次大量回 400、沒有人查基礎設施。
     * 預設方向是<b>把不確定當成故障</b>（503 會被重試、會被告警），
     * 而不是當成拒絕（400 不會被重試、不會被告警）。
     */
    private static boolean isDefinitiveRejection(Exception e) {
        return e instanceof HttpClientErrorException.NotFound;
    }

    /**
     * 送來的每一個候選群組都必須在該系統的 {@code allowedCandidateGroups} 內
     * （#88 政策 B）。
     *
     * <h2>⚠️ 參數是<b>已切開的清單</b>，不是原始字串（#93）</h2>
     *
     * <p>改動前簽名是 {@code (ExternalSystem, String raw)}，本方法自己在裡面
     * {@code raw.split(",")}。而 {@code String raw} 這個簽章就是 #93 的成因：
     * {@code docs/bpm-platform-spec.md} §9.2 示範的是 JSON <b>陣列</b>
     * {@code "firstTaskCandidateGroups": ["hr_dept"]}，而呼叫端在 controller
     * 裡先做 {@code (String) body.get(...)} —— 送陣列就在<b>那一行</b>拋
     * {@code ClassCastException}，回 500 且沒有訊息（#73 只回傳刻意丟出的理由）。
     *
     * <p><b>為什麼不用 Jackson 的 {@code convertValue}、也不在 controller 擋掉陣列</b>：
     * 那兩種做法都會讓本方法<b>繼續只認字串</b>，於是「請求長什麼形狀」與
     * 「群組怎麼驗」又變成兩處各自決定。改成收 {@code List<String>} 之後，
     * 邊界（body 這個欄位是什麼形狀）由<b>呼叫端唯一決定一次</b>，而
     * trim／丟棄空白／去重／白名單仍然<b>只有這一份</b> ——
     * 也就是本 repo 的硬規則「規則只能有一份」。
     *
     * <h2>切分之後，邊界是誰的責任</h2>
     *
     * <ul>
     *   <li><b>形狀</b>（body 是陣列還是逗號分隔字串、元素是什麼型別）
     *       → 請求契約的邊界檢查 → {@code ExternalApiController}。</li>
     *   <li><b>內容</b>（trim、丟棄空白、去重、白名單）→ 本方法。</li>
     * </ul>
     *
     * <p>理由是「形狀錯了」與「這個群組沒被授權」是<b>兩件不同的事</b>：
     * 前者的診斷是型別（回 400），後者的診斷是授權（回 403）。混在一個方法裡
     * 就會出現「一個沒有設白名單的系統因為型別不符而拿到 400」這種
     * 把「我們的設定」說成「你送錯了」的回應。
     *
     * <h2>前置條件：元素皆為非 null 的 {@code String}</h2>
     *
     * <p>本方法對每個元素做 {@code g.trim()}，所以<b>不</b>接受 null 元素 ——
     * 傳進 null 元素會是 {@code NullPointerException} → 500，而那正是本工項
     * 要修的失敗型態。這個前置條件由呼叫端的形狀解析保證（見
     * {@code ExternalApiController.parseCandidateGroups}）。
     *
     * <p><b>為什麼不在本方法加 null 防護</b>：那會是第二份「什麼算合法群組名稱」
     * 的規則。擋掉的正確位置是唯一決定形狀的那一段 —— 在那裡回 400 還能給出
     * 指名元素索引的診斷；在這裡擋掉只能回一句無法定位的 400。
     *
     * <h2>為什麼回傳解析後的清單，而不是讓呼叫端自己再 split 一次</h2>
     *
     * <p>呼叫端原本是 {@code firstGroups.split(",")} 再逐個
     * {@code addCandidateGroup}。若驗證與套用各自解析一次，就是
     * <b>同一條規則兩套形狀</b>（#84／#86 的成因）：驗證過的清單與
     * 實際寫進 identity link 的清單可能不一致（例如某個實作忘了 trim），
     * 而且「驗證了 3 個群組、實際寫了 4 個」這種 bug 不會有任何錯誤。
     * 由本方法回傳<b>同一份</b>清單，兩邊不可能對不起來。#93 之後這一份
     * 是「形狀解析後、內容規則處理後」的結果，而兩段責任仍然各只有一份。
     *
     * <h2>空白項目為什麼丟棄而不是拒絕</h2>
     *
     * <p>改動前 {@code " , "} 會被 split 成幾個空字串並原樣
     * {@code addCandidateGroup(taskId, "")} —— 建立名為空字串的候選群組。
     * 丟棄它們不是放寬：**沒有人會是空字串群組的成員**，所以丟棄不會讓
     * 任何群組的成員看不到任務。它修掉的是兩個實際問題：
     * <ul>
     *   <li>設定了白名單時，空字串不在清單內 → 403 的訊息會指名一個
     *       「看得見但看不懂」的群組名。</li>
     *   <li>空字串的 identity link 會讓 {@code UnreachableTaskListener}
     *       的 {@code hasCandidate} 為真（它只檢查 {@code getGroupId() != null}），
     *       於是「實際上沒有任何人能簽」的情況<b>不會告警</b>。丟棄之後
     *       listener 就會如實回報 —— 見 {@link #requireKnownPerson}
     *       的空白那一層為什麼拒絕而這裡只丟棄。</li>
     * </ul>
     *
     * <p>⚠️ <b>刻意不在這裡拒絕</b>「送出空白群組」：那是政策決定
     * （該回 400 還是當成沒指定），本工項不代 PM 做。
     * 但清單被丟空之後 {@code startProcess} 的「至少有一個」規則
     * 會如實看到「沒有群組」，所以不會變成靜默卡死。
     *
     * <p>⚠️ <b>空清單（{@code []} 與 {@code ["  "]}）同樣不在這裡拒絕</b>：
     * 解析後是空清單，於是 {@code startProcess} 的「至少有一個」規則會回 400。
     * 在這裡再加一條「空清單要拒絕」就是同一條規則兩套形狀。
     *
     * @param sys   發起方；白名單為空時<b>不限制</b>（見 ExternalSystemPolicy）
     * @param raw   已由呼叫端切開的群組清單（元素<b>未</b> trim、<b>不可</b>為
     *              null；相容形狀的逗號分隔已由呼叫端處理）。{@code null}
     *              或空清單 = 未指定任何群組。
     * @return 解析後的群組清單（trim 後、已丟棄空白項目、去重並保留順序）
     * @throws ResponseStatusException 403（白名單不包含某個群組）
     */
    public List<String> requireAllowedCandidateGroups(ExternalSystem sys, List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();

        // 去重並保留順序：用 LinkedHashSet 讓 "dept001,dept001" 只寫一次，
        // 同一組輸入的結果因此與書寫順序無關（deterministic）。
        Set<String> groups = new LinkedHashSet<>();
        for (String g : raw) {
            String name = g.trim();
            if (name.isEmpty()) continue;
            if (!policy.isCandidateGroupAllowed(sys, name)) {
                log.warn("系統 {} 嘗試指定未授權的候選群組 {}（白名單: {}），拒絕發起流程",
                        sys.getSystemId(), name, sys.getAllowedCandidateGroups());
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "此系統未被授權使用候選群組: " + name
                                + "。firstTaskCandidateGroups 的每一個群組都必須先被管理員"
                                + "授權（allowedCandidateGroups）；請改用已授權的群組，"
                                + "或請管理員在外部系統設定中授權這個群組。");
            }
            groups.add(name);
        }
        return List.copyOf(groups);
    }
}
