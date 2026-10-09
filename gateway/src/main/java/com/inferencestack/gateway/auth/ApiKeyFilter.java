package com.inferencestack.gateway.auth;

import com.inferencestack.gateway.config.GatewayProperties.ApiKey;
import com.inferencestack.gateway.ratelimit.RateLimiter;
import com.inferencestack.gateway.web.ErrorWriter;
import com.inferencestack.gateway.web.RequestInfo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates `X-API-Key` and applies the per-key rate limit for everything under /api/. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)  // after RequestLoggingFilter, so rejections are logged too
public class ApiKeyFilter extends OncePerRequestFilter {
    private final ApiKeyRegistry registry;
    private final RateLimiter limiter;
    private final ErrorWriter errors;

    public ApiKeyFilter(ApiKeyRegistry registry, RateLimiter limiter, ErrorWriter errors) {
        this.registry = registry;
        this.limiter = limiter;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) { return !req.getRequestURI().startsWith("/api/"); }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        Optional<ApiKey> key = registry.authenticate(req.getHeader("X-API-Key"));
        if (key.isEmpty()) {
            errors.write(req, res, 401, "unauthorized", "missing or invalid API key");
            return;
        }
        RequestInfo.of(req).apiKeyId = key.get().id();
        long retryAfter = limiter.tryAcquire(key.get());
        if (retryAfter > 0) {
            res.setHeader("Retry-After", Long.toString(retryAfter));
            errors.write(req, res, 429, "rate_limited", "rate limit exceeded");
            return;
        }
        chain.doFilter(req, res);
    }
}
