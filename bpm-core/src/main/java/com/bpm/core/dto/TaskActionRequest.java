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
        List<VariableRequest> variables,
        // uni-form 重構（計畫 §五 A5）：complete 專用的表單資料。
        // 表單定義由任務的 BPMN formKey ＋ _formVersions 鎖定版本解析，
        // body 只帶內容 —— 呼叫端不得指定定義（那就成了繞過版本鎖定的入口）。
        FormDataPayload formData
) {
    public record VariableRequest(String name, Object value) {}

    /**
     * 隨任務完成一起落地的表單資料。
     *
     * @param dataJson canonical JSON 物件字串（欄位 id == 流程變數名，spec §8.5）
     */
    public record FormDataPayload(String dataJson) {}
}
