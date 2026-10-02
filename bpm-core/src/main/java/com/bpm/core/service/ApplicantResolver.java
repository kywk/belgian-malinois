package com.bpm.core.service;

import com.bpm.core.external.ExternalActorIdentity;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Service;

/**
 * 決定<b>申請者補件關卡</b>的受理人（#83）。
 *
 * <h2>它修的缺陷是什麼</h2>
 *
 * <p>兩支 BPMN 的補件關卡原本寫死 {@code flowable:assignee="${initiator}"}：
 * <ul>
 *   <li>{@code leave-approval} 的 {@code applicantRevision}</li>
 *   <li>{@code purchase-approval} 的 {@code revisionFromManager}／{@code revisionFromFinance}</li>
 * </ul>
 *
 * <p>人工發起時 {@code initiator} 就是申請人本人，所以這行一直沒問題。
 * 但外部系統發起時 {@code initiator} 是 {@code system:<id>}（R-20）——
 * <b>那不是一個人</b>。主管把那張單退回時，補件關卡的 assignee 就會是
 * {@code system:erp}，而 {@link com.bpm.core.security.TaskHolderGuard} 的
 * 四個條件（assignee／owner／candidateUser／候選群組）全部不命中：
 *
 * <blockquote><b>沒有任何人能簽，案件永久卡死，而且沒有任何錯誤訊息。</b></blockquote>
 *
 * <p>更糟的是 {@link com.bpm.core.engine.UnreachableTaskListener} 也不會告警 ——
 * 它只擋 {@code assignee} 為 null／空白，而 {@code "system:erp"} 非空白。
 * 這是簽核系統裡最難察覺的失敗型態（靜默卡死）。
 *
 * <h2>決定順序（三段，順序本身就是政策）</h2>
 *
 * <ol>
 *   <li><b>{@code onBehalfOf} 有值 → 那位員工。</b>
 *       這是 backlog #68c <b>已定調</b>的規則（{@code ExternalApiController}
 *       在啟動時已驗證 {@code onBehalfOf} 是組織系統認識的人，
 *       見 {@link com.bpm.core.external.ExternalActorGuard} —— #88 起
 *       {@code onBehalfOf} 與 {@code firstTaskAssignee} 共用那一條規則）。
 *       必須是第一段：代員工發起時 {@code initiator} 仍然是
 *       {@code system:<id>}，不先取 {@code onBehalfOf} 就會把員工自己的單
 *       派給一個無關的收件人。</li>
 *   <li><b>{@code initiator} 是人 → {@code initiator}。</b>
 *       人工發起的既有行為，<b>完全不變</b>。這一段存在的理由是
 *       「不要為了修一個邊界情況而改掉最常見的情況」——
 *       補件關卡在人工發起時是整個產品最常走的一條路。</li>
 *   <li><b>{@code initiator} 是系統身分 → 系統受理人。</b>
 *       這一格沒有任何資料可以推導出答案：這張單沒有自然人申請人。
 *       它必須是<b>設定</b>出來的，見下一節。</li>
 * </ol>
 *
 * <h2>⚠️ 第三段的政策決定：為什麼是「權限碼」而不是外部系統的設定欄位</h2>
 *
 * <p>backlog 寫的解法方向是「改指 {@code onBehalfOf} 或<b>系統設定的受理人</b>」。
 * 「系統設定」有三種可能，本類別選了其中一種：
 *
 * <table border="1">
 *   <caption>三個候選方案</caption>
 *   <tr><th>方案</th><th>要動到什麼</th><th>為什麼沒選</th></tr>
 *   <tr>
 *     <td><b>(A) 外部系統設定檔新增欄位</b><br>
 *         {@code bpm_external_system.default_handler}</td>
 *     <td>DB schema 變更（{@code @Column} ＋ Flyway migration）
 *         ＋ entity ＋ admin API ＋管理頁欄位＋前端＋授權</td>
 *     <td>遠超過本工項的 1d，而且<b>它解決不了真正的問題</b>：
 *         一個欄位只能指定一個 userId，而「這張單沒有申請人」是所有
 *         外部系統共用的事實，不是 erp 與 crm 各自不同的事實。
 *         見下方「放棄了什麼」。</td>
 *   </tr>
 *   <tr>
 *     <td><b>(B) 一個可設定的 bean</b><br>
 *         {@code application.yml} 裡寫一個預設 userId</td>
 *     <td>零 schema 變更</td>
 *     <td>那是<b>影子授權</b>：一份藏在設定檔裡、不在權限中心、
 *         業務人員看不到也改不了的名單。本專案的整套授權
 *         （{@code audit:log:read}／{@code bpm:form:design}／
 *         {@code finance:payment:approve}）都走權限中心，
 *         再開一條路就出現「同一個問題有兩份答案」——
 *         這正是本 repo 反覆記載的缺陷成因（見
 *         {@code ProcessAccessGuard} 與 {@code TaskHolderGuard} 的類別註解）。</td>
 *   </tr>
 *   <tr>
 *     <td><b>(C) 權限中心的一個權限碼</b> ← <b>本類別採用的</b><br>
 *         {@code bpm:external:revision}</td>
 *     <td>只加一個 fixture 條目。零 DB schema 變更、零前端改動</td>
 *     <td>—</td>
 *   </tr>
 * </table>
 *
 * <p>採 (C) 的理由還有一條實質的：{@link BpmPermissionService#getFirstAvailableUser}
 * <b>已經處理了代理人與休假</b>。若用一個靜態的 userId，
 * 那個人休假時補件關卡會指派給一個不在的人 —— 那是<b>同一個缺陷的換皮</b>
 * （任務有人持有，但持有者不會動它，而且沒有任何機制會發現）。
 *
 * <h2>⚠️ 找不到受理人時為什麼<b>拋例外</b>而不是回 null</h2>
 *
 * <p>回 {@code null} 會讓 Flowable 建立一個沒有 assignee、也沒有候選人的任務 ——
 * 也就是<b>換一種方式製造同一個靜默卡死</b>。
 * {@link InitialAssigneeResolver} 的單元測試把這個寫成斷言
 * （{@code orgLookupFailurePropagates}：「靜默回 null 會產生一個沒有受理人、
 * 也沒有候選群組的任務 —— 流程看起來啟動成功，實際上停在沒有人看得到的地方」），
 * {@link OrgService#getManagerAtLevel} 也是同樣的取捨
 * （「完全沒有主管則沒有任何說得過去的答案，拋例外」）。本類別沿用同一條規則。
 *
 * <p>而且拋錯的時機比「卡住」好：補件關卡是在主管按「退回」的當下建立的，
 * 拋錯會讓那一次 {@code complete()} 整個回滾 —— 主管當場看到錯誤、
 * 案件仍停在他手上、錯誤訊息指名缺的是哪個權限碼。
 * 回 null 的話主管會看到「退回成功」，然後那張單就沒有人管了。
 *
 * <h2>⚠️ 交易內的外部呼叫（security-audit P1-10）</h2>
 *
 * <p>第三段會呼叫 {@code permService.getFirstAvailableUser(...)}，
 * 而它底下是對 bpm-core 自己的同步 HTTP。呼叫時機是任務建立，
 * 也就是在 {@code taskService.complete(...)} 的 DB 交易之內。
 *
 * <p>這與既有做法同級而非新增風險：{@code assigneeResolver} 在啟動時就會
 * {@code orgService.getDirectManager(...)}，而 {@code purchase-approval} 的
 * {@code financeReview} 用 {@code permService.getUsersByPermission(...)}
 * —— 兩者都在交易內。兩處都有 Redis 快取兜住頻率
 * （權限清單 5 分鐘、代理人 1 分鐘）。
 *
 * <h2>與 backlog #68c 的關係</h2>
 *
 * <p>第一段<b>就是</b> #68c 已定調的規則（「{@code onBehalfOf} 有值用它，
 * 否則用 {@code initiator}」）。之所以在這個工項裡一併實作，是因為
 * 離開它這個決定函式就不是一個函式：{@code onBehalfOf} 有值時
 * {@code initiator} 仍然是 {@code system:<id>}，不先取 {@code onBehalfOf}
 * 就等於在未經裁決的情況下替 #68c 選了另一個答案。
 * 三段全部都是 #68c 那句規則的延伸，沒有新增任何政策。
 *
 * <h2>#3（2026-10-02 裁決）：催辦權也走同一條規則</h2>
 *
 * <p>{@code TaskController.urgeTask} 原本只認自然人申請人
 * （{@code onBehalfOf} 優先、其次 {@code initiator}），於是
 * <b>系統發起且沒有 {@code onBehalfOf} 的案件沒有任何人能催辦</b> ——
 * 那是本類別第三段要修的同一種「沒有自然人」缺陷的另一面。
 *
 * <p>裁決是「催辦權開放給同一批系統受理人」：{@link #resolveApplicant}
 * 因此是本規則<b>唯一</b>的實作，補件關卡與催辦共用它。
 * 兩者分岔的下場是組合型式的：催得到的人簽不掉、
 * 或簽得到的人催不動，而兩邊單獨看都正常。
 *
 * <p>⚠️ 只有「沒有自然人申請人」的案件才走到第三段。自然人案件的受理人
 * 即使持有 {@link #PERM_EXTERNAL_REVISION} <b>也沒有</b>催辦權 ——
 * 那張單的申請人是 initiator，不會因為權限碼而換人。
 */
@Service
public class ApplicantResolver {

    /**
     * 「可以承辦外部系統案件補件關卡<b>與催辦</b>」的權限碼。
     *
     * <p>#3（2026-10-02 裁決）起，它同時回答「系統案件誰可以催辦」
     * —— 刻意與補件關卡共用一個碼、一個持有人清單（見類別註解）。
     *
     * <p>沿用 {@code domain:resource:action} 三段式（見
     * {@code AuthorityResolver.PERM_FORM_DESIGN} 的命名說明），
     * {@code bpm} 是本系統自己的命名空間 —— 它不是任何業務網域的簽核權限。
     */
    public static final String PERM_EXTERNAL_REVISION = "bpm:external:revision";

    private final BpmPermissionService permService;

    public ApplicantResolver(BpmPermissionService permService) {
        this.permService = permService;
    }

    /**
     * BPMN 由此決定補件關卡的受理人：
     * {@code flowable:assignee="${applicantResolver.resolve(execution)}"}。
     *
     * <h2>⚠️ 參數是 {@code execution} 而不是裸變數 —— 這點不可改回去</h2>
     *
     * <p>與 {@link InitialAssigneeResolver#resolve(DelegateExecution)} 同一個理由：
     * Flowable 的 JUEL 在呼叫方法<b>之前</b>就要解析每個識別字，
     * {@code resolve(initiator, onBehalfOf)} 這種寫法會讓人工發起（沒有
     * {@code onBehalfOf} 這個變數）在<b>每一次補件</b>都拋
     * {@code Unknown property used in expression}。
     * 用 {@code execution} 之後變數缺席就是 null，呼叫端完全不用配合。
     *
     * @return 一個<b>保證不是</b> {@code system:*} 的 userId，或拋出
     *         {@link IllegalStateException}（找不到受理人時）
     */
    public String resolve(DelegateExecution execution) {
        // ⚠️ onBehalfOf 先讀：命中時連 initiator 都不讀（既有行為，
        // ApplicantResolverTest 釘住這個順序）。因此 initiator 以延遲
        // 求值傳入共用規則，而不是先讀成兩個字串。
        String onBehalfOf = str(execution.getVariable(InitialAssigneeResolver.ON_BEHALF_OF_VAR));
        return applyRule(onBehalfOf, () -> str(execution.getVariable("initiator")));
    }

    /**
     * 三段規則的共用入口：給「已經有 {@code onBehalfOf} 與 {@code initiator}
     * 兩個值」的呼叫端（{@code TaskController.urgeTask}，見類別註解 #3）。
     *
     * <p>{@code onBehalfOf} 必須放第一個參數 —— 順序就是政策，
     * 代發案件的 {@code initiator} 仍是 {@code system:<id>}。
     *
     * @return 一個<b>保證不是</b> {@code system:*} 的 userId，或拋出
     *         {@link IllegalStateException}（沒有自然人申請人且權限中心
     *         也給不出受理人時）
     */
    public String resolveApplicant(String onBehalfOf, String initiator) {
        return applyRule(onBehalfOf, () -> initiator);
    }

    /**
     * 三段規則的<b>唯一實作</b>，由 {@link #resolve(DelegateExecution)}
     * 與 {@link #resolveApplicant(String, String)} 共用。
     *
     * <p>{@code initiator} 用 {@link java.util.function.Supplier} 是為了讓
     * {@code resolve(execution)} 保留既有的短路讀取（見該方法）。
     * 直接有字串的呼叫端不必知道這件事。
     *
     * <p>回傳值一定是自然人（非 {@code system:*}）；找不到時拋
     * {@link IllegalStateException}，<b>不回 null</b> —— 理由見類別註解
     * 「找不到受理人時為什麼拋例外」。
     */
    private String applyRule(String onBehalfOf, java.util.function.Supplier<String> initiator) {
        // 第一段：代員工發起 → 那位員工本人。見類別註解「決定順序」。
        if (isSet(onBehalfOf) && !ExternalActorIdentity.isSystemActor(onBehalfOf)) {
            return onBehalfOf;
        }

        // 第二段：人工發起 → 申請人本人。既有行為，完全不變。
        String initiatorId = initiator.get();
        if (isSet(initiatorId) && !ExternalActorIdentity.isSystemActor(initiatorId)) {
            return initiatorId;
        }

        // 第三段：沒有自然人申請人 → 系統受理人（政策，見類別註解）。
        String handler = permService.getFirstAvailableUser(PERM_EXTERNAL_REVISION);
        if (!isSet(handler)) {
            throw new IllegalStateException(
                    "這張案件的發起人是 " + (isSet(initiatorId) ? initiatorId : "(未設定)")
                            + "（系統身分，不是人），因此沒有任何人可以補件或催辦。"
                            + "請在權限中心指派 " + PERM_EXTERNAL_REVISION + " 的持有人，"
                            + "或讓外部系統改用 onBehalfOf 代員工發起。");
        }
        if (ExternalActorIdentity.isSystemActor(handler)) {
            // 權限中心不該回系統身分，但若真的回來了，寧可失敗也不要派給一個
            // 沒有任何人能持有的身分 —— 那就是本類別存在的理由本身。
            throw new IllegalStateException(
                    PERM_EXTERNAL_REVISION + " 的持有人回傳了系統身分 " + handler
                            + "，它沒有人能簽。請修正權限中心的指派。");
        }
        return handler;
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
