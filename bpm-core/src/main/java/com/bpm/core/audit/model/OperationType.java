package com.bpm.core.audit.model;

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
    EXPORT_DATA
}
