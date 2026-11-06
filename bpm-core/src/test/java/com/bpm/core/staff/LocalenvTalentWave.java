package com.bpm.core.staff;

import com.bpm.core.BpmCoreApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.SpringApplication;

/** Test-classpath-only launcher. Shared SQL instance, dedicated BPM databases and Rabbit vhost. */
public final class LocalenvTalentWave {
    public static void main(String[] args) throws Exception {
        var ready = Path.of(args[0]);
        var issuer = "https://talent.localenv.invalid";
        var env = System.getenv();
        for (String kind : List.of("CORE", "FORM", "AUDIT")) {
            String suffix = kind.equals("CORE") ? "" : kind + "_";
            if (!env.get("SPRING_DATASOURCE_" + suffix + "URL").matches(
                    "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=flashlight_wave_" +
                    kind.toLowerCase(Locale.ROOT) + ";encrypt=false")) throw new IllegalStateException("Unexpected BPM database");
        }
        if (!"flashlight-wave".equals(env.get("SPRING_RABBITMQ_VIRTUAL_HOST"))) throw new IllegalStateException("Unexpected Rabbit vhost");
        try (var staff = new StaffTestProvider()) {
            staff.callbackUrl = "http://127.0.0.1:" + env.get("E2E_UI_PORT") + "/";
            staff.denied.addAll(List.of(StaffPermissions.MANAGE, StaffPermissions.DESIGN, StaffPermissions.AUDIT, StaffPermissions.DEPLOY));
            var app = new SpringApplication(BpmCoreApplication.class);
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
                            "http://localhost:" + env.get("PORT_agent") + "/internal/bpm/v1/webhook", key, true);
                    bindings.put(kind, Map.of("workflow-code", code, "binding-version", fixture.version,
                            "process-definition-id", fixture.definition, "form-definition-id", fixture.form.getId(), "form-version", 1));
                }
                var tmp = Files.createTempFile(ready.getParent(), "bindings", ".tmp");
                Files.writeString(tmp, ExternalCaseFixture.JSON.writeValueAsString(Map.of("bindings", bindings,
                        "key", key + "agent-bff", "otherStaffToken", staff.token("W9000002"))));
                Files.move(tmp, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                System.in.read();
            }
        }
    }
}
