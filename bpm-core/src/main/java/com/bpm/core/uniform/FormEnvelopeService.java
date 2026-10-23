package com.bpm.core.uniform;

import com.bpm.core.form.model.FormDefinition;
import com.nanshan.uniform.boot4.UniformBffService;
import com.nanshan.uniform.boot4.UniformSpecResources;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 組 uni-form {@code projectV1} 的輸入並回傳完整 v1 Envelope
 * （docs/plan/2026-10-12-uniform-form-refactor.md §五 A）。
 *
 * <h2>為什麼要集中在一支服務</h2>
 *
 * <p>三個端點（任務表單、起始表單、設計器預覽）回傳的是<b>同一種</b>回應格式，
 * 差別只在「哪一份 DSL、哪一份 Access Profile、哪一份資料」。所有
 * {@code projectV1} 的必填輸入（manifests／fieldTypes／behaviorRegistry／
 * locale／frame／includeSchema）只有這一份來源 —— 任一處漏填會讓
 * SDK 以 {@code {ok:false}} 拒絕，而拒絕沒有細節（SDK 的設計），
 * 在呼叫端各自組輸入時極難診斷。集中在這裡，壞掉時只需要看一個地方。
 *
 * <h2>本階段刻意的輸入選擇（不可自行「補齊」）</h2>
 *
 * <ul>
 *   <li>{@code fieldTypes:{}}：本階段 palette 無條件型別（計畫 §三 5），
 *       <b>不猜</b> valueKind。有條件的欄位（{@code visibleWhen} 等）等到
 *       真的有宿主型別來源時再注入，否則猜錯會讓 condition 靜默走錯分支。</li>
 *   <li>{@code behaviorRegistry} 來自宿主固定的 {@link OptionsProviderRegistry}；
 *       未註冊或尚未支援的動態來源組合會在投影前拒絕，不由瀏覽器指定服務。</li>
 *   <li>{@code data} 是<b>canonical JSON object</b>（表單值語意沿 SDK，
 *       例如 dateRange 是 {@code {start,end}}，不是 {@code "a~b"}）。</li>
 * </ul>
 *
 * <h2>失敗語意</h2>
 *
 * <p>{@code {ok:false}} 一律 500，訊息指名「Envelope 投影被拒絕（定義或資源問題）」。
 * SDK 對 refusal <b>刻意不提供任何細節</b>（防 oracle），所以這裡也不可能把
 * 原因講得更細；能講的是「這不是使用者輸入的問題，是定義或資源的問題」，
 * 讓維運知道要往哪裡查（表單定義、manifest 註冊、SDK 版本）。
 */
@Service
public class FormEnvelopeService {

    /** Envelope 契約固定值（計畫 §五 A1：locale=zh-TW、frame=form、includeSchema=true）。 */
    public static final String LOCALE = "zh-TW";
    public static final String FRAME = "form";
    public static final String UNIFORM_VERSION = "1.0";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final UniformBffService bff;
    private final UniformSpecResources specResources;
    private final ObjectMapper objectMapper;

    public FormEnvelopeService(UniformBffService bff, UniformSpecResources specResources,
                               ObjectMapper objectMapper) {
        this.bff = bff;
        this.specResources = specResources;
        this.objectMapper = objectMapper;
    }

    /**
     * 由表單定義投影 Envelope（任務表單／起始表單）。
     *
     * @param def     已解析的定義（{@code key} 取 {@code formKey}、{@code version} 取定義版本
     *                —— 呼叫端已依 {@code _formVersions} 鎖定版本，見 FormEnvelopeController）
     * @param profile 該 BPMN 節點的 Access Profile（{@link FormAccessResolver}）
     * @param data    canonical JSON 物件；無資料時傳空 map
     */
    public Map<String, Object> envelope(FormDefinition def, Map<String, Object> profile,
                                        Map<String, Object> data) {
        return project(parseSchema(def), def.getFormKey(), def.getVersion(), profile, data);
    }

    /**
     * 設計器預覽：以尚未儲存的 DSL 投影 Envelope。
     *
     * <p>{@code key} 與 {@code version} 取自 DSL／固定為 1：預覽沒有定義資料列，
     * 也不該假裝有。schemaRef 因此回答的是「這份 DSL 在 v1 的樣子」。
     */
    public Map<String, Object> preview(Map<String, Object> dsl, Map<String, Object> profile,
                                       Map<String, Object> data) {
        Object key = dsl.get("key");
        if (!(key instanceof String text) || text.isBlank()) {
            // 形狀由 FormDefinitionValidator 的 schema gate 保證，這裡是第二層：
            // 沒有 key 的 DSL 進不了 projectV1（key 是必填），直接 400 比 500 誠實。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "DSL 缺少非空白的 key，無法產生預覽 Envelope");
        }
        return project(dsl, text, 1, profile, data);
    }

    // ── 內部 ────────────────────────────────────────────────────────

    private Map<String, Object> project(Map<String, Object> dsl, String key, int version,
                                        Map<String, Object> profile, Map<String, Object> data) {
        OptionsProviderRegistry.requireSupported(dsl);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dsl", dsl);
        input.put("manifests", specResources.manifests());
        input.put("profile", profile);
        input.put("data", data != null ? data : new LinkedHashMap<>());
        input.put("fieldTypes", new LinkedHashMap<>());
        input.put("behaviorRegistry", OptionsProviderRegistry.behavior());
        input.put("uniform", UNIFORM_VERSION);
        input.put("key", key);
        input.put("version", version);
        input.put("mode", profile.get("mode"));
        input.put("frame", FRAME);
        input.put("locale", LOCALE);
        input.put("includeSchema", true);

        Map<String, Object> result = bff.projectV1(input);
        if (!Boolean.TRUE.equals(result.get("ok")) || !(result.get("envelope") instanceof Map<?, ?>)) {
            // refusal 沒有細節可用（SDK 刻意如此）；訊息要讓維運知道往哪裡查。
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Envelope 投影被拒絕（定義或資源問題）：formKey=" + key + "，version=" + version
                            + "，mode=" + profile.get("mode")
                            + "。請確認表單定義是合法 DSL、manifest 已註冊、且 SDK 版本相符。");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) result.get("envelope");
        return envelope;
    }

    /** {@code schemaJson} → DSL JSON 物件（parse 失敗是定義損毀 → 500，不是使用者輸入問題）。 */
    private Map<String, Object> parseSchema(FormDefinition def) {
        String raw = def.getSchemaJson();
        if (raw == null || raw.isBlank()) {
            throw unreadable(def, "schemaJson 為空");
        }
        try {
            Map<String, Object> dsl = objectMapper.readValue(raw, MAP);
            if (dsl == null) {
                throw unreadable(def, "schemaJson 不是 JSON 物件");
            }
            return dsl;
        } catch (JacksonException e) {
            throw unreadable(def, e.getOriginalMessage());
        }
    }

    private ResponseStatusException unreadable(FormDefinition def, String detail) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "表單定義（formKey=" + def.getFormKey() + "，version=" + def.getVersion()
                        + "）的 schemaJson 無法解讀：" + detail
                        + "。本階段只接受 uni-form DSL（$uniform:\"1.0\"），"
                        + "請以設計器建立新版 draft 後發布。");
    }
}
