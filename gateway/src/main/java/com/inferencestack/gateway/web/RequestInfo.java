package com.inferencestack.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** Per-request facts collected along the request path and written to telemetry at the end. */
public class RequestInfo {
    static final String ATTRIBUTE = RequestInfo.class.getName();

    public final UUID requestId = UUID.randomUUID();
    public final long startNanos = System.nanoTime();
    public String apiKeyId;
    public boolean cacheHit;
    public Double engineMs;
    public String modelVersion;
    public String errorCategory;
    public Integer imageBytes;
    public Double score;
    public Boolean anomalous;

    public static RequestInfo of(HttpServletRequest req) {
        return (RequestInfo) req.getAttribute(ATTRIBUTE);
    }

    static RequestInfo attachTo(HttpServletRequest req) {
        RequestInfo info = new RequestInfo();
        req.setAttribute(ATTRIBUTE, info);
        return info;
    }
}
