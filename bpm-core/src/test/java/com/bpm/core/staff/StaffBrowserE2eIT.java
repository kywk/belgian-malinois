package com.bpm.core.staff;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;



import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real Vue app + RS256 HTTP adapters + Flowable on isolated Testcontainers. */
class StaffBrowserE2eIT extends StaffIntegrationTest {
    static final int UI_PORT = freePort();
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired FormDefinitionRepository forms;
    @Autowired com.bpm.core.uniform.FormDefinitionValidator formValidator;
    static int freePort() { try (var socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); } catch (Exception e) { throw new IllegalStateException(e); } }
    static { PROVIDER.callbackUrl = "http://127.0.0.1:" + UI_PORT + "/"; }
    @Test void browserLoginTodoApproveAndRevoke() throws Exception {
        PROVIDER.denied.addAll(java.util.List.of(StaffPermissions.MANAGE, StaffPermissions.AUDIT,
                StaffPermissions.DESIGN, StaffPermissions.DEPLOY));
        FormDefinition form = new FormDefinition();
        String formKey = "staff-browser-" + java.util.UUID.randomUUID();
        form.setFormKey(formKey); form.setName("內勤契約驗證表"); form.setStatus("published");
        form.setSchemaJson("{\"$uniform\":\"1.0\",\"key\":\"" + formKey + "\",\"title\":{\"zh-TW\":\"內勤契約驗證表\"},\"requires\":{\"core\":\"^1.0\"},\"root\":[{\"id\":\"n_note\",\"type\":\"textarea\",\"key\":\"note\",\"label\":\"說明\"}]}");
        formValidator.validate(form.getSchemaJson(), formKey);
        forms.saveAndFlush(form);
        String first = newTask("W9000001", null, formKey);
        String second = newTask("W9000001", null, formKey);
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
        } finally {
            child.descendants().forEach(h -> { if (h.isAlive()) h.destroy(); });
            child.destroyForcibly();
        }
    }
}
