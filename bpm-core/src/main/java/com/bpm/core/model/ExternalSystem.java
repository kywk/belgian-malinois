package com.bpm.core.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bpm_external_system")
public class ExternalSystem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true, length = 50)
    private String systemId;

    @Column(nullable = false)
    private String systemName;

    @Column(nullable = false, length = 64)
    private String apiKey; // SHA-256 hash

    private String contactEmail;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String allowedProcessKeys; // JSON array string

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String allowedActions; // JSON array string

    private String callbackUrl;
    private String ipWhitelist; // comma-separated

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant lastUsedAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSystemId() { return systemId; }
    public void setSystemId(String systemId) { this.systemId = systemId; }
    public String getSystemName() { return systemName; }
    public void setSystemName(String systemName) { this.systemName = systemName; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getAllowedProcessKeys() { return allowedProcessKeys; }
    public void setAllowedProcessKeys(String allowedProcessKeys) { this.allowedProcessKeys = allowedProcessKeys; }
    public String getAllowedActions() { return allowedActions; }
    public void setAllowedActions(String allowedActions) { this.allowedActions = allowedActions; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public String getIpWhitelist() { return ipWhitelist; }
    public void setIpWhitelist(String ipWhitelist) { this.ipWhitelist = ipWhitelist; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
