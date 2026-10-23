package com.bpm.core.dto;

import java.util.Map;

/**
 * {@code POST /api/process-instances}。申請主表以 start-form 回傳的確切版本送件；
 * 未綁主表的技術流程仍可僅帶 variables。initiator 保留作明確拒絕用途，身分由伺服器決定。
 *
 * @param processDefinitionKey 流程 key
 * @param businessKey 業務單號
 * @param initiator 不可由呼叫端指定
 * @param variables 業務變數，不得包含受保護名稱或與 formData 重複
 * @param formData 申請流程必填；技術流程不可自行附加未綁定表單
 * @param processDefinitionId 開啟 start-form 時的流程定義 ID，申請流程必填
 */
public record StartProcessRequest(
        String processDefinitionKey,
        String businessKey,
        String initiator,
        Map<String, Object> variables,
        FormDataPayload formData,
        String processDefinitionId
) {
    /** 保留 Java 呼叫端的來源相容；HTTP 申請仍由 Controller 強制完整版本脈絡。 */
    public StartProcessRequest(String processDefinitionKey, String businessKey, String initiator,
                               Map<String, Object> variables, FormDataPayload formData) {
        this(processDefinitionKey, businessKey, initiator, variables, formData, null);
    }

    /**
     * @param formDefinitionId start-form 回傳的表單定義 UUID，不接受 formKey 替代
     * @param dataJson canonical 表單填寫內容（JSON 物件字串）
     * @param formVersion start-form 回傳的已發布版本
     */
    public record FormDataPayload(String formDefinitionId, String dataJson, Integer formVersion) {
        public FormDataPayload(String formDefinitionId, String dataJson) {
            this(formDefinitionId, dataJson, null);
        }
    }
}
