package com.bpm.core.audit.model;

import java.util.Set;

/**
 * 稽核操作型別。
 *
 * <h2>⚠️ 未使用的值必須列進 {@link #NOT_YET_IMPLEMENTED}（security-audit P2-4）</h2>
 *
 * <p>一個存在於 enum 卻沒有任何程式碼發出的值，會造成一個危險的印象：
 * 讀這份清單的人（包含稽核人員與日後接手的開發者）會以為那個操作有被記錄。
 *
 * <p>而兩種「未使用」的意義完全不同：
 * <ul>
 *   <li><b>操作還沒實作</b>（例如退回發起人）—— 值是預留的，沒有缺口。</li>
 *   <li><b>操作已經實作但沒發稽核</b> —— 那是真的缺口。
 *       P2-4 修掉的 {@code CONFIG_CHANGE}、{@code DATA_ACCESS}、
 *       {@code PROCESS_COMPLETE} 全都屬於這一類：功能都在跑，只是沒留軌跡。</li>
 * </ul>
 *
 * <p>光靠註解區分不住，因為註解不會在事情變化時提醒任何人。
 * {@code OperationTypeCoverageTest} 斷言「每個值要嘛被 main 程式碼使用，
 * 要嘛列在 NOT_YET_IMPLEMENTED」，所以：
 * <ul>
 *   <li>新增一個值而忘了發出它 → 測試紅，必須明確選擇是哪一類。</li>
 *   <li>實作了某個預留操作卻忘了加稽核 → 它仍在 NOT_YET_IMPLEMENTED 裡，
 *       而那份清單在 code review 時看得見。</li>
 * </ul>
 */
public enum OperationType {
    PROCESS_START,
    /**
     * 撤回（#7）。原本列在 {@code NOT_YET_IMPLEMENTED}（「撤案：無端點」），
     * 2026-10-03 由 {@code ProcessController.cancelProcess} 接上後移出。
     */
    PROCESS_CANCEL,
    PROCESS_COMPLETE,
    TASK_APPROVE,
    TASK_REJECT,
    TASK_RETURN,
    /**
     * 退回申請人（#1）：complete 帶 {@code returnTo=initiator}，路由回起點
     * （申請者補件）。與 {@link #TASK_RETURN}（退回上一站）的差別是<b>語意</b>
     * 而非狀態 —— 兩者都是 {@code approved=false}，稽核型別由
     * {@code TaskController.resolveCompleteAuditType} 依 {@code returnTo}
     * 變數判定。原本列在 NOT_YET_IMPLEMENTED，2026-10-03 實作後移出。
     */
    TASK_RETURN_INITIATOR,
    TASK_DELEGATE,
    TASK_REASSIGN,
    /**
     * 既有任務批次轉派給代理人（#5，{@code POST /api/admin/tasks/forward-substitutes}）。
     * 與 {@link #TASK_REASSIGN} 的差別是「誰決定的」：後者是任務持有者對
     * 單一任務的明確改派，這裡是管理員對一批「受理人已設代理人」的任務
     * 做補償性轉派。用同一個型別會讓稽核查詢分不出這兩件事。
     */
    TASK_SUBSTITUTE_FORWARD,
    TASK_COUNTERSIGN,
    TASK_COMMENT,
    TASK_CLAIM,
    /** 催辦（#6）。原本列在 NOT_YET_IMPLEMENTED，2026-10-02 補上稽核後移出。 */
    TASK_URGE,
    // 以下三個先前被 TaskController 以字串送出，卻不存在於本 enum
    // → AuditEventPublisher 的 valueOf 拋 IllegalArgumentException，
    //   而該方法是 @Async 且例外被吞掉只寫 log
    // → 呼叫端收到 200，稽核表完全沒有那筆（security-audit P1-1）。
    // 兩支 BPMN 的補件任務名稱都含「補件」，因此 TASK_RESUBMIT 這條
    // 代表「每一次退回重送都不進稽核」。
    TASK_RESUBMIT,
    TASK_RESOLVE,
    TASK_UPDATE,
    /** 任務建立後沒有任何人看得到（系統告警，見 UnreachableTaskListener）。 */
    TASK_UNREACHABLE,
    FORM_SUBMIT,
    FORM_UPDATE,
    BPMN_DEPLOY,
    EXTERNAL_API_CALL,
    DATA_ACCESS,
    CONFIG_CHANGE,
    /** 稽核紀錄 CSV 匯出（#40）。原本列在 NOT_YET_IMPLEMENTED，2026-10-02 實作後移出。 */
    EXPORT_DATA,
    /**
     * 訊息進入死信佇列（#51）。由 {@code DeadLetterConsumer} 在
     * {@code dlq.audit}／{@code dlq.bpm} 收到訊息時發出（operator {@code system}／
     * 來源 {@code engine}）。它讓「什麼時候有死信」進入可查詢的軌跡，
     * 而不只依賴 ERROR log。detail 只放非敏感的 broker 中介資料，不放 payload。
     */
    DLQ_MESSAGE,
    /**
     * 人工重放死信（#51）。由 {@code DlqReplayService} 在
     * {@code POST /api/admin/dlq/replay} 處理完畢後發出（operator＝呼叫者）。
     * 重放會造成外部副作用（webhook 再送、通知再寄），必須留下誰放了多少筆。
     */
    DLQ_REPLAY;

    /**
     * 對應的操作<b>尚未實作</b>，因此不會有程式碼發出這些值。
     *
     * <p>已逐一確認（全 repo 搜尋 cancel／return-initiator 的實作：
     * cancel 於 2026-10-03 由 #7 接上、return-initiator 於同日由 #1 接上，
     * 兩者皆已移出）。
     * 實作其中任何一個時，請一併加上稽核並把它從這裡移除
     * —— {@code OperationTypeCoverageTest} 會確保這件事不被漏掉。
     *
     * <p>{@code PROCESS_CANCEL} 原本也在此清單（「撤案：無端點」），
     * 已於 2026-10-03 由 {@code ProcessController.cancelProcess}（#7）實作後移出。
     *
     * <p>{@code EXPORT_DATA} 原本也在此清單（「資料匯出：無端點」），
     * 已於 2026-10-02 由 {@code GET /api/audit-logs/export}（#40）實作後移出。
     *
     * <p>{@code TASK_URGE} 原本也在此清單（「催辦：無端點」）。它其實是
     * 另一類：{@code TaskController.urgeTask}（#6）早就存在，只是<b>沒有稽核</b>
     * —— 屬於「操作在跑但沒留軌跡」的真缺口。已於 2026-10-02 補上稽核後移出。
     *
     * <p>{@code TASK_UPDATE} 是另一種情況：{@code TaskController.updateTask}
     * 確實存在，但它的每一條分支都對應到更精確的型別
     * （CLAIM／DELEGATE／RESOLVE／REASSIGN／APPROVE…），
     * 所以這個籠統的值<b>本來就不該被使用</b>。留著它只會讓查詢時困惑
     * 「TASK_UPDATE 和 TASK_APPROVE 差在哪」。
     *
     * <p>{@code TASK_RETURN_INITIATOR} 原本也在此清單（「退回發起人：無端點」），
     * 已於 2026-10-03 由 {@code TaskController} 的 complete
     * {@code returnTo=initiator}（工項 #1）實作並發出稽核後移出。
     */
    public static final Set<OperationType> NOT_YET_IMPLEMENTED = Set.of(
            TASK_UPDATE              // 刻意不使用，見上方說明
    );
}
