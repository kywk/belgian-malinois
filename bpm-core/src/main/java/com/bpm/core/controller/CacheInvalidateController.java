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
    @io.swagger.v3.oas.annotations.Operation(summary="失效組織快取", description="依userIds/type與deptIds清除對應快取；僅ROLE_GATEWAY可呼叫。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="CacheInvalidateController_invalidateOrg")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="失效組織快取成功；直接回傳所列資料，不另加code/data envelope", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.StatusView.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="輸入形狀或業務前置條件不合法；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true, content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=CacheInvalidateRequest.class)))
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
    @io.swagger.v3.oas.annotations.Operation(summary="失效權限快取", description="依userIds及permCodes清除權限快取；僅ROLE_GATEWAY可呼叫。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="CacheInvalidateController_invalidatePerm")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="失效權限快取成功；直接回傳所列資料，不另加code/data envelope", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.StatusView.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="輸入形狀或業務前置條件不合法；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true, content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=CacheInvalidateRequest.class)))
    public Map<String, String> invalidatePerm(@RequestBody CacheInvalidateRequest req) {
        permService.invalidateCache(req.userIds(), req.permCodes());
        return Map.of("status", "ok");
    }
}
