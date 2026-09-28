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
 */
@Service
public class InitialAssigneeResolver {

    /** 流程變數名稱。三個啟動路徑都必須設定，否則 JUEL 會拋 PropertyNotFound。 */
    public static final String FIRST_ASSIGNEE_VAR = "firstTaskAssignee";
    public static final String FIRST_GROUPS_VAR = "firstTaskCandidateGroups";

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
            return firstTaskAssignee;
        }
        // 只給候選群組 → 留空，讓群組成員認領。
        // 這裡若回傳任何人，就等於把群組派工變成單人指派。
        if (isSet(firstTaskCandidateGroups)) {
            return null;
        }
        return orgService.getDirectManager(initiator);
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
