package com.bpm.core.external;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.security.CallerId;
import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.service.AuditLogService;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 外部系統的管理端點。
 *
 * <h2>為什麼這裡的每個變更都必須稽核（security-audit P2-4）</h2>
 *
 * <p>這個 controller 管的是 {@code allowedProcessKeys} 與
 * {@code allowedActions} —— 也就是「誰能從外部發起哪些流程、做哪些動作」。
 * 那是<b>授權設定</b>，改動它等於改變誰可以在這個平台上簽核什麼。
 *
 * <p>改動前這裡有四個變更端點、<b>零個稽核呼叫</b>。所以擴大一個外部系統的
 * 授權範圍、或輪換它的金鑰，在稽核軌跡裡完全不存在 ——
 * 之後即使發現有案件是被不該發起的系統送進來的，也查不出授權是何時被誰改的。
 *
 * <h2>update 記錄的是差異，不只是「有人改了」</h2>
 *
 * <p>「某人更新了系統 erp」這種紀錄在事故調查時幾乎沒有用。真正需要的是
 * {@code allowedProcessKeys: ["leave-approval"] → ["leave-approval","purchase-approval"]}
 * —— 授權被擴大了什麼、什麼時候、由誰。所以只記錄實際變動的欄位與前後值。
 *
 * <h2>⚠️ PUT 是整欄覆寫，而多數授權欄位的「空值」語意是「不限制」</h2>
 *
 * <p>{@code allowedProcessKeys}／{@code allowedActions}／{@code ipWhitelist}／
 * {@code allowedCandidateGroups}／{@code allowedWorkerTopics} 全部是自由文字的欄位，PUT 沒帶就是 {@code null}，
 * 而 {@code null} 在 {@link ExternalSystemPolicy} 的規則裡是
 * <b>{@code UNRESTRICTED}（不限制）</b>。也就是說
 * <b>「PUT 少帶一個欄位 = 把該項授權放寬」</b>。
 *
 * <p>這是既有行為（{@code allowOnBehalfOf} 之所以顯式轉 boolean，是因為它
 * 刻意選了相反的方向：欄位缺席 = 失去能力）。真正的修法是管理頁必須讓人
 * 設定這些欄位 —— 見 {@code ExternalSystemAdmin.vue}。既有條目見 backlog R-21。
 *
 * <h2>⚠️ 明文金鑰絕不可進稽核庫</h2>
 *
 * <p>API key 只在建立與輪換時回傳一次明文，之後只存雜湊。若把明文寫進
 * 稽核紀錄，雜湊就白做了 —— 稽核庫是可查詢的，而且保留期通常比金鑰生命週期長。
 * 所以稽核只記「金鑰被輪換了」這個事實與雜湊的前 8 碼（足以對帳，不足以使用）。
 *
 * <p>回呼密鑰（#21）不同：它必須以可還原形式儲存（HMAC 驗簽需要原始密鑰，
 * 見 {@code ExternalSystem.callbackSecret}），所以稽核前綴要<b>先雜湊再截</b>
 * （{@link #secretHashPrefix}）—— 直接截字串會把密鑰的一部分寫進稽核庫。
 * 對外的輪換回應同樣只給一次明文。
 */
@RestController
@RequestMapping("/api/admin/external-systems")
public class ExternalSystemAdminController {

    /** 授權相關欄位。update 時逐一比對前後值寫進稽核。 */
    private static final List<String> AUDITED_FIELDS =
            List.of("systemName", "contactEmail", "allowedProcessKeys", "allowedActions",
                    "callbackUrl", "ipWhitelist", "enabled",
                    // 授權變更：漏列的話，只改這一欄的 PUT 會被判定為「沒有變更」而不留痕。
                    "allowOnBehalfOf",
                    // #88 政策 B：候選群組白名單。漏列的話，擴大或縮小
                    // 「這個系統能把單子丟進哪些待辦池」都不會留下軌跡，
                    // 而那正是它屬於授權維度的理由。
                    "allowedCandidateGroups",
                    // #22 收尾：worker topic 白名單。漏列的話，擴大或縮小
                    // 「這個系統能認領／查詢哪些 topic」都不會留下軌跡 ——
                    // 那正是跨系統洩漏的授權維度。
                    "allowedWorkerTopics");

    private final ExternalSystemRepository repo;
    private final AuditEventPublisher auditPublisher;
    private final AuditLogService auditLogService;
    /** v2 金鑰雜湊（R-25）。舊格式的驗證在 filter，這裡只負責產生新值。 */
    private final ApiKeyHasher apiKeyHasher;
    /** rotate-key 的寬限期設定（R-25）。 */
    private final ExternalSecurityProperties securityProperties;

    public ExternalSystemAdminController(ExternalSystemRepository repo,
                                         AuditEventPublisher auditPublisher,
                                         AuditLogService auditLogService,
                                         ApiKeyHasher apiKeyHasher,
                                         ExternalSecurityProperties securityProperties) {
        this.repo = repo;
        this.auditPublisher = auditPublisher;
        this.auditLogService = auditLogService;
        this.apiKeyHasher = apiKeyHasher;
        this.securityProperties = securityProperties;
    }

    @PostMapping
    @Transactional("primaryTransactionManager")
    public Map<String, Object> create(@RequestBody ExternalSystem sys,
                                      @CallerId
                                      String operatorId) {
        // systemId 有 unique 約束，但靠約束失敗會得到一個看不懂的 500。
        // 明確檢查並回 409，同時避免「建立」意外變成覆寫 ——
        // id 已由 READ_ONLY 擋住 body 指定，這裡再擋重複的 systemId。
        if (sys.getSystemId() == null || sys.getSystemId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 systemId");
        }
        if (repo.findBySystemId(sys.getSystemId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "外部系統已存在: " + sys.getSystemId() + "（要修改請用 PUT，要換金鑰請用 rotate-key）");
        }

        String plainKey = ApiKeyUtil.generateKey();
        // R-25：新金鑰一律以 v2（HMAC-SHA256）儲存。舊系統的 legacy 雜湊
        // 不受影響 —— 驗證端雙讀，第一次通過驗證時由 filter 透明升級。
        sys.setApiKey(apiKeyHasher.hash(plainKey));
        // 回呼密鑰與 API key 同時產生（工項 #21）：明文只在這個回應出現一次。
        // ⚠️ 存的是密鑰本身而非雜湊 —— HMAC 驗簽需要原始密鑰，見
        // ExternalSystem.callbackSecret 與 V5 migration 的說明。
        // 既有系統（V5 migration 前建立）的 callbackSecret 是 null，必須由
        // rotate-callback-secret 補發 —— 這裡不影響它們。
        String plainCallbackSecret = ApiKeyUtil.generateCallbackSecret();
        sys.setCallbackSecret(plainCallbackSecret);
        sys.setEnabled(true);
        // null（沒帶）視為不允許：代發是需要明確授予的能力（R-20）。
        sys.setAllowOnBehalfOf(Boolean.TRUE.equals(sys.getAllowOnBehalfOf()));
        ExternalSystem saved = repo.save(sys);

        audit(operatorId, "create", saved.getSystemId(), Map.of(
                "allowedProcessKeys", nullSafe(saved.getAllowedProcessKeys()),
                "allowedActions", nullSafe(saved.getAllowedActions()),
                "ipWhitelist", nullSafe(saved.getIpWhitelist()),
                "allowedCandidateGroups", nullSafe(saved.getAllowedCandidateGroups()),
                "allowedWorkerTopics", nullSafe(saved.getAllowedWorkerTopics()),
                "allowOnBehalfOf", String.valueOf(saved.getAllowOnBehalfOf())));

        Map<String, Object> result = new HashMap<>();
        result.put("id", saved.getId());
        result.put("systemId", saved.getSystemId());
        result.put("systemName", saved.getSystemName());
        result.put("apiKey", plainKey); // 明文，僅此一次
        result.put("callbackSecret", plainCallbackSecret); // 明文，僅此一次
        return result;
    }

    @GetMapping
    public List<ExternalSystem> list() {
        List<ExternalSystem> all = repo.findAll();
        return all.stream().map(ExternalSystemAdminController::masked).toList();
    }

    @GetMapping("/{systemId}")
    public ExternalSystem get(@PathVariable String systemId) {
        return masked(find(systemId));
    }

    @PutMapping("/{systemId}")
    @Transactional("primaryTransactionManager")
    public ExternalSystem update(@PathVariable String systemId,
                                 @RequestBody ExternalSystem req,
                                 @CallerId
                                 String operatorId) {
        ExternalSystem sys = find(systemId);

        // 先記下舊值再套用 —— 順序反了就拿不到差異了。
        Map<String, Object> before = snapshot(sys);
        sys.setSystemName(req.getSystemName());
        sys.setContactEmail(req.getContactEmail());
        sys.setAllowedProcessKeys(req.getAllowedProcessKeys());
        sys.setAllowedActions(req.getAllowedActions());
        sys.setCallbackUrl(req.getCallbackUrl());
        sys.setIpWhitelist(req.getIpWhitelist());
        sys.setEnabled(req.getEnabled());
        // #88 政策 B：候選群組白名單。
        //
        // ⚠️ 與 allowedProcessKeys／allowedActions 同一個方向，這裡刻意
        // <b>不做「未帶就保留舊值」的處理</b>：欄位缺席 → null → 不限制。
        // 也就是說「PUT 少帶一個欄位 = 把授權放寬」，這是整欄覆寫語意的
        // 必然結果，也是 R-21（寫入端應強制必填）記錄的既有風險。
        //
        // 為什麼不在這裡把它改成「方向固定為失去能力」（像 allowOnBehalfOf 那樣
        // 明確轉 boolean）：因為「不限制」是這個欄位的預設語意（見
        // ExternalSystemPolicy.Kind.UNRESTRICTED），把「沒設定」誤判成
        // 「拒絕全部」會讓照 UI 正常流程建立的系統一個群組都不能用 ——
        // 那比放寬更糟（既有整合全部被鎖死）。
        // 真正的修法是管理頁必須讓人設定它，而那一半在 ExternalSystemAdmin.vue。
        sys.setAllowedCandidateGroups(req.getAllowedCandidateGroups());
        // #22 收尾：worker topic 白名單。與上面兩個欄位同一個方向、同一個
        // 整欄覆寫語意（欄位缺席 → null → 不限制），理由與 allowedCandidateGroups
        // 完全相同：把「沒設定」誤判成「拒絕全部」會讓照 UI 正常流程建立的
        // 系統一個 topic 都不能用，比放寬更糟。
        sys.setAllowedWorkerTopics(req.getAllowedWorkerTopics());
        // PUT 沒帶這個欄位時關閉 —— 錯誤的方向必須是「失去能力」而非「意外取得」。
        sys.setAllowOnBehalfOf(Boolean.TRUE.equals(req.getAllowOnBehalfOf()));
        ExternalSystem saved = repo.save(sys);

        Map<String, Object> changes = diff(before, snapshot(saved));
        if (!changes.isEmpty()) {
            audit(operatorId, "update", systemId, changes);
        }

        return masked(saved);
    }

    @DeleteMapping("/{systemId}")
    @Transactional("primaryTransactionManager")
    public Map<String, String> disable(@PathVariable String systemId,
                                       @CallerId
                                       String operatorId) {
        ExternalSystem sys = find(systemId);
        boolean wasEnabled = Boolean.TRUE.equals(sys.getEnabled());
        sys.setEnabled(false);
        repo.save(sys);

        // 已經是停用狀態時也記錄 —— 「嘗試停用」本身是有意義的事實，
        // 而且沉默會讓軌跡看起來像操作沒有發生。
        audit(operatorId, "disable", systemId, Map.of("wasEnabled", wasEnabled));
        return Map.of("status", "disabled");
    }

    /**
     * 輪換 API key（R-25 起支援寬限期）。
     *
     * <h2>改動前：輪替即中斷</h2>
     *
     * <p>舊金鑰在 {@code sys.setApiKey(newHash)} 寫入的瞬間失效，呼叫端
     * 到換設定之間的請求全部 401 —— 每一次輪替都是計畫性中斷。實務上的
     * 結果是「不敢輪替」，而不敢輪替的金鑰比有寬限期的輪替更危險。
     *
     * <h2>現在：上一把進 previous，新舊並存到到期</h2>
     *
     * <p>舊值移入 {@code previousApiKey}，到期時間 = 現在 +
     * {@code bpm.external.security.api-key.grace-period}（預設 24h）。
     * filter 在 current 不匹配時才檢查 previous，且只在未到期時放行。
     * 寬限期設 0（或負）＝維持改動前的立即失效行為，供「金鑰疑似外洩、
     * 必須立刻切斷」使用。
     *
     * <p>⚠️ 一次只有一個 previous：寬限期內再輪替一次，前一把會立刻失效。
     * 這是刻意的 —— 多槽並存會讓「哪把才是現行」失去單一答案。
     *
     * <p>稽核除了新舊雜湊前綴，加記舊金鑰的到期時間：事故調查問
     * 「當時舊金鑰還能用到什麼時候」時，設定檔的當下值已經不可考。
     */
    @PostMapping("/{systemId}/rotate-key")
    @Transactional("primaryTransactionManager")
    public Map<String, String> rotateKey(@PathVariable String systemId,
                                         @CallerId
                                         String operatorId) {
        ExternalSystem sys = find(systemId);
        String oldHashPrefix = hashPrefix(sys.getApiKey());
        String plainKey = ApiKeyUtil.generateKey();
        String newHash = apiKeyHasher.hash(plainKey);

        Duration grace = securityProperties.getApiKey().getGracePeriod();
        Instant previousExpiresAt = null;
        if (grace != null && !grace.isZero() && !grace.isNegative()) {
            sys.setPreviousApiKey(sys.getApiKey());
            previousExpiresAt = Instant.now().plus(grace);
            sys.setPreviousApiKeyExpiresAt(previousExpiresAt);
        } else {
            // 立即失效：連更早一次輪替留下、仍在寬限期的 previous 也一併清掉。
            // 「0 天寬限」的語意必須是「現在起只有這把新的能用」。
            sys.setPreviousApiKey(null);
            sys.setPreviousApiKeyExpiresAt(null);
        }
        sys.setApiKey(newHash);
        repo.save(sys);

        // ⚠️ 只記雜湊前綴，不記明文。見類別註解。
        // 輪換會讓原持有者的金鑰在寬限期後失效，所以這筆紀錄同時是
        // 「服務中斷（可預期時間點）」的線索。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("oldKeyHashPrefix", oldHashPrefix);
        detail.put("newKeyHashPrefix", hashPrefix(newHash));
        detail.put("previousKeyValidUntil",
                previousExpiresAt != null ? previousExpiresAt.toString() : "(none)");
        audit(operatorId, "rotate-key", systemId, detail);

        Map<String, String> result = new HashMap<>();
        result.put("systemId", systemId);
        result.put("apiKey", plainKey); // 明文，僅此一次
        // 空字串＝沒有寬限期。Map.of 不接受 null，而管理頁需要一個穩定形狀。
        result.put("previousKeyValidUntil",
                previousExpiresAt != null ? previousExpiresAt.toString() : "");
        return result;
    }

    /**
     * 輪換回呼密鑰（工項 #21）。與 {@link #rotateKey} 同一套模式：
     * 回傳新明文一次、回應與列表一律遮蔽、稽核只記雜湊前綴。
     *
     * <p>與 rotate-key 的唯一差別是儲存形式：回呼密鑰必須可還原（HMAC 驗簽
     * 需要原始密鑰），所以稽核前綴不能直接截密鑰字串，要先雜湊 ——
     * 見 {@link #secretHashPrefix}。
     *
     * <p>這是既有系統取得 callback secret 的<b>唯一</b>途徑 —— V5 migration
     * 之後它們的 callbackSecret 是 null（不能回呼），刻意不回填。
     */
    @PostMapping("/{systemId}/rotate-callback-secret")
    @Transactional("primaryTransactionManager")
    public Map<String, String> rotateCallbackSecret(@PathVariable String systemId,
                                                    @CallerId
                                                    String operatorId) {
        ExternalSystem sys = find(systemId);
        String oldHashPrefix = secretHashPrefix(sys.getCallbackSecret());
        String plainSecret = ApiKeyUtil.generateCallbackSecret();
        sys.setCallbackSecret(plainSecret);
        repo.save(sys);

        // ⚠️ 只記雜湊前綴，不記明文（與 rotate-key 相同理由，見類別註解）。
        // 輪換會讓原持有者的回呼立刻 401，所以這筆紀錄同時是「回呼中斷」的線索。
        audit(operatorId, "rotate-callback-secret", systemId, Map.of(
                "oldSecretHashPrefix", oldHashPrefix,
                "newSecretHashPrefix", secretHashPrefix(plainSecret)));

        return Map.of("systemId", systemId, "callbackSecret", plainSecret);
    }

    /**
     * 該外部系統的呼叫紀錄（工項 #20，分頁）。
     *
     * <h2>範圍：{@code operatorId = system:<systemId>} 的 {@code EXTERNAL_API_CALL}</h2>
     *
     * <p>外部系統的每一次呼叫都由伺服器鑄造的身分 {@code system:<systemId>}
     * 寫成 {@code EXTERNAL_API_CALL}（見 {@link ExternalActorIdentity}），
     * 所以「這個系統做過什麼」＝這兩個條件的交集。刻意<b>不</b>收
     * {@code CONFIG_CHANGE}：那些紀錄的 operatorId 是管理員，內容是
     * 「有人改了這個系統的授權」，屬於系統的<b>設定史</b>而不是它的<b>使用史</b>
     * —— 事故調查時兩者回答的是不同問題。
     *
     * <p>被拒絕的呼叫（{@code ExternalApiAuthFilter} 的 401／403／429）也在這個交集裡：
     * 它們以請求標頭的 {@code X-System-Id} 歸戶，所以「金鑰輪換後舊金鑰還在打」
     * 這類訊號看得出來。代價是未認證的呼叫端也能用別人的 systemId 產生紀錄
     * —— 這是既有稽核寫入路徑的性質，查詢結果不宜當成「對方確實持有金鑰」的證據。
     *
     * <h2>查詢規則與 {@code /api/audit-logs} 是同一份</h2>
     *
     * <p>直接走 {@link AuditLogService#search}／同一個 repository 查詢，只是把
     * {@code operatorId} 與 {@code operationType} 固定住。沒有第二份查詢、
     * 沒有第二種排序或日期語意 —— 分頁與日期參數的行為與
     * {@code AuditLogController.search} 逐字相同（日期為 ISO-8601 Instant）。
     *
     * <h2>已停用的系統仍可查歷史</h2>
     *
     * <p>{@code DELETE} 是停用而非刪除，而停用後最需要的就是「它以前做了什麼」
     * —— 事故調查通常發生在停用之後。所以這裡只驗系統存在（404 僅限未知的
     * systemId），不檢查 {@code enabled}。
     *
     * <p>查詢本身寫一筆 {@code DATA_ACCESS}（比照 {@code AuditLogController}）：
     * 回傳的 detail 可能含流程變數，翻閱它與翻閱稽核庫是同一種敏感行為。
     */
    @GetMapping("/{systemId}/usage-logs")
    public Page<AuditLog> usageLogs(
            @PathVariable String systemId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CallerId String requesterId) {

        find(systemId); // 未知系統 → 404；已停用系統仍可查（見上方說明）。

        Instant start = startDate != null ? Instant.parse(startDate) : null;
        Instant end = endDate != null ? Instant.parse(endDate) : null;

        Page<AuditLog> result = auditLogService.search(null,
                ExternalActorIdentity.of(systemId), OperationType.EXTERNAL_API_CALL,
                start, end, PageRequest.of(page, size));

        // 查稽核本身也要留紀錄（稽核稽核者，P2-4）。比照 AuditLogController.search
        // 只記查詢條件與命中筆數，不記回傳內容。
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("action", "usage-logs");
        access.put("systemId", systemId);
        access.put("startDate", nullSafe(startDate));
        access.put("endDate", nullSafe(endDate));
        access.put("page", page);
        access.put("size", size);
        access.put("totalHits", result.getTotalElements());
        auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(),
                requesterId != null && !requesterId.isBlank() ? requesterId : "unknown",
                null, null, access));

        return result;
    }

    private ExternalSystem find(String systemId) {
        return repo.findBySystemId(systemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void audit(String operatorId, String action, String systemId, Map<String, Object> detail) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("action", action);
        d.put("systemId", systemId);
        d.putAll(detail);
        auditPublisher.publish(new AuditEvent(
                OperationType.CONFIG_CHANGE.name(),
                operatorId != null && !operatorId.isBlank() ? operatorId : "unknown",
                null, null, d));
    }

    /**
     * 回應用的複本，apiKey 以 {@code ***} 遮蔽。
     *
     * <p>⚠️ <b>絕不可直接在 entity 上 {@code setApiKey("***")}</b>。
     * {@code update()} 有 {@code @Transactional}（稽核 fail-closed，P1-14），
     * 回傳的 entity 仍受 EntityManager 管理 —— 遮蔽會在 commit 時被 flush 進 DB，
     * <b>把該系統的 API key 雜湊蓋成 {@code ***}，外部系統立即全部 401</b>。
     * 改動前沒發生，只是因為方法沒有交易、entity 在 save() 後就已脫離管理。
     * 這是 R-20 線上實測時發現的。GET 端點一併改用複本，免得日後有人替它們加交易。
     */
    private static ExternalSystem masked(ExternalSystem s) {
        ExternalSystem m = new ExternalSystem();
        m.setId(s.getId());
        m.setSystemId(s.getSystemId());
        m.setSystemName(s.getSystemName());
        m.setApiKey("***");
        // R-25：previous 金鑰與 apiKey 同一條規則 —— 絕不可回傳可用的值。
        // 到期時間則保留：管理頁要靠它顯示「舊金鑰還能用多久」。
        m.setPreviousApiKey(s.getPreviousApiKey() == null ? null : "***");
        m.setPreviousApiKeyExpiresAt(s.getPreviousApiKeyExpiresAt());
        // callbackSecret 是「有／沒有」的狀態，不是值：null 保持 null（管理頁顯示
        // 尚未設定），有值才遮蔽成 ***。⚠️ 同 apiKey，絕不可在 entity 上遮蔽。
        m.setCallbackSecret(s.getCallbackSecret() == null ? null : "***");
        m.setContactEmail(s.getContactEmail());
        m.setAllowedProcessKeys(s.getAllowedProcessKeys());
        m.setAllowedActions(s.getAllowedActions());
        m.setCallbackUrl(s.getCallbackUrl());
        m.setIpWhitelist(s.getIpWhitelist());
        m.setEnabled(s.getEnabled());
        m.setAllowOnBehalfOf(s.getAllowOnBehalfOf());
        // ⚠️ 必須複製：管理頁的 applyForm() 只從列資料挑 blankForm() 認得的鍵。
        // 少了這行，編輯任一系統都會把白名單從 payload 裡弄丟，
        // 而後端 PUT 是整欄覆寫 → 儲存一次就把白名單清成「不限制」。
        m.setAllowedCandidateGroups(s.getAllowedCandidateGroups());
        // ⚠️ 同 allowedCandidateGroups：#22 收尾的 worker topic 白名單若不在
        // 回應裡，管理頁編輯任一系統都會把它靜默清成「不限制」。
        m.setAllowedWorkerTopics(s.getAllowedWorkerTopics());
        m.setCreatedAt(s.getCreatedAt());
        m.setLastUsedAt(s.getLastUsedAt());
        return m;
    }

    private static Map<String, Object> snapshot(ExternalSystem s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("systemName", nullSafe(s.getSystemName()));
        m.put("contactEmail", nullSafe(s.getContactEmail()));
        m.put("allowedProcessKeys", nullSafe(s.getAllowedProcessKeys()));
        m.put("allowedActions", nullSafe(s.getAllowedActions()));
        m.put("callbackUrl", nullSafe(s.getCallbackUrl()));
        m.put("ipWhitelist", nullSafe(s.getIpWhitelist()));
        m.put("enabled", String.valueOf(s.getEnabled()));
        m.put("allowOnBehalfOf", String.valueOf(s.getAllowOnBehalfOf()));
        m.put("allowedCandidateGroups", nullSafe(s.getAllowedCandidateGroups()));
        m.put("allowedWorkerTopics", nullSafe(s.getAllowedWorkerTopics()));
        return m;
    }

    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : AUDITED_FIELDS) {
            Object b = before.get(field);
            Object a = after.get(field);
            if (!Objects.equals(b, a)) {
                changes.put(field, b + " → " + a);
            }
        }
        return changes;
    }

    /** 雜湊前綴足以對帳（確認換的是哪一把），不足以使用。 */
    private static String hashPrefix(String hash) {
        if (hash == null || hash.length() < 8) return "(none)";
        return hash.substring(0, 8) + "…";
    }

    /**
     * 回呼密鑰的稽核前綴。
     *
     * <p>⚠️ 回呼密鑰以可還原形式儲存（HMAC 需要），所以<b>不能</b>像 apiKey
     * 那樣直接截前 8 碼 —— 那會把密鑰的一部分寫進可查詢的稽核庫。先雜湊再截。
     *
     * <p>這裡刻意用 {@link ApiKeyUtil#hash}（SHA-256）而不是 v2 HMAC：
     * 它只是一個「足以對帳、不足以使用」的單向指紋，不需要 server secret；
     * 換成 HMAC 也買不到額外保護（指紋本來就不可用於驗簽）。
     */
    private static String secretHashPrefix(String secret) {
        return secret == null ? "(none)" : hashPrefix(ApiKeyUtil.hash(secret));
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
