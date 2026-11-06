package com.bpm.core.external.v1;

import static com.bpm.core.external.v1.CaseError.*;

import com.bpm.core.staff.*;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(CaseRouting.Settings.class)
class CaseRouting {
    @ConfigurationProperties("bpm.external.cases")
    record Settings(Set<String> actorIssuers, Map<String, String> staffRoutes, Map<String, String> talentWorkflowIssuers) {
        Settings {
            actorIssuers = actorIssuers == null ? Set.of() : Set.copyOf(actorIssuers);
            talentWorkflowIssuers = talentWorkflowIssuers == null ? Map.of() : Map.copyOf(talentWorkflowIssuers);
            staffRoutes = staffRoutes == null ? Map.of() : Map.copyOf(staffRoutes);
        }
    }
    private final Settings settings;
    private final ObjectProvider<StaffDirectoryClient> directory;
    private final ObjectProvider<StaffAuthzClient> authz;
    private final ObjectProvider<StaffIntegrationProperties> staff;
    CaseRouting(Settings settings, ObjectProvider<StaffDirectoryClient> directory,
            ObjectProvider<StaffAuthzClient> authz, ObjectProvider<StaffIntegrationProperties> staff) {
        this.settings = settings;
        this.directory = directory;
        this.authz = authz;
        this.staff = staff;
    }
    void checkActor(CaseCommand command) {
        var actor = command.actor();
        String talentIssuer = settings.talentWorkflowIssuers().get(command.workflowCode());
        if (talentIssuer != null || "TALENT".equals(actor.type())) {
            if (!"TALENT".equals(actor.type()) || !actor.issuer().equals(talentIssuer)) throw SERVICE_FORBIDDEN.fail();
            return;
        }
        if (settings.actorIssuers().isEmpty())
            throw INTEGRATION_NOT_CONFIGURED.fail();
        if (!settings.actorIssuers().contains(actor.issuer()))
            throw SERVICE_FORBIDDEN.fail();
    }
    String reviewer(CaseStore.Binding binding) {
        String id = settings.staffRoutes().get(binding.routingRule());
        if (id == null || id.isBlank())
            throw ROUTING_UNAVAILABLE.fail();
        if (directory.getIfAvailable() == null || authz.getIfAvailable() == null || staff.getIfAvailable() == null
                || !binding.approvalPermission().equals(
                        staff.getObject().approvalPermissions().get(binding.definitionKey())))
            throw INTEGRATION_NOT_CONFIGURED.fail();
        try {
            var snapshot = directory.getObject().byUserId(id);
            for (String permission : new String[] {StaffPermissions.CONSOLE, StaffPermissions.READ,
                         StaffPermissions.ACT, binding.approvalPermission()})
                if (!authz.getObject().allowed(snapshot, permission))
                    throw ROUTING_UNAVAILABLE.fail();
            return snapshot.userId();
        } catch (StaffFailure failure) {
            if (failure.status() == 503)
                throw("BPM_AUTHZ_UNAVAILABLE".equals(failure.code()) ? AUTHZ_UNAVAILABLE : IDENTITY_UNAVAILABLE)
                        .fail();
            throw ROUTING_UNAVAILABLE.fail();
        }
    }
}
