package com.bpm.core.webhook;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WebhookConfigResolver} 的單元測試：從 BPMN 讀出 webhook 設定。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>改動前<b>沒有任何程式碼</b>設定 {@code __webhookUrl}，整套 webhook 投遞
 * 從上線起從未真正投遞過任何一筆，而失敗型態是「什麼都不發生」——
 * 沒有例外、沒有稽核、流程照跑。設定解析因此有兩層風險：
 * <ul>
 *   <li><b>格式優先順序</b>：新的 {@code extensionElements} 與舊的
 *       {@code <documentation>} JSON。規則必須是「有元素就是權威」——
 *       使用者清空設定後設計器仍會寫出空元素，若這時回頭讀舊格式，
 *       剛刪掉的設定會立刻復活，使用者看到的是「刪了沒用」。</li>
 *   <li><b>設定壞掉不得讓流程建立失敗</b>：listener 在引擎 command 內被呼叫，
 *       未捕捉的例外會讓整張單建立不出來。設定讀不到頂多是不投遞
 *       （fail-open 於流程、fail-closed 於投遞）。</li>
 * </ul>
 *
 * <p>純 Mockito + 程式化建構的 BPMN 模型：整合測試（{@code WebhookDeliveryWiringTest}）
 * 驗的是「投遞真的發生」，這裡把解析規則的每個分支逐一釘死。
 */
class WebhookConfigResolverTest {

    private static final String DEF_ID = "leave:1:1";

    private final RepositoryService repositoryService = mock(RepositoryService.class);
    private final WebhookConfigResolver resolver =
            new WebhookConfigResolver(repositoryService, new ObjectMapper());

    private static UserTask task(String id) {
        UserTask task = new UserTask();
        task.setId(id);
        return task;
    }

    private static BpmnModel model(FlowElement... elements) {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("leave");
        for (FlowElement element : elements) process.addFlowElement(element);
        model.addProcess(process);
        return model;
    }

    private static ExtensionElement hook(String event, String url, String method, String payloadTemplate) {
        ExtensionElement hook = new ExtensionElement();
        hook.setName(WebhookConfig.CHILD);
        if (event != null) hook.addAttribute(new ExtensionAttribute(WebhookConfig.ATTR_EVENT, event));
        if (url != null) hook.addAttribute(new ExtensionAttribute(WebhookConfig.ATTR_URL, url));
        if (method != null) hook.addAttribute(new ExtensionAttribute(WebhookConfig.ATTR_METHOD, method));
        if (payloadTemplate != null) {
            hook.addAttribute(new ExtensionAttribute(WebhookConfig.ATTR_PAYLOAD_TEMPLATE, payloadTemplate));
        }
        return hook;
    }

    private static void addWebhooks(org.flowable.bpmn.model.BaseElement element, ExtensionElement... hooks) {
        ExtensionElement container = new ExtensionElement();
        container.setName(WebhookConfig.ELEMENT);
        for (ExtensionElement hook : hooks) container.addChildElement(hook);
        element.addExtensionElement(container);
    }

    private void stubModel(BpmnModel model) {
        when(repositoryService.getBpmnModel(DEF_ID)).thenReturn(model);
    }

    // ── 節點層：新格式 ────────────────────────────────────────────

    @Nested
    @DisplayName("extensionElements（新格式）")
    class ExtensionElements {

        @Test
        @DisplayName("四欄位（event／url／method／payloadTemplate）都解析出來")
        void allFieldsResolved() {
            UserTask task = task("task-1");
            addWebhooks(task, hook("complete", "https://erp.example/hook", "PUT", "{\"a\":1}"));
            stubModel(model(task));

            List<WebhookConfig> configs = resolver.resolve(DEF_ID, "task-1");

            assertThat(configs).containsExactly(
                    new WebhookConfig("complete", "https://erp.example/hook", "PUT", "{\"a\":1}"));
        }

        @Test
        @DisplayName("未填 event → create；未填 method → POST（與前端選單第一項一致）")
        void defaultsApply() {
            UserTask task = task("task-1");
            addWebhooks(task, hook(null, "https://erp.example/hook", null, null));
            stubModel(model(task));

            assertThat(resolver.resolve(DEF_ID, "task-1"))
                    .containsExactly(new WebhookConfig(
                            WebhookConfig.DEFAULT_EVENT, "https://erp.example/hook",
                            WebhookConfig.DEFAULT_METHOD, null));
        }

        @Test
        @DisplayName("缺 url 的那一筆忽略，其餘照常回傳")
        void hookWithoutUrlIsSkipped() {
            UserTask task = task("task-1");
            addWebhooks(task,
                    hook("create", null, null, null),
                    hook("complete", "https://erp.example/hook", null, null));
            stubModel(model(task));

            assertThat(resolver.resolve(DEF_ID, "task-1")).hasSize(1);
            assertThat(resolver.resolve(DEF_ID, "task-1").getFirst().url())
                    .isEqualTo("https://erp.example/hook");
        }

        @Test
        @DisplayName("同一節點多筆設定全部回傳")
        void multipleHooks() {
            UserTask task = task("task-1");
            addWebhooks(task,
                    hook("create", "https://erp.example/a", null, null),
                    hook("complete", "https://erp.example/b", null, null));
            stubModel(model(task));

            assertThat(resolver.resolve(DEF_ID, "task-1"))
                    .extracting(WebhookConfig::url)
                    .containsExactly("https://erp.example/a", "https://erp.example/b");
        }

        @Test
        @DisplayName("payloadTemplate 空白 → null（未設定＝用預設 body）")
        void blankPayloadTemplateIsNull() {
            UserTask task = task("task-1");
            addWebhooks(task, hook("create", "https://erp.example/hook", null, "   "));
            stubModel(model(task));

            assertThat(resolver.resolve(DEF_ID, "task-1").getFirst().payloadTemplate()).isNull();
        }
    }

    // ── 節點層：格式優先順序 ──────────────────────────────────────

    @Nested
    @DisplayName("新舊格式的優先順序：有 webhooks 元素就是權威")
    class FormatPrecedence {

        @Test
        @DisplayName("空 webhooks 元素不得讓舊 documentation 的設定復活（使用者以為刪了沒用）")
        void emptyExtensionBlocksLegacy() {
            UserTask task = task("task-1");
            addWebhooks(task); // 使用者清空設定後設計器仍會寫出空的 <flowable:webhooks/>
            task.setDocumentation(WebhookConfigResolver.LEGACY_DOC_PREFIX
                    + "[{\"url\":\"https://legacy.example/hook\"}]");
            stubModel(model(task));

            assertThat(resolver.resolve(DEF_ID, "task-1")).isEmpty();
        }

        @Test
        @DisplayName("完全沒有 webhooks 元素時才讀舊 documentation 的 JSON")
        void legacyIsReadOnlyWhenNoExtension() {
            UserTask task = task("task-1");
            task.setDocumentation(WebhookConfigResolver.LEGACY_DOC_PREFIX
                    + "[{\"event\":\"complete\",\"url\":\"https://legacy.example/hook\",\"method\":\"PUT\"}]");
            stubModel(model(task));

            assertThat(resolver.resolve(DEF_ID, "task-1")).containsExactly(
                    new WebhookConfig("complete", "https://legacy.example/hook", "PUT", null));
        }

        @Test
        @DisplayName("舊格式未填 event → 節點層預設 create；也讀 payloadTemplate")
        void legacyDefaultsAndTemplate() {
            UserTask task = task("task-1");
            task.setDocumentation(WebhookConfigResolver.LEGACY_DOC_PREFIX
                    + "[{\"url\":\"https://legacy.example/hook\",\"payloadTemplate\":\"{\\\"k\\\":1}\"}]");
            stubModel(model(task));

            WebhookConfig config = resolver.resolve(DEF_ID, "task-1").getFirst();
            assertThat(config.event()).isEqualTo(WebhookConfig.DEFAULT_EVENT);
            assertThat(config.payloadTemplate()).isEqualTo("{\"k\":1}");
        }

        @Test
        @DisplayName("舊格式不是陣列、或缺 url、或壞 JSON → 該元素不投遞，且不拋例外")
        void malformedLegacyIsIgnoredSafely() {
            UserTask notArray = task("task-1");
            notArray.setDocumentation(WebhookConfigResolver.LEGACY_DOC_PREFIX + "{\"url\":\"x\"}");
            stubModel(model(notArray));
            assertThat(resolver.resolve(DEF_ID, "task-1")).isEmpty();

            UserTask brokenJson = task("task-2");
            brokenJson.setDocumentation(WebhookConfigResolver.LEGACY_DOC_PREFIX + "[not json");
            stubModel(model(brokenJson));
            assertThatCode(() -> resolver.resolve(DEF_ID, "task-2")).doesNotThrowAnyException();
            assertThat(resolver.resolve(DEF_ID, "task-2")).isEmpty();

            UserTask missingUrl = task("task-3");
            missingUrl.setDocumentation(WebhookConfigResolver.LEGACY_DOC_PREFIX
                    + "[{\"event\":\"create\"},{\"url\":\"https://ok.example/hook\"}]");
            stubModel(model(missingUrl));
            assertThat(resolver.resolve(DEF_ID, "task-3")).hasSize(1);
        }

        @Test
        @DisplayName("documentation 沒有 __webhooks__: 前綴（一般說明文字）→ 不解析")
        void plainDocumentationIsNotParsed() {
            UserTask task = task("task-1");
            task.setDocumentation("這是一段給人看的說明 [{\"url\":\"https://not-a-hook\"}]");
            stubModel(model(task));

            assertThat(resolver.resolve(DEF_ID, "task-1")).isEmpty();
        }
    }

    // ── 節點層：邊界與容錯 ────────────────────────────────────────

    @Nested
    @DisplayName("節點層邊界")
    class NodeBoundaries {

        @Test
        @DisplayName("節點不存在 → 空清單（不是 null）")
        void unknownNodeIsEmpty() {
            stubModel(model(task("task-1")));

            assertThat(resolver.resolve(DEF_ID, "unknown")).isEmpty();
        }

        @Test
        @DisplayName("processDefinitionId 或 nodeId 為 null → 空清單，不查 repository")
        void nullInputsSkipLookup() {
            assertThat(resolver.resolve(null, "task-1")).isEmpty();
            assertThat(resolver.resolve(DEF_ID, null)).isEmpty();
            verify(repositoryService, never()).getBpmnModel(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("repository 讀取失敗 → 空清單且不拋（設定壞掉不得讓整張單建立不出來）")
        void repositoryFailureIsSwallowed() {
            when(repositoryService.getBpmnModel(DEF_ID))
                    .thenThrow(new RuntimeException("deployment gone"));

            assertThatCode(() -> resolver.resolve(DEF_ID, "task-1")).doesNotThrowAnyException();
            assertThat(resolver.resolve(DEF_ID, "task-1")).isEmpty();
        }

        @Test
        @DisplayName("process id 與 definition key 對不上時退回 main process，設定不得靜默失效")
        void fallsBackToMainProcess() {
            BpmnModel model = new BpmnModel();
            org.flowable.bpmn.model.Process main = new org.flowable.bpmn.model.Process();
            main.setId("some-other-id");
            UserTask task = task("task-1");
            addWebhooks(task, hook("create", "https://erp.example/hook", null, null));
            main.addFlowElement(task);
            model.addProcess(main);
            stubModel(model);

            assertThat(resolver.resolve(DEF_ID, "task-1")).hasSize(1);
        }

        @Test
        @DisplayName("processKey：取第一個冒號前的前綴；無冒號時原樣；null → 空字串")
        void processKeyParsing() {
            assertThat(WebhookConfigResolver.processKey("leave:3:100")).isEqualTo("leave");
            assertThat(WebhookConfigResolver.processKey("leave")).isEqualTo("leave");
            assertThat(WebhookConfigResolver.processKey(null)).isEmpty();
        }
    }

    // ── 流程層 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveForProcess：流程層設定，未填 event 預設 process.completed")
    class ProcessLevel {

        private BpmnModel modelWithProcessHook(ExtensionElement... hooks) {
            BpmnModel model = new BpmnModel();
            org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
            process.setId("leave");
            addWebhooks(process, hooks);
            model.addProcess(process);
            return model;
        }

        @Test
        @DisplayName("讀 <process> 上的設定，event 預設 process.completed（不是 create）")
        void processLevelDefaults() {
            stubModel(modelWithProcessHook(hook(null, "https://erp.example/done", null, null)));

            assertThat(resolver.resolveForProcess(DEF_ID)).containsExactly(new WebhookConfig(
                    WebhookConfig.DEFAULT_PROCESS_EVENT, "https://erp.example/done", "POST", null));
        }

        @Test
        @DisplayName("空 webhooks 元素 → 不回退舊 documentation（與節點層同一條規則）")
        void emptyProcessExtensionBlocksLegacy() {
            BpmnModel model = new BpmnModel();
            org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
            process.setId("leave");
            addWebhooks(process);
            process.setDocumentation(WebhookConfigResolver.LEGACY_DOC_PREFIX
                    + "[{\"url\":\"https://legacy.example/hook\"}]");
            model.addProcess(process);
            stubModel(model);

            assertThat(resolver.resolveForProcess(DEF_ID)).isEmpty();
        }

        @Test
        @DisplayName("沒有 webhooks 元素 → 讀流程層的舊 documentation")
        void processLegacyDocumentation() {
            BpmnModel model = new BpmnModel();
            org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
            process.setId("leave");
            process.setDocumentation(WebhookConfigResolver.LEGACY_DOC_PREFIX
                    + "[{\"url\":\"https://legacy.example/hook\"}]");
            model.addProcess(process);
            stubModel(model);

            WebhookConfig config = resolver.resolveForProcess(DEF_ID).getFirst();
            assertThat(config.event()).isEqualTo(WebhookConfig.DEFAULT_PROCESS_EVENT);
        }

        @Test
        @DisplayName("null definition id → 空；repository 失敗 → 空且不拋")
        void boundaries() {
            assertThat(resolver.resolveForProcess(null)).isEmpty();

            when(repositoryService.getBpmnModel(DEF_ID)).thenThrow(new RuntimeException("boom"));
            assertThatCode(() -> resolver.resolveForProcess(DEF_ID)).doesNotThrowAnyException();
            assertThat(resolver.resolveForProcess(DEF_ID)).isEmpty();
        }
    }

    // ── StartEvent 也在掃描範圍 ───────────────────────────────────

    @Test
    @DisplayName("StartEvent 上的設定也會被解析（不是只有 UserTask）")
    void startEventIsResolved() {
        StartEvent start = new StartEvent();
        start.setId("start-1");
        addWebhooks(start, hook("create", "https://erp.example/start", null, null));
        stubModel(model(start));

        assertThat(resolver.resolve(DEF_ID, "start-1")).hasSize(1);
    }
}
