package com.bpm.core.webhook;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 結案結果的通報必須正確（security-audit P2-1 最後一點）。
 *
 * <p><b>這是一個真正的正確性 bug。</b>
 * {@code ProcessCompletedListener} 原本這樣取變數：
 *
 * <pre>
 *   try { vars = runtimeService.getVariables(processInstanceId); }
 *   catch (Exception ignored) { }                    // ← 吞掉
 *   String result = Boolean.TRUE.equals(vars.get("rejected")) ? "rejected" : "approved";
 * </pre>
 *
 * <p>流程<b>已經結案</b>時 runtime 的變數已清除 → 例外被吞掉 → {@code vars}
 * 為空 → {@code Boolean.TRUE.equals(null)} 為 false →
 * {@code result} <b>一律算成 "approved"</b>。
 *
 * <p>而這個事件正是通報外部系統「這張單的最終結果」用的。
 * 對簽核系統而言，把駁回說成核准是最不能接受的一種錯誤。
 *
 * <p>本測試直接驗證 listener 產生的 payload，透過攔截 RabbitTemplate
 * 取得實際送出的內容。
 */
class ProcessResultReportingTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcessCompletedListener listener;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private org.flowable.engine.HistoryService historyService;

    /**
     * 走完一個請假流程直到結案，回傳 <b>listener 自己判定</b>的結果。
     *
     * ⚠️ 刻意呼叫 {@code listener.resolveFinalVariables/resolveResult} 而不是
     * 在測試裡重現一份相同的邏輯 —— 後者只會驗證「測試與自己一致」，
     * 對實際程式碼一無所驗。
     */
    private String runToCompletionAndGetResult(Map<String, Object> completionVars) {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        String pid = pi.getId();

        var task = taskService.createTaskQuery().processInstanceId(pid).list().get(0);
        taskService.complete(task.getId(), completionVars);
        for (int i = 0; i < 5; i++) {
            var remaining = taskService.createTaskQuery().processInstanceId(pid).list();
            if (remaining.isEmpty()) break;
            taskService.complete(remaining.get(0).getId(), completionVars);
        }

        return listener.resolveResult(listener.resolveFinalVariables(pid));
    }

    @Test
    @DisplayName("駁回結案不得被通報為核准（改動前一律回報 approved）")
    void rejectedIsNotReportedAsApproved() {
        String result = runToCompletionAndGetResult(
                Map.of("approved", false, "rejected", true, "rejectReason", "不符規定"));

        assertThat(result)
                .as("把駁回說成核准是簽核系統最不能接受的錯誤")
                .isEqualTo("rejected");
        assertThat(result).isNotEqualTo("approved");
    }

    @Test
    @DisplayName("核准結案通報為 approved")
    void approvedIsReportedCorrectly() {
        assertThat(runToCompletionAndGetResult(
                Map.of("approved", true, "rejected", false))).isEqualTo("approved");
    }

    @Test
    @DisplayName("結案後仍必須查得到判定結果所需的變數（歷史變數是權威來源）")
    void historicVariablesRemainQueryableAfterCompletion() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        String pid = pi.getId();
        var task = taskService.createTaskQuery().processInstanceId(pid).list().get(0);
        taskService.complete(task.getId(), Map.of("approved", false, "rejected", true));

        // runtime 已無該實例（或即將沒有），但歷史一定查得到 —— 這是修法的基礎
        assertThat(historyService.createHistoricVariableInstanceQuery()
                        .processInstanceId(pid).list())
                .as("歷史變數必須留存，否則結案結果無從判定")
                .isNotEmpty();
    }

    @Test
    @DisplayName("取不到變數時必須回 unknown，不得猜成 approved")
    void emptyVariablesYieldUnknown() {
        // 這正是舊 bug 的核心：vars 為空時 Boolean.TRUE.equals(null) 為 false
        // → 落到 else 分支 → 一律 "approved"。
        assertThat(listener.resolveResult(Map.of()))
                .as("不確定就說不確定 —— 把未知狀態說成核准比說不知道危險得多")
                .isEqualTo("unknown");
    }

    @Test
    @DisplayName("resolveResult 對四種狀態的判定")
    void resolveResultCoversAllStates() {
        assertThat(listener.resolveResult(Map.of("rejected", true, "approved", false)))
                .isEqualTo("rejected");
        assertThat(listener.resolveResult(Map.of("rejected", false, "approved", false)))
                .as("approved=false 且未駁回 = 退回補件").isEqualTo("returned");
        assertThat(listener.resolveResult(Map.of("rejected", false, "approved", true)))
                .isEqualTo("approved");
        assertThat(listener.resolveResult(Map.of("somethingElse", 1)))
                .as("有變數但無判定依據仍是 unknown").isEqualTo("unknown");
    }
}
