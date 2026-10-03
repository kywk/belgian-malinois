package com.bpm.core.security;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProcessAccessGuard} 的單元測試：案件層授權的唯一一份規則。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>守衛的每一條規則被改壞時，症狀都不是 500 而是「回應碼變了」——
 * 非關係人從 404 變成 200（讀得到別人的薪資變數），或稽核旁路從「留痕」
 * 變成「靜默放行」。這兩種都不會有當機或錯誤訊息，只有授權悄悄放寬。
 * 既有整合測試驗的是「端點有接上守衛」，這裡驗的是守衛本身的每一條邊界。
 *
 * <p>純 Mockito：需要精確控制「runtime 查不到、只剩歷史」與「任務有／沒有
 * 候選群組」這類引擎狀態，用真實容器反而難穩定重現。
 */
class ProcessAccessGuardTest {

    private final RuntimeService runtimeService = mock(RuntimeService.class);
    private final HistoryService historyService = mock(HistoryService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);

    private final ProcessAccessGuard guard =
            new ProcessAccessGuard(runtimeService, historyService, taskService, auditPublisher);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── stub 工具 ──────────────────────────────────────────────────

    /** 執行中的實例，並可指定 initiator 變數（null 表示沒有該變數）。 */
    private void stubRunningInstance(String pid, String initiator) {
        ProcessInstanceQuery q = mock(ProcessInstanceQuery.class);
        when(q.processInstanceId(pid)).thenReturn(q);
        when(q.singleResult()).thenReturn(mock(ProcessInstance.class));
        when(q.count()).thenReturn(1L);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(q);
        if (initiator != null) {
            when(runtimeService.getVariable(pid, "initiator")).thenReturn(initiator);
        }
    }

    /** runtime 查不到此實例（已結案或不存在）。 */
    private void stubNoRunningInstance() {
        ProcessInstanceQuery q = mock(ProcessInstanceQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.singleResult()).thenReturn(null);
        when(q.count()).thenReturn(0L);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(q);
    }

    /** 已結案實例的歷史 initiator 變數。 */
    private void stubHistoricInitiator(String pid, String initiator) {
        HistoricVariableInstanceQuery q = mock(HistoricVariableInstanceQuery.class);
        when(q.processInstanceId(pid)).thenReturn(q);
        when(q.variableName("initiator")).thenReturn(q);
        List<HistoricVariableInstance> values = initiator == null ? List.of()
                : List.of(historicVariable(pid, initiator));
        when(q.list()).thenReturn(values);
        when(historyService.createHistoricVariableInstanceQuery()).thenReturn(q);
    }

    private static HistoricVariableInstance historicVariable(String pid, Object value) {
        HistoricVariableInstance v = mock(HistoricVariableInstance.class);
        when(v.getProcessInstanceId()).thenReturn(pid);
        when(v.getValue()).thenReturn(value);
        return v;
    }

    /**
     * 任務參與查詢：runtime 與歷史各給一個計數。
     *
     * <p>同一個 query mock 同時服務 {@code taskId(...).singleResult()}
     * （task → pid 解析）與 {@code processInstanceId(...).taskInvolvedUser(...).count()}
     * （關係人判定）—— 兩者在同一條授權路徑上先後發生。
     */
    private TaskQuery stubTaskQueries(Long runtimeInvolvedCount, Long historicInvolvedCount) {
        TaskQuery tq = mock(TaskQuery.class);
        when(tq.taskId(anyString())).thenReturn(tq);
        when(tq.processInstanceId(anyString())).thenReturn(tq);
        when(tq.taskInvolvedUser(anyString())).thenReturn(tq);
        when(tq.count()).thenReturn(runtimeInvolvedCount == null ? 0L : runtimeInvolvedCount);
        when(taskService.createTaskQuery()).thenReturn(tq);

        HistoricTaskInstanceQuery hq = mock(HistoricTaskInstanceQuery.class);
        when(hq.taskId(anyString())).thenReturn(hq);
        when(hq.processInstanceId(anyString())).thenReturn(hq);
        when(hq.taskInvolvedUser(anyString())).thenReturn(hq);
        when(hq.count()).thenReturn(historicInvolvedCount == null ? 0L : historicInvolvedCount);
        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(hq);
        return tq;
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user001", null,
                        java.util.Arrays.stream(authorities)
                                .map(SimpleGrantedAuthority::new).toList()));
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── stateOf／exists ────────────────────────────────────────────

    @Nested
    @DisplayName("案件狀態三分類：RUNNING／FINISHED／ABSENT")
    class StateOf {

        @Test
        @DisplayName("runtime 查得到 → RUNNING（即使歷史也有）")
        void runningWinsOverHistory() {
            ProcessInstanceQuery rq = mock(ProcessInstanceQuery.class);
            when(rq.processInstanceId("pid-1")).thenReturn(rq);
            when(rq.count()).thenReturn(1L);
            when(runtimeService.createProcessInstanceQuery()).thenReturn(rq);

            assertThat(guard.stateOf("pid-1")).isEqualTo(ProcessAccessGuard.InstanceState.RUNNING);
            verify(historyService, never()).createHistoricProcessInstanceQuery();
        }

        @Test
        @DisplayName("runtime 查不到但歷史有 → FINISHED（已結案案件必須讀得到軌跡）")
        void historyOnlyIsFinished() {
            stubNoRunningInstance();
            HistoricProcessInstanceQuery hq = mock(HistoricProcessInstanceQuery.class);
            when(hq.processInstanceId("pid-1")).thenReturn(hq);
            when(hq.count()).thenReturn(1L);
            when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);

            assertThat(guard.stateOf("pid-1")).isEqualTo(ProcessAccessGuard.InstanceState.FINISHED);
        }

        @Test
        @DisplayName("兩者都查不到 → ABSENT，exists 必須為 false")
        void absentWhenNeither() {
            stubNoRunningInstance();
            HistoricProcessInstanceQuery hq = mock(HistoricProcessInstanceQuery.class);
            when(hq.processInstanceId("pid-1")).thenReturn(hq);
            when(hq.count()).thenReturn(0L);
            when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);

            assertThat(guard.stateOf("pid-1")).isEqualTo(ProcessAccessGuard.InstanceState.ABSENT);
            assertThat(guard.exists("pid-1")).isFalse();
        }
    }

    // ── requireParticipant ────────────────────────────────────────

    @Nested
    @DisplayName("requireParticipant：關係人以外一律 404（不是 403）")
    class RequireParticipant {

        @Test
        @DisplayName("發起人本人放行")
        void initiatorIsParticipant() {
            stubRunningInstance("pid-1", "user001");

            assertThatCode(() -> guard.requireParticipant("pid-1", "user001"))
                    .doesNotThrowAnyException();
            verify(auditPublisher, never()).publishDetached(any());
        }

        @Test
        @DisplayName("未認證（null 或空白 userId）→ 404，且完全不查引擎")
        void blankUserIsNotFoundWithoutEngineCall() {
            assertNotFound(() -> guard.requireParticipant("pid-1", null));
            assertNotFound(() -> guard.requireParticipant("pid-1", "  "));
            verify(runtimeService, never()).createProcessInstanceQuery();
            verify(taskService, never()).createTaskQuery();
        }

        @Test
        @DisplayName("非關係人 → 404，且留下一筆 denied 稽核（有人嘗試翻別人的案件）")
        void nonParticipantIsDeniedWithAudit() {
            stubNoRunningInstance();
            stubHistoricInitiator("pid-1", "owner001");
            stubTaskQueries(0L, 0L);

            assertNotFound(() -> guard.requireParticipant("pid-1", "user002"));

            ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
            verify(auditPublisher).publishDetached(captor.capture());
            assertThat(captor.getValue().operatorId()).isEqualTo("user002");
            assertThat(captor.getValue().processInstanceId()).isEqualTo("pid-1");
            assertThat(captor.getValue().detail())
                    .containsEntry("denied", true)
                    .containsEntry("reason", "not a participant");
        }

        @Test
        @DisplayName("目前持有任務者（runtime identity link）放行")
        void runtimeTaskHolderIsParticipant() {
            stubNoRunningInstance();
            stubHistoricInitiator("pid-1", "owner001");
            stubTaskQueries(1L, 0L);

            assertThatCode(() -> guard.requireParticipant("pid-1", "user002"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("曾處理過此案件者（已完成關卡）放行 —— 否則簽過的人再也讀不到自己簽的單")
        void historicTaskHolderIsParticipant() {
            stubNoRunningInstance();
            stubHistoricInitiator("pid-1", "owner001");
            stubTaskQueries(0L, 1L);

            assertThatCode(() -> guard.requireParticipant("pid-1", "user002"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("已結案的案件：initiator 由歷史變數解析，發起人仍放行")
        void historicInitiatorOfFinishedInstance() {
            stubNoRunningInstance();
            stubHistoricInitiator("pid-1", "user001");

            assertThatCode(() -> guard.requireParticipant("pid-1", "user001"))
                    .doesNotThrowAnyException();
        }
    }

    // ── requireReadAccess 與稽核旁路 ──────────────────────────────

    @Nested
    @DisplayName("requireReadAccess：關係人或 audit:log:read 稽核旁路")
    class RequireReadAccess {

        @Test
        @DisplayName("關係人放行且不是旁路（回 false）")
        void participantIsNotBypass() {
            stubRunningInstance("pid-1", "user001");

            assertThat(guard.requireReadAccess("pid-1", "user001")).isFalse();
        }

        @Test
        @DisplayName("非關係人但持有 audit:log:read → 放行且回 true（呼叫端據此留痕）")
        void auditAuthorityIsBypass() {
            stubNoRunningInstance();
            stubHistoricInitiator("pid-1", "owner001");
            stubTaskQueries(0L, 0L);
            authenticate("audit:log:read");

            assertThat(guard.requireReadAccess("pid-1", "auditor")).isTrue();
        }

        @Test
        @DisplayName("ROLE_ADMIN 不構成稽核旁路 —— 「能管理系統」不等於「能看全公司薪資」")
        void adminRoleIsNotAuditBypass() {
            stubNoRunningInstance();
            stubHistoricInitiator("pid-1", "owner001");
            stubTaskQueries(0L, 0L);
            authenticate("ROLE_ADMIN");

            assertNotFound(() -> guard.requireReadAccess("pid-1", "admin001"));
            verify(auditPublisher).publishDetached(any());
        }

        @Test
        @DisplayName("非關係人且無 audit 權限 → 404 並留痕（旁路不是預設值）")
        void nonParticipantWithoutAuditIsDenied() {
            stubNoRunningInstance();
            stubHistoricInitiator("pid-1", "owner001");
            stubTaskQueries(0L, 0L);
            authenticate("ROLE_USER");

            assertNotFound(() -> guard.requireReadAccess("pid-1", "user002"));
        }

        @Test
        @DisplayName("未認證 → 404，不因 SecurityContext 為空而放行")
        void unauthenticatedIsDenied() {
            assertNotFound(() -> guard.requireReadAccess("pid-1", null));
        }
    }

    // ── requireTaskReadAccess ─────────────────────────────────────

    @Nested
    @DisplayName("requireTaskReadAccess：以 taskId 為入口的讀端授權（#79）")
    class RequireTaskReadAccess {

        @Test
        @DisplayName("taskId 不存在（runtime 與歷史都查不到）→ 404 且訊息指名 taskId")
        void unknownTaskIsNotFound() {
            stubTaskQueries(0L, 0L);

            assertThatThrownBy(() -> guard.requireTaskReadAccess("task-x", "user001"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("task-x")
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("關係人讀取 → 放行且不寫稽核（旁路才需要留痕）")
        void participantDoesNotWriteAudit() {
            TaskQuery tq = stubTaskQueries(0L, 0L);
            Task task = task("task-1", "pid-1");
            when(tq.singleResult()).thenReturn(task);
            stubRunningInstance("pid-1", "user001");

            assertThatCode(() -> guard.requireTaskReadAccess("task-1", "user001"))
                    .doesNotThrowAnyException();
            verify(auditPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("稽核旁路讀取 → 放行且寫入一筆 get_task_comments／auditBypass=true")
        void auditBypassWritesAuditInsideGuard() {
            TaskQuery tq = stubTaskQueries(0L, 0L);
            Task task = task("task-1", "pid-1");
            when(tq.singleResult()).thenReturn(task);
            stubRunningInstance("pid-1", "owner001");
            authenticate("audit:log:read");

            assertThatCode(() -> guard.requireTaskReadAccess("task-1", "auditor"))
                    .doesNotThrowAnyException();

            ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
            verify(auditPublisher).publish(captor.capture());
            AuditEvent event = captor.getValue();
            assertThat(event.taskId()).isEqualTo("task-1");
            assertThat(event.processInstanceId()).isEqualTo("pid-1");
            assertThat(event.detail())
                    .containsEntry("action", "get_task_comments")
                    .containsEntry("auditBypass", true);
        }

        @Test
        @DisplayName("已結束關卡：taskId 由歷史解析得出，關係人仍可讀簽核軌跡")
        void finishedTaskIsResolvedFromHistory() {
            TaskQuery tq = stubTaskQueries(0L, 0L);
            when(tq.singleResult()).thenReturn(null);
            HistoricTaskInstanceQuery hq = historyService.createHistoricTaskInstanceQuery();
            HistoricTaskInstance historic = mock(HistoricTaskInstance.class);
            when(historic.getProcessInstanceId()).thenReturn("pid-1");
            when(hq.singleResult()).thenReturn(historic);
            stubRunningInstance("pid-1", "user001");

            assertThatCode(() -> guard.requireTaskReadAccess("task-1", "user001"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("非關係人且無稽核權限 → 404（與讀取端同一條規則）")
        void nonParticipantIsDenied() {
            TaskQuery tq = stubTaskQueries(0L, 0L);
            Task task = task("task-1", "pid-1");
            when(tq.singleResult()).thenReturn(task);
            stubRunningInstance("pid-1", "owner001");

            assertNotFound(() -> guard.requireTaskReadAccess("task-1", "user002"));
        }
    }

    // ── requireTaskParticipant ────────────────────────────────────

    @Nested
    @DisplayName("requireTaskParticipant：留言等寫端的授權與 pid 回傳")
    class RequireTaskParticipant {

        @Test
        @DisplayName("關係人放行並回傳所屬 pid（省掉呼叫端重複查詢）")
        void returnsProcessInstanceId() {
            TaskQuery tq = stubTaskQueries(0L, 0L);
            Task task = task("task-1", "pid-1");
            when(tq.singleResult()).thenReturn(task);
            stubRunningInstance("pid-1", "user001");

            assertThat(guard.requireTaskParticipant("task-1", "user001")).isEqualTo("pid-1");
        }

        @Test
        @DisplayName("任務不存在 → 404，不落到 500")
        void unknownTaskIsNotFound() {
            stubTaskQueries(0L, 0L);

            assertNotFound(() -> guard.requireTaskParticipant("task-x", "user001"));
        }

        @Test
        @DisplayName("非關係人 → 404 並留痕；稽核旁路不適用於寫入")
        void nonParticipantIsDeniedEvenWithAuditAuthority() {
            TaskQuery tq = stubTaskQueries(0L, 0L);
            Task task = task("task-1", "pid-1");
            when(tq.singleResult()).thenReturn(task);
            stubRunningInstance("pid-1", "owner001");
            authenticate("audit:log:read");

            assertNotFound(() -> guard.requireTaskParticipant("task-1", "auditor"));
        }
    }

    // ── processInstanceIdOfTask ───────────────────────────────────

    @Nested
    @DisplayName("processInstanceIdOfTask：runtime 優先、歷史次之")
    class ProcessInstanceIdOfTask {

        @Test
        @DisplayName("runtime 有任務時直接回它，不查歷史")
        void runtimeWins() {
            TaskQuery tq = stubTaskQueries(0L, 0L);
            Task runtimeTask = task("task-1", "pid-runtime");
            when(tq.singleResult()).thenReturn(runtimeTask);

            assertThat(guard.processInstanceIdOfTask("task-1")).isEqualTo("pid-runtime");
            verify(historyService, never()).createHistoricTaskInstanceQuery();
        }

        @Test
        @DisplayName("runtime 查不到時退回歷史任務")
        void fallsBackToHistory() {
            TaskQuery tq = stubTaskQueries(0L, 0L);
            when(tq.singleResult()).thenReturn(null);
            HistoricTaskInstanceQuery hq = historyService.createHistoricTaskInstanceQuery();
            HistoricTaskInstance historic = mock(HistoricTaskInstance.class);
            when(historic.getProcessInstanceId()).thenReturn("pid-historic");
            when(hq.singleResult()).thenReturn(historic);

            assertThat(guard.processInstanceIdOfTask("task-1")).isEqualTo("pid-historic");
        }

        @Test
        @DisplayName("null／空白 taskId 直接回 null，不查引擎")
        void blankTaskIdIsNull() {
            assertThat(guard.processInstanceIdOfTask(null)).isNull();
            assertThat(guard.processInstanceIdOfTask("  ")).isNull();
            verify(taskService, never()).createTaskQuery();
        }
    }

    // ── requireSelf ───────────────────────────────────────────────

    @Nested
    @DisplayName("requireSelf：查詢身分參數只准自己（#71）")
    class RequireSelf {

        @Test
        @DisplayName("省略或空白 → 預設為呼叫者，不是『全部』")
        void omittedMeansCaller() {
            assertThat(guard.requireSelf(null, "user001", "assignee")).isEqualTo("user001");
            assertThat(guard.requireSelf("  ", "user001", "assignee")).isEqualTo("user001");
        }

        @Test
        @DisplayName("值與登入身分相同 → 放行")
        void matchingValuePasses() {
            assertThat(guard.requireSelf("user001", "user001", "assignee")).isEqualTo("user001");
        }

        @Test
        @DisplayName("值與登入身分不符 → 400，訊息指名參數與正確身分（不是靜默忽略）")
        void mismatchIsBadRequestWithParameterName() {
            assertThatThrownBy(() -> guard.requireSelf("user002", "user001", "assignee"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("assignee")
                    .hasMessageContaining("user001")
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
            verify(auditPublisher).publishDetached(captor.capture());
            assertThat(captor.getValue().detail())
                    .containsEntry("reason", "identity mismatch")
                    .containsEntry("parameter", "assignee")
                    .containsEntry("claimed", "user002");
        }

        @Test
        @DisplayName("未認證 → 401（不是退回可偽造的標頭）")
        void unauthenticatedIsUnauthorized() {
            assertThatThrownBy(() -> guard.requireSelf("user001", null, "assignee"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ── rejectCallerSuppliedGroups ────────────────────────────────

    @Nested
    @DisplayName("rejectCallerSuppliedGroups：候選群組一律由伺服器推導")
    class RejectCallerSuppliedGroups {

        @Test
        @DisplayName("省略 → 不拋例外")
        void omittedPasses() {
            assertThatCode(() -> guard.rejectCallerSuppliedGroups(null, "user001"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> guard.rejectCallerSuppliedGroups("  ", "user001"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("帶了值就拒絕，即使值等於自己的群組 —— 自稱群組等於要求伺服器相信組織歸屬")
        void anyValueIsRejected() {
            assertThatThrownBy(() -> guard.rejectCallerSuppliedGroups("dept001", "user001"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("candidateGroups")
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ── callerHoldsAuditRead ──────────────────────────────────────

    @Nested
    @DisplayName("callerHoldsAuditRead：只認精確的 audit:log:read")
    class CallerHoldsAuditRead {

        @Test
        @DisplayName("持有 audit:log:read → true")
        void exactAuthorityIsTrue() {
            authenticate("audit:log:read");
            assertThat(guard.callerHoldsAuditRead()).isTrue();
        }

        @Test
        @DisplayName("子字串不算、ROLE_ADMIN 不算、未認證不算")
        void onlyExactAuthority() {
            authenticate("audit:log:read:extended", "ROLE_ADMIN");
            assertThat(guard.callerHoldsAuditRead()).isFalse();

            SecurityContextHolder.clearContext();
            assertThat(guard.callerHoldsAuditRead()).isFalse();
        }
    }

    // ── initiatorOf ───────────────────────────────────────────────

    @Nested
    @DisplayName("initiatorOf：runtime 變數優先，已結案看歷史變數")
    class InitiatorOf {

        @Test
        @DisplayName("執行中：runtime 變數就是答案")
        void runtimeVariableWins() {
            stubRunningInstance("pid-1", "user001");

            assertThat(guard.initiatorOf("pid-1")).isEqualTo("user001");
            verify(historyService, never()).createHistoricVariableInstanceQuery();
        }

        @Test
        @DisplayName("已結案：歷史變數解析 initiator")
        void historicVariableWhenFinished() {
            stubNoRunningInstance();
            stubHistoricInitiator("pid-1", "user001");

            assertThat(guard.initiatorOf("pid-1")).isEqualTo("user001");
        }

        @Test
        @DisplayName("歷史變數值為 null → 回 null，不得 NPE")
        void nullHistoricValueYieldsNull() {
            stubNoRunningInstance();
            HistoricVariableInstanceQuery q = mock(HistoricVariableInstanceQuery.class);
            when(q.processInstanceId("pid-1")).thenReturn(q);
            when(q.variableName("initiator")).thenReturn(q);
            HistoricVariableInstance variable = historicVariable("pid-1", null);
            when(q.list()).thenReturn(List.of(variable));
            when(historyService.createHistoricVariableInstanceQuery()).thenReturn(q);

            assertThat(guard.initiatorOf("pid-1")).isNull();
        }
    }

    private static Task task(String taskId, String pid) {
        Task t = mock(Task.class);
        when(t.getId()).thenReturn(taskId);
        when(t.getProcessInstanceId()).thenReturn(pid);
        return t;
    }
}
