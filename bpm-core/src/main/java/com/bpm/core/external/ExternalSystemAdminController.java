package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/external-systems")
public class ExternalSystemAdminController {

    private final ExternalSystemRepository repo;

    public ExternalSystemAdminController(ExternalSystemRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ExternalSystem sys) {
        String plainKey = ApiKeyUtil.generateKey();
        sys.setApiKey(ApiKeyUtil.hash(plainKey));
        sys.setEnabled(true);
        ExternalSystem saved = repo.save(sys);
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
    public ExternalSystem update(@PathVariable String systemId, @RequestBody ExternalSystem req) {
        ExternalSystem sys = find(systemId);
        sys.setSystemName(req.getSystemName());
        sys.setContactEmail(req.getContactEmail());
        sys.setAllowedProcessKeys(req.getAllowedProcessKeys());
        sys.setAllowedActions(req.getAllowedActions());
        sys.setCallbackUrl(req.getCallbackUrl());
        sys.setIpWhitelist(req.getIpWhitelist());
        sys.setEnabled(req.getEnabled());
        ExternalSystem saved = repo.save(sys);
        saved.setApiKey("***");
        return saved;
    }

    @DeleteMapping("/{systemId}")
    public Map<String, String> disable(@PathVariable String systemId) {
        ExternalSystem sys = find(systemId);
        sys.setEnabled(false);
        repo.save(sys);
        return Map.of("status", "disabled");
    }

    @PostMapping("/{systemId}/rotate-key")
    public Map<String, String> rotateKey(@PathVariable String systemId) {
        ExternalSystem sys = find(systemId);
        String plainKey = ApiKeyUtil.generateKey();
        sys.setApiKey(ApiKeyUtil.hash(plainKey));
        repo.save(sys);
        return Map.of("systemId", systemId, "apiKey", plainKey);
    }

    @GetMapping("/{systemId}/usage-logs")
    public Map<String, String> usageLogs(@PathVariable String systemId) {
        // Delegate to audit-log-service; placeholder for now
        return Map.of("message", "Query audit-log-service with operatorSource=external_api and systemId=" + systemId);
    }

    private ExternalSystem find(String systemId) {
        return repo.findBySystemId(systemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
