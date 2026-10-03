package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #7 遺留：撤回 {@code reason} 超過長度上限 → 400，且案件與稽核零副作用。
 *
 * <h2>缺陷形狀：使用者錯誤變成 500，而且發生在刪除途中</h2>
 *
 * <p>{@code reason} 原樣寫進 {@code ACT_HI_PROCINST.DELETE_REASON_}
 * （{@code nvarchar(4000)}）。改動前沒有限制：超過欄位容量的輸入會讓
 * {@code runtimeService.deleteProcessInstance} 丟 SQL 例外 → 500。
 * 呼叫端以為「伺服器壞了」，實際上是自己送太長；而且錯誤發生在交易
 * 中途，若哪天例外處理改變，撤回可能以半完成狀態收場。
 *
 * <p>修法是進引擎之前先擋（{@code ProcessController.cancelReasonOf}，
 * 上限 1000）。本檔驗的是端到端結果：<b>400 之後案件原封不動、
 * 任務原封不動、零 PROCESS_CANCEL、零 DATA_ACCESS</b>；
 * 而上限本身與 1000 字元必須可撤回。
 *
 * <h2>為什麼「零稽核」要兩張表都查</h2>
 *
 * <p>400 在授權檢查（③）之前就返回，所以連被拒授權的
 * {@code DATA_ACCESS denied} 都不該有 —— 它不是授權失敗，是 payload
 * 形狀錯誤。只查 PROCESS_CANCEL 會漏掉「400 卻留了拒絕痕跡」的走鐘。
 *
 * <h2>負向控制組（2026-10-03 實測）</h2>
 *
 * <p>把 {@code ProcessController} 的長度檢查整段拿掉（其餘不動），
 * 在全新的測試容器上重跑本類別：<b>1 紅 1 綠</b>。紅的是
 * {@link #overlongReasonIsRejectedWithoutSideEffects} —— 1001 字元
 * 仍塞得進 nvarchar(4000)，於是回 200、案件真的被刪掉、稽核也多了一筆；
 * 綠的是 {@link #maxLengthReasonIsAccepted}，它本來就不依賴上限。
 * 還原檢查後恢復全綠。
 */
class ProcessCancelReasonLimitTest extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    /** 一張 leave-approval 的單，停在主管審核（assignee = mgr001）。 */
    private String startLeave() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        return pi.getId();
    }

    private ResultActions cancel(String pid, String reason) throws Exception {
        return mockMvc.perform(post("/api/process-instances/{id}/cancel", pid)
                .header("X-User-Id", "user001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("reason", reason))));
    }

    private boolean runtimeStillExists(String pid) {
        return runtimeService.createProcessInstanceQuery().processInstanceId(pid).count() > 0;
    }

    /** 這個案件留下的 PROCESS_CANCEL 筆數（撤回成功的唯一證據）。 */
    private int processCancelRows(String pid) {
        int[] count = {0};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_audit_log "
                            + "WHERE operation_type = 'PROCESS_CANCEL' AND process_instance_id = ?")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                if (rs.next()) count[0] = rs.getInt(1);
            }
        });
        return count[0];
    }

    /** 呼叫者在這個案件留下的 DATA_ACCESS 筆數（被拒授權的痕跡）。 */
    private int dataAccessRows(String pid, String operatorId) {
        List<String> details = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'DATA_ACCESS' "
                            + "AND process_instance_id = ? AND operator_id = ?")) {
                ps.setString(1, pid);
                ps.setString(2, operatorId);
                var rs = ps.executeQuery();
                while (rs.next()) details.add(rs.getString(1));
            }
        });
        return details.size();
    }

    // ── 超長 → 400，零副作用 ────────────────────────────────────────

    @Test
    @DisplayName("#7 遺留：1001 字元的 reason → 400；案件、任務、稽核全部不動")
    void overlongReasonIsRejectedWithoutSideEffects() throws Exception {
        String pid = startLeave();

        cancel(pid, "x".repeat(1001)).andExpect(status().isBadRequest());

        assertThat(runtimeStillExists(pid))
                .as("400 之後案件必須仍在 runtime —— 被拒的請求不得刪掉任何東西")
                .isTrue();
        assertThat(taskService.createTaskQuery().processInstanceId(pid).count())
                .as("受理人的任務不得被動到")
                .isEqualTo(1);
        assertThat(processCancelRows(pid))
                .as("被拒的撤回不得宣告一件沒發生的事")
                .isZero();
        assertThat(dataAccessRows(pid, "user001"))
                .as("這是 payload 形狀錯誤（①），不是授權失敗（③）—— 不留 DATA_ACCESS")
                .isZero();
        HistoricProcessInstance hp = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        assertThat(hp).isNotNull();
        assertThat(hp.getEndTime()).as("案件根本沒有被刪除").isNull();
        assertThat(hp.getDeleteReason()).isNull();
    }

    // ── 上限本身仍可撤回（正常長度不受影響）────────────────────────

    @Test
    @DisplayName("#7 遺留：剛好 1000 字元的 reason 可撤回；DELETE_REASON_ 與稽核原樣保留")
    void maxLengthReasonIsAccepted() throws Exception {
        String pid = startLeave();
        String reason = "r".repeat(1000);

        cancel(pid, reason).andExpect(status().isOk());

        assertThat(runtimeStillExists(pid)).isFalse();
        HistoricProcessInstance hp = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        assertThat(hp).isNotNull();
        assertThat(hp.getDeleteReason())
                .as("上限值本身不得被截斷或改寫 —— 寫進 DELETE_REASON_ 的就是原字串")
                .isEqualTo(reason);
        assertThat(processCancelRows(pid))
                .as("成功的撤回仍必須留下那唯一一筆稽核")
                .isEqualTo(1);
    }
}
