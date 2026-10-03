package com.bpm.core.form.validation;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * #55：{@link FormSchemaValidator} 的規則。
 *
 * <p>純單元（不開 Spring／容器）：驗證規則是本工項的核心，必須能逐條釘住
 * —— 整合測試只證明「接線對」，證明不了每一條規則的邊界
 * （空陣列算不算 required、dateRange 的兩種格式、未知型別放行…）。
 *
 * <p>這裡刻意用「真實 FormDefinition ＋ mock repository」而不是 mock 出
 * schema 物件：schemaJson 字串 → 欄位表的解析路徑本身就是規則的一部分，
 * mock 掉它等於把最容易出錯的一段排除在測試之外。
 */
class FormSchemaValidatorTest {

    private FormDefinitionRepository defRepo;
    private FormSchemaValidator validator;

    /**
     * 涵蓋每一類規則的 schema：required（文字）、可選 number、
     * required checkbox、dateRange。maxLength 刻意填了但不驗（不在範圍）。
     */
    private static final String SCHEMA = """
            {"formKey":"test-form","version":1,"mode":"edit","fields":[
              {"id":"name","type":"text","required":true},
              {"id":"reason","type":"textarea","required":true,"maxLength":500},
              {"id":"quantity","type":"number"},
              {"id":"tags","type":"checkbox","required":true},
              {"id":"period","type":"dateRange"}
            ]}""";

    @BeforeEach
    void setUp() {
        defRepo = Mockito.mock(FormDefinitionRepository.class);
        validator = new FormSchemaValidator(defRepo, new ObjectMapper());
    }

    private void givenDefinition(String schemaJson) {
        FormDefinition def = new FormDefinition();
        def.setId("def-1");
        def.setFormKey("test-form");
        def.setVersion(1);
        def.setSchemaJson(schemaJson);
        when(defRepo.findById("def-1")).thenReturn(Optional.of(def));
    }

    private List<FormSchemaValidator.Violation> validate(String dataJson) {
        givenDefinition(SCHEMA);
        return validator.validate("def-1", dataJson);
    }

    private static void assertViolation(List<FormSchemaValidator.Violation> violations,
                                        String fieldId, String reasonPart) {
        assertThat(violations)
                .as("應有欄位 '%s' 的違規（%s），實際：%s", fieldId, reasonPart, violations)
                .anySatisfy(v -> {
                    assertThat(v.fieldId()).isEqualTo(fieldId);
                    assertThat(v.reason()).contains(reasonPart);
                });
    }

    private static void assertNoViolationOn(List<FormSchemaValidator.Violation> violations,
                                            String fieldId) {
        assertThat(violations)
                .as("欄位 '%s' 不該有違規，實際：%s", fieldId, violations)
                .noneMatch(v -> v.fieldId().equals(fieldId));
    }

    // ── required ──────────────────────────────────────────────────

    @Test
    @DisplayName("required：缺 key／null／空白字串／空陣列都算未提供")
    void requiredIsReportedForMissingNullBlankAndEmptyArray() {
        assertViolation(validate("{\"reason\":\"x\",\"tags\":[\"a\"]}"), "name", "必填");
        assertViolation(validate("{\"name\":null,\"reason\":\"x\",\"tags\":[\"a\"]}"), "name", "必填");
        assertViolation(validate("{\"name\":\"  \",\"reason\":\"x\",\"tags\":[\"a\"]}"), "name", "必填");
        // checkbox 的未選狀態是 []，不是 null。
        assertViolation(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[]}"), "tags", "必填");
    }

    @Test
    @DisplayName("required：有值時不得誤報")
    void requiredWithValuePasses() {
        assertThat(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[\"a\"]}")).isEmpty();
    }

    // ── 型別 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("型別：number 欄位收到字串／布林 → 違規，且訊息說出實際型別")
    void numberFieldRejectsNonNumber() {
        assertViolation(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[\"a\"],\"quantity\":\"3\"}"),
                "quantity", "number");
        assertViolation(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[\"a\"],\"quantity\":true}"),
                "quantity", "boolean");
    }

    @Test
    @DisplayName("型別：文字欄位收到數字／物件 → 違規")
    void textFieldRejectsNonString() {
        assertViolation(validate("{\"name\":123,\"reason\":\"x\",\"tags\":[\"a\"]}"), "name", "實際為 number");
        assertViolation(validate("{\"name\":{\"x\":1},\"reason\":\"x\",\"tags\":[\"a\"]}"), "name", "實際為 object");
    }

    @Test
    @DisplayName("型別：checkbox 只接受字串陣列，訊息要講出預期形狀")
    void checkboxRequiresStringArray() {
        assertViolation(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":\"a\"}"), "tags", "checkbox 需要字串陣列");
        assertViolation(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[1]}"), "tags", "checkbox 需要字串陣列");
        assertNoViolationOn(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[\"a\",\"b\"]}"), "tags");
    }

    @Test
    @DisplayName("型別：dateRange 接受 [起,訖] 與 '起~訖' 兩種既有格式")
    void dateRangeAcceptsBothExistingFormats() {
        assertNoViolationOn(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[\"a\"],"
                + "\"period\":[\"2026-10-01\",\"2026-10-05\"]}"), "period");
        assertNoViolationOn(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[\"a\"],"
                + "\"period\":\"2026-10-01~2026-10-05\"}"), "period");

        assertViolation(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[\"a\"],"
                + "\"period\":[\"2026-10-01\"]}"), "period", "dateRange");
        assertViolation(validate("{\"name\":\"a\",\"reason\":\"x\",\"tags\":[\"a\"],"
                + "\"period\":\"2026-10-01\"}"), "period", "dateRange");
    }

    @Test
    @DisplayName("型別：渲染器沒有資料契約的型別（file／link／amount…）不做形狀檢查")
    void typesWithoutRendererContractSkipShapeCheck() {
        givenDefinition("""
                {"formKey":"test-form","fields":[
                  {"id":"attachment","type":"file"},
                  {"id":"homepage","type":"link"},
                  {"id":"price","type":"amount"},
                  {"id":"note","type":"richtext"}
                ]}""");
        assertThat(validator.validate("def-1", "{\"attachment\":[\"att-1\"],"
                + "\"homepage\":\"https://example.com\",\"price\":\"abc\",\"note\":123}"))
                .as("file／link 不綁 v-model；amount／richtext 不在渲染器支援清單 —— "
                        + "替它們發明規則只會擋掉合法資料")
                .isEmpty();
    }

    // ── 未知欄位（決策：拒絕）─────────────────────────────────────

    @Test
    @DisplayName("未知欄位：dataJson 有、schema 沒有 → 違規並指名欄位")
    void unknownFieldIsRejected() {
        List<FormSchemaValidator.Violation> violations = validate(
                "{\"name\":\"a\",\"reason\":\"x\",\"tags\":[\"a\"],\"salary\":\"95000\"}");

        assertViolation(violations, "salary", "不在表單 schema 定義的欄位中");
        assertThat(violations).hasSize(1);
    }

    @Test
    @DisplayName("未知欄位：schema 沒有 fields 時，任何 key 都是未知欄位；{} 可通過")
    void schemaWithoutFieldsRejectsEveryKey() {
        givenDefinition("{\"formKey\":\"test-form\"}");
        assertThat(validator.validate("def-1", "{}")).isEmpty();
        assertViolation(validator.validate("def-1", "{\"anything\":1}"),
                "anything", "不在表單 schema 定義的欄位中");
    }

    // ── schemaJson 毀損（決策：fail-closed 500）───────────────────

    @Test
    @DisplayName("schemaJson 不是合法 JSON → 500（fail-closed），不是略過驗證")
    void unparseableSchemaFailsClosed() {
        givenDefinition("{not json");
        assertThatThrownBy(() -> validator.validate("def-1", "{}"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(500));
    }

    @Test
    @DisplayName("schemaJson 形狀毀損（fields 非陣列／欄位缺 id／id 重複）→ 500")
    void malformedSchemaShapeFailsClosed() {
        givenDefinition("{\"fields\":{\"id\":\"x\"}}");
        assertThatThrownBy(() -> validator.validate("def-1", "{}"))
                .isInstanceOf(ResponseStatusException.class);

        givenDefinition("{\"fields\":[{\"type\":\"text\"}]}");
        assertThatThrownBy(() -> validator.validate("def-1", "{}"))
                .isInstanceOf(ResponseStatusException.class);

        givenDefinition("{\"fields\":[{\"id\":\"a\"},{\"id\":\"a\"}]}");
        assertThatThrownBy(() -> validator.validate("def-1", "{}"))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── 查不到定義（決策：略過＋warn，已知缺口）────────────────────

    @Test
    @DisplayName("formDefinitionId 查不到定義 → 略過驗證（已知缺口，以測試釘住現況）")
    void missingDefinitionSkipsValidation() {
        when(defRepo.findById("no-such-def")).thenReturn(Optional.empty());

        assertThat(validator.validate("no-such-def",
                "{\"whatever\":\"goes\",\"quantity\":\"not-a-number\"}"))
                .as("沒有 schema 可對照時只能略過；這代表不存在的 id 可以繞過驗證 —— "
                        + "未來若在 submit 路徑要求真實定義，這條測試會紅，強迫重新裁決")
                .isEmpty();
    }

    // ── 請求形狀（400）────────────────────────────────────────────

    @Test
    @DisplayName("dataJson 不是合法 JSON／不是物件／為空 → 400")
    void malformedDataJsonIsBadRequest() {
        givenDefinition(SCHEMA);
        assertThatThrownBy(() -> validator.validate("def-1", "{oops"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> validator.validate("def-1", "[1,2]"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> validator.validate("def-1", "  "))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("formDefinitionId 空白 → 400（沒有它無法驗證）")
    void blankFormDefinitionIdIsBadRequest() {
        assertThatThrownBy(() -> validator.validate(null, "{}"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> validator.validate("  ", "{}"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }
}
