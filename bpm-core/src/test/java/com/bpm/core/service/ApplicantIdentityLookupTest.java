package com.bpm.core.service;

import com.bpm.core.security.ProcessAccessGuard;
import org.flowable.engine.HistoryService;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ApplicantIdentityLookup}／{@link OnBehalfOfLookup} 的單元測試：
 * 「這張單的自然人申請人是誰」的唯一判定（#96）。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>這條規則被催辦授權與完成通知共用。判錯的兩個方向都很難看：
 * <ul>
 *   <li>先取 {@code initiator} 才取 {@code onBehalfOf} —— 通知寄給
 *       {@code system:erp}（寄不出去），催辦也把系統身分當申請人。</li>
 *   <li>不濾系統身分 —— 「代 system:evil 發起」是一句會被當真的話。</li>
 * </ul>
 *
 * <p>另外 {@code OnBehalfOfLookup} 必須<b>整批一次</b>查（歷史任務清單的
 * N+1），且分批大小沿用 {@code ProcessInvolvementService.ID_BATCH}。
 */
class ApplicantIdentityLookupTest {

    private final HistoryService historyService = mock(HistoryService.class);
    private final ProcessAccessGuard accessGuard = mock(ProcessAccessGuard.class);

    private final OnBehalfOfLookup onBehalfOfLookup = new OnBehalfOfLookup(historyService);
    private final ApplicantIdentityLookup lookup =
            new ApplicantIdentityLookup(onBehalfOfLookup, accessGuard);

    private static HistoricVariableInstance variable(String pid, Object value) {
        HistoricVariableInstance v = mock(HistoricVariableInstance.class);
        when(v.getProcessInstanceId()).thenReturn(pid);
        when(v.getValue()).thenReturn(value);
        return v;
    }

    /** 讓 onBehalfOf 的歷史變數查詢回傳指定資料。 */
    private void stubOnBehalfOfVariables(List<HistoricVariableInstance> values) {
        HistoricVariableInstanceQuery q = mock(HistoricVariableInstanceQuery.class);
        when(q.processInstanceIds(anyCollection())).thenReturn(q);
        when(q.variableName("onBehalfOf")).thenReturn(q);
        when(q.list()).thenReturn(values);
        when(historyService.createHistoricVariableInstanceQuery()).thenReturn(q);
    }

    // ── 申請人政策 ────────────────────────────────────────────────

    @Nested
    @DisplayName("applicantOf：onBehalfOf 優先，其次 initiator，系統身分不算")
    class ApplicantOf {

        @Test
        @DisplayName("onBehalfOf 有值 → 回那位員工，即使 initiator 是人也不採用")
        void onBehalfOfWins() {
            stubOnBehalfOfVariables(List.of(variable("pid-1", "user001")));

            assertThat(lookup.applicantOf("pid-1")).isEqualTo("user001");
            verify(accessGuard, never()).initiatorOf("pid-1");
        }

        @Test
        @DisplayName("沒有 onBehalfOf → 採 initiator（人工發起的既有行為）")
        void initiatorIsFallback() {
            stubOnBehalfOfVariables(List.of());
            when(accessGuard.initiatorOf("pid-1")).thenReturn("user002");

            assertThat(lookup.applicantOf("pid-1")).isEqualTo("user002");
        }

        @Test
        @DisplayName("initiator 是 system:erp → null（通知寄不出去，催辦也不該通過）")
        void systemInitiatorYieldsNull() {
            stubOnBehalfOfVariables(List.of());
            when(accessGuard.initiatorOf("pid-1")).thenReturn("system:erp");

            assertThat(lookup.applicantOf("pid-1")).isNull();
        }

        @Test
        @DisplayName("onBehalfOf 是系統身分 → 被濾掉，改走 initiator 這條路")
        void systemOnBehalfOfIsFilteredThenFallsBack() {
            // ExternalApiController 的寫入端驗證正常情況下不會產生這個值，
            // 但顯示／通知端不該把寫入端的驗證當成不變式。
            stubOnBehalfOfVariables(List.of(variable("pid-1", "system:evil")));
            when(accessGuard.initiatorOf("pid-1")).thenReturn("user001");

            assertThat(lookup.applicantOf("pid-1")).isEqualTo("user001");
        }

        @Test
        @DisplayName("兩者都不存在 → null，不得 NPE")
        void neitherYieldsNull() {
            stubOnBehalfOfVariables(List.of());
            when(accessGuard.initiatorOf("pid-1")).thenReturn(null);

            assertThat(lookup.applicantOf("pid-1")).isNull();
        }

        @Test
        @DisplayName("null／空白 pid（standalone 加簽子任務）→ null，完全不查")
        void blankPidYieldsNullWithoutQuery() {
            assertThat(lookup.applicantOf(null)).isNull();
            assertThat(lookup.applicantOf("  ")).isNull();
            verifyNoInteractions(historyService);
            verifyNoInteractions(accessGuard);
        }
    }

    // ── 批次查詢 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("OnBehalfOfLookup：整批一次、濾系統身分、取第一筆")
    class OnBehalfOfQuery {

        @Test
        @DisplayName("空集合或 null → 空 map，不查引擎")
        void emptyInputs() {
            assertThat(onBehalfOfLookup.byProcessInstances(null)).isEmpty();
            assertThat(onBehalfOfLookup.byProcessInstances(List.of())).isEmpty();
            verifyNoInteractions(historyService);
        }

        @Test
        @DisplayName("null pid、null 值與空白值都濾掉（查得到就是代發，呼叫端不必處理 null）")
        void invalidRowsAreFiltered() {
            stubOnBehalfOfVariables(List.of(
                    variable(null, "user001"),
                    variable("pid-null-value", null),
                    variable("pid-blank", "  ")));

            assertThat(onBehalfOfLookup.byProcessInstances(List.of("pid-null-value", "pid-blank")))
                    .isEmpty();
        }

        @Test
        @DisplayName("同一 pid 多筆（子流程／multi-instance）取第一筆")
        void firstValueWins() {
            stubOnBehalfOfVariables(List.of(
                    variable("pid-1", "user001"),
                    variable("pid-1", "user002")));

            assertThat(onBehalfOfLookup.byProcessInstances(List.of("pid-1")))
                    .containsEntry("pid-1", "user001");
        }

        @Test
        @DisplayName("超過 ID_BATCH 筆 → 分批查詢，不漏不重")
        void batchesAboveIdBatch() {
            List<Set<String>> batches = new ArrayList<>();
            HistoricVariableInstanceQuery q = mock(HistoricVariableInstanceQuery.class);
            when(q.processInstanceIds(anyCollection())).thenAnswer(i -> {
                batches.add(new LinkedHashSet<>(i.getArgument(0)));
                return q;
            });
            when(q.variableName("onBehalfOf")).thenReturn(q);
            when(q.list()).thenReturn(List.of());
            when(historyService.createHistoricVariableInstanceQuery()).thenReturn(q);

            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < 2500; i++) ids.add("pid-" + i);

            assertThat(onBehalfOfLookup.byProcessInstances(ids)).isEmpty();
            assertThat(batches).hasSize(3);
            assertThat(batches.get(0)).hasSize(1000);
            assertThat(batches.get(2)).hasSize(500);
        }
    }
}
