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
    static { PROVIDER.callbackUrl = "http://127.0.0.1:" + STAFF_PORT + "/"; }
    @TestConfiguration(proxyBeanMethods = false)
    static class CutoverSources {
        @Bean DynamicPropertyRegistrar cutoverProperties() {
            return r -> {
                r.add("bpm.webhook.hmac-secret", () -> HOOK_SECRET);
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
        tasks.complete(task.getId(), Map.of("approved", false, "rejected", false,
                "_applicantReturnReason", "請補上最新月份的報告"));
        return f.get(ExternalCaseFixture.COLLECTION + "/" + pid);
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
