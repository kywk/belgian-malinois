package com.bpm.core.controller;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Mock controller for external permission system. Used during development only.
 */
@RestController
@RequestMapping("/mock/perm/api")
public class MockPermController {

    @GetMapping("/permissions/{permCode}/users")
    public List<String> getUsersByPermission(@PathVariable String permCode,
                                              @RequestParam(required = false) String deptId) {
        return List.of("user001", "user002");
    }

    @GetMapping("/users/{userId}/permissions")
    public List<String> getUserPermissions(@PathVariable String userId) {
        return List.of("finance:payment:approve", "hr:leave:approve", "purchase:order:approve");
    }

    @GetMapping("/users/{userId}/has-permission")
    public Map<String, Object> hasPermission(@PathVariable String userId, @RequestParam String code) {
        return Map.of("hasPermission", true);
    }
}
