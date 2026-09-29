package com.bpm.core.security;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.service.CandidateGroupMembership;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 單一任務的持有者判定：<b>讀寫兩端共用的唯一一份授權規則</b>（#77）。
 *
 * <h2>為什麼要抽成一個元件</h2>
 *
 * <p>修掉 {@code PUT /api/tasks/{id}} 的缺陷時，{@code GET /api/tasks}
 * 已經有一套判斷（#71），而寫入端<b>完全沒有</b>。實測：user001 送出的
 * 請假單，其主管審核任務的 assignee 是 mgr001；讓與該案無關的 user002
 * 去簽卻回 {@code 200 {"status":"ok"}}，稽核留下
 * {@code TASK_APPROVE | operatorId = user002}，流程直接走完。
 * 也就是<b>任何登入者都能批准或拒絕任意一張請假單、任意一張採購單</b>。
 *
 * <p>不抽成元件、直接在 {@code updateTask} 裡寫一份的理由不成立：
 * {@link ProcessAccessGuard} 的類別註解已經記錄過這個教訓 ——
 * 兩處各自維護同一條規則，只要有人改了其中一處，就會出現
 * 「附件會拒絕而 variables 放行」那種組合型式的差異，
 * <b>而那種差異比沒有檢查更難察覺</b>，因為兩邊單獨看起來都是合理的。
 *
 * <h2>四個條件，以及為什麼是這四個</h2>
 *
 * <ol>
 *   <li><b>assignee 就是我</b>（{@code RES.ASSIGNEE_ = me}）——
 *       與待辦清單的 {@code taskAssignee} 同一條 SQL。</li>
 *   <li><b>owner 是我</b>（{@code RES.OWNER_ = me}）——
 *       <b>這是待辦清單沒有的條件，而它不能省</b>，理由見下一節。</li>
 *   <li><b>我是候選人</b>（{@code taskCandidateUser}）——
 *       與待辦清單同一條 SQL。</li>
 *   <li><b>我屬於這個任務的某個候選群組</b> ——
 *       群組由 {@link CandidateGroupMembership} 計算（部門代碼 ∪ 權限碼 ∪
 *       非 {@code ROLE_} 的 authority），<b>不在本類別重寫一份</b>。</li>
 * </ol>
 *
 * <h2>⚠️ 為什麼比待辦清單多一個 owner（這是刻意的超集，不是漏補）</h2>
 *
 * <p>Flowable 的 delegate／resolve 生命週期（已用 7.2.0 的 bytecode 與
 * {@code Task.xml} 查證）：
 *
 * <ul>
 *   <li>{@code delegateTask(id, X)}（{@code DelegateTaskCmd}）：
 *       {@code delegationState = PENDING}；{@code owner} 為 null 時
 *       <b>把原本的 assignee 寫進 owner</b>；{@code assignee} 換成 X；
 *       再由 {@code TaskHelper.addAssigneeIdentityLinks} 補一條
 *       {@code ASSIGNEE} 的 identity link。</li>
 *   <li>{@code resolveTask(id)}（{@code ResolveTaskCmd}）：把 assignee 還給
 *       <b>owner</b>，並清掉委派狀態 —— 這是「原指派人把任務收回來」，
 *       呼叫者<b>只可能是 owner</b>。</li>
 * </ul>
 *
 * <p>所以 delegate 之後：<b>delegatee 是 assignee</b>（條件 1 成立）、
 * <b>原指派人是 owner</b>（條件 2 成立）。若這裡不放行 owner，
 * {@code resolve} 這個 action 會被自己新增的檢查打死 —— 整個委派功能
 * 只剩「交出去」沒有「收回來」，而 delegatee 簽完之後原指派人就再也
 * 無法對那個任務做任何事。
 *
 * <p>{@code ProcessAccessGuard.isParticipant} 的註解也指出
 * {@code taskInvolvedUser} 的 SQL 有比對 {@code OWNER_}；那條 SQL
 * 與本類別的條件 1＋2 完全同構。delegatee 那一側則同時被
 * {@code ASSIGNEE_} 與 {@code LINK.USER_ID_} 命中，兩種算法都不會漏掉他。
 *
 * <h2>⚠️ 為什麼超集只往「多算」的方向長，不往「少算」的方向</h2>
 *
 * <p>兩端不一致時有兩種可能的樣態，後果完全不對稱：
 *
 * <ul>
 *   <li><b>讀得到、寫不進</b>：使用者在待辦裡點進案件，按下核准得到 404。
 *       這是使用者看得見的故障，回報後立刻有人處理。</li>
 *   <li><b>讀不到、寫得進</b>：沒有任何畫面指向那個任務，但 API 接受它。
 *       這是<b>授權放寬</b>，而且沒有任何症狀。</li>
 * </ul>
 *
 * <p>因此寫入端寧可多算：owner 是引擎自己寫進去的事實，
 * 不多算就是<b>靜默打死既有功能</b>（本專案已經為「查不到就不查」
 * 付出過代價，見 {@code DuplicateApprovalFilterTest}）。
 *
 * <h2>⚠️ 候選群組的查詢順序（交易內不打外部系統）</h2>
 *
 * <p>{@code application.yml:175-184} 記載：{@code org-service-url} 與
 * {@code perm-service-url} 在 mock 階段<b>指向 bpm-core 自己</b>，
 * 也就是容器內會對自己發同步 HTTP，而且是在 {@code taskService.complete()}
 * 的 DB 交易之內；執行緒池飽和時會自我死鎖（security-audit P1-10）。
 *
 * <p>{@code CandidateGroupMembership.groupsOf} 的註解也明講「本呼叫不在
 * 交易內（待辦是唯讀查詢）」。而 {@code PUT /api/tasks/{id}} 是
 * {@code @Transactional} 的簽核路徑 —— 所以這裡<b>先確認任務真的有候選群組
 * （純本地 identity link 讀取），確認有才去算呼叫端的群組</b>。
 * 指派給人、候選人、owner 這三種（也就是絕大多數簽核）完全不碰外部系統。
 * 剩下的那一種本來就必須查外部系統（要判斷「你屬於哪些群組」），
 * 風險與啟動路徑上既有的組織查詢同級，且 {@code OrgService} 有 60 分鐘 TTL
 * 快取把它壓到極低頻率。
 *
 * <h2>⚠️ 已知缺口：{@code assignee = system:<id>} 的任務沒有人是持有者</h2>
 *
 * <p>外部系統發起的案件 {@code initiator} 是 {@code system:<id>}（不是人，
 * 見 {@code ExternalApiController}），而兩支 BPMN 的補件關卡
 * （{@code applicantRevision}／{@code revisionFromManager}／
 * {@code revisionFromFinance}）都用 {@code flowable:assignee="${initiator}"}。
 * 主管把那張單退回時，補件任務的 assignee 就會是 {@code system:erp} ——
 * 四個條件都不會命中（assignee 不是人、owner 為 null、沒有候選人、沒有候選群組），
 * <b>因此誰都不能簽，案件卡在那裡</b>。
 *
 * <p>本次刻意<b>不</b>順手修，理由有兩條。其一，外部 API 那條路徑是
 * server 之間的呼叫（{@code ExternalApiController.completeTask}），
 * 有它自己的擁有權檢查，不受這個 controller 影響，缺口只在「人工去簽
 * 補件」這一條路。其二，正確的修法是讓補件關卡在 {@code initiator}
 * 不是人時改指給 {@code onBehalfOf}（代發的員工）或系統設定的受理人 ——
 * 那是 BPMN 與簽核路由語意的決定，不該在一個授權守衛裡順帶改掉。
 * 標在這裡是為了讓下一次有人看到「案件卡住」時，知道它從哪裡來。
 */
@Component
public class TaskHolderGuard {

    private final TaskService taskService;
    private final CandidateGroupMembership groupMembership;
    private final AuditEventPublisher auditPublisher;

    public TaskHolderGuard(TaskService taskService,
                           CandidateGroupMembership groupMembership,
                           AuditEventPublisher auditPublisher) {
        this.taskService = taskService;
        this.groupMembership = groupMembership;
        this.auditPublisher = auditPublisher;
    }

    // ── 讀端：一次撈回「我的待辦」的三個查詢 ────────────────────────

    /**
     * 讀端的三個查詢條件，與 {@link #isHolder} 走的是同一組查詢建構子。
     *
     * <h2>為什麼讀端不用 {@link #isHolder} 逐筆判斷</h2>
     *
     * <p>待辦清單一次要回傳呼叫者的<b>全部</b>任務。逐筆呼叫
     * {@code isHolder} 會是 N+1 次查詢（N 為收件匣大小），而且每筆都要
     * 呼叫 {@code groupsOf} → 一次 HTTP self-call。讀端因此保留
     * 「三個批次查詢 + 記憶體去重」的形狀。
     *
     * <p>但<b>條件建構本身必須是同一份</b>：{@link #inboxQueries} 與
     * {@link #isHolder} 都呼叫 {@link #assignedTo}／{@link #candidateOf}／
     * {@link #inGroups}，不是各自寫一份。兩者不一致的後果見類別註解
     * 「為什麼超集只往多算的方向長」。
     */
    public List<TaskQuery> inboxQueries(String callerId) {
        List<TaskQuery> queries = new java.util.ArrayList<>();
        queries.add(assignedTo(callerId));
        queries.add(candidateOf(callerId));
        // 候選群組任務沒有 assignee，只能靠群組找到。空集合時<b>不能</b>查 ——
        // taskCandidateGroupIn(空清單) 會產生 IN ()，MSSQL 直接報錯。
        Set<String> myGroups = groupMembership.groupsOf(callerId);
        if (!myGroups.isEmpty()) {
            queries.add(inGroups(myGroups));
        }
        return queries;
    }

    // ── 寫端：這個呼叫者能不能動這個任務 ─────────────────────────────

    /**
     * 呼叫者是不是這個任務的持有者。四個條件見類別註解。
     *
     * <p>⚠️ <b>成本不對稱</b>：前三個條件是純本地查詢（assignee 與 owner
     * 已經在 {@link Task} 上，candidateUser 是一次本地 SQL），
     * 只有第四個可能碰外部系統。因此順序也是成本順序，
     * 且第四個之前先做一次本地檢查確認任務真的有候選群組。
     */
    public boolean isHolder(Task task, String callerId) {
        if (task == null || callerId == null || callerId.isBlank()) return false;

        // 1. assignee（與讀端 taskAssignee 同一條 SQL）
        if (callerId.equals(task.getAssignee())) return true;

        // 2. owner —— delegate 之後由原指派人收回（見類別註解）
        if (callerId.equals(task.getOwner())) return true;

        // 3. 候選人（與讀端 taskCandidateUser 同一條 SQL）
        //
        // ⚠️ 這個查詢帶著 ASSIGNEE_ is null，而 claim 之後候選連結
        // <b>不會</b>被清掉。只比對 identity link 的話，另一個候選人就能在
        // mgr001 認領之後繼續簽掉他的單 —— 也就是待辦清單已經擋掉、
        // 這裡卻放行的「看得到、寫不進」反過來的那一種。用同一條查詢
        // 就不會有這個落差。
        if (candidateOf(callerId).taskId(task.getId()).count() > 0) return true;

        // 4. 候選群組。先確認任務真的有候選群組再算呼叫端的群組，
        //    避免在簽核交易內為了「這個任務根本沒有群組」而去打外部系統。
        if (candidateGroupsOf(task).isEmpty()) return false;
        return inGroups(groupMembership.groupsOf(callerId))
                .taskId(task.getId()).count() > 0;
    }

    /**
     * 要求呼叫者是該任務的持有者，否則拒絕。
     *
     * <p><b>未認證 → 401，非持有者 → 404。</b>401 沿用
     * {@code FormDataController}／{@code ProcessController} 的做法：
     * 正常情況下 {@code SecurityConfig} 的 {@code authenticated()} 會擋在前頭，
     * 這裡是為了「授權矩陣日後放寬」時不會靜默退化成可偽造的身分。
     *
     * <p><b>為什麼是 404 而不是 403</b>：沿用本 repo 的既有慣例
     * （{@code ProcessAccessGuard.requireParticipant}、
     * {@code FormDataController.update}、{@code CountersignController}）。
     * 403 會確認「這個任務存在」，而 taskId 是可枚舉的
     * —— 對可枚舉的 id 回 403 等於把枚舉管道留著。回 404 則讓
     * 「不存在」與「不是你的」無法分辨。
     *
     * <p><b>拒絕要留痕</b>：有人嘗試簽別人的單，這件事本身值得知道。
     * 用 {@code publishDetached}（不跟隨交易、失敗不拋）—— 理由與
     * {@link ProcessAccessGuard#denyNonParticipant} 相同：操作已經被拒絕，
     * 交易必然回滾，改拋 {@code AuditWriteException} 只會讓回應從 404
     * 變成 503，不會多擋下任何東西。
     *
     * @param task     已經由呼叫端查好的任務（不為 null）
     * @param callerId {@code @CallerId} 解析出的登入身分
     */
    public void requireHolder(Task task, String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "無法確認操作的身分，請先登入");
        }
        if (isHolder(task, callerId)) return;
        auditPublisher.publishDetached(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                task.getProcessInstanceId(), task.getId(),
                Map.of("denied", true, "reason", "not a task holder")));
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任務不存在: " + task.getId());
    }

    // ── 查詢建構子（讀寫兩端共用，見 inboxQueries 的說明）────────────

    private TaskQuery assignedTo(String callerId) {
        return taskService.createTaskQuery().taskAssignee(callerId);
    }

    private TaskQuery candidateOf(String callerId) {
        return taskService.createTaskQuery().taskCandidateUser(callerId);
    }

    private TaskQuery inGroups(Set<String> groups) {
        return taskService.createTaskQuery().taskCandidateGroupIn(List.copyOf(groups));
    }

    /** 這個任務的候選群組。純本地讀取（ACT_RU_IDENTITYLINK），不碰外部系統。 */
    private Set<String> candidateGroupsOf(Task task) {
        Set<String> out = new LinkedHashSet<>();
        for (var link : taskService.getIdentityLinksForTask(task.getId())) {
            if (!IdentityLinkType.CANDIDATE.equals(link.getType())) continue;
            String g = link.getGroupId();
            if (g != null && !g.isBlank()) out.add(g.trim());
        }
        return out;
    }
}
