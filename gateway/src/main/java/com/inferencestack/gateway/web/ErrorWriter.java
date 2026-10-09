package com.inferencestack.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Writes the one error format used everywhere: {"request_id", "error": {"code", "message"}}. */
@Component
public class ErrorWriter {
    private final ObjectMapper mapper;

    public ErrorWriter(ObjectMapper mapper) { this.mapper = mapper; }

    public void write(HttpServletRequest req, HttpServletResponse res, int status, String code, String message) throws IOException {
        RequestInfo info = RequestInfo.of(req);
        if (info != null) info.errorCategory = code;
        res.setStatus(status);
        res.setContentType("application/json");
        mapper.writeValue(res.getOutputStream(), Map.of(
            "request_id", info == null ? "" : info.requestId.toString(),
            "error", Map.of("code", code, "message", message)));
    }
}
