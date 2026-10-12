package com.bpm.core.uniform;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BPMN {@code uniform:access}（ADR-23）→ uni-form Access Profile JSON 的解析器。
 *
 * <h2>職責</h2>
 *
 * <p>把 BPMN 節點上的 extensionElements
 * （{@code <uniform:access mode="..." default="..."><uniform:field key="..." state="..."/></uniform:access>}）
 * 轉成 {@code spec/access/access-profile.schema.json} 的 JSON 物件
 * {@code {mode, default, fields:{key:{state, required?}}}}，供
 * {@code UniformBffService.projectV1／validateV1} 使用。
 *
 * <h2>解析來源（唯一）</h2>
 *
 * <ul>
 *   <li>userTask：{@code task.getProcessDefinitionId()} →
 *       {@code repositoryService.getBpmnModel(...)} → 以
 *       {@code task.getTaskDefinitionKey()} 在 {@code model.getMainProcess()}
 *       找 {@link UserTask}。</li>
 *   <li>startEvent：同一個 BpmnModel → {@code model.getMainProcess()}
 *       的第一個 {@link StartEvent}（startFormKey 端點已先確認它存在）。</li>
 * </ul>
 *
 * <h2>Fail-closed（計畫 §五 B；不可放寬）</h2>
 *
 * <p>「沒有權限設定」與「權限設定壞掉」都必須往<b>最保守</b>的方向倒：
 *
 * <ul>
 *   <li><b>無 access</b>：userTask → {@code {mode:"view", default:"readonly", fields:{}}}；
 *       startEvent → {@code {mode:"fill", default:"editable", fields:{}}}（新案件的
 *       起始表單本來就是填寫，沒有既有資料可看）。</li>
 *   <li><b>有 access 但解析失敗</b>（mode 非法／default 非法／field 形狀非法／
 *       找不到節點或 BpmnModel）→
 *       {@code {mode:"view", default:"readonly", fields:{}}}，<b>startEvent 也不例外</b>。
 *       壞掉的授權設定不可以退化成「編輯」或「填寫」。</li>
 *   <li><b>field 的兩種「不完整」要分開對待</b>（與 BPMN 面板的行為對齊）：
 *       設計器按「新增欄位」留下的空列（缺 {@code key}，或只有 key 而沒有任何
 *       覆寫屬性）<b>略過該列</b> —— 它不是一條授權語句，略過不授予任何權限；
 *       但「有 key 又有屬性、值卻非法」（state 非三態、required 非 true／false）
 *       是<b>無法解讀的授權意圖</b>，整份 fail-closed，不得猜測。</li>
 * </ul>
 *
 * <p>⚠️ 這裡刻意不是「解析失敗就丟 500」：BPMN 是管理者部署的資產，一份壞掉的
 * access 不該讓整個案件的表單端點癱瘓（案件還在跑）。隱藏全部欄位並轉唯讀
 * 讓故障可見（畫面空白）且不洩漏、不可寫，是 fail-closed 的正確形狀。
 *
 * <h2>extensionElements 的 key 形狀</h2>
 *
 * <p>Flowable 把 extensionElements 放進
 * {@code Map<String, List<ExtensionElement>>}，key 的形狀（有無 namespace
 * 前綴／QName）取決於引擎的解析器實作。<b>決策：兩種形狀都接受</b> ——
 * 先查 {@code "access"}，找不到再掃描 name 的 local part 是 {@code access}
 * 的項目（{@code "{http://bpm.com/uniform}access"} 或
 * {@code "uniform:access"}）。理由：本專案的 BPMN 是外部（設計器／人工）
 * 產出的檔案，key 形狀綁死任何一種都會讓「換一個 Flowable 版本就全部 fail
 * closed」，而 fail-closed 的觸發條件不該包含引擎內部的命名慣例。
 * {@code FormAccessResolverTest} 以真實 BPMN 檔（BpmnXMLConverter）釘住
 * 引擎<em>現在</em>實際給的形狀。
 *
 * <p>屬性（{@code mode}／{@code default}／{@code key}／{@code state}／
 * {@code required}）同理以 local name 讀取。
 */
@Component
public class FormAccessResolver {

    private static final Logger log = LoggerFactory.getLogger(FormAccessResolver.class);

    /** extensionElements 中 access 元素的 local name（計畫 §五 B）。 */
    static final String ACCESS = "access";

    /** access 的子元素 local name（單一 field 覆寫）。 */
    static final String FIELD = "field";

    /** 合法 mode（access-profile.schema.json 另有 design，但 BPMN 只承載 fill／view）。 */
    private static final Set<String> MODES = Set.of("fill", "view");

    /** 合法 default／state（強度順序 hidden &lt; readonly &lt; editable）。 */
    private static final Set<String> STATES = Set.of("hidden", "readonly", "editable");

    /**
     * userTask 的 fail-closed 預設：看得到、改不了。
     *
     * <p>刻意<b>不是</b> hidden —— 案件進行中的表單突然一片空白會讓審核者
     * 無從判斷案件內容，而 read-only 已足以阻擋任何寫入。
     */
    private static final Map<String, Object> TASK_FALLBACK = profile("view", "readonly");

    /** startEvent 的預設：新案件的起始表單是填寫（計畫 §五 B）。 */
    private static final Map<String, Object> START_FALLBACK = profile("fill", "editable");

    private final RepositoryService repositoryService;

    public FormAccessResolver(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    /**
     * 任務節點的 Access Profile。
     *
     * @param processDefinitionId 任務所屬流程定義 id（{@code task.getProcessDefinitionId()}）
     * @param taskDefinitionKey   任務節點 id（{@code task.getTaskDefinitionKey()}）
     * @return 保證可用的 profile；解析不到時為 {@code view／readonly}
     */
    public Map<String, Object> profileForTask(String processDefinitionId, String taskDefinitionKey) {
        FlowElement element = findElement(processDefinitionId, taskDefinitionKey);
        if (!(element instanceof UserTask userTask)) {
            // 任務不存在於 BPMN（定義被刪、key 漂移、非 UserTask）→ 一律 fail-closed。
            log.warn("無法在流程定義 {} 找到 userTask '{}'，Access Profile 採 fail-closed（view／readonly）",
                    processDefinitionId, taskDefinitionKey);
            return fallback(TASK_FALLBACK);
        }
        return fromExtension(userTask.getExtensionElements(), TASK_FALLBACK);
    }

    /**
     * startEvent 的 Access Profile。
     *
     * @return 無 access 時為 {@code fill／editable}；模型或節點找不到、或
     *         access 存在但壞掉時為 fail-closed {@code view／readonly}
     */
    public Map<String, Object> profileForStartEvent(String processDefinitionId) {
        StartEvent startEvent = startEventOf(processDefinitionId);
        if (startEvent == null) {
            log.warn("流程定義 {} 找不到 startEvent，Access Profile 採 fail-closed（view／readonly）",
                    processDefinitionId);
            return fallback(TASK_FALLBACK);
        }
        return fromExtension(startEvent.getExtensionElements(), START_FALLBACK);
    }

    /**
     * 主流程的第一個 {@link StartEvent}（{@code null} 代表找不到）。
     *
     * <p>公開給呼叫端取 {@code formKey}（start-form 端點）與 profile 使用
     * <b>同一次</b>走訪 —— 「start event 是哪一個」只允許一份實作。
     */
    public StartEvent startEventOf(String processDefinitionId) {
        BpmnModel model = model(processDefinitionId);
        if (model == null || model.getMainProcess() == null) {
            return null;
        }
        for (FlowElement element : model.getMainProcess().getFlowElements()) {
            if (element instanceof StartEvent startEvent) {
                return startEvent;
            }
        }
        return null;
    }

    // ── 解析 ────────────────────────────────────────────────────────

    /**
     * extensionElements → profile。
     *
     * @param fallback 無 access 時使用的預設（userTask：view／readonly；
     *                 startEvent：fill／editable）。access <b>存在但壞掉</b>時
     *                 不使用它，一律 fail-closed。
     */
    private Map<String, Object> fromExtension(Map<String, List<ExtensionElement>> extensionElements,
                                              Map<String, Object> fallback) {
        ExtensionElement access = accessOf(extensionElements);
        if (access == null) {
            return fallback(fallback);
        }

        String mode = attribute(access, "mode");
        String defaultState = attribute(access, "default");
        // ⚠️ null 檢查必須在 Set.contains 之前：Set.of(...).contains(null) 會拋 NPE
        // （immutable collection 的實作細節），而「屬性缺席」是合法的 BPMN 形狀。
        if (mode == null || defaultState == null
                || !MODES.contains(mode) || !STATES.contains(defaultState)) {
            log.warn("BPMN uniform:access 的 mode／default 非法（mode={}, default={}），"
                    + "Access Profile 採 fail-closed（view／readonly）", mode, defaultState);
            return fallback(TASK_FALLBACK);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        List<ExtensionElement> fieldElements = access.getChildElements().get(FIELD);
        if (fieldElements == null) {
            // 也接受 namespace 形狀的 key（見類別註解）。
            fieldElements = access.getChildElements().entrySet().stream()
                    .filter(entry -> localName(entry.getKey()).equals(FIELD))
                    .findFirst().map(Map.Entry::getValue).orElse(List.of());
        }
        for (ExtensionElement field : fieldElements) {
            String key = attribute(field, "key");
            if (key == null || key.isBlank()) {
                // 設計器「新增欄位」尚未填 key 的空列：面板刻意保留它
                // （不保留的話重繪後消失，見 uniformAccessStorage.js），
                // 但它不是一條授權語句 —— 略過，不授予任何權限。
                continue;
            }
            String state = attribute(field, "state");
            String required = attribute(field, "required");
            if (state == null && required == null) {
                // 只有 key、還沒有任何覆寫屬性（還在編輯中的列）→ no-op。
                continue;
            }
            if (state != null && !STATES.contains(state)) {
                log.warn("BPMN uniform:field '{}' 的 state 非法（{}），"
                        + "Access Profile 採 fail-closed（view／readonly）", key, state);
                return fallback(TASK_FALLBACK);
            }
            if (required != null && !"true".equals(required) && !"false".equals(required)) {
                log.warn("BPMN uniform:field '{}' 的 required 非布林（{}），"
                        + "Access Profile 採 fail-closed（view／readonly）", key, required);
                return fallback(TASK_FALLBACK);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            // state 缺席是合法的（access-profile.schema.json 的 fieldEntry 允許只帶 required），
            // 此時套用 profile 的 default；缺席不得被當成 hidden，也不得編一個值。
            if (state != null) {
                entry.put("state", state);
            }
            if (required != null) {
                entry.put("required", Boolean.parseBoolean(required));
            }
            fields.put(key, entry);
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("mode", mode);
        profile.put("default", defaultState);
        profile.put("fields", fields);
        return profile;
    }

    /**
     * 單一 {@code <uniform:field>} → access-profile 的 fieldEntry。
     *
     * @return {@code null} 代表形狀非法（呼叫端整份 profile fail-closed）
     */
    private ExtensionElement accessOf(Map<String, List<ExtensionElement>> extensionElements) {
        if (extensionElements == null || extensionElements.isEmpty()) {
            return null;
        }
        List<ExtensionElement> direct = extensionElements.get(ACCESS);
        if (direct != null && !direct.isEmpty()) {
            return direct.get(0);
        }
        for (Map.Entry<String, List<ExtensionElement>> entry : extensionElements.entrySet()) {
            if (localName(entry.getKey()).equals(ACCESS) && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    /**
     * 屬性以 local name 讀取，涵蓋 {@code mode} 與 {@code uniform:mode}／
     * {@code {ns}mode} 兩種 key（Flowable 的 {@code BaseElement.attributes} 是
     * {@code Map<String, List<ExtensionAttribute>>}：同名的重複屬性以清單承載）。
     */
    private String attribute(ExtensionElement element, String name) {
        for (Map.Entry<String, List<ExtensionAttribute>> entry : element.getAttributes().entrySet()) {
            if (!localName(entry.getKey()).equals(name)) {
                continue;
            }
            List<ExtensionAttribute> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                return values.get(0).getValue();
            }
        }
        return null;
    }

    /** {@code "{http://bpm.com/uniform}access"}／{@code "uniform:access"} → {@code access}。 */
    private static String localName(String name) {
        int brace = name.lastIndexOf('}');
        if (brace >= 0) {
            return name.substring(brace + 1);
        }
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private FlowElement findElement(String processDefinitionId, String elementId) {
        BpmnModel model = model(processDefinitionId);
        if (model == null || model.getMainProcess() == null || elementId == null) {
            return null;
        }
        return model.getMainProcess().getFlowElement(elementId, true);
    }

    private BpmnModel model(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            return null;
        }
        return repositoryService.getBpmnModel(processDefinitionId);
    }

    private static Map<String, Object> profile(String mode, String defaultState) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("mode", mode);
        profile.put("default", defaultState);
        profile.put("fields", new LinkedHashMap<>());
        return profile;
    }

    /**
     * 每次回傳一份新的 profile。
     *
     * <p>共用同一個 Map 實例會讓任何呼叫端（或 SDK 內部的防禦性複製失敗時）
     * 有機會改到全應用程式的「fail-closed 預設值」—— 那是一個沒有異常、
     * 沒有日誌的權限提升。複製的成本是一次小 map 配置。
     */
    private static Map<String, Object> fallback(Map<String, Object> template) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("mode", template.get("mode"));
        profile.put("default", template.get("default"));
        profile.put("fields", new LinkedHashMap<>());
        return profile;
    }
}
