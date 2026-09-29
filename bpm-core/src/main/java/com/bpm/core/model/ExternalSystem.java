package com.bpm.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bpm_external_system")
public class ExternalSystem {

    /**
     * ⚠️ READ_ONLY：id 絕不可由請求 body 指定（與 P0-4 同一個機制）。
     *
     * <p>Spring Data 的 {@code save()} 以 {@code id == null} 判斷 isNew ——
     * id 非 null 時會走 {@code em.merge()} 變成 UPDATE。
     *
     * <p>在這個實體上的後果比 P0-4 更嚴重，因為它管的是外部系統的授權。
     * 已在執行中的服務上實測：對「建立」端點 POST 一個帶既有 id 的 body，
     * 結果是<b>覆寫那一列</b>（資料庫裡仍只有一筆）：
     * <ul>
     *   <li>{@code allowedProcessKeys} 被擴大 —— 授權提升</li>
     *   <li>{@code apiKey} 被輪換 —— 原持有者的金鑰立刻失效（阻斷服務），
     *       而呼叫端拿到新的明文金鑰</li>
     * </ul>
     * <p>也就是一次請求即可接管既有的外部系統。而該端點當時<b>完全沒有稽核</b>，
     * 所以不會留下任何軌跡（security-audit P2-4 施作時發現）。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true, length = 50)
    private String systemId;

    @Column(nullable = false)
    private String systemName;

    /**
     * ⚠️ READ_ONLY：這是雜湊值，永遠由伺服器產生。
     *
     * <p>目前 create 會在 save 之前覆寫它、update 不會複製它，所以接受這個欄位
     * 還不至於被利用。但「請求可以指定金鑰雜湊」本身就是個陷阱 ——
     * 只要哪天有人加一條會複製它的路徑，就變成「自帶雜湊即可通過驗證」。
     * 序列化（回應）仍保留，list/get 會把它改成 *** 再回傳。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
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

    /**
     * 是否可代員工發起（body 的 {@code onBehalfOf}）。預設不允許（R-20）。
     * 見 V3__external_system_allow_on_behalf_of.sql。
     */
    @Column(nullable = false)
    private Boolean allowOnBehalfOf = false;

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
    public Boolean getAllowOnBehalfOf() { return allowOnBehalfOf; }
    public void setAllowOnBehalfOf(Boolean allowOnBehalfOf) { this.allowOnBehalfOf = allowOnBehalfOf; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
