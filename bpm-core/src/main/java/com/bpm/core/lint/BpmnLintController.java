package com.bpm.core.lint;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bpmn")
public class BpmnLintController {

    private final BpmnLintService lintService;

    public BpmnLintController(BpmnLintService lintService) {
        this.lintService = lintService;
    }

    @PostMapping("/lint")
    public BpmnLintService.LintResult lint(@RequestBody String xml) {
        return lintService.lint(xml);
    }
}
