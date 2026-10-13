package com.bpm.core.uniform;

import com.bpm.core.form.model.FormDefinition;
import com.nanshan.uniform.boot4.UniformBffService;
import com.nanshan.uniform.boot4.UniformSpecResources;
import com.nanshan.uniform.core.registry.TypeRegistry;
import com.nanshan.uniform.core.spi.JsonSchemaValidator;
import com.nanshan.uniform.jackson3.NetworkntJackson3Validator;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.common.engine.impl.util.io.InputStreamSource;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FormEnvelopeService}：{@code projectV1} 的輸入組裝與結果。
 *
 * <h2>用真的 SDK 資源與真的 BPMN，不 mock 投影</h2>
 *
 * <p>這個服務的價值就是「把正確的輸入餵給 SDK」；mock 掉 {@code projectV1}
 * 只會證明「我呼叫了它」。因此這裡用真的 spec 資源（pinned submodule 產物）、
 * 真的 networknt adapter、真的 {@code leave-approval.bpmn20.xml}
 * （Access Profile 來自 {@link FormAccessResolver}），只有 repository 是假的。
 *
 * <h2>釘住的三件事</h2>
 *
 * <ul>
 *   <li><b>review：mode=view，全部 readonly</b> —— managerReview 的 profile
 *       是 view／readonly，envelope 的 meta.states 必須反映它。</li>
 *   <li><b>revision：mode=fill，只有 reason editable</b> —— applicantRevision
 *       的 profile 是 fill／readonly＋reason editable。</li>
 *   <li><b>hidden 不存在</b>：hidden 欄位的定義與值都不得進入 Envelope
 *       （ENV-CROP；Access Profile 把它設成 hidden 時，
 *       states／data／schema 三處都看不到它）。</li>
 * </ul>
 */
class FormEnvelopeServiceTest {

    /** 與 V7 seed 同形狀的 DSL（leave-request）。 */
    private static final String LEAVE_DSL = """
            {"$uniform":"1.0","key":"leave-request",
             "title":{"zh-TW":"請假申請表"},
             "requires":{"core":"^1.0"},
             "root":[
               {"id":"n_leaveType","type":"select","key":"leaveType","label":"假別","required":true,
                "props":{"options":[{"value":"annual","label":"特休"},
                                    {"value":"personal","label":"事假"},
                                    {"value":"sick","label":"病假"}]}},
               {"id":"n_dateRange","type":"dateRange","key":"dateRange","label":"請假期間","required":true},
               {"id":"n_reason","type":"textarea","key":"reason","label":"事由","required":true,
                "props":{"maxLength":500}}]}
            """;

    private static final Map<String, Object> DATA = new LinkedHashMap<>(Map.of(
            "leaveType", "annual",
            "dateRange", Map.of("start", "2026-10-01", "end", "2026-10-03"),
            "reason", "家庭因素"));

    private final UniformSpecResources resources;
    private final FormEnvelopeService service;
    private final FormAccessResolver accessResolver;
    private final FormDefinition definition;

    FormEnvelopeServiceTest() throws Exception {
        resources = UniformSpecResources.load();
        JsonSchemaValidator schema = new NetworkntJackson3Validator(resources.schemas());
        TypeRegistry registry = resources.createRegistry(schema);
        service = new FormEnvelopeService(new UniformBffService(schema, resources), resources,
                new ObjectMapper());

        BpmnModel model;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("processes/leave-approval.bpmn20.xml")) {
            model = new BpmnXMLConverter().convertToBpmnModel(new InputStreamSource(in), true, true);
        }
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getBpmnModel("leave:1:1")).thenReturn(model);
        accessResolver = new FormAccessResolver(repositoryService);

        definition = new FormDefinition();
        definition.setFormKey("leave-request");
        definition.setVersion(1);
        definition.setSchemaJson(LEAVE_DSL);
    }

    // ── ① review：view／全 readonly ─────────────────────────────────

    @Test
    @DisplayName("managerReview（view）→ envelope mode=view、states 全 readonly、schemaRef.hash 存在")
    void reviewEnvelopeIsViewWithAllReadonlyStates() {
        Map<String, Object> profile = accessResolver.profileForTask("leave:1:1", "managerReview");

        Map<String, Object> envelope = service.envelope(definition, profile, DATA);

        assertThat(envelope).containsEntry("mode", "view").containsEntry("frame", "form");
        assertThat(states(envelope))
                .as("審核者對所有欄位都只能看")
                .containsOnly(
                        Map.entry("leaveType", "readonly"),
                        Map.entry("dateRange", "readonly"),
                        Map.entry("reason", "readonly"));
        assertThat(envelope).containsEntry("errors", List.of());
        // includeSchema=true：schema 本體必須在，且被裁剪過（見 hidden 測試）。
        assertThat(envelope).containsKey("schema");
        Map<String, Object> schemaRef = schemaRef(envelope);
        assertThat(schemaRef).containsEntry("key", "leave-request");
        assertThat(((Number) schemaRef.get("version")).intValue())
                .as("SDK 的 snapshot 會把數字正規化成 BigDecimal；比較語意上的版本號")
                .isEqualTo(1);
        assertThat(String.valueOf(schemaRef.get("hash")))
                .as("schemaRef.hash 是前端判斷「定義換了沒」的依據")
                .startsWith("sha256:");
        assertThat(envelope.get("data")).isEqualTo(DATA);
    }

    // ── ② revision：fill／reason editable ───────────────────────────

    @Test
    @DisplayName("applicantRevision（fill）→ mode=fill、只有 reason editable、其餘 readonly")
    void revisionEnvelopeAllowsReasonOnly() {
        Map<String, Object> profile = accessResolver.profileForTask("leave:1:1", "applicantRevision");

        Map<String, Object> envelope = service.envelope(definition, profile, DATA);

        assertThat(envelope).containsEntry("mode", "fill");
        assertThat(states(envelope))
                .as("補件只能改 reason（ADR-23 的核心案例）")
                .containsEntry("reason", "editable")
                .containsEntry("leaveType", "readonly")
                .containsEntry("dateRange", "readonly");
    }

    // ── ③ hidden：定義與值都不得出現 ────────────────────────────────

    @Test
    @DisplayName("profile 把 reason 設成 hidden → states／data／schema 三處都看不到它")
    void hiddenFieldNeverAppears() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("mode", "view");
        profile.put("default", "readonly");
        profile.put("fields", new LinkedHashMap<>(Map.of("reason", Map.of("state", "hidden"))));

        Map<String, Object> envelope = service.envelope(definition, profile, DATA);

        assertThat(states(envelope)).doesNotContainKey("reason");
        assertThat(dataOf(envelope))
                .as("hidden 欄位的值不得進 Envelope（ENV-CROP）")
                .doesNotContainKey("reason");
        assertThat(envelope.toString())
                .as("hidden 欄位的值不得以任何形式出現在回應中")
                .doesNotContain("家庭因素");
        assertThat(schemaNodeKeys(envelope))
                .as("hidden 欄位的定義也必須被裁剪")
                .doesNotContain("reason");
    }

    // ── ④ preview：key 取自 DSL、version 固定 1 ─────────────────────

    @Test
    @DisplayName("preview 的 key 取自 DSL、version=1、mode=fill")
    void previewUsesDslKeyAndVersionOne() throws Exception {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("mode", "fill");
        profile.put("default", "editable");
        profile.put("fields", new LinkedHashMap<>());

        @SuppressWarnings("unchecked")
        Map<String, Object> dsl = new ObjectMapper().readValue(LEAVE_DSL,
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        Map<String, Object> envelope = service.preview(dsl, profile, new LinkedHashMap<>());

        assertThat(schemaRef(envelope)).containsEntry("key", "leave-request");
        assertThat(((Number) schemaRef(envelope).get("version")).intValue()).isEqualTo(1);
        assertThat(envelope).containsEntry("mode", "fill");
        assertThat(states(envelope)).containsEntry("reason", "editable");
    }

    // ── ⑤ 損毀的定義 → 500 指名（不是靜默空表單）────────────────────

    @Test
    @DisplayName("schemaJson 不是合法 JSON → 500，訊息指名定義與版本")
    void corruptSchemaIsInternalError() {
        FormDefinition broken = new FormDefinition();
        broken.setFormKey("leave-request");
        broken.setVersion(3);
        broken.setSchemaJson("{not json");

        assertThatThrownBy(() -> service.envelope(broken, accessResolver.profileForStartEvent("leave:1:1"),
                new LinkedHashMap<>()))
                .hasMessageContaining("leave-request")
                .hasMessageContaining("version=3")
                .hasMessageContaining("無法解讀");
    }

    // ── 小工具 ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> states(Map<String, Object> envelope) {
        return (Map<String, Object>) ((Map<String, Object>) envelope.get("meta")).get("states");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaRef(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("schemaRef");
    }

    @SuppressWarnings("unchecked")
    private static List<String> schemaNodeKeys(Map<String, Object> envelope) {
        Map<String, Object> schema = (Map<String, Object>) envelope.get("schema");
        List<Object> root = (List<Object>) schema.get("root");
        return root.stream().map(node -> String.valueOf(((Map<String, Object>) node).get("key"))).toList();
    }
}
