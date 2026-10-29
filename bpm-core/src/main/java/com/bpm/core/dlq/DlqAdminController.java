package com.bpm.core.dlq;

import com.bpm.core.security.CallerId;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DLQ 維運端點（#51）。
 *
 * <h2>為什麼放在 {@code /api/admin/**}</h2>
 *
 * <p>重放會把死信<b>重新打回下游</b>：webhook 會再送一次 HTTP、
 * 通知會再寄一次信、稽核會再 append 一次。這是一個有外部副作用、
 * 而且可能造成重複投遞的操作，與部署 BPMN 同一級 —— 所以由
 * {@code SecurityConfig} 既有的 {@code /api/admin/** → ROLE_ADMIN}
 * 規則保護，這裡不新增授權規則（授權矩陣只有一份）。
 *
 * <p>⚠️ 本端點<b>沒有</b> dry-run：呼叫即重放。上限（{@code max}，
 * 預設 100、硬上限 1000）是唯一的防呆，讓誤觸最多影響 1000 筆。
 * 未來的 UI 若要「先看再放」，應該另做查詢端點而不是讓這支支援
 * 無副作用的預覽 —— 兩種語意混在同一支會讓「以為是預覽」變成
 * 真的重放。
 */
@RestController
@RequestMapping("/api/admin/dlq")
    public class DlqAdminController {

    private final DlqReplayService replayService;

    public DlqAdminController(DlqReplayService replayService) {
        this.replayService = replayService;
    }

    /**
     * 重放指定 DLQ 的訊息。
     *
     * @param queue      {@code bpm}（dlq.parking.bpm）或 {@code audit}（dlq.parking.audit）；其餘 400
     * @param max        本輪最多重放幾筆，預設 100，上限 1000
     * @param operatorId 已認證的呼叫者，寫入 {@code DLQ_REPLAY} 稽核
     */
    @PostMapping("/replay")
    @io.swagger.v3.oas.annotations.Operation(summary="重放死信佇列", description="query queue與max（預設100）；由服務核對佇列及上限，回ReplayResult並記錄操作者。 需要ROLE_ADMIN。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="DlqAdminController_replay")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="重放死信佇列成功；直接回傳 DlqReplayService.ReplayResult，動態Map欄位依本operation描述及領域內容。", useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    public DlqReplayService.ReplayResult replay(
            @RequestParam String queue,
            @RequestParam(defaultValue = "100") int max,
            @io.swagger.v3.oas.annotations.Parameter(hidden=true) @CallerId String operatorId) {
        return replayService.replay(queue, max, operatorId);
    }
}
