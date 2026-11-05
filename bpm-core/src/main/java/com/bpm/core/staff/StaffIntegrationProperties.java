package com.bpm.core.staff;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

/** Explicit consumer adapter settings; no enterprise endpoints or credentials are guessed. */
@ConfigurationProperties("bpm.staff")
public record StaffIntegrationProperties(boolean enabled, String audience, String keyId,
        String publicKeyLocation, String identityUrl, String identityToken,
        String authzUrl, String authzToken, String samlUrl, String callbackUrl,
        boolean allowHumanDeploy, String gatewayDeployUserId, String gatewayDeploySubjectId, Map<String, String> approvalPermissions) {
    public StaffIntegrationProperties {
        approvalPermissions = approvalPermissions == null ? Map.of() : Map.copyOf(approvalPermissions);
    }
}
