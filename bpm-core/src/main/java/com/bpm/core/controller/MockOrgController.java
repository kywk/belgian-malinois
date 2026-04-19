package com.bpm.core.controller;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Mock controller for external org system. Used during development only.
 *
 * Test users:
 *   user001~user003  → dept001 員工，主管 mgr001
 *   user004~user005  → dept002 員工，主管 mgr002
 *   mgr001           → dept001 主管，主管 dir001
 *   mgr002           → dept002 主管，主管 dir001
 *   dir001           → 總監
 *   admin001         → 系統管理員
 */
@RestController
@RequestMapping("/mock/org/api")
public class MockOrgController {

    private static final Map<String, Map<String, Object>> USERS = new LinkedHashMap<>();
    private static final Map<String, String> MANAGER_MAP = new HashMap<>();
    private static final Map<String, String> DEPT_MAP = new HashMap<>();

    static {
        USERS.put("user001", Map.of("userId", "user001", "name", "王小明", "deptId", "dept001", "email", "user001@example.com"));
        USERS.put("user002", Map.of("userId", "user002", "name", "李小華", "deptId", "dept001", "email", "user002@example.com"));
        USERS.put("user003", Map.of("userId", "user003", "name", "張小芳", "deptId", "dept001", "email", "user003@example.com"));
        USERS.put("user004", Map.of("userId", "user004", "name", "陳大文", "deptId", "dept002", "email", "user004@example.com"));
        USERS.put("user005", Map.of("userId", "user005", "name", "林小玲", "deptId", "dept002", "email", "user005@example.com"));
        USERS.put("mgr001", Map.of("userId", "mgr001", "name", "李主管",  "deptId", "dept001", "email", "mgr001@example.com"));
        USERS.put("mgr002", Map.of("userId", "mgr002", "name", "陳主管",  "deptId", "dept002", "email", "mgr002@example.com"));
        USERS.put("dir001", Map.of("userId", "dir001", "name", "王總監",  "deptId", "dept001", "email", "dir001@example.com"));
        USERS.put("admin001", Map.of("userId", "admin001", "name", "系統管理員", "deptId", "admin", "email", "admin@example.com"));

        MANAGER_MAP.put("user001", "mgr001"); MANAGER_MAP.put("user002", "mgr001"); MANAGER_MAP.put("user003", "mgr001");
        MANAGER_MAP.put("user004", "mgr002"); MANAGER_MAP.put("user005", "mgr002");
        MANAGER_MAP.put("mgr001", "dir001");  MANAGER_MAP.put("mgr002", "dir001");

        USERS.forEach((uid, u) -> DEPT_MAP.put(uid, (String) u.get("deptId")));
    }

    @GetMapping("/users")
    public Collection<Map<String, Object>> listUsers() { return USERS.values(); }

    @GetMapping("/users/{userId}")
    public Map<String, Object> getUser(@PathVariable String userId) {
        return USERS.getOrDefault(userId,
            Map.of("userId", userId, "name", "User " + userId, "deptId", "dept001", "email", userId + "@example.com"));
    }

    @GetMapping("/users/{userId}/manager")
    public Map<String, String> getManager(@PathVariable String userId) {
        return Map.of("managerId", MANAGER_MAP.getOrDefault(userId, "mgr001"));
    }

    @GetMapping("/users/{userId}/manager-chain")
    public List<String> getManagerChain(@PathVariable String userId, @RequestParam(defaultValue = "3") int levels) {
        List<String> chain = new ArrayList<>();
        String current = userId;
        for (int i = 0; i < levels; i++) {
            String mgr = MANAGER_MAP.get(current);
            if (mgr == null) break;
            chain.add(mgr);
            current = mgr;
        }
        return chain;
    }

    @GetMapping("/users/{userId}/department")
    public Map<String, String> getDepartment(@PathVariable String userId) {
        return Map.of("deptId", DEPT_MAP.getOrDefault(userId, "dept001"));
    }

    @GetMapping("/users/{userId}/substitute")
    public Map<String, String> getSubstitute(@PathVariable String userId) { return Map.of(); }

    @GetMapping("/departments/{deptId}/members")
    public List<String> getDeptMembers(@PathVariable String deptId) {
        List<String> members = new ArrayList<>();
        DEPT_MAP.forEach((uid, dept) -> { if (dept.equals(deptId)) members.add(uid); });
        return members.isEmpty() ? List.of("user001", "user002", "user003") : members;
    }
}
