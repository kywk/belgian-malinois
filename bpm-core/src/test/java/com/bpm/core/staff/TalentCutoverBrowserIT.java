package com.bpm.core.staff;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flowable.engine.HistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import static org.assertj.core.api.Assertions.assertThat;

/** Boots both portal UIs, the agent servlet and the owner against the real BPM/Flowable HTTP server. */
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
        PROVIDER.denied.addAll(java.util.List.of(StaffPermissions.MANAGE, StaffPermissions.DESIGN,
                StaffPermissions.AUDIT, StaffPermissions.DEPLOY));
        int relayPort = freePort();
        var external = new ExternalCaseFixture(context, apiPort, true,
                "http://localhost:" + relayPort + "/internal/bpm/v1/webhook");
        var log = Path.of("target", "talent-cutover.log");
        var script = Path.of("../../recruitment/scripts/validation/talent-cutover-browser.py");
        var child = new ProcessBuilder("python3", script.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var input = child.outputWriter()) {
                input.write(ExternalCaseFixture.JSON.writeValueAsString(Map.ofEntries(
                        Map.entry("base", external.base), Map.entry("key", external.serviceKey + "agent-bff"),
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
            var submission = key.split("/")[2];
            var receipt = external.db.queryForMap("""
                    SELECT request_id, trace_id, JSON_VALUE(actor_json, '$.type') AS actor_type,
                        JSON_VALUE(actor_json, '$.issuer') AS issuer, JSON_VALUE(actor_json, '$.subject') AS subject
                    FROM bpm_case_request WHERE system_id='agent-bff' AND business_key=? AND process_id=?
                    """, key, process.getId());
            assertThat(receipt).containsEntry("request_id", submission).containsEntry("trace_id", submission)
                    .containsEntry("actor_type", "TALENT").containsEntry("issuer", "https://talent.test")
                    .containsEntry("subject", "talent-001");
            var status = external.get(ExternalCaseFixture.COLLECTION + "/" + process.getId());
            assertThat(status.get("outcome").asString()).isEqualTo("APPROVED");
            assertThat(status.get("sourceRevision").asLong()).isEqualTo(2);
            System.out.println("Chromium cutover: " + key + " process=" + process.getId());
        } finally {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
        }
    }
}
