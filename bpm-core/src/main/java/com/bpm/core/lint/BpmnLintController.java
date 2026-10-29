package com.bpm.core.lint;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

/**
 * BPMN 靜態檢查。設計器在存檔前會打這裡。
 *
 * <h2>這個端點的暴露面（security-audit P2-5）</h2>
 *
 * <p>⚠️ 這段說明已過期：當時本專案完全沒有 Spring Security，所以在單一端點
 * 加認證沒有意義。平台層認證已於 R-01 完成，本端點現在要求
 * {@code authenticated()}（見 {@code SecurityConfig}）。
 *
 * <p>這裡處理的是<b>這個端點特有</b>的暴露面：它是一個接受任意 XML 的解析入口。
 *
 * <h3>實測過的兩條攻擊路徑目前都走不通</h3>
 *
 * <ul>
 *   <li>外部實體（XXE）：已關閉 —— 指向存在與不存在的檔案得到完全相同的錯誤。</li>
 *   <li>實體展開（billion laughs）：被 JDK 的 entityExpansionLimit 擋下，
 *       五層以上在 0.05 秒內被拒。</li>
 * </ul>
 *
 * <p>兩者都是<b>繼承來的</b>安全性，不是設計出來的。
 * {@link BpmnLintService#hardenedXmlInputFactory} 已改為明確關閉 DTD，
 * 不再依賴 JDK 預設值。
 *
 * <h3>剩下的是輸入大小</h3>
 *
 * <p>{@code @RequestBody String} 會把整份 body 讀進記憶體，再轉成
 * {@code byte[]} 給解析器 —— 同一份內容至少存在兩份。Tomcat 的
 * {@code max-http-form-post-size} 不管非表單的 body，所以這裡自己設上限。
 *
 * <p>真實的 BPMN 檔案是幾十 KB 量級；上限設在遠高於實際需求、
 * 但仍能防止單一請求吃掉大量堆積的位置。超過就回 413，而不是讓它進到解析器。
 */
@RestController
@RequestMapping("/api/bpmn")
    public class BpmnLintController {

    private final BpmnLintService lintService;
    private final int maxBytes;

    public BpmnLintController(BpmnLintService lintService,
                              @Value("${bpm.lint.max-xml-bytes:2097152}") int maxBytes) {
        this.lintService = lintService;
        this.maxBytes = maxBytes;
    }

    @PostMapping("/lint")
    @io.swagger.v3.oas.annotations.Operation(summary="檢查 BPMN XML", description="接收原始XML文字；以UTF-8 bytes限制容量，空白400、過大413。回傳valid與lint問題，不部署流程。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="BpmnLintController_lint")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="檢查 BPMN XML成功；直接回傳 BpmnLintService.LintResult，動態Map欄位依本operation描述及領域內容。", useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="輸入形狀或業務前置條件不合法；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="413", description="輸入超過限制；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true, content=@io.swagger.v3.oas.annotations.media.Content(mediaType="text/plain", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=String.class)))
    public BpmnLintService.LintResult lint(@RequestBody String xml) {
        if (xml == null || xml.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BPMN XML 不可為空");
        }
        // 用 UTF-8 的位元組數判斷，與解析器實際處理的長度一致 ——
        // 中文 BPMN 的字元數與位元組數差三倍，用 length() 會低估。
        int bytes = xml.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "BPMN XML 過大：%d bytes，上限 %d bytes。真實的流程定義是幾十 KB 量級，"
                            .formatted(bytes, maxBytes)
                            + "若確實需要更大，請調整 bpm.lint.max-xml-bytes。");
        }
        return lintService.lint(xml);
    }
}
