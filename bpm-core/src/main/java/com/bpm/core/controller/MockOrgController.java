package com.bpm.core.controller;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Mock controller for external org system. Used during development only.
 */
@RestController
@RequestMapping("/mock/org/api")
public class MockOrgController {

    @GetMapping("/users/{userId}")
    public Map<String, Object> getUser(@PathVariable String userId) {
        return Map.of("userId", userId, "name", "User " + userId, "deptId", "dept001");
    }

    @GetMapping("/users/{userId}/manager")
    public Map<String, String> getManager(@PathVariable String userId) {
        return Map.of("managerId", "mgr_" + userId);
    }

    @GetMapping("/users/{userId}/manager-chain")
    public List<String> getManagerChain(@PathVariable String userId, @RequestParam(defaultValue = "3") int levels) {
        return List.of("mgr_" + userId, "dir_" + userId, "vp_" + userId).subList(0, Math.min(levels, 3));
    }

    @GetMapping("/users/{userId}/department")
    public Map<String, String> getDepartment(@PathVariable String userId) {
        return Map.of("deptId", "dept001");
    }

    @GetMapping("/users/{userId}/substitute")
    public Map<String, String> getSubstitute(@PathVariable String userId) {
        return Map.of();  // No substitute by default
    }

    @GetMapping("/departments/{deptId}/members")
    public List<String> getDeptMembers(@PathVariable String deptId) {
        return List.of("user001", "user002", "user003");
    }
}
