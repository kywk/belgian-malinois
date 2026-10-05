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

    /**
     * 回呼（{@code POST /api/callback/{type}}）用的密鑰（工項 #21）。
     *
     * <p><b>⚠️ 這裡存的是密鑰本身，不是雜湊 —— 與 {@link #apiKey} 刻意不同。</b>
     * apiKey 可以只存 SHA-256 是因為驗證方式是「雜湊送來的明文再比對」，
     * 伺服器不需要原始值；而 HMAC 驗簽的<b>密鑰就是原始密鑰</b>，
     * 伺服器必須持有它才能重算簽章。把 HMAC 密鑰雜湊後儲存有兩種結果：
     * <ul>
     *   <li>拿雜湊當 HMAC 密鑰 → 用戶端也被要求用雜湊簽章。此時<b>雜湊本身
     *       就是可用於偽造的密鑰</b>，資料庫外洩的後果與明文相同，雜湊白做。</li>
     *   <li>拿雜湊當「驗證用摘要」→ 根本無法驗簽（伺服器沒有原始密鑰）。</li>
     * </ul>
     * 也就是說 MAC 密鑰沒有「不可逆儲存」的選項，只能選擇可還原的儲存方式
     * （明文，或搭配外部 KMS 的加密）。本工項沒有 KMS，因此採明文儲存 ——
     * 與 Stripe／GitHub 等 webhook 簽章密鑰的實務一致。
     *
     * <p>防護改成落在別處：{@code READ_ONLY} 讓請求 body 無法指定它、
     * 回應一律遮蔽成 {@code ***}、明文只在建立與輪換時各回傳一次、
     * 稽核只記「密鑰的雜湊前綴」（見
     * {@code ExternalSystemAdminController.secretHashPrefix}）。
     *
     * <p><b>可空，且空值的語意是「尚未設定」而非「不限制」</b>——
     * 這是它與 {@code allowedActions} 那組授權欄位最大的差別。
     * 沒有密鑰就無法驗簽，因此 null 一律拒絕（401）。
     * 既有資料列在 V5 migration 之後全部是 null，也就是<b>既有系統預設不能
     * 回呼</b>，直到管理員呼叫 {@code rotate-callback-secret} 產生一組為止。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(length = 64)
    private String callbackSecret; // 回呼密鑰本身；null = 尚未設定

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

    /**
     * 此系統被授權指定的候選群組白名單（#88 政策 B）。
     *
     * <p>改動前 {@code firstTaskCandidateGroups} <b>完全沒有任何驗證</b> ——
     * 外部系統可以把案件丟進任意群組的待辦池（部門代碼、權限碼、JWT authority
     * 這三種來源，見 {@code CandidateGroupMembership}），那是授權範圍問題。
     *
     * <p>⚠️ <b>空值的語意是「不限制」，不是「禁止所有群組」</b>。
     * 這不是本欄位的特例，而是 {@link com.bpm.core.external.ExternalSystemPolicy}
     * 對<b>所有</b>授權欄位共用的四態規則（{@code Kind.UNRESTRICTED}），
     * 與 {@code allowedProcessKeys}／{@code allowedActions} 完全一致。
     * 兩個後果：
     * <ul>
     *   <li>migration 之後既有資料列一律為 null，而<b>不需要回填</b>
     *       （見 V4__external_system_allowed_candidate_groups.sql）。</li>
     *   <li>⚠️ 反過來說，<b>對既有系統而言這個檢查完全沒有效果</b>，直到管理員
     *       逐一設定。與 {@code allowedProcessKeys} 是同一個已知狀況 ——
     *       差別在於 R-21 已讓 <b>{@code allowedProcessKeys} 新寫入必填</b>
     *       （見 {@code ExternalSystemAuthorizationValidator}），而本欄位
     *       仍可留空＝不限制，管理頁必須讓人明確設定它（{@code ExternalSystemAdmin.vue}）。</li>
     * </ul>
     *
     * <p>格式與 {@code allowedProcessKeys} 同樣是 JSON array 字串
     * （{@code ExternalSystemPolicy} 另外容忍逗號分隔格式）。
     */
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String allowedCandidateGroups; // JSON array string

    /**
     * 此系統被授權認領／查詢的 worker topic 白名單（#22 收尾）。
     *
     * <p>改動前 {@code /api/external/worker/**} <b>不檢查 topic 歸屬</b>：
     * Flowable 的 acquire 只按 topic ＋「尚未鎖定」挑 job，沒有任何
     * 「這個 job 屬於哪個系統」的維度，所以任何被授權 {@code external_worker}
     * 的系統都能認領任何未鎖定的 job，而 acquire 會帶回該流程的變數
     * —— 跨系統洩漏。需要隔離時只能用系統專屬的 topic 名稱，但那是
     * <b>約定</b>而不是<b>強制</b>。本欄位把隔離變成強制。
     *
     * <p>⚠️ <b>空值的語意是「不限制」，不是「禁止所有 topic」</b>。
     * 與 {@link #allowedCandidateGroups} 完全同一條四態規則
     * （見 {@link com.bpm.core.external.ExternalSystemPolicy}）：
     * migration 之後既有資料列一律為 null，而<b>不需要回填</b>
     * （見 V7__external_system_allowed_worker_topics.sql）；反過來說，
     * 對既有系統而言這個檢查完全沒有效果，直到管理員逐一設定 ——
     * 與 {@code allowedProcessKeys} 是同一個已知狀況（後者已由 R-21
     * 在寫入端強制必填，本欄位仍以空值＝不限制為預設）。
     *
     * <p>格式與 {@code allowedProcessKeys} 同樣是 JSON array 字串
     * （{@code ExternalSystemPolicy} 另外容忍逗號分隔格式）。
     */
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String allowedWorkerTopics; // JSON array string

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
    public String getCallbackSecret() { return callbackSecret; }
    public void setCallbackSecret(String callbackSecret) { this.callbackSecret = callbackSecret; }
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
    public String getAllowedCandidateGroups() { return allowedCandidateGroups; }
    public void setAllowedCandidateGroups(String allowedCandidateGroups) { this.allowedCandidateGroups = allowedCandidateGroups; }
    public String getAllowedWorkerTopics() { return allowedWorkerTopics; }
    public void setAllowedWorkerTopics(String allowedWorkerTopics) { this.allowedWorkerTopics = allowedWorkerTopics; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
