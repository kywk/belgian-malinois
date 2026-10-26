package com.bpm.core.acceptance;

import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 專用 Maven profile 才執行；每次使用全新的 Testcontainers 資料庫。 */
@TestPropertySource(properties = {
        "bpm.security.jwt.dev-secret=bpm-dev-jwt-secret-do-not-use-in-production",
        "bpm.security.gateway.enabled=false"
})
class BrowserE2eIT extends IntegrationTestBase {
    private static final Path RUNTIME = runtimeDirectory();
    // 真實通知只投遞至本次測試的 SMTP sink，不接觸本機或外部郵件服務。
    private static final GenericContainer<?> SMTP =
            new GenericContainer<>(DockerImageName.parse("mailhog/mailhog:v1.0.1"))
                    .withExposedPorts(1025);

    static {
        SMTP.start();
    }

    private static Path runtimeDirectory() {
        try {
            Path root = Path.of("target", "browser-e2e").toAbsolutePath();
            Files.createDirectories(root);
            return Files.createTempDirectory(root, "run-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("無法建立隔離的 browser E2E 工作目錄", e);
        }
    }

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.mail.host", SMTP::getHost);
        properties.add("spring.mail.port", () -> SMTP.getMappedPort(1025));
        properties.add("spring.mail.properties.mail.smtp.auth", () -> false);
        properties.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        properties.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
        properties.add("bpm.upload.dir", () -> RUNTIME.resolve("uploads").toString());
        properties.add("bpm.bpmn-definitions-dir", () -> RUNTIME.resolve("bpmn-definitions").toString());
        properties.add("bpm.bpmn.git.repo-dir", () -> RUNTIME.resolve("bpmn-definitions").toString());
    }

    @Test
    void browserWorkflow() throws Exception {
        Path frontend = Path.of(System.getProperty("e2e.frontend.dir", "../bpm-frontend")).toAbsolutePath().normalize();
        int uiPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            uiPort = socket.getLocalPort();
        }
        ProcessBuilder builder = new ProcessBuilder("npm", "run", "test:e2e")
                .directory(frontend.toFile()).redirectErrorStream(true);
        builder.environment().put("E2E_API_URL", "http://127.0.0.1:" + SERVLET_PORT);
        builder.environment().put("E2E_UI_PORT", Integer.toString(uiPort));
        Process process = builder.start();
        // 子程序不可直接寫 native stdout，否則會破壞 Failsafe 的 fork 通訊協定。
        Thread output = new Thread(() -> {
            try (var reader = process.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) System.out.println(line);
            } catch (java.io.IOException e) {
                System.out.println("[browser-e2e] 子程序輸出讀取結束：" + e.getMessage());
            }
        }, "browser-e2e-output");
        output.setDaemon(true);
        output.start();
        Set<ProcessHandle> children = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        Thread shutdown = new Thread(() -> stop(process, children), "browser-e2e-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
            boolean completed = false;
            while (System.nanoTime() < deadline) {
                process.descendants().forEach(children::add);
                if (process.waitFor(1, TimeUnit.SECONDS)) {
                    completed = true;
                    break;
                }
            }
            if (completed) output.join(3000);
            assertThat(completed).as("Playwright 必須在 15 分鐘內完成").isTrue();
            assertThat(process.exitValue()).as("Playwright 結果；請檢查 bpm-frontend/playwright-report 與 test-results").isZero();
        } finally {
            stop(process, children);
            process.getInputStream().close();
            output.join(3000);
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
    }

    private static void stop(Process process, Set<ProcessHandle> children) {
        process.descendants().forEach(children::add);
        java.util.List<ProcessHandle> handles;
        synchronized (children) {
            handles = new java.util.ArrayList<>(children);
        }
        java.util.Collections.reverse(handles);
        handles.forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
        process.destroy();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            handles.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
