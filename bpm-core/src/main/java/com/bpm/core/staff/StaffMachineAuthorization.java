package com.bpm.core.staff;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import java.util.List;

/** CI gateway credentials never establish a STAFF console identity. */
@Component
@ConditionalOnProperty(name = "bpm.staff.enabled", havingValue = "true")
public class StaffMachineAuthorization {
    private final StaffIntegrationProperties properties;
    private final StaffAuthzClient authz;
    public StaffMachineAuthorization(StaffIntegrationProperties properties, StaffAuthzClient authz) {
        this.properties = properties; this.authz = authz;
    }
    public boolean canDeploy(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_GATEWAY"))) return false;
        if (properties.gatewayDeployUserId() == null || properties.gatewayDeploySubjectId() == null
                || properties.gatewayDeployUserId().isBlank() || properties.gatewayDeploySubjectId().isBlank())
            throw new StaffFailure("BPM_INTEGRATION_NOT_CONFIGURED", 503, "CI 部署服務身分尚未設定");
        return properties.gatewayDeployUserId().equals(authentication.getName())
                && authz.allowedSubjects(List.of(properties.gatewayDeploySubjectId()), StaffPermissions.DEPLOY);
    }
}
