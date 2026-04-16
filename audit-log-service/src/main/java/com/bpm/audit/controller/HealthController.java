package com.bpm.audit.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "audit-log-service", "status", "UP");
    }
}
