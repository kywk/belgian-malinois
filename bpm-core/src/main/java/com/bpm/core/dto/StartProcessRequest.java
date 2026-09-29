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
 * @param processDefinitionKey 要啟動的流程 key
 * @param businessKey          業務單號（可為 null）
 * @param initiator            <b>不可由呼叫端指定</b>（#66）。刻意保留，只用來拒絕。
 * @param variables            業務變數。不得包含受保護的變數
 *                            （{@code initiator}／{@code effectiveInitiator}／
 *                            {@code onBehalfOf}／{@code _} 前綴），否則回 400。
 */
public record StartProcessRequest(
        String processDefinitionKey,
        String businessKey,
        String initiator,
        Map<String, Object> variables
) {}
