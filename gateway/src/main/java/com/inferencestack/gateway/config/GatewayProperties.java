package com.inferencestack.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All gateway settings; bound from application.yml / environment variables. */
@ConfigurationProperties("gateway")
public record GatewayProperties(Engine engine, Cache cache, int maxImageBytes, List<ApiKey> apiKeys) {
    public record Engine(String url, Duration connectTimeout, Duration readTimeout) {}

    public record Cache(Duration ttl, long maxSize) {}

    /** The key itself is never configured, only its SHA-256 hex digest and a non-secret id. */
    public record ApiKey(String id, String sha256, int requestsPerMinute) {}

    public GatewayProperties {
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
    }
}
