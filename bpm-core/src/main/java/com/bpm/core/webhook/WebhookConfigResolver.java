package com.bpm.core.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.engine.RepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 從 BPMN 讀出節點層的 webhook 設定（spec §11.4，#67 的斷線 (A)）。
 *
 * <h2>這裡補的是什麼</h2>
 *
 * <p>改動前<b>沒有任何程式碼</b>設定 {@code __webhookUrl}，
 * {@link WebhookConsumer} 從 RabbitMQ payload 讀它、讀不到就記一行 debug 然後丟掉。
 * 也就是說整套 webhook 投遞從 2026-04-17 起就<b>從未真正投遞過任何一筆</b>，
 * 而且失敗型態是「沒有錯誤、沒有資料、沒有稽核」—— 最難查的一種。
 *
 * <h2>⚠️ 為什麼讀 {@code getBpmnModel()} 而不是自己解析 XML</h2>
 *
 * <p>另一條路是 {@code getResourceAsStream()} 拿 {@code ACT_GE_BYTEARRAY}
 * 裡的原始位元組自己用 StAX 解析。它永遠可用，但等於自己寫一份 BPMN 剖析器。
 *
 * <p>選 {@code getBpmnModel()} 的前提是「Flowable 會保留它不認識的 extension element」，
 * 這一點是<b>實測</b>的，不是猜的 —— 見 {@link ExtensionElementPreservationTest}：
 * {@code BpmnXMLUtil.parseChildElements} 的 {@code inExtensionElements} 分支會把
 * 未知元素收成 {@link ExtensionElement}，而
 * {@code BpmnXMLUtil.writeExtensionElements} 會原樣吐回去。
 * 一旦哪天升級 Flowable 讓這個前提失效，那個測試會先紅，再回頭改這裡。
 *
 * <h2>⚠️ 效能：為什麼這裡不加快取</h2>
 *
 * <p>直覺上「每個任務事件都解析一次整份 BPMN」很貴，但實際上不會：
 * {@code RepositoryService.getBpmnModel} 內部走
 * {@code DeploymentManager.resolveProcessDefinition}，它<b>已經</b>把解析結果
 * 放進 Flowable 自己的 {@code processDefinitionCache}（key 就是 processDefinitionId）。
 * 也就是說這條路徑的成本是「一次 map 查表」，而那份解析本來就必須做。
 *
 * <p>自己再加一層快取會是<b>兩個</b>快取管同一件事，必須處理失效時機；
 * 而 processDefinitionId 對每個版本都不變，重新部署自然換 id，
 * 所以 Flowable 那個快取的失效語意已經是對的。多加一層只會多一個 stale 的來源。
 *
 * <h2>兩種格式的優先順序（前端相容性）</h2>
 *
 * <ol>
 *   <li><b>{@code extensionElements}（新）</b> —— 節點上<b>存在</b>
 *       {@code <flowable:webhooks>} 元素時，它就是唯一依據，
 *       裡面是空的就算「使用者清掉了全部設定」。</li>
 *   <li><b>{@code <documentation>}（舊）</b> —— 只有在節點上
 *       <b>完全沒有</b> {@code <flowable:webhooks>} 元素時，才回頭讀
 *       {@code __webhooks__:} 前綴的 JSON。</li>
 * </ol>
 *
 * <p>「有元素就是權威」而不是「元素非空才是權威」是刻意的：
 * 使用者在設計器裡把所有 webhook 刪掉，設計器仍會寫出一個空的
 * {@code <flowable:webhooks/>}，這時若回頭去讀舊的 documentation，
 * 剛刪掉的設定會立刻復活 —— 使用者看到的是「刪了沒用」。
 *
 * <p>⚠️ 這裡<b>不做自動轉換</b>（不在部署或執行期改寫 BPMN）。
 * 部署中的流程定義是稽核軌跡的一部分；執行期偷偷改它，
 * 等於讓「已部署的 XML」與「資料庫裡的 XML」不一致，之後誰都查不出當初部署了什麼。
 * 轉換發生在使用者下次在設計器存檔那一次（見前端的 {@code save()}），
 * 那是一個有人負責、有稽核的時機。
 */
@Component
public class WebhookConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(WebhookConfigResolver.class);

    /**
     * 舊格式的文件前綴。
     *
     * <p>2026-09-30 之前的 {@code WebhookProps.js} 把整組設定以 JSON 塞進
     * BPMN 的 {@code <documentation>}。那是當時唯一能動的欄位
     * （{@code extensionElements} 沒有人讀），所以已部署的流程裡
     * 確實存在這種寫法。
     */
    static final String LEGACY_DOC_PREFIX = "__webhooks__:";

    private final RepositoryService repositoryService;
    private final ObjectMapper objectMapper;

    /**
     * ⚠️ {@code @Lazy} 不可移除 —— 與 {@code ProcessCompletedListener} 的
     * {@code @Lazy RuntimeService} 是<b>同一個</b>結構性循環（#67 實測）：
     *
     * <pre>
     *   FlowableConfig.processEngineConfigurer
     *     → webhookTaskListener（必須在 setBeans 的 map 裡，#67）
     *       → WebhookConfigResolver
     *         → RepositoryService
     *           → ProcessEngine
     *             → engineConfigurers
     *               → processEngineConfigurer   ← 回到起點
     * </pre>
     *
     * <p>Boot 3 預設禁止循環參照，所以不加就是啟動失敗
     * （{@code Requested bean is currently in creation}）。
     *
     * <p>⚠️ 這個循環<b>不能</b>用「把 RepositoryService 的呼叫延後到
     * listener 被觸發時」解掉 —— 問題不在呼叫時機，而在建構順序：
     * listener 必須在引擎啟動<b>之前</b>就存在（它是 delegateExpression 的解析目標）。
     */
    public WebhookConfigResolver(@Lazy RepositoryService repositoryService, ObjectMapper objectMapper) {
        this.repositoryService = repositoryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 取出某個流程節點上設定的 webhook。
     *
     * @param processDefinitionId 流程定義 id（{@code key:version:deploymentId}）
     * @param nodeId               BPMN 元素 id（{@code DelegateTask#getTaskDefinitionKey()}）
     * @return 沒有設定時回空清單，<b>不</b>回 null
     */
    public List<WebhookConfig> resolve(String processDefinitionId, String nodeId) {
        if (processDefinitionId == null || nodeId == null) return List.of();

        FlowElement element;
        try {
            BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
            Process process = processOf(model, processDefinitionId);
            if (process == null) return List.of();
            element = process.getFlowElement(nodeId);
        } catch (Exception e) {
            // ⚠️ 這裡刻意<b>不</b>讓例外往上炸。
            //
            // listener 是在引擎的 command 裡被呼叫的，任何未捕捉的例外都會讓
            // 「建立任務」整個失敗 —— 也就是說，一份壞掉的 webhook 設定
            // 會讓整張單建立不出來。設定讀不到頂多是不投遞，
            // 這個取捨是刻意的（fail-open 於流程、fail-closed 於投遞）。
            log.warn("讀取流程 {} 節點 {} 的 webhook 設定失敗，本節點不投遞：{}",
                    processDefinitionId, nodeId, e.toString());
            return List.of();
        }
        if (element == null) return List.of();

        List<WebhookConfig> fromExtension = fromExtensionElements(element);
        if (!fromExtension.isEmpty()) return fromExtension;

        // ⚠️ 「有 webhooks 元素但內容是空的」必須仍然回空清單，
        // 不能落到舊格式 —— 見類別註解「有元素就是權威」那段。
        if (hasWebhooksElement(element)) return List.of();

        return fromLegacyDocumentation(element);
    }

    /** 節點上是否<b>存在</b> {@code <flowable:webhooks>} 元素（不管裡面有沒有東西）。 */
    private boolean hasWebhooksElement(FlowElement element) {
        return !element.getExtensionElements().getOrDefault(WebhookConfig.ELEMENT, List.of()).isEmpty();
    }

    private List<WebhookConfig> fromExtensionElements(FlowElement element) {
        List<WebhookConfig> out = new ArrayList<>();
        for (ExtensionElement container : element.getExtensionElements()
                .getOrDefault(WebhookConfig.ELEMENT, List.of())) {
            for (ExtensionElement hook : container.getChildElements()
                    .getOrDefault(WebhookConfig.CHILD, List.of())) {
                // ⚠️ namespace 必須傳 null。BaseElement.getAttributeValue 是
                // 「namespace 相等」比對，而 <flowable:webhook event="..."> 的
                // event 屬性沒有前置 → 它的 namespace 就是 null。
                // 傳 WebhookUrlPolicy 那邊的常數會拿到 null， 看起來像「屬性被丟了」。
                WebhookConfig cfg = WebhookConfig.of(
                        hook.getAttributeValue(null, WebhookConfig.ATTR_EVENT),
                        hook.getAttributeValue(null, WebhookConfig.ATTR_URL),
                        hook.getAttributeValue(null, WebhookConfig.ATTR_METHOD));
                if (cfg == null) {
                    log.warn("節點 {} 有 {} 元素但沒有 url 屬性，忽略該筆設定（id={}）",
                            element.getId(), WebhookConfig.CHILD, element.getId());
                    continue;
                }
                out.add(cfg);
            }
        }
        return out;
    }

    /**
     * 舊格式：{@code <documentation>__webhooks__:[...]</documentation>}。
     *
     * <p>⚠️ {@code FlowElement#getDocumentation()} 只保留<b>最後一個</b>
     * {@code <documentation>} 元素（Flowable 的 {@code DocumentationParser}
     * 是單值欄位）。舊前端只寫一個，所以正常情況讀得到；
     * 但若有人在同一個節點上又補了一段一般說明，那段會蓋掉它 ——
     * 這是舊格式的先天限制，{@link #resolve} 的說明裡說明新格式不受影響。
     */
    private List<WebhookConfig> fromLegacyDocumentation(FlowElement element) {
        String doc = element.getDocumentation();
        if (doc == null || !doc.startsWith(LEGACY_DOC_PREFIX)) return List.of();

        String json = doc.substring(LEGACY_DOC_PREFIX.length()).trim();
        List<WebhookConfig> out = new ArrayList<>();
        try {
            var tree = objectMapper.readTree(json);
            if (!tree.isArray()) {
                log.warn("節點 {} 的舊格式 webhook 設定不是陣列，忽略：{}", element.getId(), json);
                return List.of();
            }
            for (var node : tree) {
                WebhookConfig cfg = WebhookConfig.of(
                        text(node, WebhookConfig.ATTR_EVENT),
                        text(node, WebhookConfig.ATTR_URL),
                        text(node, WebhookConfig.ATTR_METHOD));
                if (cfg == null) {
                    log.warn("節點 {} 的舊格式 webhook 設定缺少 url，忽略該筆", element.getId());
                    continue;
                }
                out.add(cfg);
            }
        } catch (Exception e) {
            // 同上：設定壞掉不得讓流程建立失敗。
            log.warn("節點 {} 的舊格式 webhook 設定無法解析，本節點不投遞：{}",
                    element.getId(), e.toString());
            return List.of();
        }
        return out;
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        var v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * 從 {@code key:version:deploymentId} 找出這份 {@link BpmnModel} 裡對應的 process。
     *
     * <p>⚠️ 不能用 {@code BpmnModel#getProcess(String)}：Flowable 7 的那個方法
     * 收的是<b>池（pool）id</b>，不是 process id，傳 process id 會回 null。
     * （寫這段的時候實測踩過；{@code ExtensionElementPreservationTest} 也留了記錄。）
     */
    private static Process processOf(BpmnModel model, String processDefinitionId) {
        String key = processKey(processDefinitionId);
        for (Process p : model.getProcesses()) {
            if (p.getId().equals(key)) return p;
        }
        // 找不到同 id 的 process（例如 id 與 key 不一致的自訂流程）：
        // 退到 main process，而不是直接放棄 —— 放棄會讓設定靜默失效。
        return model.getMainProcess();
    }

    static String processKey(String processDefinitionId) {
        if (processDefinitionId == null) return "";
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }
}
