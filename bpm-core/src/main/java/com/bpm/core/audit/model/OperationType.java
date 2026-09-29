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
 *   <li><b>操作還沒實作</b>（例如撤案、催辦）—— 值是預留的，沒有缺口。</li>
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
    PROCESS_CANCEL,
    PROCESS_COMPLETE,
    TASK_APPROVE,
    TASK_REJECT,
    TASK_RETURN,
    TASK_RETURN_INITIATOR,
    TASK_DELEGATE,
    TASK_REASSIGN,
    TASK_COUNTERSIGN,
    TASK_COMMENT,
    TASK_CLAIM,
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
    FORM_SUBMIT,
    FORM_UPDATE,
    BPMN_DEPLOY,
    EXTERNAL_API_CALL,
    DATA_ACCESS,
    CONFIG_CHANGE,
    EXPORT_DATA;

    /**
     * 對應的操作<b>尚未實作</b>，因此不會有程式碼發出這些值。
     *
     * <p>已逐一確認（全 repo 搜尋 cancel／urge／return-initiator／export
     * 的實作，皆無命中）。實作其中任何一個時，請一併加上稽核並把它從這裡移除
     * —— {@code OperationTypeCoverageTest} 會確保這件事不被漏掉。
     *
     * <p>{@code TASK_UPDATE} 是另一種情況：{@code TaskController.updateTask}
     * 確實存在，但它的每一條分支都對應到更精確的型別
     * （CLAIM／DELEGATE／RESOLVE／REASSIGN／APPROVE…），
     * 所以這個籠統的值<b>本來就不該被使用</b>。留著它只會讓查詢時困惑
     * 「TASK_UPDATE 和 TASK_APPROVE 差在哪」。
     */
    public static final Set<OperationType> NOT_YET_IMPLEMENTED = Set.of(
            PROCESS_CANCEL,          // 撤案：無端點
            TASK_RETURN_INITIATOR,   // 退回發起人：無端點（TASK_RETURN 是退回上一站）
            TASK_URGE,               // 催辦：無端點
            EXPORT_DATA,             // 資料匯出：無端點
            TASK_UPDATE              // 刻意不使用，見上方說明
    );
}
