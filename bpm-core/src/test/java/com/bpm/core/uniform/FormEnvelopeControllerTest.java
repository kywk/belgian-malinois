package com.bpm.core.uniform;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import com.bpm.core.support.UniformTestDsl;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * uni-form Envelope 端點（計畫 §五 A1／A2／A3／A4）的真實 HTTP 整合測試。
 *
 * <h2>為什麼走真實 HTTP</h2>
 *
 * <p>與 {@code FormDataAuthorizationTest} 同一理由：MockMvc 不做 error dispatch，
 * {@code ResponseStatusException} 的 404／400 是容器轉出來的。用 MockMvc 寫，
 * 「守衛沒接線」與「正確拒絕」都是同一個空白狀態。
 *
 * <h2>用 seed 的資料走完整條鏈</h2>
 *
 * <p>案件、表單定義、BPMN Access Profile 全部用 V7／seed 的真實資料 ——
 * 這條測試證明的是「DSL seed ＋ BPMN {@code uniform:access} ＋ 投影」
 * 真的能串起來，而不只是服務層單獨正確。
 */
class FormEnvelopeControllerTest extends IntegrationTestBase {

    private static final String APPLICANT = "user001";
    private static final String MANAGER = "mgr001";
    private static final String OUTSIDER = "outsider001";
    private static final String LEAVE = "leave-approval";
    private static final String LEAVE_FORM_KEY = "leave-request";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    // ── HTTP 小工具 ─────────────────────────────────────────────────

    private HttpResponse<String> send(String method, String path, String userId, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> res) {
        return objectMapper.readTree(res.body());
    }

    // ── 情境小工具 ──────────────────────────────────────────────────

    /** 以 formData 啟動一個請假案件（真實 seed 的 leave-request DSL）。 */
    private String startWithFormData(String reason) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("processDefinitionKey", LEAVE);
        payload.put("formData", Map.of("formDefinitionId", LEAVE_FORM_KEY,
                "dataJson", objectMapper.writeValueAsString(Map.of(
                        "leaveType", "annual",
                        "dateRange", Map.of("start", "2026-10-01", "end", "2026-10-03"),
                        "reason", reason))));
        var res = send("POST", "/api/process-instances", APPLICANT,
                objectMapper.writeValueAsString(payload));
        assertThat(res.statusCode()).as("前置條件：啟動應成功，實際 " + res.body()).isEqualTo(200);
        return json(res).get("processInstanceId").asText();
    }

    private Task currentTask(String pid) {
        return taskService.createTaskQuery().processInstanceId(pid).singleResult();
    }

    // ── ① GET /api/tasks/{taskId}/form ──────────────────────────────

    @Test
    @DisplayName("主管審核的任務表單：mode=view、全 readonly、data 是最新一列、有 schemaRef.hash")
    void reviewerSeesViewEnvelopeWithAllReadonlyStates() throws Exception {
        String marker = "投影測試事由-" + System.nanoTime();
        String pid = startWithFormData(marker);
        Task managerTask = currentTask(pid);

        var res = send("GET", "/api/tasks/" + managerTask.getId() + "/form", MANAGER, null);

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        JsonNode envelope = json(res);
        assertThat(envelope.get("mode").asText()).isEqualTo("view");
        assertThat(envelope.get("frame").asText()).isEqualTo("form");
        assertThat(envelope.get("data").get("reason").asText()).isEqualTo(marker);
        assertThat(envelope.get("meta").get("states").get("reason").asText()).isEqualTo("readonly");
        assertThat(envelope.get("meta").get("states").get("leaveType").asText()).isEqualTo("readonly");
        assertThat(envelope.get("schemaRef").get("key").asText()).isEqualTo(LEAVE_FORM_KEY);
        assertThat(envelope.get("schemaRef").get("version").asInt()).isEqualTo(1);
        assertThat(envelope.get("schemaRef").get("hash").asText()).startsWith("sha256:");
        assertThat(envelope.has("schema")).as("includeSchema=true：schema 本體必須在").isTrue();
    }

    @Test
    @DisplayName("申請者補件的任務表單：mode=fill、只有 reason editable")
    void applicantRevisionSeesFillEnvelopeWithReasonEditable() throws Exception {
        String pid = startWithFormData("原始事由");
        Task managerTask = currentTask(pid);
        taskService.complete(managerTask.getId(), Map.of("approved", false, "rejected", false));
        Task revision = currentTask(pid);

        var res = send("GET", "/api/tasks/" + revision.getId() + "/form", APPLICANT, null);

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        JsonNode envelope = json(res);
        assertThat(envelope.get("mode").asText()).isEqualTo("fill");
        assertThat(envelope.get("meta").get("states").get("reason").asText()).isEqualTo("editable");
        assertThat(envelope.get("meta").get("states").get("leaveType").asText()).isEqualTo("readonly");
    }

    @Test
    @DisplayName("非關係人讀任務表單 → 404（讀端政策不因新端點放寬）")
    void outsiderCannotReadTaskForm() throws Exception {
        String pid = startWithFormData("x");
        Task managerTask = currentTask(pid);

        var res = send("GET", "/api/tasks/" + managerTask.getId() + "/form", OUTSIDER, null);

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).doesNotContain("x");
    }

    @Test
    @DisplayName("不存在的 taskId → 404")
    void unknownTaskIsNotFound() throws Exception {
        assertThat(send("GET", "/api/tasks/no-such-task/form", APPLICANT, null).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("沒有 formKey 的任務 → 404，訊息指名『不是動態表單任務』")
    void taskWithoutFormKeyIsNotFound() throws Exception {
        String key = "envelope-noform-" + System.nanoTime();
        deploy(key, """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="t"/>
                    <userTask id="t" name="無表單關卡" flowable:assignee="user001"/>
                    <sequenceFlow id="f1" sourceRef="t" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """);
        String pid = runtimeService.startProcessInstanceByKey(key, Map.of("initiator", APPLICANT)).getId();

        var res = send("GET", "/api/tasks/" + currentTask(pid).getId() + "/form", APPLICANT, null);

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("不是動態表單任務");
    }

    @Test
    @DisplayName("external: 前綴的任務（外部表單）→ 404，訊息指名")
    void externalFormKeyIsNotFound() throws Exception {
        String key = "envelope-external-" + System.nanoTime();
        deploy(key, """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="t"/>
                    <userTask id="t" name="外部表單關卡" flowable:assignee="user001"
                              flowable:formKey="external:erp-form"/>
                    <sequenceFlow id="f1" sourceRef="t" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """);
        String pid = runtimeService.startProcessInstanceByKey(key, Map.of("initiator", APPLICANT)).getId();

        var res = send("GET", "/api/tasks/" + currentTask(pid).getId() + "/form", APPLICANT, null);

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("不是動態表單任務");
    }

    private void deploy(String key, String template) {
        repositoryService.createDeployment()
                .name(key)
                .addString(key + ".bpmn20.xml", template.formatted(key))
                .deploy();
    }

    // ── ② GET /api/process-definitions/{key}/start-form ─────────────

    @Test
    @DisplayName("起始表單：mode=fill、default 不進 envelope 但 states 全 editable、data 空")
    void startFormIsFillEditableWithEmptyData() throws Exception {
        var res = send("GET", "/api/process-definitions/" + LEAVE + "/start-form", APPLICANT, null);

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        JsonNode envelope = json(res);
        assertThat(envelope.get("mode").asText()).isEqualTo("fill");
        assertThat(envelope.get("data").isEmpty())
                .as("起始表單沒有案件，data 必須是空物件").isTrue();
        assertThat(envelope.get("meta").get("states").get("reason").asText()).isEqualTo("editable");
        assertThat(envelope.get("meta").get("states").get("leaveType").asText()).isEqualTo("editable");
        assertThat(envelope.get("schemaRef").get("key").asText()).isEqualTo(LEAVE_FORM_KEY);
    }

    @Test
    @DisplayName("不存在的流程 key → 404")
    void startFormOfUnknownProcessIsNotFound() throws Exception {
        assertThat(send("GET", "/api/process-definitions/no-such-process/start-form",
                APPLICANT, null).statusCode()).isEqualTo(404);
    }

    // ── ③ GET /api/process-definitions 的 startFormKey ──────────────

    @Test
    @DisplayName("流程定義清單帶 startFormKey（沒有的流程是 null）")
    void processDefinitionListCarriesStartFormKey() throws Exception {
        var res = send("GET", "/api/process-definitions?latestVersion=true", APPLICANT, null);

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode leave = null;
        for (JsonNode item : json(res)) {
            if (LEAVE.equals(item.get("key").asText())) {
                leave = item;
            }
        }
        assertThat(leave).as("清單必須包含 leave-approval").isNotNull();
        assertThat(leave.get("startFormKey").asText()).isEqualTo(LEAVE_FORM_KEY);
    }

    // ── ④ POST /api/forms/preview ───────────────────────────────────

    @Test
    @DisplayName("預覽：合法 DSL → 200、fill、version=1、key 取自 DSL")
    void previewReturnsFillEnvelope() throws Exception {
        String dsl = UniformTestDsl.withKeys("preview-form", "note");
        var res = send("POST", "/api/forms/preview", MANAGER,
                objectMapper.writeValueAsString(Map.of("schemaJson", dsl)));

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        JsonNode envelope = json(res);
        assertThat(envelope.get("mode").asText()).isEqualTo("fill");
        assertThat(envelope.get("schemaRef").get("key").asText()).isEqualTo("preview-form");
        assertThat(envelope.get("schemaRef").get("version").asInt()).isEqualTo(1);
        assertThat(envelope.get("meta").get("states").get("note").asText()).isEqualTo("editable");
        assertThat(envelope.get("data").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("預覽：不是合法 DSL → 400（零副作用：不建立任何定義）")
    void previewRejectsInvalidDsl() throws Exception {
        var res = send("POST", "/api/forms/preview", MANAGER,
                objectMapper.writeValueAsString(Map.of(
                        "schemaJson", "{\"fields\":[{\"id\":\"legacy\"}]}")));

        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(400);
        assertThat(res.body()).contains("schemaJson");
    }

    @Test
    @DisplayName("預覽：沒有 bpm:form:design 的人 → 403（沿用 POST /api/forms/** 的規則）")
    void previewRequiresFormDesignPermission() throws Exception {
        var res = send("POST", "/api/forms/preview", APPLICANT,
                objectMapper.writeValueAsString(Map.of(
                        "schemaJson", UniformTestDsl.minimal("preview-denied"))));

        assertThat(res.statusCode()).isEqualTo(403);
    }
}
