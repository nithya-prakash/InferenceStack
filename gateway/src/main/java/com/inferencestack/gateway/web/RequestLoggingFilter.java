package com.inferencestack.gateway.web;

import com.inferencestack.gateway.telemetry.TelemetryRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost filter for /api/**: assigns the request id, times the request, and records one telemetry row.
 * Telemetry is best effort: if the insert fails the client still gets its answer, the failure is logged with
 * the request id and counted in {@link #telemetryFailures()}; that row is lost (no retry queue).
 * Logs contain the request id, key id, status and latency, never keys or payloads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final TelemetryRepository telemetry;
    private final AtomicLong failures = new AtomicLong();

    public RequestLoggingFilter(TelemetryRepository telemetry) { this.telemetry = telemetry; }

    public long telemetryFailures() { return failures.get(); }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) { return !req.getRequestURI().startsWith("/api/"); }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        RequestInfo info = RequestInfo.attachTo(req);
        Instant received = Instant.now();
        res.setHeader("X-Request-Id", info.requestId.toString());
        MDC.put("request_id", info.requestId.toString());
        try {
            chain.doFilter(req, res);
        } finally {
            double ms = (System.nanoTime() - info.startNanos) / 1e6;
            int status = res.getStatus();
            log.info("{} {} -> {} key={} cache_hit={} {}ms", req.getMethod(), req.getRequestURI(), status, info.apiKeyId,
                info.cacheHit, String.format("%.1f", ms));
            record(info, status, received, ms);
            MDC.remove("request_id");
        }
    }

    private void record(RequestInfo info, int status, Instant received, double ms) {
        try {
            telemetry.insert(info, status, received, ms);
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.error("telemetry insert failed for request {}: {}", info.requestId, e.getClass().getSimpleName());
        }
    }
}
