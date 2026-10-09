package com.inferencestack.gateway.ratelimit;

import com.inferencestack.gateway.config.GatewayProperties.ApiKey;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-API-key token bucket (Bucket4j). State lives in this JVM's memory: limits are NOT shared between gateway
 * instances and reset when the process restarts. Buckets are thread-safe (lock-free CAS inside Bucket4j).
 */
@Component
public class RateLimiter {
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Returns 0 if allowed, otherwise the seconds to wait (at least 1). */
    public long tryAcquire(ApiKey key) {
        Bucket bucket = buckets.computeIfAbsent(key.id(), id -> Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(key.requestsPerMinute())
                .refillGreedy(key.requestsPerMinute(), Duration.ofMinutes(1)).build())
            .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) return 0;
        return Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
    }
}
