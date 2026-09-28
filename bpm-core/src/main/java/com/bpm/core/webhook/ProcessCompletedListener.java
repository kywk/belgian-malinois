package com.bpm.core.webhook;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.event.impl.FlowableEntityEventImpl;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Lazy;

@Component
public class ProcessCompletedListener implements FlowableEventListener {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProcessCompletedListener.class);

    private final RabbitTemplate rabbitTemplate;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;

    /**
     * ⚠️ {@code @Lazy} 不可移除 —— Stage 4 已實測確認（2026-09-28）。
     *
     * <p>升級計畫要求「確認 @Lazy RuntimeService 的循環依賴 workaround 是否仍必要」。
     * 答案是<b>仍然必要</b>，而且它不是 Flowable 6 的遺留物，是結構性的循環：
     *
     * <pre>
     *   processCompletedListener
     *     → runtimeService
     *       → StandaloneEngineConfiguration
     *         → engineConfigurers
     *           → processEngineConfigurer（FlowableConfig）
     *             → processCompletedListener   ← 回到起點
     * </pre>
     *
     * <p>成因：{@code FlowableConfig.processEngineConfigurer} 必須注入本 listener
     * 才能呼叫 {@code setEventListeners()}，而本 listener 又需要引擎產生的
     * {@code RuntimeService}。移除 {@code @Lazy} 後啟動直接失敗：
     * {@code Requested bean is currently in creation: Is there an unresolvable
     * circular reference?}（Boot 3 預設禁止循環參照）。
     */
    public ProcessCompletedListener(RabbitTemplate rabbitTemplate,
                                    @Lazy RuntimeService runtimeService,
                                    @Lazy HistoryService historyService) {
        this.rabbitTemplate = rabbitTemplate;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() != FlowableEngineEventType.PROCESS_COMPLETED) return;

        if (event instanceof FlowableEntityEventImpl entityEvent
                && entityEvent.getEntity() instanceof ExecutionEntity exec) {

            String processInstanceId = exec.getProcessInstanceId();
            String processDefKey = extractKey(exec.getProcessDefinitionId());

            // ⚠️ 這裡原本是正確性 bug（security-audit P2-1 最後一點）。
            //
            // 改動前：runtimeService.getVariables() 在流程「已經結案」時取不到
            // 變數（執行期資料已清除），例外被 catch (Exception ignored) 吞掉
            // → vars 為空 → Boolean.TRUE.equals(null) 為 false
            // → result 一律算成 "approved"。
            //
            // 也就是**駁回結案會被回報為核准**。而這個事件正是通報外部系統
            // 「這張單的最終結果」用的 —— 對簽核系統來說，把駁回說成核准
            // 是最不能接受的一種錯誤。
            //
            // 修法：結案後應該查歷史變數，那才是結案狀態的權威來源。
            Map<String, Object> vars = resolveFinalVariables(processInstanceId);

            // 取不到變數時不再猜「approved」——「不知道」就說不知道。
            String result = resolveResult(vars);

            Map<String, Object> payload = new HashMap<>();
            payload.put("event", "process.completed");
            payload.put("timestamp", Instant.now().toString());
            payload.put("processInstanceId", processInstanceId);
            payload.put("processDefinitionKey", processDefKey);
            payload.put("businessKey", exec.getBusinessKey());
            payload.put("result", result);
            // ⚠️ 刻意不再外送全部流程變數（security-audit P2-1）。
            //
            // 表單欄位 id 就是流程變數名（spec §8.5），因此 allVariables 等於
            // 把薪資、身分證號等表單內容原封不動送到外部 URL，而且沒有任何
            // 白名單。需要明細的接收端應該回頭呼叫 API（那條路徑有授權）。
            //
            // 保留 businessKey 與 result 已足以讓外部系統知道「哪張單、
            // 什麼結果」並自行查詢。

            rabbitTemplate.convertAndSend("bpm.exchange", "bpm.webhook." + processDefKey, payload);
        }
    }

    @Override
    public boolean isFailOnException() { return false; }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() { return false; }

    @Override
    public String getOnTransaction() { return null; }

    /**
     * 取得結案後的流程變數。
     *
     * <p>流程結案後 runtime 的變數已清除，因此優先查歷史變數 ——
     * 那是結案狀態的權威來源。runtime 仍查得到時（例如子流程情境）也接受。
     */
    // package-private：讓同套件的測試能直接驗證這段判定邏輯。
    // 測試若只是「重現一份相同的邏輯」則什麼都沒驗到。
    Map<String, Object> resolveFinalVariables(String processInstanceId) {
        Map<String, Object> out = new HashMap<>();
        try {
            historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId).list()
                    .forEach(v -> out.put(v.getVariableName(), v.getValue()));
        } catch (Exception e) {
            log.warn("查詢流程 {} 的歷史變數失敗: {}", processInstanceId, e.toString());
        }
        if (out.isEmpty()) {
            try {
                out.putAll(runtimeService.getVariables(processInstanceId));
            } catch (Exception e) {
                log.warn("流程 {} 的執行期變數亦不可得，最終結果將回報為 unknown", processInstanceId);
            }
        }
        return out;
    }

    /**
     * 判定最終結果。
     *
     * <p>取不到變數時回傳 {@code "unknown"} 而非猜 {@code "approved"} ——
     * 對簽核系統而言，把不確定的狀態說成「核准」比說「不知道」危險得多。
     */
    // package-private：同上。
    String resolveResult(Map<String, Object> vars) {
        if (vars.isEmpty()) return "unknown";
        if (Boolean.TRUE.equals(vars.get("rejected"))) return "rejected";
        if (Boolean.FALSE.equals(vars.get("approved"))) return "returned";
        if (Boolean.TRUE.equals(vars.get("approved"))) return "approved";
        return "unknown";
    }

    private String extractKey(String processDefinitionId) {
        if (processDefinitionId == null) return "";
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }
}
