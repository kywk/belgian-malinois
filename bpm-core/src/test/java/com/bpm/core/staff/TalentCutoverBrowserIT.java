package com.bpm.core.staff;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flowable.engine.HistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Boots both portal UIs, the agent servlet and the owner against the real BPM/Flowable HTTP server. */
@org.springframework.test.context.TestPropertySource(properties = {
        "bpm.external.cases.talent-workflow-issuers[recruitment.interview]=https://talent.test",
        "bpm.staff.approval-permissions.recruitment-interview-review=" + StaffTestProvider.INTERVIEW_APPROVE})
@Import(TalentCutoverBrowserIT.CutoverSources.class)
class TalentCutoverBrowserIT extends StaffIntegrationTest {
    private static final String HOOK_SECRET = UUID.randomUUID().toString();

    private static int freePort() {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static final int STAFF_PORT = freePort();
    private static final int EVIDENCE_PORT = freePort();
    static { PROVIDER.callbackUrl = "http://127.0.0.1:" + STAFF_PORT + "/"; }
    @TestConfiguration(proxyBeanMethods = false)
    static class CutoverSources {
        @Bean DynamicPropertyRegistrar cutoverProperties() {
            return r -> {
                r.add("bpm.webhook.hmac-secret", () -> HOOK_SECRET);
                r.add("bpm.staff.evidence.owner-url", () -> "http://127.0.0.1:" + EVIDENCE_PORT);
                r.add("bpm.staff.evidence.owner-key", () -> HOOK_SECRET);
                r.add("bpm.external.mock-enabled", () -> false);
                r.add("bpm.external.org-service-url", PROVIDER::url);
                r.add("bpm.external.perm-service-url", PROVIDER::url);
                r.add("bpm.uniform.options.source", () -> "disabled");
            };
        }
    }

    @Test void talentSubmitStaffApproveAndManagerReadDuringDrain() throws Exception {
        runCutover("CREDIT_CHECK", "recruitment.credit", "bff-contract-review", "talent-cutover.log");
    }

    @Test void interviewSubmitStaffApproveAndManagerRead() throws Exception {
        runCutover("INTERVIEW", "recruitment.interview", "recruitment-interview-review", "interview-cutover.log");
    }

    @Test void creditReturnRoutesToSupplement() {
        var result = completeCreditReturn();
        assertThat(result.path("reviewStatus").asString()).isEqualTo("RETURNED");
    }

    @Test void mappedReturnCarriesDecisionReason() {
        var result = completeCreditReturn();
        assertThat(result.path("returnReason").asString("")).isEqualTo("請補上最新月份的報告");
    }

    private tools.jackson.databind.JsonNode completeCreditReturn() {
        var review = startCreditReview();
        var task = review.task();
        tasks.complete(task.getId(), Map.of("approved", false, "rejected", false,
                "_applicantReturnReason", "請補上最新月份的報告"));
        return review.fixture().get(ExternalCaseFixture.COLLECTION + "/" + task.getProcessInstanceId());
    }

    private record Review(ExternalCaseFixture fixture, org.flowable.task.api.Task task, String submission) {}

    private Review startCreditReview() {
        var f = new ExternalCaseFixture(context, apiPort, "recruitment.credit", null, UUID.randomUUID().toString(), true);
        var command = f.command();
        command.put("workflowCode", "recruitment.credit");
        command.put("caseRef", Map.of("domain", "recruitment", "applicationId", "app-A",
                "submissionId", command.get("requestId"), "workflowRole", "review", "submissionRevision", 1));
        var actor = new java.util.HashMap<String,Object>(Map.of("type","TALENT", "subject","candidate", "issuer","https://talent.test"));
        actor.put("agentCode", null);
        command.put("actor", actor);
        command.put("formData", Map.of("formDefinitionId", f.form.getId(), "formVersion",1, "dataJson","{}"));
        var receipt = f.expect(f.post(command), 201);
        var pid = receipt.path("processInstanceId").asString();
        var task = tasks.createTaskQuery().processInstanceId(pid).singleResult();
        return new Review(f, task, command.get("requestId").toString());
    }

    @Test void onlyCurrentAssigneeReadsFrozenEvidence() throws Exception {
        var review = startCreditReview();
        var task = review.task();
        String taskPath = "/api/tasks/" + task.getId() + "/review-evidence";
        String ownerPath = "/internal/staff-review/v1/applications/app-A/submissions/" + review.submission()
                + "/revisions/1/instances/" + task.getProcessInstanceId();
        String document = UUID.randomUUID().toString();
        byte[] bytes = "%PDF-1.7 fixture %%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var metadata = Map.of("applicationId", "app-A", "applicationType", "CREDIT_CHECK", "submissionId", review.submission(),
                "revision", 1, "documents", java.util.List.of(Map.of("documentId", document, "version", "1",
                "contentHash", hash, "purpose", "CREDIT_REPORT", "fileName", "credit.pdf", "contentType", "application/pdf",
                "sizeBytes", bytes.length, "source", "MOCK")));
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var documentType = new java.util.concurrent.atomic.AtomicReference<>("application/pdf");
        var documentBytes = new java.util.concurrent.atomic.AtomicReference<>(bytes);
        var owner = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", EVIDENCE_PORT), 0);
        owner.createContext(ownerPath, request -> {
            try (request) {
                var headers = request.getRequestHeaders();
                if (!"bpm-staff-review".equals(headers.getFirst("X-Service-Id")) ||
                        !HOOK_SECRET.equals(headers.getFirst("X-Service-Key")) || headers.containsKey("Authorization")) {
                    request.sendResponseHeaders(401, -1); return;
                }
                boolean download = request.getRequestURI().getPath().equals(ownerPath + "/documents/" + document);
                if (!download && !request.getRequestURI().getPath().equals(ownerPath)) {
                    request.sendResponseHeaders(404, -1); return;
                }
                hits.incrementAndGet();
                byte[] body = download ? documentBytes.get() : ExternalCaseFixture.JSON.writeValueAsBytes(metadata);
                request.getResponseHeaders().add("Content-Type", download ? documentType.get() : "application/json");
                request.sendResponseHeaders(200, body.length);
                request.getResponseBody().write(body);
            }
        });
        owner.start();
        try {
            String reviewer = "Bearer " + PROVIDER.token("login-alias");
            String other = "Bearer " + PROVIDER.token("W9000002");
            String content = taskPath + "/documents/" + document;
            for (String path : java.util.List.of(taskPath, content)) {
                mockMvc.perform(get(path).header("Authorization", other)).andExpect(status().isNotFound());
                for (var pair : Map.of("applicationId","app-B","submissionId","old","revision","2").entrySet()) {
                    mockMvc.perform(get(path).header("Authorization", reviewer).param(pair.getKey(), pair.getValue()))
                            .andExpect(status().isNotFound());
                }
            }
            assertThat(hits).hasValue(0);
            mockMvc.perform(get(taskPath).header("Authorization", reviewer)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.submissionId").value(review.submission()))
                    .andExpect(jsonPath("$.documents[0].contentHash").value(hash));
            mockMvc.perform(get(content).header("Authorization", reviewer)).andExpect(status().isOk())
                    .andExpect(content().bytes(bytes)).andExpect(header().string("Cache-Control", "no-store"));
            mockMvc.perform(get(taskPath + "/documents/" + UUID.randomUUID()).header("Authorization", reviewer))
                    .andExpect(status().isNotFound());
            documentType.set("text/html");
            mockMvc.perform(get(content).header("Authorization", reviewer)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("BPM_EVIDENCE_UNAVAILABLE"));
            documentType.set("application/pdf");
            documentBytes.set("%PDF-changed".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            mockMvc.perform(get(content).header("Authorization", reviewer)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("BPM_EVIDENCE_UNAVAILABLE"));
            documentBytes.set(bytes);
            PROVIDER.denied.add(StaffTestProvider.APPROVE);
            mockMvc.perform(get(content).header("Authorization", reviewer)).andExpect(status().isForbidden());
            PROVIDER.denied.remove(StaffTestProvider.APPROVE);
            tasks.delegateTask(task.getId(), "W9000002");
            mockMvc.perform(get(content).header("Authorization", other)).andExpect(status().isNotFound());
            tasks.resolveTask(task.getId());
            tasks.complete(task.getId(), Map.of("approved", true, "rejected", false));
            mockMvc.perform(get(taskPath).header("Authorization", reviewer)).andExpect(status().isNotFound());
            mockMvc.perform(get(content).header("Authorization", reviewer)).andExpect(status().isNotFound());
        } finally { owner.stop(0); }
    }

    private void runCutover(String type, String workflow, String processKey, String logName) throws Exception {
        PROVIDER.denied.addAll(java.util.List.of(StaffPermissions.MANAGE, StaffPermissions.DESIGN,
                StaffPermissions.AUDIT, StaffPermissions.DEPLOY));
        int relayPort = freePort();
        var external = new ExternalCaseFixture(context, apiPort, workflow,
                "http://localhost:" + relayPort + "/internal/bpm/v1/webhook");
        var log = Path.of("target", logName);
        var script = Path.of("../../recruitment/scripts/validation/talent-cutover-browser.py");
        var broker = context.getBean(AmqpAdmin.class);
        var queue = broker.declareQueue();
        String routingKey = "bpm.webhook." + processKey;
        broker.declareBinding(BindingBuilder.bind(queue).to(context.getBean("bpmExchange", TopicExchange.class))
                .with(routingKey));
        var child = new ProcessBuilder("python3", script.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var input = child.outputWriter()) {
                input.write(ExternalCaseFixture.JSON.writeValueAsString(Map.ofEntries(
                        Map.entry("base", external.base), Map.entry("key", external.serviceKey + "agent-bff"),
                        Map.entry("applicationType", type), Map.entry("workflow", workflow),
                        Map.entry("hook", HOOK_SECRET), Map.entry("binding", external.version),
                        Map.entry("definition", external.definition), Map.entry("form", external.form.getId()),
                        Map.entry("container", MSSQL.getContainerId()), Map.entry("port", MSSQL.getMappedPort(1433)),
                        Map.entry("staffPort", STAFF_PORT), Map.entry("relayPort", relayPort),
                        Map.entry("provider", PROVIDER.url()))));
            }
            assertThat(child.waitFor(6, TimeUnit.MINUTES)).as("browser cutover timed out").isTrue();
            assertThat(child.exitValue()).as("see %s", log.toAbsolutePath()).isZero();
            var key = Files.readAllLines(log).getLast();
            var process = context.getBean(HistoryService.class).createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(key).singleResult();
            assertThat(process.getEndTime()).isNotNull();
            assertThat(process.getProcessDefinitionId()).isEqualTo(external.definition);
            if ("INTERVIEW".equals(type)) {
                assertThat(UUID.fromString(external.definition).toString()).isEqualTo(external.definition);
            }
            var message = context.getBean(RabbitTemplate.class).receive(queue.getName(), 5000);
            assertThat(message).as("completion routed to %s", routingKey).isNotNull();
            assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo(routingKey);
            var payload = ExternalCaseFixture.JSON.readTree(message.getBody());
            assertThat(payload.path("event").asString()).isEqualTo("process.completed");
            assertThat(payload.path("processDefinitionKey").asString()).isEqualTo(processKey);
            assertThat(payload.path("businessKey").asString()).isEqualTo(key);
            assertThat(payload.path("processInstanceId").asString()).isEqualTo(process.getId());
            var submission = key.split("/")[2];
            var receipt = external.db.queryForMap("""
                    SELECT request_id, trace_id, workflow_code, definition_id, form_id, form_version, JSON_VALUE(actor_json, '$.type') AS actor_type,
                        JSON_VALUE(actor_json, '$.issuer') AS issuer, JSON_VALUE(actor_json, '$.subject') AS subject
                    FROM bpm_case_request WHERE system_id='agent-bff' AND business_key=? AND process_id=?
                    """, key, process.getId());
            assertThat(receipt).containsEntry("request_id", submission).containsEntry("trace_id", submission)
                    .containsEntry("workflow_code", workflow).containsEntry("definition_id", external.definition)
                    .containsEntry("form_id", external.form.getId()).containsEntry("form_version", 1)
                    .containsEntry("actor_type", "TALENT").containsEntry("issuer", "https://talent.test")
                    .containsEntry("subject", "talent-001");
            var status = external.get(ExternalCaseFixture.COLLECTION + "/" + process.getId());
            assertThat(status.get("outcome").asString()).isEqualTo("APPROVED");
            assertThat(status.get("sourceRevision").asLong()).isEqualTo(2);
            System.out.println("Chromium cutover: " + key + " process=" + process.getId()
                    + " definition=" + external.definition + " routingKey=" + routingKey);
        } finally {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
            broker.deleteQueue(queue.getName());
        }
    }
}
