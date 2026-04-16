package com.bpm.core.controller;

import com.bpm.core.dto.CacheInvalidateRequest;
import com.bpm.core.service.BpmPermissionService;
import com.bpm.core.service.OrgService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/internal/cache-invalidate")
public class CacheInvalidateController {

    private final OrgService orgService;
    private final BpmPermissionService permService;

    public CacheInvalidateController(OrgService orgService, BpmPermissionService permService) {
        this.orgService = orgService;
        this.permService = permService;
    }

    @PostMapping("/org")
    public Map<String, String> invalidateOrg(@RequestBody CacheInvalidateRequest req) {
        orgService.invalidateCache(req.userIds(), req.type());
        return Map.of("status", "ok");
    }

    @PostMapping("/perm")
    public Map<String, String> invalidatePerm(@RequestBody CacheInvalidateRequest req) {
        permService.invalidateCache(req.userIds(), req.permCodes());
        return Map.of("status", "ok");
    }
}
