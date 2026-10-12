package com.bpm.core.uniform;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.common.engine.impl.util.io.InputStreamSource;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FormAccessResolver}：BPMN {@code uniform:access} → Access Profile。
 *
 * <h2>為什麼這組測試要讀「真的 BPMN 檔」</h2>
 *
 * <p>解析的正確性完全取決於 Flowable 把 extensionElements 放進
 * {@code Map<String,List<ExtensionElement>>} 時用的 key 形狀（有無 namespace
 * 前綴、QName 還是 local name），以及屬性存在哪一種 key 下。那個形狀是
 * 引擎實作細節，不是 BPMN 標準 —— 用 Mockito 造一個「我以為的」
 * {@code BpmnModel} 只會測到自己的假設。
 *
 * <p>因此這裡把 {@code src/main/resources/processes/leave-approval.bpmn20.xml}
 * （線上實際部署的那一份）用 {@code BpmnXMLConverter} 讀進來，
 * <b>釘死 Flowable 8.0.0 現在的行為</b>：未來升級 Flowable 而 key 形狀改變時，
 * 這裡會先紅，而不是等到某個案件的權限靜默失效。
 *
 * <h2>fail-closed 的每一條分支</h2>
 *
 * <p>{@code spec} 的設計原則是「缺權限設定 → 最保守」：壞掉的 access
 * 不可以退化成「可編輯」。真實 BPMN 檔裡沒有壞掉的 access（它們是
 * 種子檔），所以負向案例用內嵌的 BPMN 字串 —— 同樣經過
 * {@code BpmnXMLConverter}，不是手工造模型。
 */
class FormAccessResolverTest {

    private static final String LEAVE = "processes/leave-approval.bpmn20.xml";

    private static BpmnModel parse(String xml) {
        return new BpmnXMLConverter().convertToBpmnModel(
                new InputStreamSource(new java.io.ByteArrayInputStream(
                        xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                true, true);
    }

    private static BpmnModel load(String resource) throws Exception {
        try (InputStream in = FormAccessResolverTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as("測試資源必須存在: " + resource).isNotNull();
            return new BpmnXMLConverter().convertToBpmnModel(new InputStreamSource(in), true, true);
        }
    }

    private static FormAccessResolver resolverFor(BpmnModel model) {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getBpmnModel("leave:1:1")).thenReturn(model);
        return new FormAccessResolver(repositoryService);
    }

    // ── 真實 BPMN：managerReview 是 view／readonly ──────────────────

    @Test
    @DisplayName("真檔 leave-approval 的 managerReview（view／readonly）解析正確")
    void managerReviewIsViewReadonly() throws Exception {
        FormAccessResolver resolver = resolverFor(load(LEAVE));

        Map<String, Object> profile = resolver.profileForTask("leave:1:1", "managerReview");

        assertThat(profile).containsEntry("mode", "view").containsEntry("default", "readonly");
        assertThat(fields(profile)).as("managerReview 沒有任何 field 覆寫").isEmpty();
    }

    // ── 真實 BPMN：applicantRevision 是 fill／readonly＋reason editable ──

    @Test
    @DisplayName("真檔 leave-approval 的 applicantRevision（fill＋reason editable）解析正確")
    void applicantRevisionAllowsReasonOnly() throws Exception {
        FormAccessResolver resolver = resolverFor(load(LEAVE));

        Map<String, Object> profile = resolver.profileForTask("leave:1:1", "applicantRevision");

        assertThat(profile).containsEntry("mode", "fill").containsEntry("default", "readonly");
        assertThat(fields(profile))
                .as("補件只能改 reason —— 這是 ADR-23 的核心案例")
                .containsOnlyKeys("reason");
        assertThat(fields(profile).get("reason"))
                .isEqualTo(Map.of("state", "editable"));
    }

    // ── 真實 BPMN：startEvent 沒有 access → fill／editable ─────────

    @Test
    @DisplayName("真檔 leave-approval 的 startEvent 沒有 access → 預設 fill／editable")
    void startEventWithoutAccessDefaultsToFillEditable() throws Exception {
        FormAccessResolver resolver = resolverFor(load(LEAVE));

        assertThat(resolver.profileForStartEvent("leave:1:1"))
                .as("新案件的起始表單本來就是填寫（計畫 §五 B）")
                .containsEntry("mode", "fill")
                .containsEntry("default", "editable");
        assertThat(fields(resolver.profileForStartEvent("leave:1:1"))).isEmpty();
    }

    // ── 引擎形狀：extensionElements 的 key 與屬性存法 ───────────────

    @Test
    @DisplayName("引擎形狀：extensionElements 的 key 是 local name、屬性是無前綴 key")
    void engineStoresAccessUnderLocalName() throws Exception {
        BpmnModel model = load(LEAVE);
        var task = model.getMainProcess().getFlowElement("applicantRevision", true);

        // 這條不是測 resolver，是測「resolver 依賴的引擎行為」。
        // 若哪天它紅了，resolver 也會紅（兩者成組），但這條的訊息直接指出
        // 是引擎換了形狀，而不是「權限解析錯了」。
        List<ExtensionElement> access = task.getExtensionElements().get("access");
        assertThat(access)
                .as("Flowable 8 以 local name 存放 extension element（無 namespace 前綴）")
                .isNotNull()
                .hasSize(1);
        assertThat(access.get(0).getAttributeValue(null, "mode")).isEqualTo("fill");
        assertThat(access.get(0).getAttributeValue(null, "default")).isEqualTo("readonly");
        assertThat(access.get(0).getChildElements().get("field"))
                .as("子元素同樣是 local name")
                .hasSize(1);
    }

    // ── fail-closed：無 access 的 userTask ──────────────────────────

    @Test
    @DisplayName("無 access 的 userTask → view／readonly（fail-closed）")
    void userTaskWithoutAccessFailsClosed() {
        FormAccessResolver resolver = resolverFor(parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/test">
                  <process id="noaccess" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="t1"/>
                    <userTask id="t1" name="無 access" flowable:formKey="f"/>
                  </process>
                </definitions>
                """));

        assertThat(resolver.profileForTask("leave:1:1", "t1"))
                .as("沒有權限設定時必須看得到、改不了")
                .containsEntry("mode", "view")
                .containsEntry("default", "readonly");
    }

    // ── fail-closed：壞掉的 access（mode／field／required 形狀非法）──

    @Test
    @DisplayName("mode 非法 → view／readonly（fail-closed），不採信填寫")
    void illegalModeFailsClosed() {
        assertThat(resolverFor(parse(accessTask("mode=\"design\" default=\"editable\"")))
                .profileForTask("leave:1:1", "t1"))
                .containsEntry("mode", "view")
                .containsEntry("default", "readonly");
    }

    @Test
    @DisplayName("default 非法 → view／readonly（fail-closed）")
    void illegalDefaultFailsClosed() {
        assertThat(resolverFor(parse(accessTask("mode=\"fill\" default=\"whatever\"")))
                .profileForTask("leave:1:1", "t1"))
                .containsEntry("mode", "view")
                .containsEntry("default", "readonly");
    }

    @Test
    @DisplayName("設計器未填完的空列（缺 key／無覆寫屬性）→ 略過該列，其餘照用")
    void incompleteRowsAreSkipped() {
        Map<String, Object> profile = resolverFor(parse(accessWithField(
                "mode=\"fill\" default=\"readonly\"",
                "<uniform:field/>"
                        + "<uniform:field key=\"dateRange\"/>"
                        + "<uniform:field key=\"reason\" state=\"editable\"/>")))
                .profileForTask("leave:1:1", "t1");

        assertThat(profile)
                .as("空列不是授權語句，略過不影響 mode；略過也不會授予任何權限")
                .containsEntry("mode", "fill")
                .containsEntry("default", "readonly");
        assertThat(fields(profile))
                .as("只有真正帶覆寫屬性的列生效")
                .containsOnlyKeys("reason");
        assertThat(fields(profile).get("reason")).isEqualTo(Map.of("state", "editable"));
    }

    @Test
    @DisplayName("field 有 key 但屬性非法（state 非三態／required 非布林）→ 整份 fail-closed")
    void illegalFieldShapesFailClosed() {
        assertThat(resolverFor(parse(accessWithField("mode=\"fill\" default=\"readonly\"",
                "<uniform:field key=\"reason\" state=\"writable\"/>")))
                .profileForTask("leave:1:1", "t1"))
                .as("state 非法不得靜默降級成 default")
                .containsEntry("mode", "view");

        assertThat(resolverFor(parse(accessWithField("mode=\"fill\" default=\"readonly\"",
                "<uniform:field key=\"reason\" state=\"editable\" required=\"yes\"/>")))
                .profileForTask("leave:1:1", "t1"))
                .as("required 非布林")
                .containsEntry("mode", "view");
    }

    @Test
    @DisplayName("startEvent 的 access 壞掉 → 仍是 fail-closed（不得退化成 fill／editable）")
    void brokenStartAccessStillFailsClosed() {
        FormAccessResolver resolver = resolverFor(parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:uniform="http://bpm.com/uniform"
                             targetNamespace="http://bpm.com/test">
                  <process id="badstart" isExecutable="true">
                    <startEvent id="start" flowable:formKey="f">
                      <extensionElements>
                        <uniform:access mode="fill" default="editable">
                          <uniform:field key="reason" state="nope"/>
                        </uniform:access>
                      </extensionElements>
                    </startEvent>
                  </process>
                </definitions>
                """));

        assertThat(resolver.profileForStartEvent("leave:1:1"))
                .as("壞掉的授權設定不可以退化成「新案件可填」")
                .containsEntry("mode", "view")
                .containsEntry("default", "readonly");
    }

    // ── 找不到節點／模型 → fail-closed ──────────────────────────────

    @Test
    @DisplayName("找不到 userTask／BpmnModel → view／readonly（fail-closed）")
    void missingElementsFailClosed() throws Exception {
        FormAccessResolver resolver = resolverFor(load(LEAVE));

        assertThat(resolver.profileForTask("leave:1:1", "no-such-task"))
                .containsEntry("mode", "view")
                .containsEntry("default", "readonly");
        assertThat(resolver.profileForTask(null, "managerReview"))
                .containsEntry("mode", "view");
        assertThat(resolver.profileForTask("missing:1:1", "managerReview"))
                .containsEntry("mode", "view");
        assertThat(resolver.profileForStartEvent("missing:1:1"))
                .containsEntry("mode", "view")
                .containsEntry("default", "readonly");
    }

    // ── field 的 required 與 state 缺席（access-profile schema 允許）──

    @Test
    @DisplayName("field 只帶 required（state 缺席）→ 不編 state、保留 required")
    void fieldWithRequiredOnlyIsAccepted() {
        FormAccessResolver resolver = resolverFor(parse(accessWithField(
                "mode=\"fill\" default=\"readonly\"",
                "<uniform:field key=\"reason\" required=\"true\"/>")));

        assertThat(fields(resolver.profileForTask("leave:1:1", "t1")))
                .as("state 缺席套用 profile 的 default，不得被當成 hidden 或編一個值")
                .containsEntry("reason", Map.of("required", true));
    }

    // ── 每次回傳獨立物件（共用可變 map 是靜默的權限提升）────────────

    @Test
    @DisplayName("fallback profile 每次都是新物件，改一個不會污染下一個")
    void fallbackProfilesAreIndependent() {
        FormAccessResolver resolver = resolverFor(parse(accessTask("")));

        Map<String, Object> first = resolver.profileForTask("leave:1:1", "t1");
        assertThat(first).containsEntry("mode", "view");
        first.put("mode", "fill");

        assertThat(resolver.profileForTask("leave:1:1", "t1"))
                .as("共用同一個 Map 實例等於讓任何呼叫端改到全應用程式的 fail-closed 預設")
                .containsEntry("mode", "view");
    }

    // ── 內嵌 BPMN 小工具 ────────────────────────────────────────────

    private static String accessTask(String accessAttributes) {
        return accessWithField(accessAttributes, "");
    }

    private static String accessWithField(String accessAttributes, String fieldXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:uniform="http://bpm.com/uniform"
                             targetNamespace="http://bpm.com/test">
                  <process id="probe" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="t1"/>
                    <userTask id="t1" name="探測" flowable:formKey="f">
                      <extensionElements>
                        <uniform:access %s>%s</uniform:access>
                      </extensionElements>
                    </userTask>
                  </process>
                </definitions>
                """.formatted(accessAttributes, fieldXml);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Map<String, Object> profile) {
        return (Map<String, Object>) profile.get("fields");
    }
}
