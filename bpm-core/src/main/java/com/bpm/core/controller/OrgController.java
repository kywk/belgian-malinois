package com.bpm.core.controller;

import com.bpm.core.client.OrgRestClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** 登入者的任務選人查詢；只回傳選人所需欄位，不暴露組織服務憑證或聯絡資料。 */
@RestController
@RequestMapping("/api/org")
    public class OrgController {
    private final OrgRestClient client;

    public OrgController(OrgRestClient client) {
        this.client = client;
    }

    @GetMapping("/users/search")
    @io.swagger.v3.oas.annotations.Operation(summary="搜尋組織人員", description="keyword去空白；空字串回空陣列，超過100字400；最多50筆PersonOption，上游無有效結果502。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="OrgController_searchUsers")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="搜尋組織人員成功；直接回傳 List<PersonOption>，動態Map欄位依本operation描述及領域內容。", useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="輸入形狀或業務前置條件不合法；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="502", description="上游回應不可用；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    public List<PersonOption> searchUsers(@RequestParam String keyword) {
        String query = keyword.trim();
        if (query.isEmpty()) return List.of();
        if (query.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜尋文字不得超過 100 字");
        }
        List<Map<String, Object>> users = client.searchUsers(query);
        if (users == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "組織查詢未回傳有效結果");
        }
        return users.stream().limit(50)
                .map(user -> new PersonOption((String) user.get("userId"),
                        (String) user.get("name"), (String) user.get("deptId")))
                .toList();
    }

    public record PersonOption(String userId, String name, String deptId) {}
}
