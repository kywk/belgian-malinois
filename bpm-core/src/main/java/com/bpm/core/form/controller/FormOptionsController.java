package com.bpm.core.form.controller;

import com.bpm.core.form.service.FormOptionsService;
import com.bpm.core.form.service.FormOptionsService.FormOption;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 表單動態選項的代理端點（#56）。
 *
 * <p>{@code GET /api/forms/options?url=...}：前端不直接連外部選項來源，
 * 由後端代抓後回 {@code {"options":[{label,value},...]}}。抓取、SSRF 政策、
 * 快取與錯誤語意的規則全部在 {@link FormOptionsService}（唯一一份），
 * controller 只負責路由與回應形狀。
 *
 * <h2>為什麼是 {@code /api/forms/options} 而不是獨立前綴</h2>
 *
 * <p>它與表單 schema 同一組資源。⚠️ 與 {@code FormDefinitionController} 的
 * {@code GET /api/forms/{formKey}} 並存：Spring MVC 對字面路徑
 * （{@code /options}）的優先序高於變數路徑（{@code /{formKey}}），所以
 * 這個端點會贏；代價是<b>不能有 formKey 叫 "options"</b>（讀 schema 會被
 * 這裡攔走）。formKey 由設計者命名，這個保留字是已知限制。
 *
 * <h2>授權</h2>
 *
 * <p>沒有專屬規則，落在 {@code SecurityConfig} 的 {@code /api/**}
 * → {@code authenticated()}：任何登入者都能查選項。這是刻意的 —— 選項
 * 是填表需要的資料，不該要求表單設計權限；真正的安全邊界是
 * {@code WebhookUrlPolicy}（能打哪些位址）而不是「誰能查」。
 */
@RestController
@RequestMapping("/api/forms/options")
    public class FormOptionsController {

    private final FormOptionsService formOptionsService;

    public FormOptionsController(FormOptionsService formOptionsService) {
        this.formOptionsService = formOptionsService;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(summary="查詢允許的表單選項來源", description="query url由FormOptionsService驗證並載入，回options陣列。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="FormOptionsController_options")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="查詢允許的表單選項來源成功；直接回傳 Map<String, List<FormOption>>，動態Map欄位依本operation描述及領域內容。", useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    public Map<String, List<FormOption>> options(@RequestParam("url") String url) {
        return Map.of("options", formOptionsService.load(url));
    }
}
