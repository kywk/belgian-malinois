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
import java.util.List;
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
     * 流程層 webhook 設定的來源（#67 斷線 (A) 的第四個實例）。
     *
     * <p>與節點層共用同一個 {@link WebhookConfigResolver}：它讀的是
     * {@code <process>} 上的 {@code <flowable:webhooks>}，規則與節點層對稱。
     *
     * <p>⚠️ 這裡<b>不</b>需要 {@code @Lazy}：resolver 自己對
     * {@code RepositoryService} 才是 {@code @Lazy}，循環在那一層就斷開了。
     */
    private final WebhookConfigResolver configResolver;

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
    private final com.bpm.core.audit.AuditEventPublisher auditPublisher;

    public ProcessCompletedListener(RabbitTemplate rabbitTemplate,
                                    @Lazy RuntimeService runtimeService,
                                    @Lazy HistoryService historyService,
                                    WebhookConfigResolver configResolver,
                                    com.bpm.core.audit.AuditEventPublisher auditPublisher) {
        this.rabbitTemplate = rabbitTemplate;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.configResolver = configResolver;
        this.auditPublisher = auditPublisher;
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

            // 稽核：結案本身必須在軌跡上（security-audit P2-4）。
            //
            // 改動前這個 listener 只送 webhook，不寫稽核。於是稽核鏈看得到
            // 每一次 TASK_APPROVE / TASK_REJECT，卻看不到「這張單結案了、
            // 最終結果是什麼」—— 而結案是外部系統與帳務系統唯一在意的事實。
            //
            // 少了它，要重建案件的最終狀態只能推論：看最後一個任務動作，
            // 再假設流程沒有其他分支。那個假設在有閘道與退回重送的流程上不成立。
            //
            // 寫在 webhook 之前：webhook 可能失敗（外部 URL 不通、被
            // WebhookUrlPolicy 拒絕），而稽核不該取決於外部系統是否可達。
            auditPublisher.publish(new com.bpm.core.dto.AuditEvent(
                    com.bpm.core.audit.model.OperationType.PROCESS_COMPLETE.name(),
                    // 結案由引擎觸發，沒有人類操作者。記 "system" 而非 null ——
                    // null 在查詢時容易被誤讀成「不知道是誰」，
                    // 而這裡是「確定不是人」。兩者意思不同。
                    "system",
                    "engine",
                    processDefKey,
                    processInstanceId,
                    null,
                    exec.getBusinessKey(),
                    Map.of("result", result,
                            // 變數取不到時 result 會是 unknown（P2-1）。
                            // 記下變數是否取得到，才能分辨「確實不知道」
                            // 與「歷史資料被清掉了」。
                            "finalVariablesResolved", !vars.isEmpty()),
                    Instant.now()));

            // ── 流程層 webhook 的投遞設定（#67 斷線 (A) 的第四個實例）──
            //
            // 改動前這裡無條件送出一則沒有 __webhookUrl 的 payload，
            // WebhookConsumer 讀到 null 就記一行 debug 丟掉 —— 沒有例外、
            // 沒有錯誤、沒有稽核，與節點層改動前的失敗型態完全相同。
            //
            // 現在與節點層對稱：讀 <process> 上的設定，沒有設定就完全不發訊息。
            // 理由同 WebhookTaskListener：queue 不要有雜訊，DLQ 不要混著
            // 從來不該投遞的訊息 —— 否則排查時分不出「該投但失敗」與「本來就不該投」。
            //
            // ⚠️ 這一段在 auditPublisher.publish() 之後：稽核不該取決於
            // 外部系統是否可達（見上方註解）。沒有設定而提前 return，稽核仍已寫入。
            List<WebhookConfig> configs =
                    configResolver.resolveForProcess(exec.getProcessDefinitionId());
            if (configs.isEmpty()) {
                log.debug("流程 {} 沒有流程層 webhook 設定，結案不發送", processDefKey);
                return;
            }
            List<WebhookConfig> matching = configs.stream()
                    .filter(c -> matchesProcessCompleted(c.event()))
                    .toList();
            if (matching.isEmpty()) {
                log.debug("流程 {} 有 {} 筆流程層 webhook 設定，但沒有一筆對應 process.completed",
                        processDefKey, configs.size());
                return;
            }

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

            for (WebhookConfig config : matching) {
                // ⚠️ 這兩個欄位是 WebhookConsumer 與本 listener 之間唯一的契約。
                // 欄位名不可改：consumer 用 payload.remove("__webhookUrl") 讀，
                // 改名等於把整條鏈路再斷一次（而斷掉之後不會有任何錯誤）。
                payload.put("__webhookUrl", config.url());
                payload.put("__webhookMethod", config.method());
                rabbitTemplate.convertAndSend("bpm.exchange", "bpm.webhook." + processDefKey, payload);
                log.info("流程 {} 的結案事件已排入投遞佇列：{} {}",
                        processDefKey, config.method(), config.url());
            }
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

    /**
     * 這一筆流程層設定是否對應「流程結案」。
     *
     * <p>package-private：比照 {@link WebhookTaskListener#matches}，讓測試能直接
     * 釘住規則本身。端到端測試只證明「有設定就投遞」，證明不了這條對應規則 ——
     * 把 {@code create} 誤當成命中，端到端照樣綠。
     *
     * <h2>規則與理由（流程層只有一個事件）</h2>
     *
     * <p>Flowable 對流程結案只發一個事件，payload 的 {@code event} 固定是
     * {@code process.completed}。因此：
     * <ol>
     *   <li><b>{@code process.completed}</b> 命中 —— 正式名稱。</li>
     *   <li><b>{@code complete}</b> 命中 —— 與節點層的 {@code complete} 同名，
     *       使用者從節點層遷移過來時最直覺的寫法；設計器輸入不經驗證。</li>
     *   <li><b>{@code all}</b> 命中 —— 與節點層同一個逃生門。</li>
     *   <li><b>省略（null／空白）</b> 命中 —— 視為 {@code process.completed}。
     *       解析端（{@link WebhookConfigResolver#resolveForProcess}）已用
     *       {@link WebhookConfig#DEFAULT_PROCESS_EVENT} 把省略補成
     *       {@code process.completed}；這裡再容忍一次是為了讓這條規則
     *       <b>自成一體、可單獨驗證</b>，且讓直接建構的設定也有一致行為。</li>
     *   <li>其餘（{@code create}／{@code timeout}／{@code reject}）<b>不命中</b> ——
     *       它們是節點層的事件。刻意不在流程層推導 {@code reject}：
     *       流程結案後才判定結果，與「完成任務當下判 rejected」不同；
     *       最終結果已由 payload 的 {@code result} 欄位表達。</li>
     * </ol>
     *
     * <p>大小寫不拘，同節點層 —— 設計器輸入不經過驗證。
     */
    static boolean matchesProcessCompleted(String configEvent) {
        String want = (configEvent == null || configEvent.isBlank())
                ? WebhookConfig.DEFAULT_PROCESS_EVENT
                : configEvent.trim();
        return "all".equalsIgnoreCase(want)
                || WebhookConfig.DEFAULT_PROCESS_EVENT.equalsIgnoreCase(want)
                || "complete".equalsIgnoreCase(want);
    }

    private String extractKey(String processDefinitionId) {
        if (processDefinitionId == null) return "";
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }
}
