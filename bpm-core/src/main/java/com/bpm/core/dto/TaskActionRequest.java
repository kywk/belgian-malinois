package com.bpm.core.dto;

import java.util.List;
import java.util.Map;

public record TaskActionRequest(
        String action,           // claim, complete, delegate, resolve
        String assignee,         // for reassign
        String delegateUser,     // for delegate
        List<VariableRequest> variables
) {
    public record VariableRequest(String name, Object value) {}
}
