package com.bpm.core.dto;

import java.util.List;

public record CacheInvalidateRequest(
        String type,
        List<String> userIds,
        List<String> permCodes
) {}
