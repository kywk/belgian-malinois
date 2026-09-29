package com.bpm.core.security;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 流程實例的物件層授權：這個呼叫者「看得到／碰得動」哪些案件。
 *
 * <h2>為什麼從 AttachmentController 抽成一個元件（#71）</h2>
 *
 * <p>改動前這組判斷是 {@code AttachmentController} 的 private 方法，
 * 也就是<b>只有附件的三個端點有授權</b>，而 {@code GET /api/process-instances/{id}/variables}
 * 零檢查 —— 欄位 id == 流程變數名（spec §8.5），於是薪資等敏感表單資料
 * 任何登入者都能讀。
 *
 * <p>不重複寫一份的理由不是「DRY」，而是<b>授權規則只能有一份</b>：
 * 兩處各自維護 {@code isParticipant}，只要有人改了其中一處（例如加上
 * 「直屬主管也算關係人」），附件會拒絕而 variables 放行 —— 那個組合型式的
 * 差異比沒有檢查更難察覺，因為兩邊單獨看起來都是合理的。
 */
@Component
public class ProcessAccessGuard {

    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final AuditEventPublisher auditPublisher;

    public ProcessAccessGuard(RuntimeService runtimeService, HistoryService historyService,
                              TaskService taskService, AuditEventPublisher auditPublisher) {
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
    }

    /**
     * 案件的存在狀態。
     *
     * <p>為什麼需要這個三分類：改動前 {@code getVariables} 是
     * {@code catch (Exception e) { return Map.of(); }}，於是「你沒權看這個案件」
     * 「這個案件不存在」與「引擎出錯」三種語意全部塌成
     * {@code 200 + {}}。呼叫端看到空物件時完全無法分辨該怎麼處理 ——
     * 而這三種情況該做的事正好相反（前者是權限問題、後者兩者是資料問題）。
     */
    public enum InstanceState {
        /** 執行中，runtime 變數存在。 */
        RUNNING,
        /** 已結束，runtime 變數已由 Flowable 清除，只剩歷史。 */
        FINISHED,
        /** 從未存在（或已被清除到連歷史都沒有）。 */
        ABSENT
    }

    /** 案件的狀態。runtime 優先，因為只有執行中的實例才有可讀的流程變數。 */
    public InstanceState stateOf(String processInstanceId) {
        if (runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count() > 0) {
            return InstanceState.RUNNING;
        }
        if (historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).count() > 0) {
            return InstanceState.FINISHED;
        }
        return InstanceState.ABSENT;
    }

    public boolean exists(String processInstanceId) {
        return stateOf(processInstanceId) != InstanceState.ABSENT;
    }

    /**
     * 要求呼叫者是該案件的關係人，否則 404。
     *
     * <p>審查指出「即使補上認證，程式碼裡也<b>沒有可掛授權判斷的位置</b>」
     * （security-audit P1-6）—— 這個方法就是那個位置。
     *
     * <p>關係人的定義：案件發起人、或在此案件中持有／曾持有任務的人
     * （含候選人）。這涵蓋申請人、各關卡簽核人與加簽人。
     *
     * <p>回 404 而非 403：403 會確認「這個附件／案件存在」，
     * 對可枚舉的 id 來說等於把枚舉管道留著。
     *
     * <p>呼叫者身分來自 {@code @CallerId}，也就是已認證的 principal
     * （JWT 的 sub，或閘道認證過的身分）。R-01 已完成 ——
     * 這個方法先前的註解說「身分仍可自報，因此不是完整的安全邊界」，
     * 那個限制已經不存在了。
     *
     * <p>讀取端點（列表、下載）另有稽核旁路，見 {@link #requireReadAccess}。
     * 上傳<b>沒有</b>旁路：稽核人員的職責是查閱，不是替案件補件。
     */
    public void requireParticipant(String processInstanceId, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!isParticipant(processInstanceId, userId)) {
            denyNonParticipant(processInstanceId, userId);
        }
    }

    /**
     * 讀取權限：案件參與者，<b>或</b>持有 {@code audit:log:read} 的稽核人員。
     *
     * <h2>稽核旁路（2026-09-29 決策：只開稽核權限、唯讀、每次留痕）</h2>
     *
     * <p>調查一張單時，附件往往是關鍵證據（報價單、請假證明），而調查者不會是
     * 該案的參與者。沒有旁路的話只能到伺服器上直接取檔 —— 那條路完全不留痕，
     * 比開一個有稽核的旁路更糟。流程變數（{@code GET .../{id}/variables}）
     * 與附件是同一批敏感資料，因此沿用同一個政策。
     *
     * <p>刻意<b>只認 {@code audit:log:read}，不認 {@code ROLE_ADMIN}</b>：
     * 「能管理系統」與「能看全公司的薪資單附件」是不同的權責。
     * 通配權限的持有者被 {@code AuthorityResolver} 轉成 {@code ROLE_ADMIN}，
     * 不會自動取得這個 authority —— 管理員要調閱，權限中心就得明確指派。
     *
     * <p>每次經由旁路的存取都寫一筆 {@code DATA_ACCESS}，標記 {@code auditBypass=true}，
     * 讓「誰以稽核身分看了哪些案件」本身可以被稽核。
     *
     * @return {@code true} 表示這次存取是經由稽核旁路（呼叫端必須據此留痕）
     */
    public boolean requireReadAccess(String processInstanceId, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (isParticipant(processInstanceId, userId)) return false;
        if (callerHoldsAuditRead()) return true;
        denyNonParticipant(processInstanceId, userId);
        return false; // 不會執行到：denyNonParticipant 一定拋例外
    }

    /** 拒絕別人的案件，並留下「有人嘗試存取」的稽核。 */
    public void denyNonParticipant(String processInstanceId, String userId) {
        // 稽核拒絕：有人嘗試存取無關案件的資料，這件事本身值得留痕。
        auditPublisher.publishDetached(new AuditEvent(OperationType.DATA_ACCESS.name(), userId,
                processInstanceId, null,
                Map.of("denied", true, "reason", "not a participant")));
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /**
     * 解析「只准查自己」的身分參數（#71）。
     *
     * <p>改動前 {@code ?initiator=}／{@code ?assignee=}／{@code ?candidateUser=}
     * 都是 {@code required=false} 且完全不檢查 —— 省略時等於「全部」。
     *
     * <h2>三條規則（沿用 #66 對啟動路徑定下的政策，不可各自發明）</h2>
     *
     * <ol>
     *   <li><b>帶了與自己不符的值 → 明確 400。</b>與 #66 的
     *       {@code POST /api/process-instances} 同一理由：不靜默忽略，
     *       否則呼叫端會以為它查得到對方的申請，而實際拿到自己的。</li>
     *   <li><b>省略（或空白）→ 預設為呼叫者</b>，不是「全部」。</li>
     *   <li><b>空白視同省略</b>：空白不可能指向別人，拒絕它只製造無意義的破壞
     *       （與 {@code DocumentController.createdBy} 的判斷一致）。</li>
     * </ol>
     *
     * <p>拒絕時留一筆稽核：有人嘗試翻別人的收件匣，這件事本身值得知道。
     * 用 {@code publishDetached}（不跟隨交易、失敗不拋）—— 理由與
     * {@link #denyNonParticipant} 相同：操作已經被拒絕，改拋
     * {@code AuditWriteException} 只會讓回應從 400 變成 503，不會多擋下任何東西。
     *
     * @param requested 呼叫端送來的身分參數（可能為 null／空白）
     * @param callerId  已認證的呼叫者
     * @param paramName 參數名，只用於把訊息寫得讓呼叫端知道要改哪裡
     * @return 一定是 {@code callerId}
     */
    public String requireSelf(String requested, String callerId, String paramName) {
        // 未認證不回 401 的理由見 CallerIdArgumentResolver 類別註解：
        // 退回讀標頭會讓整套認證變成裝飾。
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "無法確認身分，請先登入");
        }
        if (requested == null || requested.isBlank()) return callerId;
        String claimed = requested.trim();
        if (callerId.equals(claimed)) return callerId;

        auditPublisher.publishDetached(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                null, null,
                Map.of("denied", true,
                        "reason", "identity mismatch",
                        "parameter", paramName,
                        "claimed", claimed)));
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                paramName + " 只接受登入身分（" + callerId + "），不可指定他人。"
                        + "請省略 " + paramName + " 參數（省略即代表你自己），或改為 " + callerId + "。");
    }

    /**
     * 拒絕呼叫端自稱的候選群組（#71）。
     *
     * <p>候選群組與 assignee／candidateUser 不同：後兩者至少還可以解讀成
     * 「查自己」，而群組是一個<b>集合</b>的自稱 —— 等於要求伺服器相信
     * 「我屬於這個組織」。所以只要呼叫端帶了值就拒絕，不論值是什麼、
     * 是否恰好等於他自己的群組。
     *
     * <p>省略時由 {@link com.bpm.core.service.CandidateGroupMembership} 計算
     * 呼叫端實際所屬的群組；不計算會讓「所屬單位」指派的任務從收件匣消失
     * （見該類別的註解）。
     */
    public void rejectCallerSuppliedGroups(String requested, String callerId) {
        if (requested == null || requested.isBlank()) return;
        String claimed = requested.trim();
        auditPublisher.publishDetached(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                null, null,
                Map.of("denied", true,
                        "reason", "candidate groups are server-derived",
                        "parameter", "candidateGroups",
                        "claimed", claimed)));
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "candidateGroups 由伺服器依登入身分（" + callerId + "）推導，不可指定。"
                        + "請移除 candidateGroups 參數。");
    }

    public boolean callerHoldsAuditRead() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> AuthorityResolver.PERM_AUDIT_READ.equals(a.getAuthority()));
    }

    /**
     * 呼叫者是不是這個案件的關係人。
     *
     * <p>三個條件與 {@code AttachmentController} 原本的實作完全相同：
     * 發起人（含 R-20 的 onBehalfOf 對應的 initiator 值）、目前持有或為候選人的任務、
     * 曾經處理過此案件的任務。
     *
     * <p>⚠️ <b>不涵蓋候選群組</b>：Flowable 的 {@code taskInvolvedUser} 只比對
     * {@code ACT_RU_IDENTITYLINK}／{@code ACT_HI_IDENTITYLINK} 的 {@code USER_ID_}，
     * 群組連結的 {@code GROUP_ID_} 不會命中。所以「所屬部門」指派的任務
     * 在受眾判定上是看不到的 —— 這是引擎的限制，不是這裡的取捨。
     * 待辦清單那一側由 {@code CandidateGroupMembership} 另行補上（見 TaskController）。
     */
    public boolean isParticipant(String processInstanceId, String userId) {
        // 1. 發起人（執行中看 runtime 變數，已結案看歷史變數）
        if (userId.equals(initiatorOf(processInstanceId))) return true;

        // 2. 目前持有任務或為候選人
        boolean hasRuntimeTask = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskInvolvedUser(userId).count() > 0;
        if (hasRuntimeTask) return true;

        // 3. 曾經處理過此案件的任務（含已完成的關卡、加簽）
        return historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskInvolvedUser(userId).count() > 0;
    }

    /**
     * 案件的 initiator（執行中看 runtime 變數，已結案看歷史變數）。
     *
     * <p>⚠️ <b>不可改成「呼叫 {@code getVariable} 再 catch 例外」</b>：
     * Flowable 命令在外層交易中拋例外會把整個交易標成 rollback-only，
     * catch 住也救不回來（commit 時變成 UnexpectedRollbackException）。
     * 附件上傳是 {@code @Transactional}（稽核 fail-closed，P1-14），
     * 因此改用這個「先確認實例存在再讀變數」的寫法。整段註解連同程式碼一起搬移。
     */
    public String initiatorOf(String processInstanceId) {
        if (runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult() != null) {
            Object v = runtimeService.getVariable(processInstanceId, "initiator");
            if (v != null) return v.toString();
        }
        // 已結案的實例在 runtimeService 查不到，改看歷史變數
        var hv = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).variableName("initiator").list();
        return hv.isEmpty() || hv.get(0).getValue() == null
                ? null : hv.get(0).getValue().toString();
    }
}
