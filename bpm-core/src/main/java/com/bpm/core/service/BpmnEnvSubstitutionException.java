package com.bpm.core.service;

/**
 * 環境變數替換失敗（backlog #53）。
 *
 * <p>被 {@link BpmnEnvSubstitutor} 拋出、由 {@code DeploymentController} 轉成
 * 400 —— 這條路徑上沒有任何「部分成功」：替換失敗發生在任何寫檔／commit／
 * 引擎部署之前，所以呼叫端不需要清理（fail-closed）。
 *
 * <p>訊息刻意同時帶<b>變數名</b>與<b>完整設定鍵</b>：維運拿到的是一句
 * 可以直接照著設定的指令（{@code bpmn.variables.ENV_FINANCE_GROUP}），
 * 而不是「某個佔位符沒有值」。
 *
 * <p>帶結構化欄位（而非只有字串）是為了讓呼叫端與測試不必 parse 訊息；
 * 訊息本身仍可獨立閱讀。
 */
public class BpmnEnvSubstitutionException extends RuntimeException {

    private final String variableName;
    private final String configKey;

    public BpmnEnvSubstitutionException(String variableName, String configKey, String detail) {
        this(variableName, configKey, detail, null);
    }

    public BpmnEnvSubstitutionException(String variableName, String configKey, String detail,
                                        Throwable cause) {
        super("BPMN 環境變數替換失敗：" + detail + "（變數 " + variableName
                + "，設定鍵 " + configKey + "）", cause);
        this.variableName = variableName;
        this.configKey = configKey;
    }

    /** 佔位符名稱，例如 {@code ENV_FINANCE_GROUP}。 */
    public String variableName() {
        return variableName;
    }

    /** Spring 設定鍵，例如 {@code bpmn.variables.ENV_FINANCE_GROUP}。 */
    public String configKey() {
        return configKey;
    }
}
