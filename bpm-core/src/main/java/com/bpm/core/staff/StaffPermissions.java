package com.bpm.core.staff;

import java.util.List;
import java.util.Map;

public final class StaffPermissions {
    private StaffPermissions() {}
    public static final String CONSOLE = "bpm:console:access:use";
    public static final String READ = "bpm:task:work:read";
    public static final String ACT = "bpm:task:work:act";
    public static final String DESIGN = "bpm:form:definition:design";
    public static final String DEPLOY = "bpm:bpmn:definition:deploy";
    public static final String MANAGE = "bpm:system:config:manage";
    public static final String AUDIT = "bpm:audit:log:read";
    public static final List<String> CATALOG = List.of(CONSOLE, READ, ACT, DESIGN, DEPLOY, MANAGE, AUDIT);
    public static final Map<String, String> ALIASES = Map.of(DESIGN, "bpm:form:design",
            DEPLOY, "bpm:bpmn:deploy", AUDIT, "audit:log:read");
    public static String canonical(String code) {
        return ALIASES.entrySet().stream().filter(e -> e.getValue().equals(code))
                .map(Map.Entry::getKey).findFirst().orElse(code);
    }
}
