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
    final String serviceKey;
    final long version = System.currentTimeMillis();
    final JdbcTemplate db;
    final FormDefinition form;
    final String definition;
    final String base;
    private final String processKey;
    private final boolean interview;
    private final boolean routedReview;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    ExternalCaseFixture(ApplicationContext ctx, int port) {
        this(ctx, port, false);
    }
    ExternalCaseFixture(ApplicationContext ctx, int port, boolean talent) {
        this(ctx, port, talent ? "recruitment.credit" : "contract.staff-review", null);
    }
    ExternalCaseFixture(ApplicationContext ctx, int port, String workflow, String completionHook) {
        this(ctx, port, workflow, completionHook, UUID.randomUUID().toString(), false);
    }
    ExternalCaseFixture(ApplicationContext ctx, int port, String workflow, String completionHook, String key, boolean sharedWave) {
        serviceKey = key;
        routedReview = sharedWave || completionHook != null;
        boolean talent = workflow.startsWith("recruitment.");
        interview = workflow.equals("recruitment.interview");
        processKey = interview ? "recruitment-interview-review" : "bff-contract-review";
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
            sys.setAllowedProcessKeys(JSON.writeValueAsString(sharedWave ? List.of("recruitment-interview-review", "bff-contract-review") : List.of(processKey)));
            sys.setAllowedCandidateGroups("[]");
            sys.setAllowedWorkerTopics("[]");
            sys.setAllowOnBehalfOf(false);
            sys.setLastUsedAt(java.time.Instant.now());
            systems.saveAndFlush(sys);
        }
        form = new FormDefinition();
        String formKey = (interview ? "interview-review-" : "case-") + UUID.randomUUID();
        form.setFormKey(formKey);
        form.setName(interview ? "面談送審單" : "外部送件驗收");
        form.setStatus("published");
        form.setSchemaJson(JSON.writeValueAsString(Map.of("$uniform", "1.0", "key", formKey, "title",
                Map.of("zh-TW", interview ? "面談送審單" : "案件"), "requires", Map.of("core", "^1.0"), "root",
                List.of(Map.of("id", "n_note", "type", "textarea", "key", talent ? "submissionId" : "note", "label", interview ? "面談送件編號" : "說明")))));
        ctx.getBean(FormDefinitionRepository.class).saveAndFlush(form);
        var nextForm = new FormDefinition();
        nextForm.setFormKey(formKey);
        nextForm.setName("新版");
        nextForm.setVersion(2);
        nextForm.setStatus("published");
        nextForm.setSchemaJson(form.getSchemaJson().replace("note", "vTwoField"));
        ctx.getBean(FormDefinitionRepository.class).saveAndFlush(nextForm);
        var repository = ctx.getBean(RepositoryService.class);
        definition = deploy(repository, formKey, completionHook);
        // Deploy again before POST: an implementation using latest would start the wrong version.
        deploy(repository, formKey);
        db.update("""
                INSERT INTO bpm_case_binding (workflow_code,binding_version,caller_system,domain,workflow_role,
                definition_key,definition_id,form_id,form_version,routing_rule,approval_permission,enabled,approved_end,returned_node,rejected_end)
                VALUES (?,?,'agent-bff',?,'review',?,?,?,1,
                'review-team',?,1,'endApproved',?,?)
                """, workflow, version, domain, processKey, definition, form.getId(),
                interview ? StaffTestProvider.INTERVIEW_APPROVE : StaffTestProvider.APPROVE,
                routedReview ? "endReturned" : talent ? "review" : null,
                routedReview ? "endRejected" : null);
    }
    String deploy(RepositoryService repository, String key) {
        return deploy(repository, key, null);
    }
    private String deploy(RepositoryService repository, String key, String completionHook) {
        var model = new BpmnModel();
        var process = new org.flowable.bpmn.model.Process();
        process.setId(processKey);
        if (completionHook != null) {
            var hooks = extension("webhooks");
            var hook = extension("webhook");
            hook.addAttribute(new ExtensionAttribute("event", "process.completed"));
            hook.addAttribute(new ExtensionAttribute("url", completionHook));
            hooks.addChildElement(hook);
            process.addExtensionElement(hooks);
        }
        var start = new StartEvent();
        start.setId("start");
        start.setFormKey(key);
        var task = new UserTask();
        task.setId("review");
        task.setFormKey(key);
        task.setName(interview ? "準增員面談審核" : "內勤案件審核");
        task.setAssignee("${firstTaskAssignee}");
        var end = new EndEvent();
        end.setId("endApproved");
        process.addFlowElement(start);
        process.addFlowElement(task);
        process.addFlowElement(end);
        process.addFlowElement(new SequenceFlow("start", "review"));
        if (!routedReview) {
            process.addFlowElement(new SequenceFlow("review", "endApproved"));
        } else {
            task.setName((interview ? "準增員面談審核" : "內勤案件審核・聯徵")
                    + " · ${_caseApplicationId} · 送件版本 ${_caseSubmissionRevision}");
            var gateway = new ExclusiveGateway();
            gateway.setId("decision");
            process.addFlowElement(gateway);
            process.addFlowElement(new SequenceFlow("review", "decision"));
            for (var route : Map.of("endApproved", "${approved && !rejected}",
                    "endReturned", "${!approved && !rejected}", "endRejected", "${rejected}").entrySet()) {
                if (!route.getKey().equals(end.getId())) {
                    var terminal = new EndEvent();
                    terminal.setId(route.getKey());
                    process.addFlowElement(terminal);
                }
                var flow = new SequenceFlow("decision", route.getKey());
                flow.setConditionExpression(route.getValue());
                process.addFlowElement(flow);
            }
        }
        model.addProcess(process);
        var deployed = repository.createDeployment().addBpmnModel("case.bpmn20.xml", model).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployed.getId()).singleResult().getId();
    }
    private static ExtensionElement extension(String name) {
        var element = new ExtensionElement();
        element.setNamespace("http://flowable.org/bpmn");
        element.setNamespacePrefix("flowable");
        element.setName(name);
        return element;
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
