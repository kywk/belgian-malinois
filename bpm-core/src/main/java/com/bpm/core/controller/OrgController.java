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
