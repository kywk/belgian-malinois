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
     * @param queue      {@code bpm}（dlq.bpm）或 {@code audit}（dlq.audit）；其餘 400
     * @param max        本輪最多重放幾筆，預設 100，上限 1000
     * @param operatorId 已認證的呼叫者，寫入 {@code DLQ_REPLAY} 稽核
     */
    @PostMapping("/replay")
    public DlqReplayService.ReplayResult replay(
            @RequestParam String queue,
            @RequestParam(defaultValue = "100") int max,
            @CallerId String operatorId) {
        return replayService.replay(queue, max, operatorId);
    }
}
