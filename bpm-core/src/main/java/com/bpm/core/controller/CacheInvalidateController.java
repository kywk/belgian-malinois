package com.bpm.core.controller;

import com.bpm.core.dto.CacheInvalidateRequest;
import com.bpm.core.service.BpmPermissionService;
import com.bpm.core.service.OrgService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * <p>未知的 {@code type} 回 400。改動前那個參數完全沒有被讀取，
     * 呼叫端送什麼都是「全部清掉」—— 現在打錯字會被告知，
     * 而不是靜默地做別的事（security-audit P2-8）。
     *
     * <p>本專案沒有 {@code @ControllerAdvice}，所以在這裡明確轉成
     * {@link ResponseStatusException}，否則 IllegalArgumentException 會變成 500。
     */
    @PostMapping("/org")
    public Map<String, String> invalidateOrg(@RequestBody CacheInvalidateRequest req) {
        try {
            orgService.invalidateCache(req.userIds(), req.type());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        // 部門成員清單：使用者「加入」新部門那一半後端推導不出來，
        // 必須由呼叫端明確指定。見 CacheInvalidateRequest#deptIds。
        orgService.invalidateDeptMembers(req.deptIds());
        return Map.of("status", "ok");
    }

    @PostMapping("/perm")
    public Map<String, String> invalidatePerm(@RequestBody CacheInvalidateRequest req) {
        permService.invalidateCache(req.userIds(), req.permCodes());
        return Map.of("status", "ok");
    }
}
