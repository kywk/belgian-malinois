package com.bpm.core.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
    public class HealthController {

    @GetMapping("/health")
    @io.swagger.v3.oas.annotations.Operation(summary="讀取 BPM Core 存活結果", description="固定回service=bpm-core/status=UP；/api/health仍需認證，公開健康路徑是另行提供的/actuator/health。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="HealthController_health")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="讀取 BPM Core 存活結果成功；直接回傳所列資料，不另加code/data envelope", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.HealthView.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    public Map<String, String> health() {
        return Map.of("service", "bpm-core", "status", "UP");
    }
}
