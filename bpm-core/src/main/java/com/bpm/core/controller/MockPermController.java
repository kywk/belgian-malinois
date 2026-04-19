package com.bpm.core.controller;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Mock controller for external permission system. Used during development only.
 */
@RestController
@RequestMapping("/mock/perm/api")
public class MockPermController {

    private static final Map<String, List<String>> PERM_USERS = new HashMap<>();
    private static final Map<String, List<String>> USER_PERMS = new HashMap<>();

    static {
        PERM_USERS.put("hr:leave:approve",      List.of("mgr001", "mgr002", "dir001"));
        PERM_USERS.put("finance:payment:approve", List.of("mgr001", "dir001"));
        PERM_USERS.put("purchase:order:approve",  List.of("mgr001", "mgr002", "dir001"));
        PERM_USERS.put("legal:contract:review",   List.of("dir001"));
        PERM_USERS.put("purchase:self:approve",   List.of("dir001"));

        USER_PERMS.put("mgr001", List.of("hr:leave:approve", "finance:payment:approve", "purchase:order:approve"));
        USER_PERMS.put("mgr002", List.of("hr:leave:approve", "purchase:order:approve"));
        USER_PERMS.put("dir001", List.of("hr:leave:approve", "finance:payment:approve", "purchase:order:approve",
                                          "legal:contract:review", "purchase:self:approve"));
        USER_PERMS.put("admin001", List.of("*"));
    }

    @GetMapping("/permissions/{permCode}/users")
    public List<String> getUsersByPermission(@PathVariable String permCode,
                                              @RequestParam(required = false) String deptId) {
        List<String> users = PERM_USERS.getOrDefault(permCode, List.of("mgr001"));
        if (deptId == null) return users;
        // 簡單過濾：dept001 → mgr001/dir001, dept002 → mgr002
        return users.stream().filter(u -> {
            if ("dept001".equals(deptId)) return List.of("mgr001", "dir001", "user001", "user002", "user003").contains(u);
            if ("dept002".equals(deptId)) return List.of("mgr002", "user004", "user005").contains(u);
            return true;
        }).toList();
    }

    @GetMapping("/users/{userId}/permissions")
    public List<String> getUserPermissions(@PathVariable String userId) {
        return USER_PERMS.getOrDefault(userId, List.of());
    }

    @GetMapping("/users/{userId}/has-permission")
    public Map<String, Object> hasPermission(@PathVariable String userId, @RequestParam String code) {
        List<String> perms = USER_PERMS.getOrDefault(userId, List.of());
        boolean has = perms.contains("*") || perms.contains(code);
        return Map.of("hasPermission", has);
    }
}
