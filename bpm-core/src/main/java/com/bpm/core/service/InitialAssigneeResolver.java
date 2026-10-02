package com.bpm.core.service;

import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 決定流程第一個人工任務的受理人。
 *
 * <h2>為什麼需要把這個決定抽出來（P2-7 施作時發現）</h2>
 *
 * <p>原本兩支 BPMN 的 {@code managerReview} 都寫死
 * {@code flowable:assignee="${orgService.getDirectManager(initiator)}"}。
 * 人工發起時這是對的，但<b>外部系統發起時 initiator 是 {@code system:erp}</b>
 * —— 那不是一個人，組織系統永遠查不到它的主管。
 *
 * <p>這個缺陷一直沒被發現，是因為開發用的組織 mock 對不認識的 userId
 * 一律回 {@code mgr001}。而外部 API 的測試剛好都用 {@code firstTaskAssignee=mgr001}，
 * 於是捏造的答案與預期答案<b>恰好相同</b>，測試全綠。
 * mock 改為 fail-closed 之後，TC-A04 的三個案例立刻失敗 ——
 * 那才是這條路徑在正式環境的真實行為。
 *
 * <h2>{@code firstTaskCandidateGroups} 的情況更糟</h2>
 *
 * <p>只給候選群組時，BPMN 仍會算出 {@code mgr001} 並<b>指派</b>給他，
 * 之後 {@code ExternalApiController} 才加上候選群組。任務已有 assignee，
 * 群組成員<b>永遠認領不到</b>。正確行為是留空，讓群組成員自行認領。
 *
 * <h2>為什麼不把三元運算式塞進 BPMN</h2>
 *
 * <p>三種情況的判斷寫成 JUEL 會是一串嵌套三元式，而且分散在兩支 BPMN 裡 ——
 * 改一次要改兩個地方，也無法單獨測試。放在一個有名字的方法裡，
 * BPMN 只需表達意圖，判斷邏輯有唯一來源且可測。
 *
 * <h2>#5：解析出「人」之後一律過代理人（{@code resolveEffective}）</h2>
 *
 * <p>第一關受理人決定後，若那個人設了代理人（休假／出差），任務要改派給
 * 代理人 —— 否則案件會停在休假期間的收件匣裡，而代理人明明就在。
 * 這是 {@code BpmPermissionService.getFirstAvailableUser}（第三段）早就
 * 在做的事，但一般指派路徑一直沒有，於是「全部不在」的權限挑人會走代理人、
 * 而一般主管路由不會 —— 同一條「誰來簽」的規則有兩套形狀。
 *
 * <p>代換<b>只套用在解析出真人的兩格</b>：
 * <ul>
 *   <li><b>明確受理人</b>（外部系統的 {@code firstTaskAssignee}）—— 是真人，
 *       所以會代換。存在性驗證（{@code ExternalActorGuard}）在呼叫端、
 *       <b>代換之前</b>就已經做過（見 {@code ExternalApiController}），
 *       這裡不繞過它。</li>
 *   <li><b>主管</b>（人工發起或 {@code onBehalfOf}）—— 是真人，所以會代換。</li>
 *   <li><b>只給候選群組</b> —— <b>不代換</b>。這一格本來就沒有指名任何人，
 *       也就不存在「這個人休假」的對象；回傳任何人都會把群組派工變成
 *       單人指派，正是上面那條規則要避免的。</li>
 * </ul>
 *
 * <p>⚠️ {@code resolveEffective} 只解<b>一層</b>（代理人的代理人不再往下），
 * 與 {@code BpmPermissionService} 的既有語意一致。刻意如此：代理鏈可能成環，
 * 而且「代理人的代理人」已超出原持有人的委託意圖。
 *
 * <p>⚠️ <b>動態子任務（加簽）、reassign／delegate 不走這裡。</b>
 * 那些是明確的人為選擇（「我指定他」），不是平台自動路由 ——
 * 代換它們等於推翻按下按鈕的人的決定。
 */
@Service
public class InitialAssigneeResolver {

    /** 流程變數名稱。三個啟動路徑都必須設定，否則 JUEL 會拋 PropertyNotFound。 */
    public static final String FIRST_ASSIGNEE_VAR = "firstTaskAssignee";
    public static final String FIRST_GROUPS_VAR = "firstTaskCandidateGroups";
    /** 外部系統代員工發起時的員工（R-20）。只有被授權的系統能設定，見 ExternalApiController。 */
    public static final String ON_BEHALF_OF_VAR = "onBehalfOf";

    private final OrgService orgService;

    public InitialAssigneeResolver(OrgService orgService) {
        this.orgService = orgService;
    }

    /** 只有外部 API 需要寫入這兩個變數；人工發起不必設定（見 {@link #resolve}）。 */
    public static void putIfPresent(Map<String, Object> variables,
                                    String firstAssignee, String firstGroups) {
        if (firstAssignee != null) variables.put(FIRST_ASSIGNEE_VAR, firstAssignee);
        if (firstGroups != null) variables.put(FIRST_GROUPS_VAR, firstGroups);
    }

    /**
     * BPMN 由此決定第一個任務的受理人。
     *
     * <h2>⚠️ 參數是 {@code execution}，不是三個變數 —— 這點不可改回去</h2>
     *
     * <p>第一版寫成 {@code resolve(initiator, firstTaskAssignee, firstTaskCandidateGroups)}，
     * 結果 40 個測試壞掉：Flowable 的 JUEL 在呼叫方法<b>之前</b>就要解析每個
     * 識別字，未定義的變數名稱會直接拋
     * {@code Unknown property used in expression}。
     *
     * <p>那個寫法等於要求<b>每一個</b>啟動流程的地方都先塞好哨兵變數 ——
     * 包含三個 controller、所有測試輔助方法，以及日後任何新增的啟動路徑。
     * 漏一個就是執行期爆炸，而且錯誤訊息指向 BPMN 而不是漏掉的那行程式。
     *
     * <p>改成接 {@code execution}（Flowable 一律提供）之後，變數缺席就是 null，
     * 呼叫端完全不用配合。這是把「不變式」放在只有一個實作的地方，
     * 而不是散佈成每個呼叫端都要記得的約定。
     *
     * <p>回傳 {@code null} 代表<b>刻意不指派</b> —— 任務留在候選群組裡等人認領。
     */
    public String resolve(DelegateExecution execution) {
        String initiator = str(execution.getVariable("initiator"));
        String firstTaskAssignee = str(execution.getVariable(FIRST_ASSIGNEE_VAR));
        String firstTaskCandidateGroups = str(execution.getVariable(FIRST_GROUPS_VAR));
        // 明確指定受理人時不查組織 —— 這是呼叫端的決定，而且
        // initiator 可能根本不是人（system:erp），查了只會失敗。
        if (isSet(firstTaskAssignee)) {
            return effectiveAssignee(firstTaskAssignee);
        }
        // 只給候選群組 → 留空，讓群組成員認領。
        // 這裡若回傳任何人，就等於把群組派工變成單人指派。
        // ⚠️ 這一格不套用代理人：沒有指名任何人，就沒有「這個人休假」的對象。
        if (isSet(firstTaskCandidateGroups)) {
            return null;
        }
        // 代員工發起：路由到那位員工的主管。initiator 此時是 system:<id>，不是人。
        String onBehalfOf = str(execution.getVariable(ON_BEHALF_OF_VAR));
        String manager = orgService.getDirectManager(isSet(onBehalfOf) ? onBehalfOf : initiator);
        // 鏈頂人員沒有主管（null）＝刻意不指派；null 不查代理人（沒有對象）。
        return manager == null ? null : effectiveAssignee(manager);
    }

    /**
     * 有代理人回代理人，否則回本人（#5）。
     *
     * <p>呼叫端已經驗證過「這個人是組織系統認識的人」之後才呼叫這裡
     * （見 {@link #resolve} 與 {@code ExternalApiController}）——
     * 本方法只做代換，不做存在性驗證，也不查第二次網路。
     *
     * <p>⚠️ <b>外部 API 必須用它，不能直接傳原始值。</b>
     * {@code ExternalApiController} 在流程啟動後會再
     * {@code taskService.setAssignee} 一次第一關受理人；那裡若傳原始值，
     * 就會把 BPMN 在任務建立當下已代換的代理人<b>蓋回休假者本人</b> ——
     * 代換看起來生效了，實際被下一步覆寫，而且沒有任何錯誤訊息。
     * 兩處都走這一支，代換規則才只有一份。
     *
     * @param userId 已驗證存在的人員 id；{@code null}（未指名）原樣回 null
     */
    public String effectiveAssignee(String userId) {
        return userId == null ? null : orgService.resolveEffective(userId);
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
