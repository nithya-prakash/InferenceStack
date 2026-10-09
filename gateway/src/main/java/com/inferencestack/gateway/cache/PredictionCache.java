package com.inferencestack.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.inferencestack.gateway.config.GatewayProperties;
import com.inferencestack.gateway.engine.EnginePrediction;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory (per gateway instance) cache of successful predictions. Key = model version + SHA-256 of the
 * exact PNG bytes. When the engine reports a different model version the whole cache is dropped, so a model
 * swap can never serve old predictions. Only successful engine results are ever put here.
 */
@Component
public class PredictionCache {
    private final Cache<String, EnginePrediction> cache;
    private String modelVersion;  // guarded by `this`

    @Autowired
    public PredictionCache(GatewayProperties props) {
        this(props.cache().ttl(), props.cache().maxSize());
    }

    public PredictionCache(java.time.Duration ttl, long maxSize) {
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).recordStats().build();
    }

    public synchronized Optional<EnginePrediction> get(String imageHash) {
        if (modelVersion == null) return Optional.empty();
        return Optional.ofNullable(cache.getIfPresent(modelVersion + ":" + imageHash));
    }

    public synchronized void put(String imageHash, EnginePrediction p) {
        observeModelVersion(p.modelVersion());
        cache.put(modelVersion + ":" + imageHash, p);
    }

    /** Called with every version string seen from the engine; a change invalidates everything. */
    public synchronized void observeModelVersion(String version) {
        if (!version.equals(modelVersion)) {
            cache.invalidateAll();
            modelVersion = version;
        }
    }

    /** Caffeine evicts asynchronously; this runs pending maintenance now (used by tests). */
    public void cleanUp() { cache.cleanUp(); }

    public CacheStats stats() { return cache.stats(); }
}
