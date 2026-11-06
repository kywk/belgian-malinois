package com.bpm.core.staff;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"bpm.staff.enabled=true", "bpm.security.jwt.dev-secret=",
        "bpm.security.jwt.issuer-uri=https://staff-sso.example.test", "bpm.staff.gateway-deploy-user-id=ci-test",
        "bpm.staff.gateway-deploy-subject-id=service-bpm-ci", "bpm.security.gateway.enabled=true",
        "bpm.external.cases.actor-issuers[0]=https://agent.test", "bpm.external.cases.staff-routes.review-team=W9000001",
        "bpm.staff.approval-permissions.bff-contract-review=bpm:approval:bff_contract_review:approve"})
@org.springframework.context.annotation.Import(StaffIntegrationTest.RandomPortConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StaffIntegrationTest extends IntegrationTestBase {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class RandomPortConfiguration {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.server.ConfigurableWebServerFactory> staffRandomPort() {
            // The legacy base pins server.port; factory customization avoids competing dynamic-property ordering.
            return factory -> factory.setPort(0);
        }
    }
    static final org.testcontainers.containers.GenericContainer<?> SMTP = new org.testcontainers.containers.GenericContainer<>(
            org.testcontainers.utility.DockerImageName.parse("mailhog/mailhog:v1.0.1")).withExposedPorts(1025);
    static { SMTP.start(); }
    static final StaffTestProvider PROVIDER = new StaffTestProvider();
    @DynamicPropertySource static void staffProperties(DynamicPropertyRegistry r) {
        r.add("spring.mail.host", SMTP::getHost);
        r.add("spring.mail.port", () -> SMTP.getMappedPort(1025));
        r.add("spring.mail.properties.mail.smtp.auth", () -> false);
        r.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        r.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
        r.add("bpm.staff.audience", () -> StaffTestProvider.AUDIENCE);
        r.add("bpm.staff.key-id", () -> StaffTestProvider.KID);
        r.add("bpm.staff.public-key-location", () -> PROVIDER.publicKey.toUri().toString());
        r.add("bpm.staff.identity-url", PROVIDER::url);
        r.add("bpm.staff.authz-url", PROVIDER::url);
        r.add("bpm.staff.saml-url", PROVIDER::url);
        r.add("bpm.staff.callback-url", () -> PROVIDER.callbackUrl);
        r.add("bpm.staff.identity-token", () -> "Bearer fixture-service");
        r.add("bpm.staff.authz-token", () -> "Bearer fixture-service");
    }
    @Autowired org.springframework.context.ApplicationContext context;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int apiPort;
    ExternalCaseFixture caseFixture() { return new ExternalCaseFixture(context,apiPort); }

    @Test void simultaneousExternalDeliveryAndReconciliation() throws Exception {
        var f = caseFixture();
        var command = f.command();
        var post = f.post(command);
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = java.util.stream.IntStream.range(0,20).mapToObj(i -> pool.submit(() -> {
                go.await();
                return f.send(post);
            })).toList();
            go.countDown();
            var statuses = new ArrayList<Integer>();
            for (var call : calls) {
                var response = call.get();
                assertThat(response.statusCode()).as(response.body()).isIn(200,201,202);
                statuses.add(response.statusCode());
            }
            assertThat(statuses).containsOnlyOnce(201);
        }
        String requestPath = ExternalCaseFixture.REQUESTS + command.get("requestId");
        var receipt = f.get(requestPath);
        String id = receipt.get("processInstanceId").asString();
        String path = ExternalCaseFixture.COLLECTION + "/" + id;
        String key = receipt.get("businessKey").asString();
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(key).count()).isEqualTo(1);
        assertThat(runtime.getVariable(id,"initiator")).isEqualTo("system:agent-bff");
        assertThat(runtime.getVariable(id,"_formVersions")).isEqualTo(Map.of(f.form.getFormKey(),1));
        assertThat(f.get(path).get("processDefinitionId").asString()).isEqualTo(f.definition);
        assertThat(f.get(path).get("sourceRevision").asLong()).isEqualTo(1);
        String lookup = ExternalCaseFixture.COLLECTION + "?businessKey=" + key;
        assertThat(f.get(lookup).get("items").get(0)).isEqualTo(f.get(path));
        for (String other : List.of(requestPath,path,lookup))
            assertThat(f.expect(f.req("case-other",other,null),404).get("code").asString()).isEqualTo("BPM_NOT_FOUND");
        f.expect(f.req("agent-bff","/api/external/process-instances","{}"),403);
        var changed = new HashMap<>(command); changed.put("requestId",UUID.randomUUID().toString());
        assertThat(f.expect(f.post(changed),409).get("code").asString()).isEqualTo("BPM_CASE_ALREADY_STARTED");
        changed = new HashMap<>(command); changed.put("processDefinitionId","different");
        assertThat(f.expect(f.post(changed),409).get("code").asString()).isEqualTo("BPM_IDEMPOTENCY_CONFLICT");

        runtime.deleteProcessInstance(id,"cancelled-by-test");
        var cancelled = f.get(path);
        assertThat(cancelled.get("engineState").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("outcome").asString()).isEqualTo("UNKNOWN");
        assertThat(cancelled.get("sourceRevision").asLong()).isEqualTo(2);
        f.db.update("UPDATE bpm_case_binding SET enabled=0 WHERE binding_version=?", f.version);
        var replay = f.send(post);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        var disabled = f.command();
        assertThat(f.expect(f.post(disabled),409).get("code").asString()).isEqualTo("BPM_DEFINITION_VERSION_MISMATCH");
        assertThat(f.get(ExternalCaseFixture.REQUESTS+disabled.get("requestId")).get("state").asString()).isEqualTo("REJECTED");
        assertThat(f.send(f.post(disabled)).headers().firstValue("Idempotency-Replayed")).contains("true");
    }

    @Test void externalRejectedInputAndUncertainRecovery() {
        var f = caseFixture();
        var command = f.command();
        var mixed = java.net.http.HttpRequest.newBuilder(f.post(command), (name,value) -> true)
                .header("Authorization", "Bearer forwarded-token").build();
        assertThat(f.expect(mixed,401).get("code").asString()).isEqualTo("BPM_SERVICE_AUTH_INVALID");
        var invalid = new HashMap<>(command);
        invalid.put("actor",Map.of("type","AGENT","agentCode","AG1","subject","shared","issuer","https://untrusted.test"));
        f.expect(f.post(invalid),403);
        f.expect(f.req("agent-bff",ExternalCaseFixture.REQUESTS+command.get("requestId"),null),404);
        invalid.put("actor",Map.of("type","TALENT","agentCode","AG1","subject","shared","issuer","https://agent.test"));
        f.expect(f.post(invalid),400);
        f.expect(f.req("agent-bff",ExternalCaseFixture.COLLECTION," ".repeat(1024*1024+1)),413);
        invalid = new HashMap<>(command); invalid.put("formData",null);
        assertThat(f.expect(f.post(invalid),409).get("code").asString()).isEqualTo("BPM_FORM_VERSION_MISMATCH");
        invalid = new HashMap<>(f.command());
        invalid.put("formData",Map.of("formDefinitionId",f.form.getId(),"formVersion",1,"dataJson","{\"unknown\":1}"));
        assertThat(f.expect(f.post(invalid),422).get("code").asString()).isEqualTo("BPM_FORM_VALIDATION_FAILED");

        // A registered workflow variable still cannot smuggle an omitted DSL field past UniForm.
        var specs = context.getBean(com.bpm.core.repository.ProcessVariableSpecRepository.class);
        var spec = new com.bpm.core.model.ProcessVariableSpec();
        spec.setProcessDefinitionKey("bff-contract-review"); spec.setVariableName("note"); spec.setVariableType("string");
        specs.saveAndFlush(spec);
        try {
            var shadow = f.command();
            shadow.put("variables",Map.of("note","skip-validation"));
            shadow.put("formData",Map.of("formDefinitionId",f.form.getId(),"formVersion",1,"dataJson","{}"));
            assertThat(f.expect(f.post(shadow),400).get("code").asString()).isEqualTo("BPM_VALIDATION_ERROR");
            f.expect(f.req("agent-bff",ExternalCaseFixture.REQUESTS+shadow.get("requestId"),null),404);
        } finally { specs.delete(spec); }

        // Simulate the persistent boundary left by a crashed worker before primary commit.
        String id = invalid.get("requestId").toString();
        f.db.update("UPDATE bpm_case_request SET state='PROCESSING',error_code=NULL WHERE request_id=?",id);
        var processing = f.send(f.post(invalid));
        assertThat(processing.statusCode()).isEqualTo(202);
        assertThat(processing.headers().firstValue("Retry-After")).contains("2");
        f.db.update("UPDATE bpm_case_request SET reserved_at=DATEADD(minute,-6,SYSUTCDATETIME()) WHERE request_id=?",id);
        assertThat(f.get(ExternalCaseFixture.REQUESTS+id).get("state").asString()).isEqualTo("UNCERTAIN");
        assertThat(f.expect(f.post(invalid),500).get("outcome").asString()).isEqualTo("UNKNOWN");
        var uncertain = f.get(ExternalCaseFixture.REQUESTS+id);
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(uncertain.get("businessKey").asString()).count()).isZero();
    }

    @Test void technicalWorkflowRequiresExplicitOutcomeAndDetectsUnlinkedLegacyProcesses() {
        var f = caseFixture();
        String technical = f.deploy(repository,null);
        // Register a separate immutable version with no form and no terminal outcome mapping.
        long version = f.version + 1;
        f.db.update("""
                INSERT INTO bpm_case_binding (workflow_code,binding_version,caller_system,domain,workflow_role,
                  definition_key,definition_id,routing_rule,approval_permission,enabled)
                VALUES ('contract.staff-review',?,'agent-bff','contract-fixture','review','bff-contract-review',
                  ?,'review-team',?,1)
                """,version,technical,StaffTestProvider.APPROVE);
        var command = f.command();
        command.put("bindingVersion",version); command.put("processDefinitionId",technical); command.put("formData",null);
        var receipt = f.expect(f.post(command),201);
        String pid = receipt.get("processInstanceId").asString();
        tasks.complete(tasks.createTaskQuery().processInstanceId(pid).singleResult().getId());
        var status = f.get(ExternalCaseFixture.COLLECTION+"/"+pid);
        assertThat(status.get("sourceRevision").asLong()).isEqualTo(2);
        assertThat(status.get("outcome").asString()).isEqualTo("UNKNOWN");
        assertThat(status.get("reasonCode").asString()).isEqualTo("RESULT_MAPPING_UNAVAILABLE");
        assertThat(status.get("formVersion").isNull()).isTrue();

        command = f.command();
        String key = "contract-fixture/app-A/"+command.get("requestId")+"/review";
        for (int n=0; n<2; n++)
            runtime.startProcessInstanceById(technical,key,Map.of("_externalSystemId","agent-bff","firstTaskAssignee","W9000001"));
        assertThat(f.expect(f.post(command),500).get("code").asString()).isEqualTo("BPM_OPERATION_UNCERTAIN");
        assertThat(f.get(ExternalCaseFixture.REQUESTS+command.get("requestId")).get("state").asString()).isEqualTo("UNCERTAIN");
        f.expect(f.req("agent-bff",ExternalCaseFixture.COLLECTION+"?businessKey="+key,null),500);
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(key).count()).isEqualTo(2);
    }

    @Test void compensationFailureRetainsAttemptForOperatorReconciliation() {
        var f = caseFixture();
        var command = f.command();
        var requestId = UUID.fromString(command.get("requestId").toString());
        var formId = UUID.fromString(f.form.getId());
        var formDb = new org.springframework.jdbc.core.JdbcTemplate(context.getBean("formDataSource", javax.sql.DataSource.class));
        // Fail the core confirmation after the separate form transaction commits.
        // Both triggers apply only to this fixture's identifiers and are removed in finally.
        try {
            f.db.execute("""
                    CREATE TRIGGER case_test_reject_confirm ON bpm_case_request AFTER UPDATE AS
                    IF EXISTS (SELECT 1 FROM inserted WHERE request_id='%s' AND state='CONFIRMED')
                        THROW 51001,'fixture core commit failure',1
                    """.formatted(requestId));
            formDb.execute("""
                    CREATE TRIGGER case_test_reject_delete ON bpm_form_data AFTER DELETE AS
                    IF EXISTS (SELECT 1 FROM deleted WHERE form_definition_id='%s')
                        THROW 51002,'fixture compensation failure',1
                    """.formatted(formId));
            assertThat(f.expect(f.post(command),500).get("code").asString()).isEqualTo("BPM_OPERATION_UNCERTAIN");
            var receipt = f.get(ExternalCaseFixture.REQUESTS + requestId);
            assertThat(receipt.get("state").asString()).isEqualTo("UNCERTAIN");
            String attempt = f.db.queryForObject("SELECT attempt_id FROM bpm_case_request WHERE request_id=?",String.class,requestId.toString());
            assertThat(formDb.queryForObject("SELECT COUNT(*) FROM bpm_form_data WHERE process_instance_id=?",Integer.class,attempt)).isEqualTo(1);
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(attempt).count()).isZero();
            f.expect(f.post(command),500);
            command.put("requestId",UUID.randomUUID().toString());
            assertThat(f.expect(f.post(command),409).get("code").asString()).isEqualTo("BPM_CASE_ALREADY_STARTED");
        } finally {
            f.db.execute("DROP TRIGGER IF EXISTS case_test_reject_confirm");
            formDb.execute("DROP TRIGGER IF EXISTS case_test_reject_delete");
        }
    }

    @Autowired RepositoryService repository;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @BeforeEach void resetProvider() { PROVIDER.reset(); }
    @AfterAll static void closeProvider() { PROVIDER.close(); }
    private String bearer() { return "Bearer " + PROVIDER.token("login-alias"); }
    @Test void sessionUsesCanonicalStaffAndAuthoritativeOrganizations() throws Exception {
        mockMvc.perform(get("/api/me/session").header("Authorization", bearer()).param("userId", "attacker"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value("W9000001"))
                .andExpect(jsonPath("$.userType").value("STAFF"))
                .andExpect(jsonPath("$.deptId").value("50310001"));
        assertThat(PROVIDER.lastSubjects).containsExactly("staff-w9000001", "org-50310001");
    }
    @Test void rolesDoNotGrantAuditOrDesignAndManageDoesNotGrantApproval() throws Exception {
        PROVIDER.denied.addAll(List.of(StaffPermissions.AUDIT, StaffPermissions.DESIGN, StaffTestProvider.APPROVE));
        mockMvc.perform(get("/api/audit-logs").header("Authorization", bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BPM_PERMISSION_DENIED"));
        mockMvc.perform(post("/api/forms").header("Authorization", bearer()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        String task = newTask("W9000001", null);
        complete(task).andExpect(status().isForbidden());
        assertThat(tasks.createTaskQuery().taskId(task).count()).isEqualTo(1);
        mockMvc.perform(get("/api/me/permissions").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true));
    }
    @Test void inactiveExternalMissingAndUnknownStatusFailClosed() throws Exception {
        PROVIDER.status = "INACTIVE"; sessionError(403, "BPM_ACCOUNT_INACTIVE");
        PROVIDER.status = "ACTIVE"; PROVIDER.userType = "AGENT"; sessionError(403, "BPM_STAFF_REQUIRED");
        PROVIDER.userType = "STAFF"; PROVIDER.identityResult = "NOT_FOUND"; sessionError(403, "BPM_STAFF_REQUIRED");
        PROVIDER.identityResult = "FOUND"; PROVIDER.status = "UNKNOWN"; sessionError(503, "BPM_IDENTITY_UNAVAILABLE");
        PROVIDER.status = "ACTIVE"; PROVIDER.staleIdentity = true; sessionError(503, "BPM_IDENTITY_UNAVAILABLE");
    }
    @Test void authzDenyAndBrokenSourceRemainDifferent() throws Exception {
        PROVIDER.denied.add(StaffPermissions.CONSOLE); sessionError(403, "BPM_PERMISSION_DENIED");
        PROVIDER.denied.clear(); PROVIDER.authzCode = "200"; sessionError(503, "BPM_AUTHZ_UNAVAILABLE");
        PROVIDER.authzCode = "0000"; PROVIDER.malformedAllowed = true; sessionError(503, "BPM_AUTHZ_UNAVAILABLE");
        PROVIDER.malformedAllowed = false; PROVIDER.failIdentity = true; sessionError(503, "BPM_IDENTITY_UNAVAILABLE");
    }
    @Test void gatewayHeadersCannotOpenStaffConsole() throws Exception {
        // The base fixture supplies valid gateway headers; the staff chain must still reject them.
        mockMvc.perform(get("/api/me/session")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BPM_AUTH_INVALID"));
    }
    @Test void wrongAudienceIssuerKidSubjectAndTimestampsAreRejected() throws Exception {
        var bad = new ArrayList<Map<String, Object>>();
        bad.add(Map.of("aud", "wrong")); bad.add(Map.of("iss", "https://wrong.example.test"));
        bad.add(Map.of("kid", "unknown")); bad.add(Map.of("sub", " W9000001"));
        bad.add(Map.of("iat", Date.from(java.time.Instant.now().plusSeconds(120))));
        bad.add(Map.of("exp", Date.from(java.time.Instant.now().minusSeconds(120))));
        var missing = new HashMap<String, Object>(); missing.put("iat", null); bad.add(missing);
        for (var overrides : bad) mockMvc.perform(get("/api/me/session")
                .header("Authorization", "Bearer " + PROVIDER.token("login-alias", overrides)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("BPM_AUTH_INVALID"));
    }
    @Test void todoFollowsOrgMoveAndDoesNotTrustOldOfficeClaim() throws Exception {
        String task = newTask(null, "50310001");
        String body = mockMvc.perform(get("/api/tasks").header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains(task);
        PROVIDER.dept = "50319999";
        body = mockMvc.perform(get("/api/tasks").header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(task);
        complete(task).andExpect(status().isNotFound());
    }
    @Test void revocationPreventsWriteAndRegrantAllowsActualFlowableCompletion() throws Exception {
        String task = newTask("W9000001", null);
        mockMvc.perform(get("/api/me/session").header("Authorization", bearer())).andExpect(status().isOk());
        PROVIDER.denied.add(StaffTestProvider.APPROVE);
        complete(task).andExpect(status().isForbidden());
        assertThat(tasks.createTaskQuery().taskId(task).count()).isEqualTo(1);
        PROVIDER.denied.clear(); complete(task).andExpect(status().isOk());
        assertThat(tasks.createTaskQuery().taskId(task).count()).isZero();
    }
    @Test void privilegeDoesNotBypassHolder() throws Exception {
        String otherTask = newTask("W9000002", null);
        complete(otherTask).andExpect(status().isNotFound());
        assertThat(tasks.createTaskQuery().taskId(otherTask).count()).isEqualTo(1);
    }
    @Test void targetMustBeActiveAndCountersignCannotBypassApproval() throws Exception {
        String task = newTask("W9000001", null);
        PROVIDER.inactiveUserId = "W9000002";
        mockMvc.perform(put("/api/tasks/" + task).header("Authorization", bearer()).contentType("application/json")
                .content("{\"action\":\"reassign\",\"assignee\":\"W9000002\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BPM_ACCOUNT_INACTIVE"));
        assertThat(tasks.createTaskQuery().taskId(task).singleResult().getAssignee()).isEqualTo("W9000001");
        PROVIDER.denied.add(StaffTestProvider.APPROVE);
        mockMvc.perform(post("/api/countersign/" + task).header("Authorization", bearer()).contentType("application/json")
                .content("{\"countersignUserId\":\"W9000002\"}"))
                .andExpect(status().isForbidden());
        assertThat(tasks.getSubTasks(task)).isEmpty();
    }
    @Test void gatewayCiHasDedicatedGrantAndCannotUseStaffJwtRoles() throws Exception {
        mockMvc.perform(multipart("/api/deployments").header("X-User-Id", "ci-test"))
                .andExpect(status().isBadRequest()); // authenticated machine reached required-file validation
        assertThat(PROVIDER.lastSubjects).containsExactly("service-bpm-ci");
        PROVIDER.denied.add(StaffPermissions.DEPLOY);
        mockMvc.perform(post("/api/deployments").header("X-User-Id", "ci-test")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/deployments").header("X-User-Id", "ci-test").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/internal/cache-invalidate").header("Authorization", bearer())
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void unboundTaskCannotBeCompleted() throws Exception {
        var task = tasks.newTask(); task.setAssignee("W9000001"); tasks.saveTask(task);
        complete(task.getId()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BPM_INTEGRATION_NOT_CONFIGURED"));
        assertThat(tasks.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
    }
    private void sessionError(int status, String code) throws Exception {
        mockMvc.perform(get("/api/me/session").header("Authorization", bearer()))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
    private org.springframework.test.web.servlet.ResultActions complete(String task) throws Exception {
        return mockMvc.perform(put("/api/tasks/" + task).header("Authorization", bearer())
                .contentType("application/json").content("{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":true}]}"));
    }
    protected String newTask(String assignee, String group) { return newTask(assignee, group, null); }
    protected String newTask(String assignee, String group, String formKey) {
        var model = new org.flowable.bpmn.model.BpmnModel();
        var process = new org.flowable.bpmn.model.Process(); process.setId("bff-contract-review");
        var start = new org.flowable.bpmn.model.StartEvent(); start.setId("start");
        var review = new org.flowable.bpmn.model.UserTask(); review.setId("review"); review.setName("內勤契約簽核");
        review.setFormKey(formKey);
        review.setAssignee(assignee); if (group != null) review.setCandidateGroups(List.of(group));
        var end = new org.flowable.bpmn.model.EndEvent(); end.setId("end");
        process.addFlowElement(start); process.addFlowElement(review); process.addFlowElement(end);
        process.addFlowElement(new org.flowable.bpmn.model.SequenceFlow("start", "review"));
        process.addFlowElement(new org.flowable.bpmn.model.SequenceFlow("review", "end")); model.addProcess(process);
        var deploy = repository.createDeployment().addBpmnModel("staff.bpmn20.xml", model).deploy();
        String definition = repository.createProcessDefinitionQuery().deploymentId(deploy.getId()).singleResult().getId();
        Map<String, Object> variables = new HashMap<>(); variables.put("initiator", "W9000001");
        if (formKey != null) variables.put("_formVersions", Map.of(formKey, 1));
        String id = runtime.startProcessInstanceById(definition, variables).getId();
        return tasks.createTaskQuery().processInstanceId(id).singleResult().getId();
    }
}
