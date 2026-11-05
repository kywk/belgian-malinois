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
