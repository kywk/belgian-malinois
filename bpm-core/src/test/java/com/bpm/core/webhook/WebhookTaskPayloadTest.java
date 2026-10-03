package com.bpm.core.webhook;

import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 節點層 webhook payload 的欄位契約（#25）—— <b>純單元測試</b>。
 *
 * <h2>為什麼直接測 {@link WebhookTaskListener#buildPayload}</h2>
 *
 * <p>與 {@code ProcessCompletedListenerTest} 相同的理由：投遞位址改由 BPMN
 * 設定之後，端到端測試只能證明「有一則訊息出去了」，證明不了「這一則訊息
 * 帶了哪些欄位」。而欄位是與外部系統的契約，多加一個敏感欄位（表單內容、
 * 姓名、候選人）或漏掉一個必要欄位，都不會有任何錯誤訊息 ——
 * 只有把 map 本身釘住才防得住。</p>
 *
 * <h2>這一組特別在守兩件事</h2>
 *
 * <ol>
 *   <li><b>#25 補上的欄位</b>：{@code task.timeout} 的
 *       {@code assignee}／{@code dueDate}／{@code overdueHours}
 *       （{@code overdueHours} 的定義見 {@link WebhookTaskListener#overdueHours}）。</li>
 *   <li><b>P2-1 紅線（負向斷言）</b>：payload 不得出現
 *       {@code variables}／{@code comment}／{@code operatorName}／
 *       {@code candidateUsers}／{@code candidateGroups}。使用者 2026-10-02
 *       裁決沿用紅線：只補非敏感欄位。</li>
 * </ol>
 */
class WebhookTaskPayloadTest {

    private static final String PROC_DEF =
            "leave-approval:1:12345678-1234-1234-1234-123456789abc";

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final WebhookConfigResolver resolver = mock(WebhookConfigResolver.class);
    private final WebhookTaskListener listener = new WebhookTaskListener(rabbitTemplate, resolver);

    /** 共用欄位齊全的 task；個別測試再補自己關心的欄位。 */
    private static DelegateTask taskWithEvent(String event) {
        DelegateTask task = mock(DelegateTask.class);
        when(task.getEventName()).thenReturn(event);
        when(task.getProcessInstanceId()).thenReturn("pi-1");
        when(task.getProcessDefinitionId()).thenReturn(PROC_DEF);
        when(task.getTaskDefinitionKey()).thenReturn("approve");
        when(task.getId()).thenReturn("task-1");
        when(task.getName()).thenReturn("審核關卡");
        when(task.getVariable("businessKey")).thenReturn("BK-1");
        return task;
    }

    // ── #25：task.timeout 的三個欄位 ────────────────────────────────

    @Nested
    @DisplayName("task.timeout：assignee／dueDate／overdueHours")
    class Timeout {

        @Test
        @DisplayName("有 dueDate → 三個欄位都在，overdueHours 為逾期整點小時")
        void carriesAssigneeDueDateAndOverdueHours() {
            Date due = Date.from(Instant.now().minus(Duration.ofHours(3)));
            DelegateTask task = taskWithEvent("timeout");
            when(task.getAssignee()).thenReturn("mgr001");
            when(task.getDueDate()).thenReturn(due);

            Map<String, Object> payload = listener.buildPayload(task, "timeout");

            assertThat(payload)
                    .containsEntry("event", "task.timeout")
                    .containsEntry("assignee", "mgr001")
                    .containsEntry("dueDate", due)
                    .containsEntry("businessKey", "BK-1")
                    .containsEntry("taskId", "task-1")
                    .containsEntry("taskName", "審核關卡");
            // dueDate 是三小時前；呼叫與斷言之間的毫秒級延遲不影響
            // Duration.toHours() 的結果（3 小時又幾毫秒 → 3）。
            assertThat(payload.get("overdueHours"))
                    .as("overdueHours = dueDate 到事件時間的整點小時（無條件捨去）")
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("dueDate 為 null → overdueHours 為 null，鍵仍在（schema 固定）")
        void nullDueDateYieldsNullOverdueHours() {
            DelegateTask task = taskWithEvent("timeout");
            when(task.getDueDate()).thenReturn(null);

            Map<String, Object> payload = listener.buildPayload(task, "timeout");

            // 沒有 dueDate 就沒有計算起點，不猜、也不把鍵拿掉 ——
            // 接收端的 schema 不應該因為「這個任務沒有截止日」而變形。
            assertThat(payload).containsKey("dueDate");
            assertThat(payload).containsKey("overdueHours");
            assertThat(payload.get("dueDate")).isNull();
            assertThat(payload.get("overdueHours")).isNull();
        }

        @Test
        @DisplayName("事件時間早於 dueDate → overdueHours 為 0，不回報負數")
        void futureDueDateClampsToZero() {
            DelegateTask task = taskWithEvent("timeout");
            when(task.getAssignee()).thenReturn("mgr001");
            when(task.getDueDate()).thenReturn(Date.from(Instant.now().plus(Duration.ofHours(2))));

            Map<String, Object> payload = listener.buildPayload(task, "timeout");

            assertThat(payload.get("overdueHours")).isEqualTo(0L);
        }
    }

    /**
     * {@code overdueHours} 的定義用固定時鐘逐條釘住。
     *
     * <p>不經過 {@code buildPayload}／{@code Instant.now()}，所以邊界值
     * （59 分 vs 60 分、剛好 0、負值）不會因為執行速度而 flaky。</p>
     */
    @Nested
    @DisplayName("overdueHours 的定義（固定時鐘）")
    class OverdueHoursDefinition {

        private final Instant due = Instant.parse("2026-01-01T00:00:00Z");

        @Test
        @DisplayName("整點小時無條件捨去：59 分 59 秒 → 0、60 分 → 1、3.5 小時 → 3")
        void truncatesToWholeHours() {
            assertThat(WebhookTaskListener.overdueHours(
                    Date.from(due), due.plusSeconds(3599))).isEqualTo(0L);
            assertThat(WebhookTaskListener.overdueHours(
                    Date.from(due), due.plus(Duration.ofMinutes(60)))).isEqualTo(1L);
            assertThat(WebhookTaskListener.overdueHours(
                    Date.from(due), due.plus(Duration.ofMinutes(210)))).isEqualTo(3L);
        }

        @Test
        @DisplayName("剛好在 dueDate 上 → 0")
        void exactlyAtDueDateIsZero() {
            assertThat(WebhookTaskListener.overdueHours(Date.from(due), due)).isEqualTo(0L);
        }

        @Test
        @DisplayName("事件早於 dueDate → 0（下限），不是負數")
        void clampsToZeroBeforeDueDate() {
            assertThat(WebhookTaskListener.overdueHours(
                    Date.from(due), due.minus(Duration.ofMinutes(30)))).isEqualTo(0L);
            assertThat(WebhookTaskListener.overdueHours(
                    Date.from(due), due.minus(Duration.ofHours(5)))).isEqualTo(0L);
        }

        @Test
        @DisplayName("dueDate 為 null → null（沒有基準點不猜）")
        void nullDueDateIsNull() {
            assertThat(WebhookTaskListener.overdueHours(null, due)).isNull();
        }
    }

    // ── P2-1 紅線：不得出現的欄位 ───────────────────────────────────

    @Nested
    @DisplayName("P2-1 紅線：敏感欄位一律不得出現在 payload")
    class RedLines {

        @Test
        @DisplayName("timeout payload 不得帶 variables／comment／operatorName／候選人")
        void timeoutPayloadHasNoSensitiveFields() {
            DelegateTask task = taskWithEvent("timeout");
            when(task.getAssignee()).thenReturn("mgr001");
            when(task.getDueDate()).thenReturn(Date.from(Instant.now().minus(Duration.ofHours(1))));
            // 流程變數確實存在（表單欄位 id 就是變數名，spec §8.5）——
            // 斷言的重點是「存在也不得被外送」。
            when(task.getVariable("salary")).thenReturn(100_000);
            when(task.getVariable("idNumber")).thenReturn("A123456789");

            Map<String, Object> payload = listener.buildPayload(task, "timeout");

            assertThat(payload).doesNotContainKeys(
                    "variables", "allVariables",
                    "comment", "operatorName",
                    "candidateUsers", "candidateGroups",
                    "salary", "idNumber");
        }

        @Test
        @DisplayName("complete+rejected 送 rejectReason，但仍不得帶 operatorName／comment／variables")
        void rejectedCarriesReasonWithoutSensitiveFields() {
            DelegateTask task = taskWithEvent("complete");
            when(task.getAssignee()).thenReturn("mgr001");
            when(task.getVariable("rejected")).thenReturn(true);
            when(task.getVariable("rejectReason")).thenReturn("證件不清");
            // 簽核意見存在，但那是可能夾帶表單內容的自由文字 → 不外送。
            when(task.getVariable("comment")).thenReturn("申請人月薪 100000，不同意");

            Map<String, Object> payload = listener.buildPayload(task, "complete");

            assertThat(payload)
                    .containsEntry("event", "task.complete")
                    .containsEntry("operatorId", "mgr001")
                    .containsEntry("action", "rejected")
                    .containsEntry("rejectReason", "證件不清")
                    .doesNotContainKeys(
                            "variables", "allVariables",
                            "comment", "operatorName",
                            "candidateUsers", "candidateGroups");
        }
    }

    // ── 投遞接線：notify 真的把契約欄位塞進 convertAndSend ───────────

    @Nested
    @DisplayName("notify：設定 event=timeout → 送出的 map")
    class Wiring {

        @Test
        @DisplayName("payload 含 __webhookUrl／__webhookMethod 與 timeout 欄位，且無敏感欄位")
        @SuppressWarnings("unchecked")
        void timeoutConfigSendsTimeoutPayload() {
            DelegateTask task = taskWithEvent("timeout");
            when(task.getAssignee()).thenReturn("mgr001");
            when(task.getDueDate()).thenReturn(Date.from(Instant.now().minus(Duration.ofHours(3))));
            when(resolver.resolve(PROC_DEF, "approve")).thenReturn(List.of(
                    new WebhookConfig("timeout", "https://erp.example/timeout", "PUT")));

            listener.notify(task);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(rabbitTemplate).convertAndSend(
                    eq("bpm.exchange"), eq("bpm.webhook.task"), captor.capture());

            assertThat(captor.getValue())
                    .containsEntry("__webhookUrl", "https://erp.example/timeout")
                    .containsEntry("__webhookMethod", "PUT")
                    .containsEntry("event", "task.timeout")
                    .containsEntry("assignee", "mgr001")
                    .containsEntry("overdueHours", 3L)
                    .doesNotContainKeys(
                            "variables", "allVariables",
                            "comment", "operatorName",
                            "candidateUsers", "candidateGroups");
        }
    }

    // ── 自訂 body（#28）─────────────────────────────────────────────

    /**
     * 模板與 listener 之間的接線。
     *
     * <p>⚠️ 這一組刻意斷言 {@code __webhookBody} 的<b>字串內容</b>而不是
     * 「有沒有這個鍵」：鍵存在但渲染順序錯了（例如在加入 {@code __webhookUrl}
     * 之後才渲染、或把 {@code __webhookBody} 自己渲染進去）時，端到端測試
     * 照樣會綠（body 送出去了、簽章也對），只有把內容釘住才抓得到。
     */
    @Nested
    @DisplayName("payloadTemplate：notify 送出的 __webhookBody")
    class PayloadTemplateWiring {

        @Test
        @DisplayName("有模板 → __webhookBody 是渲染後的字串；__* 契約欄位不在可引用範圍")
        @SuppressWarnings("unchecked")
        void templateRendersIntoContractField() {
            DelegateTask task = taskWithEvent("create");
            when(task.getAssignee()).thenReturn("mgr001");
            when(task.getDueDate()).thenReturn(Date.from(Instant.parse("2026-01-01T00:00:00Z")));
            when(resolver.resolve(PROC_DEF, "approve")).thenReturn(List.of(
                    new WebhookConfig("create", "https://erp.example/hook", "POST",
                            "{\"t\":\"{{taskName}}\",\"u\":\"{{__webhookUrl}}\"}")));

            listener.notify(task);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(rabbitTemplate).convertAndSend(
                    eq("bpm.exchange"), eq("bpm.webhook.task"), captor.capture());

            assertThat(captor.getValue())
                    .as("模板在加入 __* 之前渲染：__webhookUrl 不是資料，只能是未代換的 placeholder")
                    .containsEntry("__webhookBody", "{\"t\":\"審核關卡\",\"u\":\"{{__webhookUrl}}\"}")
                    .containsEntry("__webhookUrl", "https://erp.example/hook");
        }

        @Test
        @DisplayName("沒有模板 → 完全沒有 __webhookBody（既有 payload 逐欄不變）")
        @SuppressWarnings("unchecked")
        void noTemplateMeansNoCustomBody() {
            DelegateTask task = taskWithEvent("create");
            when(task.getAssignee()).thenReturn("mgr001");
            when(resolver.resolve(PROC_DEF, "approve")).thenReturn(List.of(
                    new WebhookConfig("create", "https://erp.example/hook", "POST")));

            listener.notify(task);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(rabbitTemplate).convertAndSend(
                    eq("bpm.exchange"), eq("bpm.webhook.task"), captor.capture());

            assertThat(captor.getValue())
                    .doesNotContainKey("__webhookBody")
                    .containsEntry("event", "task.create")
                    .containsEntry("taskName", "審核關卡");
        }
    }

    /**
     * KNOWN_FIELDS（lint 用的白名單）與實際 payload 的漂移守門（#28）。
     *
     * <p>兩個 listener put 的欄位與 {@link WebhookPayloadTemplate#KNOWN_FIELDS}
     * 是同一份事實的兩個寫法，沒有機制強制同步。新增 payload 欄位卻忘了加進
     * 白名單，症狀會是「模板引用一個明明存在的欄位，lint 卻說未知」——
     * 使用者被錯誤訊息誤導，比不警告更糟。這裡用實際的 payload map 比對，
     * 漂移時紅在新增欄位的那一次改動上。
     */
    @Nested
    @DisplayName("KNOWN_FIELDS 與實際 payload 不得漂移")
    class KnownFieldsMirror {

        @Test
        @DisplayName("每個事件 buildPayload 的欄位都在 KNOWN_FIELDS 內")
        void everyTaskPayloadFieldIsKnown() {
            DelegateTask task = taskWithEvent("complete");
            when(task.getAssignee()).thenReturn("mgr001");
            when(task.getDueDate()).thenReturn(new Date());
            when(task.getVariable("rejected")).thenReturn(true);
            when(task.getVariable("rejectReason")).thenReturn("證件不清");

            for (String event : List.of("create", "complete", "delete", "timeout", "assignment")) {
                assertThat(WebhookPayloadTemplate.KNOWN_FIELDS)
                        .as("事件 %s 的 payload 欄位必須都在 KNOWN_FIELDS 內（新增欄位時同步白名單）", event)
                        .containsAll(listener.buildPayload(task, event).keySet());
            }
        }
    }
}
