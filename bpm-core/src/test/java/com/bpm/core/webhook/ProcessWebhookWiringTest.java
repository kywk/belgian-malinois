package com.bpm.core.webhook;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.WebhookTestSink;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流程層 webhook（{@code <process>} 的 {@code flowable:webhooks}）真的被投遞了
 * —— #67 斷線 (A) 的第四個實例。
 *
 * <h2>這個實例與前三個的關係</h2>
 *
 * <p>節點層（{@code WebhookTaskListener}）已於 2026-09-30 接上。
 * {@code ProcessCompletedListener} 是同一條鏈路上<b>最後一個</b>無條件送出
 * 「沒有 {@code __webhookUrl}」payload 的地方：consumer 讀到 null 就丟掉，
 * 沒有例外、沒有錯誤、沒有稽核。本測試證明它也被接上了。
 *
 * <h2>⚠️ 斷言策略：正向案例不能只驗「沒有拋例外」</h2>
 *
 * <p>與 {@code WebhookDeliveryWiringTest} 相同 —— 真正的證據是一個 HTTP 端點
 * （{@link WebhookTestSink}）收到了請求，而不是「流程跑完了」。
 * 「沒有設定 → 不投遞」的負向斷言必須成對存在，否則一個「永遠不投遞」
 * 的實作也會讓它全綠（那正是改動前的狀態）。
 */
class ProcessWebhookWiringTest extends IntegrationTestBase {

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private WebhookConfigResolver resolver;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void resetSink() {
        WebhookTestSink.reset();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private String sinkUrl(String name) {
        // 主機名必須是 localhost（WebhookUrlPolicy 的允許清單比對字面 host）。
        return "http://localhost:" + SERVLET_PORT + "/mock/test-webhook-sink/" + name;
    }

    /**
     * 部署一支最簡單的流程（start → userTask → end），把 {@code processInner}
     * 放在 {@code <process>} 的第一個子節點位置。
     *
     * <p>⚠️ {@code <documentation>} 必須排在 {@code <extensionElements>} 之前
     * （BPMN XSD 的 sequence），而兩者都必須排在 flow elements 之前 ——
     * {@code processInner} 會接在 {@code <startEvent>} 之前，所以只要內部順序對即可。
     */
    private String deployProcess(String processKey, String processInner) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/webhook-process-test">
                  <process id="%1$s" isExecutable="true">%2$s
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="審核關卡" flowable:assignee="mgr001"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, processInner);
        repositoryService.createDeployment()
                .name("whproc-" + processKey)
                .addString(processKey + ".bpmn20.xml", xml)
                .deploy();
        return processKey;
    }

    private String webhooksElement(String... webhookAttrs) {
        return "<extensionElements><flowable:webhooks>"
                + String.join("", webhookAttrs)
                + "</flowable:webhooks></extensionElements>";
    }

    private String webhook(String event, String url, String method) {
        String eventAttr = event == null ? "" : " event=\"" + event + "\"";
        return "<flowable:webhook" + eventAttr + " url=\"" + url
                + "\" method=\"" + method + "\"/>";
    }

    private String legacyDoc(String name) {
        return "<documentation>" + WebhookConfigResolver.LEGACY_DOC_PREFIX
                + "[{\"event\":\"process.completed\",\"url\":\"" + sinkUrl(name)
                + "\",\"method\":\"POST\"}]</documentation>";
    }

    /** 完整跑完一支單關卡流程（帶真正的流程 businessKey）。 */
    private void runToCompletion(String processKey, String businessKey, Map<String, Object> vars) {
        var pi = runtimeService.startProcessInstanceByKey(processKey, businessKey, vars);
        Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(task)
                .as("流程 %s 必須建立任務（否則後面的結案根本不會發生）", processKey)
                .isNotNull();
        taskService.complete(task.getId(), vars);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(pi.getId()).singleResult())
                .as("流程 %s 必須真的結案", processKey)
                .isNull();
    }

    private void awaitDelivery(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean())
                .as("等待 15 秒後仍未發生：%s%n實際收到的投遞：%s", what, WebhookTestSink.received())
                .isTrue();
    }

    private Map<String, Object> body(WebhookTestSink.Received r) {
        try {
            return objectMapper.readValue(r.body(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new AssertionError("投遞 body 不是 JSON：" + r.body(), e);
        }
    }

    private List<WebhookConfig> resolveForProcess(String processKey) {
        var pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).latestVersion().singleResult();
        assertThat(pd).as("流程 %s 必須已部署", processKey).isNotNull();
        return resolver.resolveForProcess(pd.getId());
    }

    // ── 端到端投遞 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("結案投遞（真的走到 HTTP）")
    class Delivery {

        @Test
        @DisplayName("有設定 → 結案後真的投遞，body 含 result／businessKey 且不含流程變數")
        void processCompletedIsActuallyDelivered() {
            String key = deployProcess("pw-deliver",
                    webhooksElement(webhook("process.completed", sinkUrl("p1"), "POST")));

            runToCompletion(key, "PW-1", Map.of("approved", true, "leaveType", "annual", "days", 3));

            awaitDelivery(() -> !WebhookTestSink.receivedTo("p1").isEmpty(), "流程結案的投遞");
            WebhookTestSink.Received r = WebhookTestSink.receivedTo("p1").get(0);

            assertThat(r.method()).isEqualTo("POST");
            assertThat(body(r))
                    .containsEntry("event", "process.completed")
                    .containsEntry("processDefinitionKey", key)
                    .containsEntry("businessKey", "PW-1")
                    .containsEntry("result", "approved")
                    // ⚠️ 刻意不外送流程變數（security-audit P2-1）——
                    // 與 spec §11.4 修正後的說法一致。
                    .doesNotContainKeys("leaveType", "days", "variables", "allVariables");
        }

        @Test
        @DisplayName("省略 event → 預設 process.completed，仍然投遞（與節點層預設 create 不同）")
        void omittedEventDefaultsToProcessCompleted() {
            // ⚠️ 若沿用 WebhookConfig.of 的預設（create），這一條會什麼都收不到，
            // 而症狀是「設定成功、畫面正常、卻永遠不投遞」。
            String key = deployProcess("pw-default",
                    webhooksElement(webhook(null, sinkUrl("p2"), "POST")));

            runToCompletion(key, "PW-2", Map.of("approved", true));

            awaitDelivery(() -> !WebhookTestSink.receivedTo("p2").isEmpty(), "省略 event 的預設投遞");
            assertThat(body(WebhookTestSink.receivedTo("p2").get(0)))
                    .containsEntry("event", "process.completed");
        }

        @Test
        @DisplayName("event=\"all\" 也投遞結案")
        void allEventIsDelivered() {
            String key = deployProcess("pw-all",
                    webhooksElement(webhook("all", sinkUrl("p3"), "PUT")));

            runToCompletion(key, "PW-3", Map.of("approved", true));

            awaitDelivery(() -> !WebhookTestSink.receivedTo("p3").isEmpty(), "all 設定的投遞");
            assertThat(WebhookTestSink.receivedTo("p3").get(0).method())
                    .as("設 PUT 就必須真的用 PUT").isEqualTo("PUT");
        }
    }

    // ── 對照組 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("對照組")
    class Control {

        @Test
        @DisplayName("沒有流程層設定 → 結案後不投遞（但流程照樣結案）")
        void processWithoutConfigSendsNothing() {
            String key = deployProcess("pw-none", "");

            runToCompletion(key, "PW-4", Map.of("approved", true));

            // 給 consumer 一點時間：若其實有送出，這裡會抓到。
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(WebhookTestSink.received())
                    .as("沒有流程層設定就不得有任何投遞")
                    .isEmpty();
        }

        @Test
        @DisplayName("流程層設定成節點事件（create）→ 結案不投遞")
        void nodeLayerEventOnProcessDoesNotDeliver() {
            // 事件對應規則的端到端對照：若把 create 當命中，這一條會紅。
            // 正例在 Delivery 那組（否則「永遠不投」也會綠）。
            String key = deployProcess("pw-create",
                    webhooksElement(webhook("create", sinkUrl("p5"), "POST")));

            runToCompletion(key, "PW-5", Map.of("approved", true));

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(WebhookTestSink.receivedTo("p5"))
                    .as("process 層的 create 設定不該在結案時投遞")
                    .isEmpty();
        }
    }

    // ── resolver 的讀取規則（不經 HTTP）────────────────────────────

    @Nested
    @DisplayName("流程層設定讀取規則（直接呼叫 resolver）")
    class Resolution {

        @Test
        @DisplayName("新格式（<process> 的 extensionElements）被正確讀出")
        void readsProcessExtensionElements() {
            deployProcess("pr-r1", webhooksElement(
                    webhook("process.completed", sinkUrl("r1"), "PUT"),
                    webhook("all", sinkUrl("r2"), "POST")));

            assertThat(resolveForProcess("pr-r1"))
                    .containsExactly(
                            new WebhookConfig("process.completed", sinkUrl("r1"), "PUT"),
                            new WebhookConfig("all", sinkUrl("r2"), "POST"));
        }

        @Test
        @DisplayName("省略 event 的流程層設定被讀成 process.completed（不是 create）")
        void omittedEventReadsAsProcessCompleted() {
            deployProcess("pr-r2", webhooksElement(webhook(null, sinkUrl("r3"), "POST")));

            assertThat(resolveForProcess("pr-r2"))
                    .containsExactly(new WebhookConfig("process.completed", sinkUrl("r3"), "POST"));
        }

        @Test
        @DisplayName("空的 webhooks 元素是權威 → 不落回舊 documentation")
        void emptyContainerIsAuthoritative() {
            deployProcess("pr-r3", legacyDoc("r4") + webhooksElement());

            assertThat(resolveForProcess("pr-r3"))
                    .as("空元素代表使用者清空了設定，舊 documentation 不得讓它復活")
                    .isEmpty();
        }

        @Test
        @DisplayName("完全沒有 webhooks 元素時才讀舊 documentation")
        void fallsBackToLegacyOnlyWhenNoContainer() {
            deployProcess("pr-r4", legacyDoc("r5"));

            assertThat(resolveForProcess("pr-r4"))
                    .containsExactly(new WebhookConfig(
                            "process.completed", sinkUrl("r5"), "POST"));
        }

        @Test
        @DisplayName("沒有設定／不存在的流程定義 → 空清單而不是拋例外")
        void noConfigIsNotAnError() {
            deployProcess("pr-r5", "");

            assertThat(resolveForProcess("pr-r5")).isEmpty();
            assertThat(resolver.resolveForProcess(null)).isEmpty();
            assertThat(resolver.resolveForProcess("no-such-definition:1:999"))
                    .as("查不到流程定義時不得讓結案失敗")
                    .isEmpty();
        }

        @Test
        @DisplayName("沒有 url 的流程層設定被忽略")
        void webhookWithoutUrlIsIgnored() {
            deployProcess("pr-r6",
                    "<extensionElements><flowable:webhooks>"
                            + "<flowable:webhook event=\"process.completed\" method=\"POST\"/>"
                            + "</flowable:webhooks></extensionElements>");

            assertThat(resolveForProcess("pr-r6")).isEmpty();
        }
    }
}
