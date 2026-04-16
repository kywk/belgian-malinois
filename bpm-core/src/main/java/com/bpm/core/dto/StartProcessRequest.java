package com.bpm.core.dto;

import java.util.Map;

public record StartProcessRequest(
        String processDefinitionKey,
        String businessKey,
        String initiator,
        Map<String, Object> variables
) {}
