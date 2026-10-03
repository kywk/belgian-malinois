package com.bpm.core.security;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.service.CandidateGroupMembership;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

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
 * {@link TaskHolderGuard} 的單元測試：單一任務「持有者」的唯一一份規則（#77）。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>改動前 {@code PUT /api/tasks/{id}} 完全沒有持有者檢查 ——
 * 任何登入者都能批准任意一張請假單，而且稽核看起來完全正常
 * （{@code TASK_APPROVE | operatorId = user002}）。本類別的每一條測試
 * 都是在釘住「誰可以動這個任務」的四個條件；少任何一個條件，
 * 對應的合法使用者就會被靜默擋掉（讀得到、寫不進），
 * 或反過來多放行一個不該動的人。
 *
 * <p>另外兩條不是功能而是成本／可觀察性：<b>沒有候選群組的任務不得
 * 去算呼叫者的群組</b>（簽核交易內打外部系統會自我死鎖，P1-10），
 * 以及拒絕必須留痕。
 */
class TaskHolderGuardTest {

    private final TaskService taskService = mock(TaskService.class);
    private final CandidateGroupMembership groupMembership = mock(CandidateGroupMembership.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);

    private final TaskHolderGuard guard =
            new TaskHolderGuard(taskService, groupMembership, auditPublisher);

    /** 一個可用於 {@code createTaskQuery()} 鏈式呼叫的 query mock。 */
    private static TaskQuery queryWithCount(long count) {
        TaskQuery q = mock(TaskQuery.class);
        when(q.taskId(anyString())).thenReturn(q);
        when(q.taskAssignee(anyString())).thenReturn(q);
        when(q.taskCandidateUser(anyString())).thenReturn(q);
        when(q.taskCandidateGroupIn(any())).thenReturn(q);
        when(q.count()).thenReturn(count);
        return q;
    }

    private static Task task(String taskId, String assignee, String owner) {
        Task t = mock(Task.class);
        when(t.getId()).thenReturn(taskId);
        when(t.getAssignee()).thenReturn(assignee);
        when(t.getOwner()).thenReturn(owner);
        when(t.getProcessInstanceId()).thenReturn("pid-1");
        return t;
    }

    private static IdentityLink candidateGroupLink(String groupId) {
        IdentityLink link = mock(IdentityLink.class);
        when(link.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(link.getGroupId()).thenReturn(groupId);
        return link;
    }

    // ── isHolder：四個條件 ────────────────────────────────────────

    @Nested
    @DisplayName("isHolder：assignee／owner／候選人／候選群組四個條件")
    class IsHolder {

        @Test
        @DisplayName("assignee 是我 → true，且不必碰 identity link 或外部系統")
        void assigneeHolds() {
            assertThat(guard.isHolder(task("task-1", "user001", null), "user001")).isTrue();

            verify(taskService, never()).getIdentityLinksForTask(anyString());
            verify(groupMembership, never()).groupsOf(anyString());
        }

        @Test
        @DisplayName("owner 是我 → true（delegate 後原指派人必須能 resolve 收回任務）")
        void ownerHolds() {
            // delegateTask 會把原 assignee 寫進 owner、assignee 換成 delegatee。
            // 少了這一條，resolve 這個 action 會被自己新增的檢查打死。
            assertThat(guard.isHolder(task("task-1", "delegatee", "user001"), "user001")).isTrue();
        }

        @Test
        @DisplayName("我是候選人（taskCandidateUser 命中）→ true")
        void candidateUserHolds() {
            TaskQuery candidateQuery = queryWithCount(1L);
            when(taskService.createTaskQuery()).thenReturn(candidateQuery);

            assertThat(guard.isHolder(task("task-1", null, null), "user001")).isTrue();
        }

        @Test
        @DisplayName("我屬於任務的候選群組 → true（群組由 CandidateGroupMembership 計算，不重寫一份）")
        void candidateGroupHolds() {
            TaskQuery candidateQuery = queryWithCount(0L);
            TaskQuery groupQuery = queryWithCount(1L);
            when(taskService.createTaskQuery()).thenReturn(candidateQuery, groupQuery);
            IdentityLink link = candidateGroupLink("dept001");
            when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of(link));
            when(groupMembership.groupsOf("user001")).thenReturn(Set.of("dept001"));

            assertThat(guard.isHolder(task("task-1", null, null), "user001")).isTrue();
        }

        @Test
        @DisplayName("任務有候選群組但我不屬於 → false")
        void otherGroupDoesNotHold() {
            TaskQuery candidateQuery = queryWithCount(0L);
            TaskQuery groupQuery = queryWithCount(0L);
            when(taskService.createTaskQuery()).thenReturn(candidateQuery, groupQuery);
            IdentityLink link = candidateGroupLink("dept002");
            when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of(link));
            when(groupMembership.groupsOf("user001")).thenReturn(Set.of("dept001"));

            assertThat(guard.isHolder(task("task-1", null, null), "user001")).isFalse();
        }

        @Test
        @DisplayName("任務沒有任何候選群組 → 不得去算呼叫者的群組（簽核交易內的外部 HTTP 會自我死鎖）")
        void noCandidateGroupsNeverCallsMembership() {
            TaskQuery query = queryWithCount(0L);
            when(taskService.createTaskQuery()).thenReturn(query);
            when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of());

            assertThat(guard.isHolder(task("task-1", null, null), "user001")).isFalse();
            verify(groupMembership, never()).groupsOf(anyString());
        }

        @Test
        @DisplayName("非 CANDIDATE 的 identity link（assignee／participant）不算候選群組")
        void nonCandidateLinksAreIgnored() {
            IdentityLink participant = mock(IdentityLink.class);
            when(participant.getType()).thenReturn("participant");
            when(participant.getGroupId()).thenReturn("dept001");
            TaskQuery query = queryWithCount(0L);
            when(taskService.createTaskQuery()).thenReturn(query);
            when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of(participant));

            assertThat(guard.isHolder(task("task-1", null, null), "user001")).isFalse();
            verify(groupMembership, never()).groupsOf(anyString());
        }

        @Test
        @DisplayName("候選群組連結的 groupId 為 null／空白 → 濾掉，不視為一個空群組")
        void blankGroupIdsAreFiltered() {
            TaskQuery query = queryWithCount(0L);
            when(taskService.createTaskQuery()).thenReturn(query);
            IdentityLink nullGroup = candidateGroupLink(null);
            IdentityLink blankGroup = candidateGroupLink("  ");
            when(taskService.getIdentityLinksForTask("task-1"))
                    .thenReturn(List.of(nullGroup, blankGroup));

            assertThat(guard.isHolder(task("task-1", null, null), "user001")).isFalse();
            verify(groupMembership, never()).groupsOf(anyString());
        }

        @Test
        @DisplayName("task 為 null 或呼叫者未認證 → false（不拋例外，由 requireHolder 決定回應碼）")
        void nullInputsAreFalse() {
            assertThat(guard.isHolder(null, "user001")).isFalse();
            assertThat(guard.isHolder(task("task-1", null, null), null)).isFalse();
            assertThat(guard.isHolder(task("task-1", null, null), "  ")).isFalse();
        }
    }

    // ── inboxQueries：讀寫兩端共用同一組查詢建構子 ────────────────

    @Nested
    @DisplayName("inboxQueries：我的待辦的三個批次查詢")
    class InboxQueries {

        @Test
        @DisplayName("沒有群組 → 只有兩條查詢，不得產生 IN ()（MSSQL 直接報錯）")
        void withoutGroupsOnlyTwoQueries() {
            TaskQuery assigned = queryWithCount(0L);
            TaskQuery candidate = queryWithCount(0L);
            when(taskService.createTaskQuery()).thenReturn(assigned, candidate);
            when(groupMembership.groupsOf("user001")).thenReturn(Set.of());

            assertThat(guard.inboxQueries("user001")).containsExactly(assigned, candidate);
            verify(assigned).taskAssignee("user001");
            verify(candidate).taskCandidateUser("user001");
        }

        @Test
        @DisplayName("有群組 → 第三條查詢帶上群組清單")
        void withGroupsAddsGroupQuery() {
            TaskQuery assigned = queryWithCount(0L);
            TaskQuery candidate = queryWithCount(0L);
            TaskQuery groups = queryWithCount(0L);
            when(taskService.createTaskQuery()).thenReturn(assigned, candidate, groups);
            when(groupMembership.groupsOf("user001")).thenReturn(Set.of("dept001", "hr:leave:approve"));

            assertThat(guard.inboxQueries("user001")).hasSize(3);
            verify(groups).taskCandidateGroupIn(
                    org.mockito.ArgumentMatchers.argThat(
                            g -> g.containsAll(List.of("dept001", "hr:leave:approve"))));
        }
    }

    // ── requireHolder：回應碼分流與留痕 ───────────────────────────

    @Nested
    @DisplayName("requireHolder：未認證 401、非持有者 404、拒絕留痕")
    class RequireHolder {

        @Test
        @DisplayName("持有者放行，不寫稽核")
        void holderPasses() {
            assertThatCode(() -> guard.requireHolder(task("task-1", "user001", null), "user001"))
                    .doesNotThrowAnyException();
            verify(auditPublisher, never()).publishDetached(any());
        }

        @Test
        @DisplayName("未認證 → 401（與非持有者的 404 分流，不靜默退化）")
        void unauthenticatedIsUnauthorized() {
            assertThatThrownBy(() -> guard.requireHolder(task("task-1", "user001", null), null))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("非持有者 → 404（不是 403：403 會確認任務存在，taskId 可枚舉）並留痕")
        void nonHolderIsNotFoundWithAudit() {
            TaskQuery query = queryWithCount(0L);
            when(taskService.createTaskQuery()).thenReturn(query);
            when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of());
            Task task = task("task-1", "mgr001", null);

            assertThatThrownBy(() -> guard.requireHolder(task, "user002"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("task-1")
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
            verify(auditPublisher).publishDetached(captor.capture());
            assertThat(captor.getValue().operatorId()).isEqualTo("user002");
            assertThat(captor.getValue().taskId()).isEqualTo("task-1");
            assertThat(captor.getValue().detail())
                    .containsEntry("denied", true)
                    .containsEntry("reason", "not a task holder");
        }
    }
}
