package com.bpm.core.webhook;

import com.bpm.core.audit.AuditEventPublisher;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.event.impl.FlowableEntityEventImpl;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流程層 webhook 的投遞來源（#67 斷線 (A) 的第四個實例）—— <b>純單元測試</b>。
 *
 * <h2>為什麼這條要獨立於整合測試</h2>
 *
 * <p>{@link WebhookConsumer} 收到訊息後會 {@code payload.remove("__webhookUrl")}，
 * 所以端到端測試在 HTTP 接收端<b>看不到</b>這個欄位 —— 它只能證明「有投遞」，
 * 證明不了「送出的欄位名正確」。而欄位名正是 consumer 與 listener 之間唯一的契約，
 * 錯了就整條鏈路再斷一次，且不會有任何錯誤。
 *
 * <p>這裡用 mock 攔下 {@link RabbitTemplate#convertAndSend} 的<b>原始 map</b>，
 * 直接釘住 {@code __webhookUrl}／{@code __webhookMethod} 兩個欄位名。
 *
 * <h2>為什麼事件對應也在這裡</h2>
 *
 * <p>端到端只證明「有設定就投遞」，證明不了「{@code create} 不會被誤投」。
 * 把規則抽成 {@link ProcessCompletedListener#matchesProcessCompleted} 後
 * 單獨測，規則錯了就會紅，與「有沒有接上線」互相獨立。
 */
class ProcessCompletedListenerTest {

    private static final String PROC_DEF =
            "leave-approval:1:12345678-1234-1234-1234-123456789abc";

    // ── 事件對應（單獨測 static 方法）────────────────────────────────

    @Nested
    @DisplayName("matchesProcessCompleted：流程層只有一個事件")
    class Matching {

        @Test
        @DisplayName("all／process.completed／complete 命中")
        void expectedValuesMatch() {
            assertThat(ProcessCompletedListener.matchesProcessCompleted("process.completed")).isTrue();
            assertThat(ProcessCompletedListener.matchesProcessCompleted("complete")).isTrue();
            assertThat(ProcessCompletedListener.matchesProcessCompleted("all")).isTrue();
        }

        @Test
        @DisplayName("省略 event → 視為 process.completed（不是節點層的 create）")
        void omittedMeansProcessCompleted() {
            assertThat(ProcessCompletedListener.matchesProcessCompleted(null))
                    .as("省略 → 命中，且不得被當成 create")
                    .isTrue();
            assertThat(ProcessCompletedListener.matchesProcessCompleted("   ")).isTrue();
        }

        @Test
        @DisplayName("create／timeout／reject 不命中（它們是節點層事件）")
        void nodeLayerEventsDoNotMatch() {
            assertThat(ProcessCompletedListener.matchesProcessCompleted("create")).isFalse();
            assertThat(ProcessCompletedListener.matchesProcessCompleted("timeout")).isFalse();
            assertThat(ProcessCompletedListener.matchesProcessCompleted("reject")).isFalse();
            assertThat(ProcessCompletedListener.matchesProcessCompleted("complet"))
                    .as("拼錯不得落到 complete 上").isFalse();
        }

        @Test
        @DisplayName("大小寫不拘")
        void caseInsensitive() {
            assertThat(ProcessCompletedListener.matchesProcessCompleted("PROCESS.COMPLETED")).isTrue();
            assertThat(ProcessCompletedListener.matchesProcessCompleted("Complete")).isTrue();
            assertThat(ProcessCompletedListener.matchesProcessCompleted("ALL")).isTrue();
        }
    }

    @Nested
    @DisplayName("WebhookConfig 的預設值：流程層與節點層必須不同")
    class Defaults {

        @Test
        @DisplayName("流程層多載：省略 event → process.completed")
        void processLayerDefault() {
            assertThat(WebhookConfig.of(null, "https://erp.example/done", "POST",
                    WebhookConfig.DEFAULT_PROCESS_EVENT).event())
                    .isEqualTo("process.completed");
        }

        @Test
        @DisplayName("節點層預設不得被改掉：省略 event → create")
        void nodeLayerDefaultUnchanged() {
            // ⚠️ 這是「不要把節點層的預設改成 process.completed」的守門測試。
            // 改了它，所有省略 event 的節點設定都會變成永不觸發。
            assertThat(WebhookConfig.of(null, "https://erp.example/hook", "POST").event())
                    .isEqualTo("create");
        }

        @Test
        @DisplayName("沒有 url 的設定仍然不成立（兩層皆同）")
        void urlIsStillRequired() {
            assertThat(WebhookConfig.of("process.completed", "  ", "POST",
                    WebhookConfig.DEFAULT_PROCESS_EVENT)).isNull();
        }
    }

    // ── 投遞接線：送出的 map 必須帶 __webhookUrl ─────────────────────

    @Nested
    @DisplayName("onEvent：設定 → 訊息內容")
    class Wiring {

        private RabbitTemplate rabbitTemplate;
        private WebhookConfigResolver resolver;
        private AuditEventPublisher auditPublisher;
        private ProcessCompletedListener listener;
        private FlowableEntityEventImpl event;

        @SuppressWarnings("unchecked")
        private void wire(List<WebhookConfig> configs) {
            rabbitTemplate = mock(RabbitTemplate.class);
            RuntimeService runtimeService = mock(RuntimeService.class);
            HistoryService historyService = mock(HistoryService.class);
            resolver = mock(WebhookConfigResolver.class);
            auditPublisher = mock(AuditEventPublisher.class);

            listener = new ProcessCompletedListener(
                    rabbitTemplate, runtimeService, historyService, resolver, auditPublisher);

            ExecutionEntity exec = mock(ExecutionEntity.class);
            when(exec.getProcessInstanceId()).thenReturn("pi-1");
            when(exec.getProcessDefinitionId()).thenReturn(PROC_DEF);
            when(exec.getBusinessKey()).thenReturn("BK-1");

            event = mock(FlowableEntityEventImpl.class);
            when(event.getType()).thenReturn(FlowableEngineEventType.PROCESS_COMPLETED);
            when(event.getEntity()).thenReturn(exec);

            HistoricVariableInstanceQuery q = mock(HistoricVariableInstanceQuery.class);
            when(historyService.createHistoricVariableInstanceQuery()).thenReturn(q);
            when(q.processInstanceId("pi-1")).thenReturn(q);
            when(q.list()).thenReturn(List.of());
            // 歷史查不到 → 退到 runtime；給一個 approved=true 讓 result 可判定。
            when(runtimeService.getVariables("pi-1")).thenReturn(Map.of("approved", true));

            when(resolver.resolveForProcess(PROC_DEF)).thenReturn(configs);
        }

        @Test
        @DisplayName("有設定 → convertAndSend 的 payload 含 __webhookUrl 與 __webhookMethod")
        void sendsContractFields() {
            wire(List.of(new WebhookConfig("process.completed",
                    "https://erp.example/done", "PUT")));

            listener.onEvent(event);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(rabbitTemplate).convertAndSend(
                    eq("bpm.exchange"), eq("bpm.webhook.leave-approval"), captor.capture());

            assertThat(captor.getValue())
                    .as("這兩個欄位是 consumer 與 listener 唯一契約，欄位名不可改")
                    .containsEntry("__webhookUrl", "https://erp.example/done")
                    .containsEntry("__webhookMethod", "PUT")
                    .containsEntry("event", "process.completed")
                    .containsEntry("processDefinitionKey", "leave-approval")
                    .containsEntry("businessKey", "BK-1")
                    .containsEntry("result", "approved")
                    .doesNotContainKeys("variables", "allVariables");

            // 稽核照舊、不受投遞設定影響。
            verify(auditPublisher).publish(any());
        }

        @Test
        @DisplayName("沒有設定 → 完全不發訊息，但稽核仍必須寫入")
        void noConfigSendsNothingButStillAudits() {
            wire(List.of());

            listener.onEvent(event);

            verify(rabbitTemplate, never())
                    .convertAndSend(anyString(), anyString(), any(Object.class));
            verify(auditPublisher).publish(any());
        }

        @Test
        @DisplayName("有設定但事件對不上（create）→ 不發訊息")
        void nonMatchingEventSendsNothing() {
            wire(List.of(new WebhookConfig("create", "https://erp.example/hook", "POST")));

            listener.onEvent(event);

            verify(rabbitTemplate, never())
                    .convertAndSend(anyString(), anyString(), any(Object.class));
        }

        @Test
        @DisplayName("多筆命中 → 每筆各自送出（不是只取第一筆）")
        void eachMatchingConfigIsSent() {
            wire(List.of(
                    new WebhookConfig("process.completed", "https://erp.example/a", "POST"),
                    new WebhookConfig("all", "https://erp.example/b", "PUT")));

            listener.onEvent(event);

            verify(rabbitTemplate, org.mockito.Mockito.times(2))
                    .convertAndSend(eq("bpm.exchange"), eq("bpm.webhook.leave-approval"), any(Object.class));
        }
    }
}
