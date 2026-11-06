package com.bpm.core.staff;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real Vue app + RS256 HTTP adapters + Flowable on isolated Testcontainers. */
class StaffBrowserE2eIT extends StaffIntegrationTest {
    static final int UI_PORT = freePort();
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    static int freePort() { try (var socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); } catch (Exception e) { throw new IllegalStateException(e); } }
    static { PROVIDER.callbackUrl = "http://127.0.0.1:" + UI_PORT + "/"; }
    @Test void browserLoginTodoApproveAndRevoke() throws Exception {
        var external = caseFixture();
        approveInBrowser(external, external.start());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "agent.case.classpath", matches = ".+")
    void agentOutboxThroughStaffApproval() throws Exception {
        var external = caseFixture();
        var command = external.command();
        var ready = java.nio.file.Files.createTempDirectory(Path.of("target"), "agent-case-").resolve("ready").toAbsolutePath();
        String classpath = java.nio.file.Files.readString(Path.of(System.getProperty("agent.case.classpath"))).strip();
        var logFile = ready.resolveSibling("agent.log");
        var agent = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, "com.nanshan.agentportalservice.module.workflowcase.AgentCaseBrowserBridge", ready.toString())
                .redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        try (var pipe = agent.outputWriter()) {
            pipe.write(ExternalCaseFixture.JSON.writeValueAsString(java.util.Map.of("base", external.base,
                    "key", external.serviceKey + "agent-bff", "command", command)));
            pipe.newLine();
            pipe.flush();
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
            while (!java.nio.file.Files.exists(ready) && agent.isAlive() && System.nanoTime() < deadline)
                Thread.sleep(200);
            assertThat(ready).as("BFF delivered the owning service's saved submission").exists();
            String process = java.nio.file.Files.readString(ready);
            String task = tasks.createTaskQuery().processInstanceId(process).singleResult().getId();
            for (var probe : java.util.Map.of("/api/tasks/" + task, "PUT", "/api/deployments", "POST",
                    "/api/admin/notify-configs", "GET").entrySet()) {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(external.base + probe.getKey()))
                        .header("X-System-Id", "agent-bff").header("X-API-Key", external.serviceKey + "agent-bff")
                        .method(probe.getValue(), java.net.http.HttpRequest.BodyPublishers.noBody()).build();
                assertThat(external.send(request).statusCode()).isIn(401, 403);
            }
            assertThat(tasks.createTaskQuery().taskId(task).count()).isOne();
            assertThat(runtime.getVariable(process, "initiator")).isEqualTo("system:agent-bff");
            var row = external.db.queryForMap("SELECT actor_json, trace_id FROM bpm_case_request WHERE request_id=? AND process_id=?",
                    command.get("requestId"), process);
            assertThat(row.get("trace_id")).isEqualTo("case.contract.trace");
            assertThat(row.get("actor_json").toString()).contains("AG000001", "shared-sub");
            approveInBrowser(external, process);
            pipe.write("approved");
            pipe.newLine();
            pipe.flush();
            assertThat(agent.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(agent.exitValue()).isZero();
        } finally {
            agent.destroyForcibly();
            if (java.nio.file.Files.exists(logFile)) {
                java.nio.file.Files.readAllLines(logFile).forEach(System.out::println);
                java.nio.file.Files.delete(logFile);
            }
            java.nio.file.Files.deleteIfExists(ready);
            java.nio.file.Files.deleteIfExists(ready.getParent());
        }
    }

    private void approveInBrowser(ExternalCaseFixture external, String processId) throws Exception {
        PROVIDER.denied.addAll(java.util.List.of(StaffPermissions.MANAGE, StaffPermissions.AUDIT,
                StaffPermissions.DESIGN, StaffPermissions.DEPLOY));
        String first = tasks.createTaskQuery().processInstanceId(processId).singleResult().getId();
        String second = newTask("W9000001", null, external.form.getFormKey());
        assertThat(external.get(ExternalCaseFixture.COLLECTION + "/" + processId).get("reviewStatus").asString()).isEqualTo("RUNNING");
        var approvedTask = tasks.createTaskQuery().taskId(first).singleResult();
        approvedTask.setName("內勤契約簽核－成功"); tasks.saveTask(approvedTask);
        var revokedTask = tasks.createTaskQuery().taskId(second).singleResult();
        revokedTask.setName("內勤契約簽核－撤權"); tasks.saveTask(revokedTask);
        ProcessBuilder builder = new ProcessBuilder("npm", "exec", "--", "playwright", "test", "--config=playwright.staff.config.js")
                .directory(Path.of("../bpm-frontend").toFile()).redirectErrorStream(true);
        builder.environment().put("E2E_API_URL", "http://127.0.0.1:" + port);
        builder.environment().put("E2E_UI_PORT", Integer.toString(UI_PORT));
        builder.environment().put("E2E_STAFF_PROVIDER", PROVIDER.url());
        builder.environment().put("E2E_STAFF_TASK", first);
        builder.environment().put("E2E_STAFF_REVOKED_TASK", second);
        Process child = builder.start();
        Thread output = new Thread(() -> { try (var reader = child.inputReader()) {
            String line; while ((line = reader.readLine()) != null) System.out.println(line);
        } catch (Exception ignored) {} }); output.setDaemon(true); output.start();
        try {
            assertThat(child.waitFor(5, TimeUnit.MINUTES)).as("staff Playwright timeout").isTrue();
            output.join(3000);
            assertThat(child.exitValue()).isZero();
            assertThat(tasks.createTaskQuery().taskId(first).count()).isZero();
            assertThat(tasks.createTaskQuery().taskId(second).count()).isEqualTo(1);
            var status = external.get(ExternalCaseFixture.COLLECTION + "/" + processId);
            assertThat(status.get("outcome").asString()).isEqualTo("APPROVED");
            assertThat(status.get("sourceRevision").asLong()).isEqualTo(2);
        } finally {
            child.descendants().forEach(h -> { if (h.isAlive()) h.destroy(); });
            child.destroyForcibly();
        }
    }
}
