package com.bpm.core.staff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;

public final class StaffErrorWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private StaffErrorWriter() {}
    public static void write(HttpServletRequest req, HttpServletResponse res, StaffFailure failure) throws IOException {
        res.setStatus(failure.status());
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-store");
        var body = new LinkedHashMap<String, Object>();
        body.put("code", failure.code()); body.put("message", failure.getMessage());
        body.put("traceId", UUID.randomUUID().toString()); body.put("requestId", null);
        body.put("retryable", failure.status() == 503);
        body.put("outcome", "GET".equals(req.getMethod()) ? "NOT_APPLICABLE" : "NOT_APPLIED");
        res.getWriter().write(JSON.writeValueAsString(body));
    }
}
