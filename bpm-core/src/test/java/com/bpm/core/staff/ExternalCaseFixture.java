package com.bpm.core.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.bpm.core.external.ApiKeyHasher;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.flowable.bpmn.model.*;
import org.flowable.engine.RepositoryService;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real machine HTTP client; neither the browser nor its page context receives the BFF key. */
class ExternalCaseFixture {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String COLLECTION = "/api/external/v1/process-instances";
    static final String REQUESTS = "/api/external/v1/requests/";
    final String serviceKey = UUID.randomUUID().toString();
    final long version = System.currentTimeMillis();
    final JdbcTemplate db;
    final FormDefinition form;
    final String definition;
    final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    ExternalCaseFixture(ApplicationContext ctx, int port) {
        this(ctx, port, false);
    }
    ExternalCaseFixture(ApplicationContext ctx, int port, boolean talent) {
        String workflow = talent ? "recruitment.credit" : "contract.staff-review";
        String domain = talent ? "recruitment" : "contract-fixture";
        base = "http://127.0.0.1:" + port;
        db = new JdbcTemplate(ctx.getBean("primaryDataSource", DataSource.class));
        var systems = ctx.getBean(ExternalSystemRepository.class);
        for (String name : List.of("agent-bff", "case-other")) {
            var sys = systems.findBySystemId(name).orElseGet(ExternalSystem::new);
            sys.setSystemId(name);
            sys.setSystemName(name);
            sys.setEnabled(true);
            sys.setApiKey(ctx.getBean(ApiKeyHasher.class).hash(serviceKey + name));
            sys.setAllowedActions("[\"start_process\",\"query_status\"]");
            sys.setAllowedProcessKeys("[\"bff-contract-review\"]");
            sys.setAllowedCandidateGroups("[]");
            sys.setAllowedWorkerTopics("[]");
            sys.setAllowOnBehalfOf(false);
            sys.setLastUsedAt(java.time.Instant.now());
            systems.saveAndFlush(sys);
        }
        form = new FormDefinition();
        String formKey = "case-" + UUID.randomUUID();
        form.setFormKey(formKey);
        form.setName("外部送件驗收");
        form.setStatus("published");
        form.setSchemaJson(JSON.writeValueAsString(Map.of("$uniform", "1.0", "key", formKey, "title",
                Map.of("zh-TW", "案件"), "requires", Map.of("core", "^1.0"), "root",
                List.of(Map.of("id", "n_note", "type", "textarea", "key", talent ? "submissionId" : "note", "label", "說明")))));
        ctx.getBean(FormDefinitionRepository.class).saveAndFlush(form);
        var nextForm = new FormDefinition();
        nextForm.setFormKey(formKey);
        nextForm.setName("新版");
        nextForm.setVersion(2);
        nextForm.setStatus("published");
        nextForm.setSchemaJson(form.getSchemaJson().replace("note", "vTwoField"));
        ctx.getBean(FormDefinitionRepository.class).saveAndFlush(nextForm);
        var repository = ctx.getBean(RepositoryService.class);
        definition = deploy(repository, formKey);
        // Deploy again before POST: an implementation using latest would start the wrong version.
        deploy(repository, formKey);
        db.update("""
                INSERT INTO bpm_case_binding (workflow_code,binding_version,caller_system,domain,workflow_role,
                definition_key,definition_id,form_id,form_version,routing_rule,approval_permission,enabled,approved_end,returned_node)
                VALUES (?,?,'agent-bff',?,'review','bff-contract-review',?,?,1,
                'review-team',?,1,'endApproved',?)
                """, workflow, version, domain, definition, form.getId(), StaffTestProvider.APPROVE, talent ? "review" : null);
    }
    String deploy(RepositoryService repository, String key) {
        var model = new BpmnModel();
        var process = new org.flowable.bpmn.model.Process();
        process.setId("bff-contract-review");
        var start = new StartEvent();
        start.setId("start");
        start.setFormKey(key);
        var task = new UserTask();
        task.setId("review");
        task.setFormKey(key);
        task.setName("內勤案件審核");
        task.setAssignee("${firstTaskAssignee}");
        var end = new EndEvent();
        end.setId("endApproved");
        process.addFlowElement(start);
        process.addFlowElement(task);
        process.addFlowElement(end);
        process.addFlowElement(new SequenceFlow("start", "review"));
        process.addFlowElement(new SequenceFlow("review", "endApproved"));
        model.addProcess(process);
        var deployed = repository.createDeployment().addBpmnModel("case.bpmn20.xml", model).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployed.getId()).singleResult().getId();
    }
    Map<String, Object> command() {
        String id = UUID.randomUUID().toString();
        return new LinkedHashMap<>(Map.of("requestId", id, "workflowCode", "contract.staff-review", "bindingVersion",
                version, "caseRef",
                Map.of("domain", "contract-fixture", "applicationId", "app-A", "submissionId", id, "workflowRole",
                        "review", "submissionRevision", 1),
                "actor",
                Map.of("type", "AGENT", "issuer", "https://agent.test", "subject", "shared-sub", "agentCode",
                        "AG000001"),
                "processDefinitionId", definition, "variables", Map.of(), "formData",
                Map.of("formDefinitionId", form.getId(), "formVersion", 1, "dataJson", "{\"note\":\"驗收\"}")));
    }
    HttpRequest post(Map<String, Object> command) {
        return req("agent-bff", COLLECTION, JSON.writeValueAsString(command));
    }
    HttpRequest req(String sys, String path, String body) {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                              .timeout(Duration.ofSeconds(60))
                              .header("X-System-Id", sys)
                              .header("X-API-Key", serviceKey + sys)
                              .header("X-Trace-Id", "fixture.trace");
        if (body != null)
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return builder.build();
    }
    HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
    JsonNode expect(HttpRequest req, int status) {
        var response = send(req);
        assertThat(response.statusCode()).as("%s %s", req.uri().getPath(), response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
    JsonNode get(String path) {
        return expect(req("agent-bff", path, null), 200);
    }
    String start() {
        return expect(post(command()), 201).get("processInstanceId").asString();
    }
}
