package com.bpm.core.uniform;

import com.nanshan.uniform.boot4.UniformSpecResources;
import com.nanshan.uniform.core.compile.CompileDiagnostic;
import com.nanshan.uniform.core.compile.CompileOptions;
import com.nanshan.uniform.core.compile.CompileResult;
import com.nanshan.uniform.core.compile.SchemaCompiler;
import com.nanshan.uniform.core.compile.ValueSchemaRegistry;
import com.nanshan.uniform.core.model.UfError;
import com.nanshan.uniform.core.model.UniformDocumentReader;
import com.nanshan.uniform.core.registry.TypeRegistry;
import com.nanshan.uniform.core.spi.JsonSchemaValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code schemaJson} 必須是合法 uni-form DSL v1 的守衛
 * （docs/plan/2026-10-12-uniform-form-refactor.md §五 A7／§六 BE）。
 *
 * <h2>兩道 gate（缺一不可）</h2>
 *
 * <ol>
 *   <li><b>DSL JSON Schema</b>（{@code spec/dsl/uniform.schema.json}）：
 *       結構與型別的先決條件。這裡用 {@code Map.of("$ref", id)} 讓
 *       networknt 自行解析 {@code $id} 指向的本地資源 —— spec 是唯一權威，
 *       宿主不得自己複製一份 schema。</li>
 *   <li><b>{@code SchemaCompiler.compileStatic}</b>：跨節點語意
 *       （key 唯一、condition 引用存在、repeater 規則、manifest props…）。
 *       schema 過得了、compile 過不了的 DSL 是<b>壞的定義</b>，
 *       不是「還不能用的定義」—— 它會在投影或提交時才炸。</li>
 * </ol>
 *
 * <p>⚠️ compile 必須<b>注入 value schema 註冊表</b>（{@code ValueSchemaRegistry}）：
 * manifest 的 {@code valueSchema} 用 {@code uniform:values/<stem>} 簡寫
 * （seed 的 {@code dateRange} 就是），少了註冊表會得到
 * {@code MANIFEST-VALUESCHEMA-01} —— 症狀是「含 dateRange 的表單存不進去」。
 * stems 的建法與 {@code EnvelopeProjectionV1} 相同（kebab 與 camel 兩種寫法）。
 * （2026-10-13 修：E2E 首次以真表單存檔時發現；BE 首輪測試只用了 textarea。）
 *
 * <h2>為什麼在建立／更新／發布都擋</h2>
 *
 * <p>舊格式（扁平 {@code fields[]}）不再被任何路徑接受（計畫 §三 1）。
 * 沒有一道 gate，壞的 DSL 會先被存成 draft，等到某個流程節點投影時才
 * 以 500 出現 —— 那時使用者已經在跑流程，而且錯誤現場與肇因（存檔）
 * 相隔很遠。400 + 指名的 compile 診斷把問題留在存檔當下，
 * 且<b>零副作用</b>（驗證在任何 repository 寫入之前）。
 *
 * <h2>發布也要驗的理由</h2>
 *
 * <p>draft 可能來自 {@code createNextDraft} 的複製：舊格式或更早版本
 * 的內容可以在未經本 gate 的情況下被複製成 draft。發布是「這份版本
 * 開始給流程用」的時點，是最後一個能零成本擋下的地方。
 */
@Service
public class FormDefinitionValidator {

    /**
     * DSL schema 的 {@code $id}（{@code UniformSpecResources.schemas()} 的 key）。
     * 不可改成路徑或另外內嵌一份 schema。
     */
    static final String DSL_SCHEMA_ID = "https://uniform.nanshan.com.tw/spec/v1/dsl/uniform.schema.json";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JsonSchemaValidator schemaValidator;
    private final TypeRegistry registry;
    private final UniformSpecResources specResources;
    private final ObjectMapper objectMapper;
    /** {@code uniform:values/<stem>} 的註冊表；compile 必需（見類別註解）。 */
    private final ValueSchemaRegistry valueSchemas;

    public FormDefinitionValidator(JsonSchemaValidator schemaValidator, TypeRegistry registry,
                                   UniformSpecResources specResources, ObjectMapper objectMapper) {
        this.schemaValidator = schemaValidator;
        this.registry = registry;
        this.specResources = specResources;
        this.objectMapper = objectMapper;
        this.valueSchemas = buildValueSchemas(specResources);
    }

    /**
     * 由 spec 資源建 value schema 註冊表（stems 的建法與
     * {@code EnvelopeProjectionV1} 相同：kebab 與 camel 兩種寫法都註冊）。
     */
    private static ValueSchemaRegistry buildValueSchemas(UniformSpecResources resources) {
        final String base = "https://uniform.nanshan.com.tw/spec/v1/values/";
        Map<String, String> stems = new java.util.LinkedHashMap<>();
        for (String id : resources.schemas().keySet()) {
            if (id.startsWith(base) && id.endsWith(".schema.json")) {
                String stem = id.substring(base.length(), id.length() - ".schema.json".length());
                stems.put(stem, id);
                StringBuilder camel = new StringBuilder();
                boolean upper = false;
                for (char ch : stem.toCharArray()) {
                    if (ch == '-') {
                        upper = true;
                    } else {
                        camel.append(upper ? Character.toUpperCase(ch) : ch);
                        upper = false;
                    }
                }
                stems.put(camel.toString(), id);
            }
        }
        return ValueSchemaRegistry.of(stems);
    }

    /**
     * 驗證一份 DSL 字串。
     *
     * @throws ResponseStatusException 400：不是合法 DSL（訊息彙整、指名 path）
     */
    public void validate(String schemaJson) {
        require(parse(schemaJson), null);
    }

    /**
     * 驗證一份 DSL 字串，並要求 DSL 的 {@code key} 等於 {@code formKey}
     * （建立／更新／發布等「要落地為定義」的路徑）。
     */
    public void validate(String schemaJson, String formKey) {
        require(parse(schemaJson), formKey);
    }

    /**
     * 驗證一份已解析的 DSL 物件（設計器預覽用 —— 不必為了驗證再序列化一次）。
     */
    public void validate(Map<String, Object> dsl) {
        require(dsl, null);
    }

    /** 已解析 DSL ＋ key／formKey 一致性檢查（建立／更新／發布）。 */
    public void validate(Map<String, Object> dsl, String formKey) {
        require(dsl, formKey);
    }

    /** 解析 {@code schemaJson} → DSL 物件；失敗 400（呼叫端的輸入）。 */
    public Map<String, Object> parse(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "schemaJson 為必填 —— 表單定義必須是 uni-form DSL（$uniform:\"1.0\"）。");
        }
        try {
            Map<String, Object> dsl = objectMapper.readValue(schemaJson, MAP);
            if (dsl == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "schemaJson 必須是 JSON 物件（{...}）。");
            }
            return dsl;
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "schemaJson 不是合法的 JSON：" + e.getOriginalMessage());
        }
    }

    private void require(Map<String, Object> dsl, String formKey) {
        // ① spec 的 DSL schema 是唯一權威；$ref 由 adapter 對本地資源解析。
        if (!specResources.schemas().containsKey(DSL_SCHEMA_ID)) {
            // 資源不存在＝部署問題（spec jar 沒帶到）；不猜、不用內建副本。
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "找不到 uni-form DSL schema 資源（" + DSL_SCHEMA_ID + "）——"
                            + "請確認 uniform-spec 已隨部署打包。");
        }
        List<UfError> schemaErrors = schemaValidator.validate(Map.of("$ref", DSL_SCHEMA_ID), dsl);
        if (!schemaErrors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "schemaJson 不符 uni-form DSL schema：" + summarize(schemaErrors));
        }

        // ①' DSL 的 key 必須與 formKey 一致：FormEnvelopeService 以 formKey 當
        // Envelope 的 key 送進 projectV1，而 SDK 要求它等於 dsl.key。不一致的定義
        // 存得進去、直到某個案件投影時才以 500 出現 —— 在這裡擋，錯誤留在存檔
        // 當下、零副作用。formKey 為 null／空白時不檢查（那是 FormService.create
        // 的「formKey 為必填」錯誤，訊息更貼近肇因）。
        if (formKey != null && !formKey.isBlank()) {
            Object key = dsl.get("key");
            if (!formKey.equals(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "DSL 的 key 必須等於 formKey（DSL key=" + key + "，formKey=" + formKey + "）。"
                                + "表單識別在存檔與投影兩條路上必須是同一個值。");
            }
        }

        // ② 跨節點語意：schema 過了仍可能 compile 不過（key 重複、condition 引用不存在…）。
        CompileResult compiled;
        try {
            compiled = SchemaCompiler.compileStatic(UniformDocumentReader.read(dsl), registry,
                    // value schemas 必需（dateRange 等 $ref 型別）；validator 讓
                    // defaultValue 的型別也被檢查（通道②）。其餘檢查全開，
                    // 與設計器「存檔當下就把壞的定義擋下」的目標一致。
                    CompileOptions.all().withValueSchemas(valueSchemas).withValidator(schemaValidator));
        } catch (RuntimeException e) {
            // UniformDocumentReader／JsonReader 對結構問題會丟例外；schema gate 之後
            // 這裡只會是「schema 允許但 reader 讀不動」的邊界。仍是 400（定義問題）。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "schemaJson 無法解讀為 uni-form DSL：" + e.getMessage());
        }
        if (!compiled.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "schemaJson 無法編譯為 uni-form DSL（欄位／條件／型別問題）："
                            + compiled.errors().stream()
                                    .map(FormDefinitionValidator::describe)
                                    .collect(Collectors.joining("；")));
        }
    }

    /**
     * UfError → {@code <path> <message>}。
     *
     * <p>schema 驗證的 path 是相對於被驗證文件（DSL）的 JSON Pointer，
     * 直接放進訊息就能指出哪一個節點壞掉。
     */
    private static String summarize(List<UfError> errors) {
        return errors.stream()
                .map(error -> (error.path() == null || error.path().isEmpty() ? "/" : error.path())
                        + " " + error.message()
                        + "（" + error.code() + "）")
                .collect(Collectors.joining("；"));
    }

    /**
     * CompileDiagnostic → {@code <path> <message>}。
     *
     * <p>{@code message} 取自 SDK 的固定目錄（不得含輸入值，ENV-ERR-06(1)），
     * 因此可以安全地原樣回給呼叫端。
     */
    private static String describe(CompileDiagnostic diagnostic) {
        return diagnostic.path() + " " + diagnostic.message()
                + "（" + diagnostic.code() + "）";
    }
}
