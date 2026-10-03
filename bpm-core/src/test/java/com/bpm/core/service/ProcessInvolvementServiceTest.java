package com.bpm.core.service;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProcessInvolvementService} 的單元測試：「我參與的案件」的四段查詢。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>這個服務的失敗型態是<b>清單少了幾筆</b>，而不是錯誤：
 * 以 runtime 任務為驅動會漏掉「我審過、案子現在在別人關卡」的最常見情境；
 * 分批若漏掉或重複，案件會從「我參與的」清單靜默消失（本 repo 已經為
 * 這種「靜默消失」付過代價，見 {@code DuplicateApprovalFilterTest}）。
 *
 * <p>另外，MSSQL 的 {@code IN (…)} 參數上限是 2100 —— 分批是<b>正確性需求</b>，
 * 不是效能優化，所以批次大小與完整性都在這裡釘死。
 */
class ProcessInvolvementServiceTest {

    private final TaskService taskService = mock(TaskService.class);
    private final RuntimeService runtimeService = mock(RuntimeService.class);
    private final HistoryService historyService = mock(HistoryService.class);

    private final ProcessInvolvementService service =
            new ProcessInvolvementService(taskService, runtimeService, historyService);

    private static HistoricTaskInstance historicTask(String pid) {
        HistoricTaskInstance t = mock(HistoricTaskInstance.class);
        when(t.getProcessInstanceId()).thenReturn(pid);
        return t;
    }

    private static ProcessInstance runningInstance(String pid) {
        ProcessInstance pi = mock(ProcessInstance.class);
        when(pi.getProcessInstanceId()).thenReturn(pid);
        return pi;
    }

    private static HistoricProcessInstance historicInstance(String pid) {
        HistoricProcessInstance pi = mock(HistoricProcessInstance.class);
        when(pi.getId()).thenReturn(pid);
        return pi;
    }

    private static Task task(String id) {
        Task t = mock(Task.class);
        when(t.getId()).thenReturn(id);
        return t;
    }

    // ── 參與集合 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("historicInstanceIdsTouchedBy：以歷史任務為驅動")
    class TouchedBy {

        @Test
        @DisplayName("同一案件多個任務只留一筆，null pid 濾掉")
        void deduplicatesAndFiltersNulls() {
            HistoricTaskInstanceQuery q = mock(HistoricTaskInstanceQuery.class);
            when(q.taskInvolvedUser("user001")).thenReturn(q);
            HistoricTaskInstance first = historicTask("pid-1");
            HistoricTaskInstance duplicate = historicTask("pid-1");
            HistoricTaskInstance nullPid = historicTask(null);
            HistoricTaskInstance second = historicTask("pid-2");
            when(q.list()).thenReturn(List.of(first, duplicate, nullPid, second));
            when(historyService.createHistoricTaskInstanceQuery()).thenReturn(q);

            assertThat(service.historicInstanceIdsTouchedBy("user001"))
                    .containsExactly("pid-1", "pid-2");
        }

        @Test
        @DisplayName("執行中的任務也有歷史列，所以這個集合包含目前開著的關卡")
        void includesRunningTasks() {
            HistoricTaskInstanceQuery q = mock(HistoricTaskInstanceQuery.class);
            when(q.taskInvolvedUser("user001")).thenReturn(q);
            HistoricTaskInstance running = historicTask("pid-running");
            when(q.list()).thenReturn(List.of(running));
            when(historyService.createHistoricTaskInstanceQuery()).thenReturn(q);

            assertThat(service.historicInstanceIdsTouchedBy("user001"))
                    .containsExactly("pid-running");
        }
    }

    @Nested
    @DisplayName("runningInstanceIdsInitiatedBy：發起與代發兩條查詢的聯集")
    class RunningInitiatedBy {

        @Test
        @DisplayName("initiator 與 onBehalfOf 都算，重複的 pid 只留一筆")
        void unionsInitiatorAndOnBehalfOf() {
            ProcessInstanceQuery initiator = mock(ProcessInstanceQuery.class);
            when(initiator.variableValueEquals("initiator", "user001")).thenReturn(initiator);
            ProcessInstance pid1 = runningInstance("pid-1");
            ProcessInstance pid2 = runningInstance("pid-2");
            when(initiator.list()).thenReturn(List.of(pid1, pid2));

            ProcessInstanceQuery onBehalf = mock(ProcessInstanceQuery.class);
            when(onBehalf.variableValueEquals("onBehalfOf", "user001")).thenReturn(onBehalf);
            ProcessInstance pid2Again = runningInstance("pid-2");
            ProcessInstance pid3 = runningInstance("pid-3");
            when(onBehalf.list()).thenReturn(List.of(pid2Again, pid3));

            when(runtimeService.createProcessInstanceQuery()).thenReturn(initiator, onBehalf);

            assertThat(service.runningInstanceIdsInitiatedBy("user001"))
                    .containsExactly("pid-1", "pid-2", "pid-3");
        }
    }

    @Nested
    @DisplayName("historicInstanceIdsInitiatedBy：歷史（含執行中）的發起與代發")
    class HistoricInitiatedBy {

        @Test
        @DisplayName("兩條歷史查詢的結果聯集，重複去重")
        void unionsHistoricInitiatorAndOnBehalfOf() {
            HistoricProcessInstanceQuery initiator = mock(HistoricProcessInstanceQuery.class);
            when(initiator.variableValueEquals("initiator", "user001")).thenReturn(initiator);
            HistoricProcessInstance pid1 = historicInstance("pid-1");
            when(initiator.list()).thenReturn(List.of(pid1));

            HistoricProcessInstanceQuery onBehalf = mock(HistoricProcessInstanceQuery.class);
            when(onBehalf.variableValueEquals("onBehalfOf", "user001")).thenReturn(onBehalf);
            HistoricProcessInstance pid1Again = historicInstance("pid-1");
            HistoricProcessInstance pid2 = historicInstance("pid-2");
            when(onBehalf.list()).thenReturn(List.of(pid1Again, pid2));

            when(historyService.createHistoricProcessInstanceQuery()).thenReturn(initiator, onBehalf);

            assertThat(service.historicInstanceIdsInitiatedBy("user001"))
                    .containsExactly("pid-1", "pid-2");
        }
    }

    @Nested
    @DisplayName("involved*：參與過的 ∪ 發起的")
    class Involved {

        @Test
        @DisplayName("執行中參與集合 = 碰過的任務 ∪ runtime 發起／代發")
        void runningUnion() {
            HistoricTaskInstanceQuery touched = mock(HistoricTaskInstanceQuery.class);
            when(touched.taskInvolvedUser("user001")).thenReturn(touched);
            HistoricTaskInstance touchedTask = historicTask("pid-touched");
            when(touched.list()).thenReturn(List.of(touchedTask));
            when(historyService.createHistoricTaskInstanceQuery()).thenReturn(touched);

            ProcessInstanceQuery initiator = mock(ProcessInstanceQuery.class);
            when(initiator.variableValueEquals(anyString(), any())).thenReturn(initiator);
            ProcessInstance initiated = runningInstance("pid-initiated");
            when(initiator.list()).thenReturn(List.of(initiated));
            when(runtimeService.createProcessInstanceQuery()).thenReturn(initiator);

            assertThat(service.involvedRunningInstanceIds("user001"))
                    .containsExactly("pid-touched", "pid-initiated");
        }

        @Test
        @DisplayName("歷史參與集合 = 碰過的任務 ∪ 歷史發起／代發")
        void historicUnion() {
            HistoricTaskInstanceQuery touched = mock(HistoricTaskInstanceQuery.class);
            when(touched.taskInvolvedUser("user001")).thenReturn(touched);
            HistoricTaskInstance touchedTask = historicTask("pid-touched");
            when(touched.list()).thenReturn(List.of(touchedTask));
            when(historyService.createHistoricTaskInstanceQuery()).thenReturn(touched);

            HistoricProcessInstanceQuery initiator = mock(HistoricProcessInstanceQuery.class);
            when(initiator.variableValueEquals(anyString(), any())).thenReturn(initiator);
            HistoricProcessInstance initiated = historicInstance("pid-initiated");
            when(initiator.list()).thenReturn(List.of(initiated));
            when(historyService.createHistoricProcessInstanceQuery()).thenReturn(initiator);

            assertThat(service.involvedHistoricInstanceIds("user001"))
                    .containsExactly("pid-touched", "pid-initiated");
        }
    }

    // ── 分批撈回 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("findRunning／findHistoric／findOpenTasks：依 ID_BATCH 分批")
    class Batching {

        @Test
        @DisplayName("2500 個 id → 3 批（1000／1000／500），不漏不重")
        void runningBatchesAtOneThousand() {
            List<Set<String>> batches = new ArrayList<>();
            when(runtimeService.createProcessInstanceQuery()).thenAnswer(inv -> {
                ProcessInstanceQuery q = mock(ProcessInstanceQuery.class);
                when(q.processInstanceIds(anySet())).thenAnswer(i -> {
                    batches.add(new LinkedHashSet<>(i.getArgument(0)));
                    return q;
                });
                when(q.orderByStartTime()).thenReturn(q);
                when(q.desc()).thenReturn(q);
                when(q.list()).thenReturn(List.of());
                return q;
            });

            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < 2500; i++) ids.add("pid-" + i);

            assertThat(service.findRunning(ids)).isEmpty();
            assertThat(batches).hasSize(3);
            assertThat(batches.get(0)).hasSize(1000);
            assertThat(batches.get(1)).hasSize(1000);
            assertThat(batches.get(2)).hasSize(500);
            assertThat(batches.stream().flatMap(Collection::stream).collect(java.util.stream.Collectors.toSet()))
                    .as("分批不得漏掉或重複任何案件 —— 漏掉的會從清單靜默消失")
                    .isEqualTo(ids);
        }

        @Test
        @DisplayName("空集合不得產生查詢（IN () 會讓 MSSQL 直接報錯）")
        void emptyIdsProduceNoQuery() {
            assertThat(service.findRunning(Set.of())).isEmpty();
            assertThat(service.findHistoric(Set.of())).isEmpty();
            assertThat(service.findOpenTasks(Set.of())).isEmpty();

            verify(runtimeService, never()).createProcessInstanceQuery();
            verify(historyService, never()).createHistoricProcessInstanceQuery();
            verify(taskService, never()).createTaskQuery();
        }

        @Test
        @DisplayName("findHistoric 每批都套用開始時間遞減排序，結果合併")
        void historicBatchingAndOrdering() {
            List<Set<String>> batches = new ArrayList<>();
            when(historyService.createHistoricProcessInstanceQuery()).thenAnswer(inv -> {
                HistoricProcessInstanceQuery q = mock(HistoricProcessInstanceQuery.class);
                when(q.processInstanceIds(anySet())).thenAnswer(i -> {
                    batches.add(new LinkedHashSet<>(i.getArgument(0)));
                    return q;
                });
                when(q.orderByProcessInstanceStartTime()).thenReturn(q);
                when(q.desc()).thenReturn(q);
                HistoricProcessInstance instance = historicInstance("h");
                when(q.list()).thenReturn(List.of(instance));
                return q;
            });

            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < 1500; i++) ids.add("pid-" + i);

            assertThat(service.findHistoric(ids)).hasSize(2);
            assertThat(batches).hasSize(2);
        }

        @Test
        @DisplayName("findOpenTasks 依 processInstanceIdIn 分批，空集合不查")
        void openTasksBatching() {
            List<Set<String>> batches = new ArrayList<>();
            when(taskService.createTaskQuery()).thenAnswer(inv -> {
                TaskQuery q = mock(TaskQuery.class);
                when(q.processInstanceIdIn(anyCollection())).thenAnswer(i -> {
                    batches.add(new LinkedHashSet<>(i.getArgument(0)));
                    return q;
                });
                when(q.orderByTaskCreateTime()).thenReturn(q);
                when(q.asc()).thenReturn(q);
                Task openTask = task("task-1");
                when(q.list()).thenReturn(List.of(openTask));
                return q;
            });

            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < 1001; i++) ids.add("pid-" + i);

            assertThat(service.findOpenTasks(ids)).hasSize(2);
            assertThat(batches).hasSize(2);
            assertThat(batches.get(0)).hasSize(1000);
            assertThat(batches.get(1)).hasSize(1);
        }
    }

    // ── partition 邊界 ────────────────────────────────────────────

    @Nested
    @DisplayName("partition：邊界與空輸入")
    class Partition {

        @Test
        @DisplayName("剛好 1000 筆 → 一批；1001 筆 → 兩批")
        void exactBoundaries() {
            Set<String> exactly = new LinkedHashSet<>();
            for (int i = 0; i < ProcessInvolvementService.ID_BATCH; i++) exactly.add("pid-" + i);
            assertThat(ProcessInvolvementService.partition(exactly)).hasSize(1);

            Set<String> oneMore = new LinkedHashSet<>(exactly);
            oneMore.add("pid-overflow");
            assertThat(ProcessInvolvementService.partition(oneMore)).hasSize(2);
        }

        @Test
        @DisplayName("null 與空集合 → 空批次清單")
        void emptyInputs() {
            assertThat(ProcessInvolvementService.partition(null)).isEmpty();
            assertThat(ProcessInvolvementService.partition(Set.of())).isEmpty();
        }
    }
}
