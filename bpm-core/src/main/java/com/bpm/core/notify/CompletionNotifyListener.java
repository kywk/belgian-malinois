package com.bpm.core.notify;

import com.bpm.core.service.ApplicantIdentityLookup;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.impl.context.Context;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.event.FlowableEntityWithVariablesEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 任務完成後對申請人的通知（{@code process_returned}／{@code process_rejected}／
 * {@code process_completed}）—— <b>全域</b>發送端（#96）。
 *
 * <h2>它修的缺陷</h2>
 *
 * <p>改動前這個通知只存在於 {@code TaskController.updateTask} 的 complete 分支，
 * 而 {@code ExternalApiController.completeTask} 完成任務時<b>完全不發</b>：
 * 同一個「任務完成」事實有兩條路徑，規則卻只掛在其中一條上。外部系統
 * 完成最後一關之後，申請人永遠不會收到「已核准完成」。
 *
 * <p>現在由 {@code FlowableConfig} 把本 listener 註冊進引擎的
 * {@code setEventListeners}，<b>兩條路徑</b>（HTTP 與外部 API）都經過同一份
 * 事件判定（{@link NotifyPublisher#applicantEventFor}）。
 * {@link NotifyPublisher} 仍是唯一發送端。
 *
 * <h2>⚠️ 為什麼拆成兩個事件，以及「恰好一則」怎麼保證</h2>
 *
 * <p>{@code TASK_COMPLETED} 觸發時（同一個 command 內）流程<b>還沒</b>繼續走
 * —— 執行是在事件之後才被觸發（Flowable {@code TaskHelper.completeTask}：
 * 先送 {@code TASK_COMPLETED}，再 {@code planTriggerExecutionOperation}）。
 * 所以：
 *
 * <ul>
 *   <li><b>{@code TASK_COMPLETED}</b>：以 {@code applicantEventFor(vars, false)}
 *       判定。此時只可能得到 {@code process_returned}／{@code process_rejected}
 *       —— {@code process_completed} 需要「核准＋流程真的結束」，而流程還沒結束。
 *       補件任務在此略過（見下）。</li>
 *   <li><b>{@code PROCESS_COMPLETED}</b>：以
 *       {@code applicantEventFor(vars, true)} 判定，且<b>只有等於
 *       {@code process_completed} 時才發</b>。若寫成「非 null 就發」，
 *       被拒絕的單（{@code rejected=true} 且流程結束）會在結案時再收到
 *       一則重複通知 —— 申請人同時收到兩封「已被拒絕」。</li>
 * </ul>
 *
 * <p>兩個事件的通知種類因此互斥：{@code TASK_COMPLETED} 只在
 * {@code returned}／{@code rejected} 時發，{@code PROCESS_COMPLETED} 只在
 * {@code completed} 時發。
 *
 * <h2>⚠️ 實測：{@code PROCESS_COMPLETED} 查歷史變數<b>不足</b></h2>
 *
 * <p>原設計是「{@code PROCESS_COMPLETED} 時查歷史變數（runtime 已清除）」。
 * 但實測（Flowable 7.2.0 ＋ MSSQL）發現：<b>同一個 command 內剛寫入的
 * {@code approved}／{@code rejected} 不會出現在歷史變數查詢結果裡</b>
 * —— 那些列還在本 command 的 DbSqlSession 中尚未 flush。以單關卡流程
 * 核准結案為例，歷史查詢只回傳流程啟動時就有的變數
 * （{@code initiator}／{@code leaveType}…），{@code approved=true} 缺席
 * → 判定 {@code null} → <b>通知漏發</b>。
 * （{@code ProcessCompletedListener.resolveFinalVariables} 有同樣的限制；
 * 既有 webhook 測試之所以綠，是因為它們把 {@code approved} 放在
 * 流程啟動的變數裡，那是上一個 command 已 commit 的資料。）
 *
 * <p>修法有兩層，都不新增第二份判定規則：
 *
 * <ol>
 *   <li><b>同一 command 的暫存</b>：{@code TASK_COMPLETED} 當下把「剛完成的
 *       任務與它這次送出的變數」記進 Flowable 的 {@link CommandContext}
 *       attribute（key 含 processInstanceId）。{@code PROCESS_COMPLETED}
 *       是同一個 command 內稍後發生的事件，直接取回同一份
 *       {@code vars} —— 那是「這次 complete 送出的值」的權威來源，與改動前
 *       TaskController 直接拿 {@code vars} 判定完全相同。順帶取回正確的
 *       taskId／taskName：歷史任務查詢在同一 command 內也看不到剛完成的
 *       end_time（未 flush），以結束時間排序會挑到<b>上一關</b>。
 *       attribute 的生命週期就是 command，沒有跨請求殘留，也不是 ThreadLocal
 *       （ThreadLocal 在執行緒重用下需要額外清理，且掩蓋了「兩個事件同
 *       command」這個事實）。</li>
 *   <li><b>沒有暫存時的 fallback</b>：流程不是由本次 command 的任務完成
 *       觸發結案時（例如非同步續行、自動結束），歷史變數已在先前 command
 *       commit，查得到；另外把 {@code ExecutionEntity} 的 in-memory 變數
 *       覆蓋上去，涵蓋「同 command 由 service task 寫入」的情形。</li>
 * </ol>
 *
 * <h2>⚠️ 補件任務不通知</h2>
 *
 * <p>申請人自己重送補件時，HTTP 路徑的 complete 會補上
 * {@code approved=false}／{@code rejected=false} 預設值，照 vars 判定會變成
 * 「您的申請已被退回」—— 荒謬。排除條件是
 * {@link NotifyPublisher#isRevisionTask}（與 TaskController 的稽核判定
 * 共用同一份，見該方法）。暫存 snapshot 也會帶上 revision 標記，
 * 補件任務若直接讓流程結案，{@code PROCESS_COMPLETED} 同樣不發結案通知。
 *
 * <h2>⚠️ 不可影響簽核（fail-open）</h2>
 *
 * <p>{@link NotifyPublisher#publish} 吞例外，{@link #isFailOnException()} 回
 * {@code false} —— 通知失敗絕不能讓使用者的簽核失敗或回滾。
 */
@Component
public class CompletionNotifyListener implements FlowableEventListener {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CompletionNotifyListener.class);

    /**
     * 同一個 Flowable command 內「剛完成的任務」的暫存 key 前綴。
     *
     * <p>以 processInstanceId 區分：同一個 command 可能連續結束多個流程實例
     * （Call Activity 子流程與父流程），彼此的完成關卡不可互相覆蓋。
     */
    private static final String COMPLETED_TASK_ATTR = "bpm.notify.completedTask.";

    private final NotifyPublisher notifyPublisher;

    /**
     * {@code @Lazy} 與 {@code ProcessCompletedListener}／
     * {@code UnreachableTaskListener} 的理由相同：本 listener 是
     * {@code FlowableConfig.processEngineConfigurer} 的建構子參數，
     * 而 applicantLookup → ProcessAccessGuard → RuntimeService 會回到引擎
     * 設定，形成循環。{@code @Lazy} 讓真正的 bean 在第一次事件（引擎啟動完成
     * 之後）才建立。
     */
    private final ApplicantIdentityLookup applicantLookup;

    /** 沒有 command 暫存時的 fallback：結案後 runtime 變數已清除，權威來源是歷史。 */
    private final HistoryService historyService;

    private final RuntimeService runtimeService;

    public CompletionNotifyListener(NotifyPublisher notifyPublisher,
                                    @Lazy ApplicantIdentityLookup applicantLookup,
                                    @Lazy HistoryService historyService,
                                    @Lazy RuntimeService runtimeService) {
        this.notifyPublisher = notifyPublisher;
        this.applicantLookup = applicantLookup;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() == FlowableEngineEventType.TASK_COMPLETED) {
            onTaskCompleted(event);
        } else if (event.getType() == FlowableEngineEventType.PROCESS_COMPLETED) {
            onProcessCompleted(event);
        }
    }

    /**
     * 退回／拒絕：完成當下通知申請人；核准時只暫存，等結案事件判定。
     *
     * <p>核准但流程還在跑（例如 {@code purchase-approval} 的主管核准後還有
     * 財務關卡）在此<b>不發</b>：{@code applicantEventFor(vars, false)} 為
     * {@code null}，通知留給 {@code PROCESS_COMPLETED}。
     */
    private void onTaskCompleted(FlowableEvent event) {
        if (!(event instanceof FlowableEntityEvent entityEvent)
                || !(entityEvent.getEntity() instanceof TaskEntity task)) {
            return;
        }
        // ⚠️ standalone 加簽子任務（taskService.newTask()）沒有 processInstanceId：
        // 它不屬於任何流程，不該發 process_* 事件。
        String processInstanceId = task.getProcessInstanceId();
        if (processInstanceId == null || processInstanceId.isBlank()) return;

        // ⚠️ 補件不通知。判定只有一份（NotifyPublisher.isRevisionTask），
        // TaskController 的 TASK_RESUBMIT 稽核也用同一份。
        boolean revision = NotifyPublisher.isRevisionTask(task.getName());
        Map<String, Object> vars = variablesOf(event, task);
        // 先暫存再判定：PROCESS_COMPLETED 需要同一份 vars 與正確的關卡資訊。
        rememberCompletedTask(processInstanceId, task, revision, vars);

        if (revision) return;
        if (NotifyPublisher.applicantEventFor(vars, false) == null) {
            // 核准的中間關卡（最常見）不必多打一次 onBehalfOf／initiator 查詢。
            // 判定的仍是 applicantEventFor 這一顆 —— 沒有第二份規則。
            return;
        }

        notifyPublisher.taskCompleted(processInstanceId, task.getProcessDefinitionId(),
                task.getId(), task.getName(), vars, false,
                applicantLookup.applicantOf(processInstanceId));
    }

    /**
     * 結案：只有「核准且流程結束」才發 {@code process_completed}。
     *
     * <p>⚠️ 條件必須是等於 {@code process_completed}，不能只判非 null ——
     * 否則 rejected／returned 的單在結案時會再收一則重複通知（見類別註解）。
     */
    private void onProcessCompleted(FlowableEvent event) {
        if (!(event instanceof FlowableEntityEvent entityEvent)
                || !(entityEvent.getEntity() instanceof ExecutionEntity execution)) {
            return;
        }
        String processInstanceId = execution.getProcessInstanceId();
        if (processInstanceId == null || processInstanceId.isBlank()) return;

        CompletedTaskSnapshot justCompleted = takeCompletedTask(processInstanceId);
        if (justCompleted != null && justCompleted.revision()) {
            // 補件任務直接讓流程結案時也不發（與 TASK_COMPLETED 的排除同一條規則）。
            return;
        }

        Map<String, Object> vars = justCompleted != null
                ? justCompleted.vars() : finalVariablesOf(processInstanceId, execution);
        if (!"process_completed".equals(NotifyPublisher.applicantEventFor(vars, true))) {
            // returned／rejected 已在 TASK_COMPLETED 發過；無判定結果（變數
            // 取不到、補件重送）也不發。
            return;
        }

        // payload 的 taskId／taskName 沿用「最後完成的關卡」—— 與改動前
        // HTTP 路徑在 complete() 之後直接發送的形狀一致（EmailConsumer 的
        // 預設模板會用 taskName）。同一 command 有暫存時用它（唯一可靠）；
        // 沒有時才退回歷史查詢（非同步結案等跨 command 情形）。
        String taskId = justCompleted != null ? justCompleted.taskId() : null;
        String taskName = justCompleted != null ? justCompleted.taskName() : null;
        if (justCompleted == null) {
            HistoricTaskInstance last = lastCompletedTask(processInstanceId);
            if (last != null) {
                taskId = last.getId();
                taskName = last.getName();
            }
        }
        notifyPublisher.taskCompleted(processInstanceId, execution.getProcessDefinitionId(),
                taskId, taskName, vars, true, applicantLookup.applicantOf(processInstanceId));
    }

    /**
     * 這一筆完成事件帶的變數。
     *
     * <p>{@code TASK_COMPLETED} 由 {@code TaskHelper.completeTask} 在
     * {@code execution.setVariables(variables)} <b>之後</b>才送出，且把同一份
     * map 放進 {@code FlowableEntityWithVariablesEvent}。優先取它：那是
     * 「這次 complete 送出的值」的權威來源，與改動前 TaskController 直接拿
     * {@code vars} 判定完全相同。
     *
     * <p>事件不是 with-variables 形狀時（例如以 API 完成 standalone task）
     * 退回 {@code task.getVariables()}，涵蓋任務區域變數與所屬執行變數。
     * 兩者都拿不到時回空 map —— {@code applicantEventFor} 對空 map 回 null，
     * 不會亂猜事件種類。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> variablesOf(FlowableEvent event, TaskEntity task) {
        if (event instanceof FlowableEntityWithVariablesEvent withVariables
                && withVariables.getVariables() != null) {
            return withVariables.getVariables();
        }
        Map<String, Object> vars = task.getVariables();
        return vars == null ? Map.of() : vars;
    }

    // ── 同 command 的完成關卡暫存 ───────────────────────────────────

    /** 一個剛完成的關卡：PROCESS_COMPLETED 要用它發結案通知，或據此排除補件。 */
    private record CompletedTaskSnapshot(String taskId, String taskName, boolean revision,
                                           Map<String, Object> vars) {}

    private static void rememberCompletedTask(String processInstanceId, TaskEntity task,
                                              boolean revision, Map<String, Object> vars) {
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) return;   // 不在 command 內（理論上不會發生）
        commandContext.addAttribute(COMPLETED_TASK_ATTR + processInstanceId,
                new CompletedTaskSnapshot(task.getId(), task.getName(), revision, vars));
    }

    /** 取出並移除（同一個 command 內只會被消費一次）。 */
    private static CompletedTaskSnapshot takeCompletedTask(String processInstanceId) {
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) return null;
        String key = COMPLETED_TASK_ATTR + processInstanceId;
        Object value = commandContext.getAttribute(key);
        commandContext.removeAttribute(key);
        return value instanceof CompletedTaskSnapshot snapshot ? snapshot : null;
    }

    // ── 沒有暫存時的 fallback ───────────────────────────────────────

    /**
     * 結案後的流程變數：歷史優先、runtime 次之，最後蓋上 execution 的
     * in-memory 值。
     *
     * <p>前兩段的順序與 {@code ProcessCompletedListener.resolveFinalVariables}
     * 相同；第三段是本工項實測後新增的：同一 command 剛寫入的變數不在
     * 歷史查詢結果（未 flush），但 {@code ExecutionEntity} 仍持有最終值
     * （見類別註解「實測」）。取不到時回空 map，讓判定回 null ——「不知道」
     * 不可以被說成「已核准」。
     */
    private Map<String, Object> finalVariablesOf(String processInstanceId,
                                                 ExecutionEntity execution) {
        Map<String, Object> out = new HashMap<>();
        try {
            historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId).list()
                    .forEach(v -> out.put(v.getVariableName(), v.getValue()));
        } catch (Exception e) {
            log.warn("查詢流程 {} 的歷史變數失敗（結案通知將以剩餘來源判定）: {}",
                    processInstanceId, e.toString());
        }
        if (out.isEmpty()) {
            try {
                out.putAll(runtimeService.getVariables(processInstanceId));
            } catch (Exception e) {
                log.warn("流程 {} 的執行期變數亦不可得，改以事件 execution 的狀態判定",
                        processInstanceId);
            }
        }
        try {
            Map<String, Object> inMemory = execution.getVariables();
            if (inMemory != null) out.putAll(inMemory);
        } catch (Exception e) {
            log.warn("讀取流程 {} 結案 execution 的變數失敗: {}",
                    processInstanceId, e.toString());
        }
        return out;
    }

    /**
     * 最後完成的關卡（只在沒有同 command 暫存時使用）。
     *
     * <p>以結束時間由晚到早取第一筆；未結束的任務 end time 為 null，在遞減
     * 排序中排在最後（MSSQL）。⚠️ 同一 command 剛完成的關卡 end_time 尚未
     * flush，用這個查詢會挑到上一關 —— 所以同步結案一律走暫存，這裡只服務
     * 跨 command 的情形。查無資料回 {@code null}。
     */
    private HistoricTaskInstance lastCompletedTask(String processInstanceId) {
        return historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceEndTime().desc()
                .listPage(0, 1).stream().findFirst().orElse(null);
    }

    /** 通知失敗不可影響簽核（見類別註解）。 */
    @Override
    public boolean isFailOnException() {
        return false;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }
}
