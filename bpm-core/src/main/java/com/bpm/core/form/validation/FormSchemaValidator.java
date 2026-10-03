package com.bpm.core.form.validation;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 送出表單資料時的 schema 驗證（#55）。
 *
 * <p>輸入是 {@code FormData.formDefinitionId} 指向的
 * {@code FormDefinition.schemaJson} 與請求的 {@code dataJson}；
 * 輸出是違規清單（欄位 id ＋ 原因）。本類別<b>只讀不寫</b>：
 * 呼叫端 {@code FormDataController.submit} 在 {@code formService.submitData}
 * 之前擋下違規，因此不留下資料列、也不寫 {@code FORM_SUBMIT} 稽核。
 *
 * <h2>三個決策（各自有理由，不是預設值）</h2>
 *
 * <ol>
 *   <li><b>未知欄位（dataJson 有、schema 沒有）→ 400。</b>理由有三：
 *       <b>(1)</b> 渲染器 {@code DynamicForm.vue} 只會送出 schema 定義過的欄位
 *       （{@code handleSubmit} 逐 field 收集），未知欄位不可能來自正常 UI，
 *       只可能是呼叫端 bug 或刻意注入。
 *       <b>(2)</b> 依 spec §8.5「欄位 id == 流程變數名」，{@code dataJson} 與
 *       流程變數是同一批值的兩條路；未知 key 一旦日後被轉寫成流程變數，
 *       就是繞過 {@code ProcessController} 受保護變數檢查（{@code initiator}、
 *       {@code _} 前綴、{@code onBehalfOf}）的注入面。schema 是唯一知道
 *       合法欄位集合的地方，所以在這裡關掉。
 *       <b>(3)</b> 放行等於讓型別檢查可被「改個 key 名」繞過（把值塞進
 *       schema 沒有的欄位），驗證會變成半套。這與 repo 對 {@code initiator}／
 *       {@code createdBy}／{@code submittedBy} 的既有政策一致：
 *       不靜默忽略，明確 400 並指名欄位。</li>
 *
 *   <li><b>schemaJson 無法解析 → 500（fail-closed），不是略過。</b>
 *       表單定義存在、卻讀不出 schema，代表這張表的定義已經毀損；略過驗證
 *       會讓「這張表從此不驗」變成靜默事實，呼叫端與維運都不會知道。
 *       seed 的四張表與既有測試資料的 schemaJson 都是合法 JSON，因此
 *       fail-closed 不影響既有資料；代價是壞掉的表單在修復前完全收不了件
 *       —— 但那張表在前端本來也渲染不出來（{@code DynamicForm.loadSchema}
 *       解析失敗會得到空欄位），擋下只是把靜默故障變成看得見的故障，
 *       不是多擋一個正常流程。</li>
 *
 *   <li><b>formDefinitionId 查不到定義 → 略過驗證（warn）。</b>
 *       這是<b>已知缺口</b>，不是理想行為：查不到定義時沒有 schema 可對照，
 *       只能略過；呼叫端因此可以用一個不存在的 id 讓整包 dataJson 跳過驗證。
 *       不在此 fail-closed 的理由是 {@code bpm_form_data.form_definition_id}
 *       沒有 FK 約束，既有測試資料就存在對不到定義的 id（例：
 *       {@code "leave-form-v1"}），硬擋會把資料完整性問題變成送件中斷。
 *       缺口以 warn 保持可觀察；要關掉它需要在 submit 路徑要求
 *       formDefinitionId 必須指向真實定義（後續工項）。</li>
 * </ol>
 *
 * <h2>型別規則：以 DynamicForm.vue（渲染器）的資料契約為準</h2>
 *
 * <ul>
 *   <li>{@code text}／{@code textarea}／{@code select}／{@code radio}／
 *       {@code date} → 字串。</li>
 *   <li>{@code number} → JSON number。刻意不接受數字字串：那正是驗證要抓的漂移。</li>
 *   <li>{@code checkbox} → 字串陣列（el-checkbox-group 的 v-model）。</li>
 *   <li>{@code dateRange} → 長度 2 的字串陣列（el-date-picker），或
 *       {@code "起~訖"} 字串（{@code StartProcess.vue}／{@code DocumentDetail.vue}
 *       已在使用的既有格式）。</li>
 *   <li>其餘型別（{@code file}、{@code link}、{@code orgSelector}、
 *       {@code amount}、{@code richtext} 與未知型別）<b>不做形狀檢查</b>：
 *       渲染器對它們沒有 v-model 資料契約（file／link 不綁值），
 *       替它們發明規則只會擋掉合法資料。required 與未知欄位檢查仍套用。</li>
 * </ul>
 *
 * <p>{@code maxLength}／{@code min}／{@code max}／options 成員資格等細部規則
 * 不在本工項範圍。
 *
 * <p>⚠️ {@code PUT /api/form-data/{id}}（退回修改）<b>不走這裡</b>（#58 的範圍），
 * 兩條路的驗證落差是已知的。
 */
@Component
public class FormSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(FormSchemaValidator.class);

    private final FormDefinitionRepository defRepo;
    private final ObjectMapper objectMapper;

    // 直接使用 repository 而不是 FormService.getById：getById 找不到定義時
    // 拋 404，而本類別需要的是「找不到 → 略過驗證」這個第三種結果
    // （見類別註解第 3 點）。規則只有一份，不在呼叫端再包一層 try/catch。
    public FormSchemaValidator(FormDefinitionRepository defRepo, ObjectMapper objectMapper) {
        this.defRepo = defRepo;
        this.objectMapper = objectMapper;
    }

    /** 一個違規：哪個欄位（id == 流程變數名）、為什麼。 */
    public record Violation(String fieldId, String reason) {}

    /**
     * 驗證一筆即將送出的表單資料。
     *
     * @param formDefinitionId {@code FormData.formDefinitionId}，指向 schema 的來源
     * @param dataJson         表單填寫資料（JSON 物件字串）
     * @return 違規清單；空清單＝通過（含「查不到定義、略過驗證」）
     * @throws ResponseStatusException 400：formDefinitionId 空白／dataJson 不是
     *                                 合法 JSON 物件（呼叫端的錯）；
     *                                 500：定義存在但 schemaJson 無法解讀（見類別註解第 2 點）
     */
    public List<Violation> validate(String formDefinitionId, String dataJson) {
        if (formDefinitionId == null || formDefinitionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "formDefinitionId 為必填 —— 沒有它無法驗證 dataJson 是否符合表單 schema。");
        }
        // dataJson 的形狀檢查不分「有沒有 schema」：請求本身不是 JSON 物件
        // 時，任何 schema 都無從比對，先講清楚是呼叫端要改 payload。
        JsonNode data = parseDataJson(dataJson);

        FormDefinition def = defRepo.findById(formDefinitionId).orElse(null);
        if (def == null) {
            log.warn("找不到表單定義 {}（formDefinitionId），跳過 dataJson 的 schema 驗證。"
                            + "這是已知缺口：不存在的 id 會讓驗證被略過，見 FormSchemaValidator 類別註解。",
                    formDefinitionId);
            return List.of();
        }

        return check(parseSchema(def), data);
    }

    // ── 解析 ──────────────────────────────────────────────────────

    private JsonNode parseDataJson(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dataJson 為必填 —— 表單資料必須是 JSON 物件。");
        }
        JsonNode data;
        try {
            data = objectMapper.readTree(dataJson);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dataJson 不是合法的 JSON：" + e.getOriginalMessage());
        }
        if (data == null || !data.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dataJson 必須是 JSON 物件（{...}）。");
        }
        return data;
    }

    /**
     * schemaJson → 欄位表（id → 欄位節點，保留 schema 的順序）。
     *
     * <p>讀不出來一律 500（見類別註解第 2 點）。沒有 {@code fields} 視為空表單，
     * 與 {@code DynamicForm.vue} 的 {@code schema.fields || []} 一致 ——
     * 那是「空」而不是「毀損」。
     */
    private Map<String, JsonNode> parseSchema(FormDefinition def) {
        String raw = def.getSchemaJson();
        if (raw == null || raw.isBlank()) {
            throw unreadable(def, "schemaJson 為空");
        }
        JsonNode schema;
        try {
            schema = objectMapper.readTree(raw);
        } catch (JacksonException e) {
            throw unreadable(def, "JSON 解析失敗：" + e.getOriginalMessage());
        }
        if (schema == null || !schema.isObject()) {
            throw unreadable(def, "最外層不是 JSON 物件");
        }
        JsonNode fieldsNode = schema.path("fields");
        if (fieldsNode.isMissingNode() || fieldsNode.isNull()) {
            return Map.of();
        }
        if (!fieldsNode.isArray()) {
            throw unreadable(def, "fields 不是陣列");
        }
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        for (JsonNode field : fieldsNode) {
            if (!field.isObject()) {
                throw unreadable(def, "fields 內含非物件的元素");
            }
            String id = field.path("id").asText("");
            if (id.isBlank()) {
                throw unreadable(def, "fields 內有欄位缺少 id");
            }
            if (fields.put(id, field) != null) {
                // 重複 id 讓 required／type 的語意變得模糊，而渲染器的
                // :key="field.id" 本來就會壞掉 —— 視為 schema 毀損。
                throw unreadable(def, "欄位 id 重複：" + id);
            }
        }
        return fields;
    }

    private ResponseStatusException unreadable(FormDefinition def, String detail) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "表單定義（formKey=" + def.getFormKey() + "，version=" + def.getVersion()
                        + "，id=" + def.getId() + "）的 schemaJson 無法解讀：" + detail
                        + "。為避免寫入未經驗證的資料，本次送出已拒絕；"
                        + "請修復該表單定義（建立新版 draft 並發布）後再送出。");
    }

    // ── 規則（只有這一份）────────────────────────────────────────

    private List<Violation> check(Map<String, JsonNode> fields, JsonNode data) {
        List<Violation> violations = new ArrayList<>();

        // 未知欄位先報：它與 schema 的欄位順序無關，且是最可能的注入樣態。
        // Jackson 3 把 fieldNames() 更名為 propertyNames()（回傳 Collection）。
        data.propertyNames().forEach(name -> {
            if (!fields.containsKey(name)) {
                violations.add(new Violation(name, "不在表單 schema 定義的欄位中"));
            }
        });

        fields.forEach((id, field) -> {
            JsonNode value = data.get(id);
            if (isRequired(field) && isEmpty(value)) {
                violations.add(new Violation(id, "為必填欄位，未提供或為空"));
                return;
            }
            if (value == null || value.isNull()) {
                return;
            }
            String mismatch = mismatchOf(field, value);
            if (mismatch != null) {
                violations.add(new Violation(id, mismatch));
            }
        });

        return violations;
    }

    private boolean isRequired(JsonNode field) {
        return field.path("required").asBoolean(false);
    }

    /** required 的「空」：缺 key、null、空白字串、空陣列（checkbox／dateRange 的未選狀態）。 */
    private boolean isEmpty(JsonNode value) {
        if (value == null || value.isNull()) return true;
        if (value.isTextual()) return value.textValue().isBlank();
        return value.isArray() && value.isEmpty();
    }

    /** 型別不符的原因；不做形狀檢查的型別回 {@code null}（見類別註解的型別規則）。 */
    private String mismatchOf(JsonNode field, JsonNode value) {
        String type = field.path("type").asText("");
        return switch (type) {
            case "text", "textarea", "select", "radio", "date" ->
                    value.isTextual() ? null : mismatch(type, value);
            case "number" -> value.isNumber() ? null : mismatch(type, value);
            // checkbox／dateRange 的預期形狀比「是不是陣列」更細，訊息要講出
            // 形狀本身，否則 [1] 會得到「實際為 array」這種沒指出問題的訊息。
            case "checkbox" -> isStringArray(value) ? null
                    : "型別不符：checkbox 需要字串陣列（例如 [\"opt1\"]），實際為 " + jsonType(value);
            case "dateRange" -> isDateRange(value) ? null
                    : "型別不符：dateRange 需要 [\"起\",\"訖\"] 或 \"起~訖\"，實際為 " + jsonType(value);
            default -> null;
        };
    }

    private boolean isStringArray(JsonNode value) {
        if (!value.isArray()) return false;
        for (JsonNode item : value) {
            if (!item.isTextual()) return false;
        }
        return true;
    }

    /** dateRange：{@code ["起","訖"]}（渲染器）或 {@code "起~訖"}（既有變數格式）。 */
    private boolean isDateRange(JsonNode value) {
        if (value.isArray()) {
            return value.size() == 2 && value.get(0).isTextual() && value.get(1).isTextual();
        }
        return value.isTextual() && value.textValue().contains("~");
    }

    private String mismatch(String type, JsonNode value) {
        return "型別不符：schema 定義為 " + type + "，實際為 " + jsonType(value);
    }

    private String jsonType(JsonNode value) {
        if (value.isTextual()) return "string";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        if (value.isArray()) return "array";
        if (value.isObject()) return "object";
        return "null";
    }
}
