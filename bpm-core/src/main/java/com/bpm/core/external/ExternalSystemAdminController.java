package com.bpm.core.external;

import com.bpm.core.security.CallerId;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
 * <h2>⚠️ 明文金鑰絕不可進稽核庫</h2>
 *
 * <p>API key 只在建立與輪換時回傳一次明文，之後只存雜湊。若把明文寫進
 * 稽核紀錄，雜湊就白做了 —— 稽核庫是可查詢的，而且保留期通常比金鑰生命週期長。
 * 所以稽核只記「金鑰被輪換了」這個事實與雜湊的前 8 碼（足以對帳，不足以使用）。
 */
@RestController
@RequestMapping("/api/admin/external-systems")
public class ExternalSystemAdminController {

    /** 授權相關欄位。update 時逐一比對前後值寫進稽核。 */
    private static final List<String> AUDITED_FIELDS =
            List.of("systemName", "contactEmail", "allowedProcessKeys", "allowedActions",
                    "callbackUrl", "ipWhitelist", "enabled");

    private final ExternalSystemRepository repo;
    private final AuditEventPublisher auditPublisher;

    public ExternalSystemAdminController(ExternalSystemRepository repo,
                                         AuditEventPublisher auditPublisher) {
        this.repo = repo;
        this.auditPublisher = auditPublisher;
    }

    @PostMapping
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
        sys.setApiKey(ApiKeyUtil.hash(plainKey));
        sys.setEnabled(true);
        ExternalSystem saved = repo.save(sys);

        audit(operatorId, "create", saved.getSystemId(), Map.of(
                "allowedProcessKeys", nullSafe(saved.getAllowedProcessKeys()),
                "allowedActions", nullSafe(saved.getAllowedActions()),
                "ipWhitelist", nullSafe(saved.getIpWhitelist())));

        Map<String, Object> result = new HashMap<>();
        result.put("id", saved.getId());
        result.put("systemId", saved.getSystemId());
        result.put("systemName", saved.getSystemName());
        result.put("apiKey", plainKey); // 明文，僅此一次
        return result;
    }

    @GetMapping
    public List<ExternalSystem> list() {
        List<ExternalSystem> all = repo.findAll();
        all.forEach(s -> s.setApiKey("***")); // 不回傳 hash
        return all;
    }

    @GetMapping("/{systemId}")
    public ExternalSystem get(@PathVariable String systemId) {
        ExternalSystem sys = find(systemId);
        sys.setApiKey("***");
        return sys;
    }

    @PutMapping("/{systemId}")
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
        ExternalSystem saved = repo.save(sys);

        Map<String, Object> changes = diff(before, snapshot(saved));
        if (!changes.isEmpty()) {
            audit(operatorId, "update", systemId, changes);
        }

        saved.setApiKey("***");
        return saved;
    }

    @DeleteMapping("/{systemId}")
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

    @PostMapping("/{systemId}/rotate-key")
    public Map<String, String> rotateKey(@PathVariable String systemId,
                                         @CallerId
                                         String operatorId) {
        ExternalSystem sys = find(systemId);
        String oldHashPrefix = hashPrefix(sys.getApiKey());
        String plainKey = ApiKeyUtil.generateKey();
        String newHash = ApiKeyUtil.hash(plainKey);
        sys.setApiKey(newHash);
        repo.save(sys);

        // ⚠️ 只記雜湊前綴，不記明文。見類別註解。
        // 輪換會讓原持有者的金鑰立刻失效，所以這筆紀錄同時是「服務中斷」的線索。
        audit(operatorId, "rotate-key", systemId, Map.of(
                "oldKeyHashPrefix", oldHashPrefix,
                "newKeyHashPrefix", hashPrefix(newHash)));

        return Map.of("systemId", systemId, "apiKey", plainKey);
    }

    @GetMapping("/{systemId}/usage-logs")
    public Map<String, String> usageLogs(@PathVariable String systemId) {
        // Placeholder: 稽核查詢已併入 bpm-core（2026-04-24），改指向本服務的 /api/audit-logs。
        // 真正的實作見 docs/backend-development-backlog.md 外部系統 usage logs 項目。
        return Map.of("message", "Query /api/audit-logs with operatorSource=external_api and systemId=" + systemId);
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

    private static Map<String, Object> snapshot(ExternalSystem s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("systemName", nullSafe(s.getSystemName()));
        m.put("contactEmail", nullSafe(s.getContactEmail()));
        m.put("allowedProcessKeys", nullSafe(s.getAllowedProcessKeys()));
        m.put("allowedActions", nullSafe(s.getAllowedActions()));
        m.put("callbackUrl", nullSafe(s.getCallbackUrl()));
        m.put("ipWhitelist", nullSafe(s.getIpWhitelist()));
        m.put("enabled", String.valueOf(s.getEnabled()));
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

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
