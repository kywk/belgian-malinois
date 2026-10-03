package com.bpm.core.service;

import com.bpm.core.external.ExternalActorIdentity;
import org.springframework.stereotype.Service;

/**
 * 執行期動態計算審核人的 EL bean（#47）。
 *
 * <h2>缺口：自動路由的「最後一哩」只做了一半</h2>
 *
 * <p>平台既有的挑人服務都在，但放進 {@code flowable:assignee} 時各自缺了
 * 同一種最後一哩：
 *
 * <ul>
 *   <li>{@link OrgService#getManagerAtLevel} 推導第 N 階主管，但<b>不套代理人</b>
 *       —— #5 的代換只放進第一個任務的 {@link InitialAssigneeResolver}。</li>
 *   <li>{@link BpmPermissionService#getFirstAvailableUser} 挑第一位可受理的權限
 *       持有人（全部不在時派第一位的代理人），但<b>沒有持有人時回 null</b>
 *       —— 寫進 assignee 就是一個沒有人看得到的任務。</li>
 *   <li>{@link BpmQueryService#getManagerWithPermission} 沿主管鏈找持有權限碼的
 *       主管，但<b>找不到時回 null，也不套代理人</b>。</li>
 * </ul>
 *
 * <p>本類別把三者包成 assignee 可用的形狀：<b>一律套代理人、一律不回 null</b>。
 * 挑人與走鏈的規則仍只有一份（在原本的三個服務裡），這裡不新增授權政策，
 * 也不查新的來源。
 *
 * <pre>{@code
 * <userTask id="managerReview"
 *           flowable:assignee="${dynamicAssignee.managerAtLevel(initiator, 2)}"/>
 * <userTask id="financeReview"
 *           flowable:assignee="${dynamicAssignee.firstAvailable('finance:payment:approve')}"/>
 * <userTask id="legalReview"
 *           flowable:assignee="${dynamicAssignee.managerWithPermission(initiator, 'legal:contract:review')}"/>
 * }</pre>
 *
 * <h2>為什麼是 EL bean，而不是 serviceTask delegate</h2>
 *
 * <p>backlog 的原名是 {@code DynamicAssigneeDelegate}（spec §13.3），但那個
 * 名字來自 delegate 清單，不是對機制的裁定。這裡選 EL bean，理由：
 *
 * <ul>
 *   <li><b>指派時機正確</b>：運算式在 UserTask 建立當下由
 *       {@code UserTaskActivityBehavior.handleAssignments} 求值，就在寫入
 *       assignee 的同一個 command 裡；拋錯則整個交易回滾。delegate 需要
 *       「先跑一個節點把值寫進流程變數、下一個節點再讀」的兩段式，
 *       中間多一個可能沒被執行到的環節。</li>
 *   <li><b>部署前就檢查得到</b>：assignee 欄位是 lint 規則 g（EL 白名單）／
 *       i（裸變數）／k（非必填變數）的涵蓋範圍；delegate 的
 *       {@code flowable:field expression} 目前不在任何 lint 規則內，
 *       打錯 bean 名稱要等使用者送出才爆 —— 正是本 repo 反覆在修的
 *       「失敗時間與編輯時間脫鉤」。</li>
 *   <li><b>設計師體驗</b>：一個運算式，對比兩個節點加一個必須先宣告的流程
 *       變數（沒宣告就會被規則 i 擋下）。</li>
 * </ul>
 *
 * <p>代價是「找不到人」不能用 boundary error 建模。這是刻意的取捨，
 * 見下一節。
 *
 * <h2>與 {@link InitialAssigneeResolver} 的分工</h2>
 *
 * <p>不重疊也不取代。{@code assigneeResolver} 回答「啟動路徑的第一關該派給
 * 誰」，輸入是流程變數（{@code firstTaskAssignee}／
 * {@code firstTaskCandidateGroups}／{@code onBehalfOf}），並處理外部系統發起。
 * 本類別回答「這個節點該派給誰」，輸入是明確的 userId 與權限碼，
 * <b>不讀那些啟動變數</b>。第一個任務若要用本類別，呼叫端要自己給對
 * userId —— 例如代員工發起時應傳 {@code onBehalfOf} 而不是 {@code initiator}，
 * 那個「該傳哪一個」的判斷仍由 {@code assigneeResolver} 負責。
 *
 * <h2>找不到人時為什麼拋例外，不是回 null 也不是 BpmnError</h2>
 *
 * <p>回 {@code null} 會產生一個沒有 assignee、也沒有候選人的任務 ——
 * 對所有人都不可見，案件靜默卡死。這是本 repo 已有定調的紅線
 * （見 {@link InitialAssigneeResolver} 的單元測試、{@link OrgService#getManagerAtLevel}、
 * {@link ApplicantResolver} 的類別註解）。
 *
 * <p>不用 {@code BpmnError}：這個失敗是「設定／資料缺失」，不是業務分支，
 * 沒有合理的替代路徑可建模；而且 assignee 運算式在 UserTask 建立<b>期間</b>
 * 求值，BpmnError 的 boundary 行為（任務已插入卻被中斷）在本 repo 未經測試。
 * 拋 {@link IllegalStateException} 會讓 {@code complete()}／啟動的整個交易
 * 回滾：呼叫端當場看到錯誤，案件停在上一個人手上，錯誤訊息指名缺的是哪個
 * 權限碼或哪一條鏈。
 *
 * <h2>代理人的既有語意（#5）不變</h2>
 *
 * <p>與 {@link InitialAssigneeResolver#effectiveAssignee} 同一條：
 * 只解一層、代理鏈成環不追、代理人未必持有該權限碼（代理是組織層的委託，
 * 不經過權限中心）。已經是代理人的值不會再代換 ——
 * {@link OrgService#resolveEffective} 對沒有代理人的人回本人。
 */
@Service("dynamicAssignee")
public class DynamicAssigneeResolver {

    private final OrgService orgService;
    private final BpmPermissionService permService;
    private final BpmQueryService bpmQueryService;

    public DynamicAssigneeResolver(OrgService orgService,
                                   BpmPermissionService permService,
                                   BpmQueryService bpmQueryService) {
        this.orgService = orgService;
        this.permService = permService;
        this.bpmQueryService = bpmQueryService;
    }

    /**
     * 第 {@code level} 階主管（1 = 直屬主管）＋代理人代換。
     *
     * <p>與 {@code ${orgService.getManagerAtLevel(initiator, N)}} 只差代理人：
     * 那個方法回的是休假中的主管本人，案件會停在他的收件匣裡等他回來。
     *
     * <p>鏈比要求的短時沿用既有行為：回最高階並記 warn；
     * 完全沒有主管則由 {@code getManagerAtLevel} 拋
     * {@link IllegalStateException}（不回 null，見類別註解）。
     */
    public String managerAtLevel(String userId, int level) {
        String assignee = orgService.resolveEffective(orgService.getManagerAtLevel(userId, level));
        requireHuman(assignee, userId + " 的第 " + level + " 階主管（含代理人）");
        return assignee;
    }

    /**
     * 權限碼持有人中第一位可受理的人＋代理人（指派制，不是候選制）。
     *
     * <p>挑人規則完全在 {@link BpmPermissionService#getFirstAvailableUser}：
     * 先挑第一位沒有代理人的持有人；全部不在時派第一位的代理人。
     * 本方法只補它缺的那一哩 —— <b>找不到人時大聲失敗</b>。
     *
     * <p>與 {@code ${permService.getUsersByPermission('...')}} 放在
     * candidateUsers 的差別：那個是「列出候選人、等人認領」，這個是
     * 「直接指派給其中一位」，任務立刻出現在某個人的待辦清單裡。
     *
     * @throws IllegalStateException 權限碼沒有任何持有人
     */
    public String firstAvailable(String permCode) {
        String userId = permService.getFirstAvailableUser(permCode);
        if (!isSet(userId)) {
            throw new IllegalStateException(
                    "權限碼 '" + permCode + "' 沒有任何持有人，無法決定審核人。"
                            + "請在權限中心指派持有人，或改用 "
                            + "${permService.getUsersByPermission('" + permCode + "')} "
                            + "放在 candidateUsers 讓群組成員自行認領。");
        }
        requireHuman(userId, "權限碼 '" + permCode + "' 的持有人清單");
        return userId;
    }

    /**
     * 主管鏈上最近一位持有指定權限碼的主管 ＋代理人。
     *
     * <p>走鏈與權限判定在 {@link BpmQueryService#getManagerWithPermission}
     * （由近而遠找第一位持有權限碼的主管）；本方法補它缺的兩件事：
     * 代理人代換、找不到人時大聲失敗（那個方法回 null）。
     *
     * @throws IllegalStateException 主管鏈上沒有人持有該權限碼
     */
    public String managerWithPermission(String userId, String permCode) {
        String manager = bpmQueryService.getManagerWithPermission(userId, permCode);
        if (!isSet(manager)) {
            throw new IllegalStateException(
                    userId + " 的主管鏈上沒有人持有權限碼 '" + permCode + "'，無法決定審核人。"
                            + "請確認權限中心已指派此權限碼，或改用其他指派方式。");
        }
        String assignee = orgService.resolveEffective(manager);
        requireHuman(assignee, userId + " 的主管鏈（含代理人）");
        return assignee;
    }

    /**
     * 系統身分（{@code system:*}）不是人：沒有任何持有者判定會命中它，
     * 派給它等於製造一個沒有人看得到的任務（#83 的規則，這裡重用）。
     *
     * <p>檢查的是<b>即將寫進 assignee 的那個值</b>（含代理人的代換結果），
     * 與 {@link ApplicantResolver} 的第三段同一條。真的出現時寧可當場失敗，
     * 也不要讓案件停在一個沒有任何人能簽的關卡。
     */
    private static void requireHuman(String userId, String source) {
        if (ExternalActorIdentity.isSystemActor(userId)) {
            throw new IllegalStateException(
                    source + " 回傳了系統身分 " + userId + "，它沒有人能簽。請修正來源的指派。");
        }
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }
}
