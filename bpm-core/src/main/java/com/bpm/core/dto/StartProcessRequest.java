package com.bpm.core.dto;

import java.util.Map;

/**
 * {@code POST /api/process-instances} 的請求。
 *
 * <h2>為什麼「拒絕」之後還要把 {@code initiator} 欄位留在 DTO 上</h2>
 *
 * <p>若把它刪掉，Spring Boot 預設關閉 {@code FAIL_ON_UNKNOWN_PROPERTIES} →
 * body 裡的 {@code initiator} 會被<b>靜默丟棄</b>，請求回 200，案件以呼叫者的
 * 身分啟動。那正是這次要避免的行為：呼叫端送出
 * {@code "initiator": "victim"} 之後拿到 200，會合理地推論
 * 「單子是以 victim 的名義送出」，而實際上不是 —— 它會在下游看到錯誤的
 * 申請人、錯誤的主管，卻沒有任何錯誤訊息指出哪裡不對。明確 400 才能讓
 * 呼叫端立刻知道「這個欄位由登入身分決定，請移除」。
 *
 * <p>因此這個欄位是<b>為了明確拒絕而存在</b>，不是為了接受它。
 * 保留它之後 server 端檢查到非 null 就回 400（見 ProcessController）。
 *
 * <p>與 {@code ExternalApiController}（R-20）語意一致：那條路用
 * {@code body.containsKey("initiator")} 判斷，效果相同 ——
 * <b>有送就拒絕</b>，連「送了與自己相同的值」也拒絕。判斷的技術手段不同
 * （record 拿到的是已反序列化的值，無法知道欄位是否「送成 null」），
 * 但對呼叫端的契約相同。
 *
 * <h2>#60：{@code formData} —— 隨啟動一起送出的表單資料</h2>
 *
 * <p>啟動流程與表單資料是兩個資料庫（{@code bpm_core_db}／{@code bpm_form_db}），
 * 但產品上「送單」是一個動作。呼叫端因此可以在同一個請求裡帶上表單內容，
 * 由 {@code ProcessController} 依序完成 schema 驗證、變數推導、流程啟動與
 * 表單落地（跨 DB 原子性見該方法的 javadoc）。
 *
 * <p>欄位刻意<b>可選</b>：只帶 {@code variables} 的既有呼叫端（外部系統、
 * 既有測試、舊版前端）不受影響，行為與 #60 之前完全相同。
 *
 * <p>{@code formDefinitionId} 的語意沿用 {@code FormData.formDefinitionId}，
 * 但多了一層解析：先當定義 id（UUID）查，查不到再當 formKey 查最新
 * published 版本 —— 前端只知道 BPMN 裡的 formKey，而
 * {@code FormSchemaValidator} 需要的是定義 id（見 ProcessController）。
 *
 * @param processDefinitionKey 要啟動的流程 key
 * @param businessKey          業務單號（可為 null）
 * @param initiator            <b>不可由呼叫端指定</b>（#66）。刻意保留，只用來拒絕。
 * @param variables            業務變數。不得包含受保護的變數
 *                            （{@code initiator}／{@code effectiveInitiator}／
 *                            {@code onBehalfOf}／{@code _} 前綴），否則回 400。
 * @param formData             隨啟動送出的表單資料（可為 null）。欄位名不得與
 *                            {@code variables} 重疊，推導出的變數同樣套用保護名單。
 */
public record StartProcessRequest(
        String processDefinitionKey,
        String businessKey,
        String initiator,
        Map<String, Object> variables,
        FormDataPayload formData
) {

    /**
     * 啟動流程時一併落地的表單資料。
     *
     * @param formDefinitionId 表單定義的 id，或 BPMN 使用的 formKey（由 server 解析）
     * @param dataJson         表單填寫內容（JSON 物件字串；欄位 id == 流程變數名，spec §8.5）
     */
    public record FormDataPayload(
            String formDefinitionId,
            String dataJson
    ) {}
}
