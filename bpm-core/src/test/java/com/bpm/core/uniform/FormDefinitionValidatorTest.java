package com.bpm.core.uniform;

import com.nanshan.uniform.boot4.UniformBffService;
import com.nanshan.uniform.boot4.UniformSpecResources;
import com.nanshan.uniform.core.registry.TypeRegistry;
import com.nanshan.uniform.core.spi.JsonSchemaValidator;
import com.nanshan.uniform.jackson3.NetworkntJackson3Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FormDefinitionValidator}：{@code schemaJson} 必須是合法 uni-form DSL。
 *
 * <p>直接在測試裡組 SDK 的 bean（{@code UniformSpecResources.load()} ＋
 * networknt adapter）而不是開 Spring context：這組測的是「兩道 gate 的判定」，
 * 不是接線；用真的 spec 資源（classpath 上的 pinned submodule 產物）
 * 才有證明力 —— 自己造一份 schema 只會測到自己的假設。
 */
class FormDefinitionValidatorTest {

    /** 與 V7 seed 同形狀的最小合法 DSL（leave-request 的精簡版）。 */
    private static final String VALID_DSL = """
            {"$uniform":"1.0","key":"leave-request",
             "title":{"zh-TW":"請假申請表"},
             "requires":{"core":"^1.0"},
             "root":[{"id":"n_reason","type":"textarea","key":"reason","label":"事由","required":true}]}
            """;

    private static FormDefinitionValidator validator() throws Exception {
        UniformSpecResources resources = UniformSpecResources.load();
        JsonSchemaValidator schema = new NetworkntJackson3Validator(resources.schemas());
        TypeRegistry registry = resources.createRegistry(schema);
        return new FormDefinitionValidator(schema, registry, resources, new ObjectMapper());
    }

    @Test
    @DisplayName("合法 DSL 通過（seed 同形狀）")
    void validDslPasses() throws Exception {
        validator().validate(VALID_DSL);
    }

    @Test
    @DisplayName("缺 key → 400，訊息指名 path")
    void missingKeyIsRejectedWithPath() throws Exception {
        String dsl = """
                {"$uniform":"1.0","title":{"zh-TW":"無 key"},"root":[]}
                """;

        assertThatThrownBy(() -> validator().validate(dsl))
                .hasMessageContaining("key");
    }

    @Test
    @DisplayName("$uniform:\"0.9\" → 400（版本閘門由 compile 拒絕，不是 schema pattern）")
    void unsupportedUniformVersionIsRejected() throws Exception {
        // schema 的 pattern 只要求「數字.數字」形狀，0.9 過得了 schema；
        // 真正拒絕它的是 compile 的版本閘門（DSL-UNIFORM-01）。
        String dsl = """
                {"$uniform":"0.9","key":"leave-request","requires":{"core":"^1.0"},"root":[]}
                """;

        assertThatThrownBy(() -> validator().validate(dsl))
                .hasMessageContaining("$uniform")
                .hasMessageContaining("DSL-UNIFORM-01");
    }

    @Test
    @DisplayName("舊格式（扁平 fields[]）→ 400：不再被任何路徑接受")
    void legacyFlatFormatIsRejected() throws Exception {
        String legacy = """
                {"formKey":"leave-request","version":1,"mode":"edit",
                 "fields":[{"id":"reason","type":"textarea","required":true}]}
                """;

        assertThatThrownBy(() -> validator().validate(legacy))
                .as("舊格式沒有 $uniform／root，schema gate 直接擋下")
                .hasMessageContaining("schemaJson");
    }

    @Test
    @DisplayName("不是 JSON → 400（呼叫端的輸入）")
    void nonJsonIsBadRequest() throws Exception {
        assertThatThrownBy(() -> validator().validate("{not json"))
                .hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("schema 過得了但 compile 不過（欄位 key 重複）→ 400，訊息帶 compile 診斷")
    void compileErrorsAreRejected() throws Exception {
        // key 唯一性只有 compileStatic 檢查得到：兩個節點同 key 在 DSL schema 是合法的。
        String dsl = """
                {"$uniform":"1.0","key":"leave-request","title":{"zh-TW":"重複 key"},
                 "requires":{"core":"^1.0"},
                 "root":[{"id":"n1","type":"textarea","key":"reason","label":"A"},
                         {"id":"n2","type":"textarea","key":"reason","label":"B"}]}
                """;

        assertThatThrownBy(() -> validator().validate(dsl))
                .as("schema 過了不代表可用 —— compile gate 才是欄位語意的守衛")
                .hasMessageContaining("DSL-KEY-01")
                .hasMessageContaining("/root/1/key");
    }

    @Test
    @DisplayName("DSL key 與 formKey 不一致 → 400（投影才爆的等式在存檔當下擋）")
    void keyMustEqualFormKey() throws Exception {
        FormDefinitionValidator validator = validator();

        // 對照組：一致時通過。
        validator.validate(VALID_DSL, "leave-request");

        assertThatThrownBy(() -> validator.validate(VALID_DSL, "purchase-request"))
                .hasMessageContaining("formKey")
                .hasMessageContaining("leave-request");

        // Map 版本同一份判定。
        assertThatThrownBy(() -> validator.validate(validator.parse(VALID_DSL), "purchase-request"))
                .hasMessageContaining("formKey");
    }

    @Test
    @DisplayName("validate(Map) 與 validate(String) 同一份判定（設計器預覽用）")
    void parsedOverloadSharesTheSameGate() throws Exception {
        FormDefinitionValidator validator = validator();
        Map<String, Object> dsl = validator.parse(VALID_DSL);
        validator.validate(dsl);

        assertThat(dsl)
                .as("parse 只是解析；DSL 的內容不得被驗證過程改寫")
                .containsEntry("key", "leave-request");
        assertThat(dsl.get("root")).isInstanceOf(List.class);
    }
}
