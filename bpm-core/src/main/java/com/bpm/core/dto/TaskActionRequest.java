package com.bpm.core.dto;

import java.util.List;
import java.util.Map;

public record TaskActionRequest(
        String action,           // claim, complete, delegate, resolve
        String assignee,         // for reassign
        String delegateUser,     // for delegate
        // #1 退回申請人：complete 專用的路由指示。只接受 "initiator"，
        // 由伺服器轉寫成流程變數（見 TaskController 的 complete 分支）。
        String returnTo,
        List<VariableRequest> variables
) {
    public record VariableRequest(String name, Object value) {}
}
