package com.bpm.core.staff;

import com.bpm.core.BpmCoreApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;

/** Test-classpath-only launcher. Shared SQL instance, dedicated BPM databases and Rabbit vhost. */
public final class LocalenvTalentWave {
    private static final Pattern RUN_ID = Pattern.compile("[0-9a-f]{32}");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: LocalenvTalentWave <ready-file>");
        var ready = Path.of(args[0]).toAbsolutePath().normalize();
        var issuer = "https://talent.localenv.invalid";
        var env = System.getenv();
        String expectedVhost = expectedVhost(ready);
        requireVhost(env.get("SPRING_RABBITMQ_VIRTUAL_HOST"), expectedVhost);
        for (String kind : List.of("CORE", "FORM", "AUDIT")) {
            String suffix = kind.equals("CORE") ? "" : kind + "_";
            String url = env.get("SPRING_DATASOURCE_" + suffix + "URL");
            if (url == null || !url.matches(
                    "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=flashlight_wave_" +
                    kind.toLowerCase(Locale.ROOT) + ";encrypt=false")) throw new IllegalStateException("Unexpected BPM database");
        }
        String callbackUrl = callbackUrl(env.get("WAVE_CALLBACK_PORT"), env.get("PORT_agent"));
        try (var staff = new StaffTestProvider()) {
            staff.callbackUrl = "http://127.0.0.1:" + env.get("E2E_UI_PORT") + "/";
            staff.denied.addAll(List.of(StaffPermissions.MANAGE, StaffPermissions.DESIGN, StaffPermissions.AUDIT, StaffPermissions.DEPLOY));
            String dlqReplayAdmin = "wave-dlq-admin";
            staff.dlqReplayAdmins.add(dlqReplayAdmin);
            var app = new SpringApplication(BpmCoreApplication.class);
            if (env.containsKey("WAVE_RACE_DIR")) app.addPrimarySources(Set.of(WaveRaceConfig.class));
            try (var ctx = app.run("--server.address=127.0.0.1", "--spring.profiles.active=test",
                    "--bpm.staff.enabled=true", "--bpm.security.gateway.enabled=false", "--bpm.security.jwt.dev-secret=",
                    "--bpm.security.jwt.issuer-uri=" + StaffTestProvider.ISSUER,
                    "--bpm.staff.audience=" + StaffTestProvider.AUDIENCE, "--bpm.staff.key-id=" + StaffTestProvider.KID,
                    "--bpm.staff.public-key-location=" + staff.publicKey.toUri(),
                    "--bpm.staff.identity-url=" + staff.url(), "--bpm.staff.saml-url=" + staff.url(),
                    "--bpm.staff.authz-url=" + staff.url(), "--bpm.staff.callback-url=" + staff.callbackUrl,
                    "--bpm.staff.identity-token=Bearer fixture-service", "--bpm.staff.authz-token=Bearer fixture-service",
                    "--bpm.external.mock-enabled=false", "--bpm.uniform.options.source=disabled",
                    "--bpm.external.org-service-url=" + staff.url(), "--bpm.external.perm-service-url=" + staff.url(),
                    "--bpm.external.cases.staff-routes.review-team=W9000001",
                    "--bpm.staff.approval-permissions.bff-contract-review=" + StaffTestProvider.APPROVE,
                    "--bpm.staff.approval-permissions.recruitment-interview-review=" + StaffTestProvider.INTERVIEW_APPROVE,
                    "--bpm.external.cases.talent-workflow-issuers[recruitment.interview]=" + issuer,
                    "--bpm.external.cases.talent-workflow-issuers[recruitment.credit]=" + issuer)) {
                var bindings = new LinkedHashMap<String, Object>();
                String key = UUID.randomUUID().toString();
                for (String kind : List.of("INTERVIEW", "CREDIT_CHECK")) {
                    String code = "INTERVIEW".equals(kind) ? "recruitment.interview" : "recruitment.credit";
                    var fixture = new ExternalCaseFixture(ctx, Integer.parseInt(env.get("SERVER_PORT")), code,
                            callbackUrl, key, true);
                    bindings.put(kind, Map.of("workflow-code", code, "binding-version", fixture.version,
                            "process-definition-id", fixture.definition, "form-definition-id", fixture.form.getId(), "form-version", 1));
                }
                var tmp = Files.createTempFile(ready.getParent(), "bindings", ".tmp");
                Files.writeString(tmp, ExternalCaseFixture.JSON.writeValueAsString(Map.of("bindings", bindings,
                        "key", key + "agent-bff", "otherStaffToken", staff.token("W9000002"),
                        "replayHeaders", Map.of("Authorization", "Bearer " + staff.token(dlqReplayAdmin)))));
                Files.move(tmp, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                System.in.read();
            }
        }
    }

    static String expectedVhost(Path ready) {
        Path parent = ready.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("Ready file parent must be an existing private run directory");
        }
        String runId = parent.getFileName().toString();
        if (!RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("Ready parent directory must be a lowercase 32-hex run id");
        }
        return "flashlight-wave-" + runId;
    }

    static void requireVhost(String configured, String expected) {
        if (configured == null || !configured.matches("flashlight-wave-[0-9a-f]{32}")
                || !configured.equals(expected)) {
            throw new IllegalStateException("Rabbit vhost must match ready parent directory: " + expected);
        }
    }

    static String callbackUrl(String callbackPort, String agentPort) {
        int port = parsePort(callbackPort == null || callbackPort.isBlank() ? agentPort : callbackPort,
                callbackPort == null || callbackPort.isBlank() ? "PORT_agent" : "WAVE_CALLBACK_PORT");
        return "http://localhost:" + port + "/internal/bpm/v1/webhook";
    }

    private static int parsePort(String value, String name) {
        if (value == null || !value.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException(name + " must be a TCP port between 1 and 65535");
        }
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(name + " must be a TCP port between 1 and 65535");
        }
        return port;
    }
}
