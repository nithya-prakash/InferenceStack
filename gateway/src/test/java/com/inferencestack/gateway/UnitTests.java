package com.inferencestack.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inferencestack.gateway.auth.ApiKeyRegistry;
import com.inferencestack.gateway.cache.PredictionCache;
import com.inferencestack.gateway.config.GatewayProperties;
import com.inferencestack.gateway.config.GatewayProperties.ApiKey;
import com.inferencestack.gateway.engine.EnginePrediction;
import com.inferencestack.gateway.png.PngValidator;
import com.inferencestack.gateway.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnitTests {
    private static GatewayProperties props(ApiKey... keys) {
        return new GatewayProperties(null, null, 1000, List.of(keys));
    }

    private static String hash(String key) { return ApiKeyRegistry.sha256Hex(key.getBytes(StandardCharsets.UTF_8)); }

    @Test void registryAcceptsOnlyConfiguredKeys() {
        var reg = new ApiKeyRegistry(props(new ApiKey("a", hash("secret-a"), 10), new ApiKey("b", hash("secret-b"), 10)));
        assertThat(reg.authenticate("secret-b")).map(ApiKey::id).contains("b");
        assertThat(reg.authenticate("secret-a ")).isEmpty();
        assertThat(reg.authenticate("")).isEmpty();
        assertThat(reg.authenticate(null)).isEmpty();
    }

    @Test void registryRejectsBadConfiguration() {
        assertThatThrownBy(() -> new ApiKeyRegistry(props())).hasMessageContaining("no API keys");
        assertThatThrownBy(() -> new ApiKeyRegistry(props(new ApiKey("a", "plaintext-key", 10)))).hasMessageContaining("64 hex");
        assertThatThrownBy(() -> new ApiKeyRegistry(props(new ApiKey("a", hash("x"), 0)))).hasMessageContaining("requests-per-minute");
        assertThatThrownBy(() -> new ApiKeyRegistry(props(new ApiKey("a", hash("x"), 1), new ApiKey("a", hash("y"), 1))))
            .hasMessageContaining("unique");
    }

    @Test void pngValidatorAcceptsContractImageOnly() {
        PngValidator.validate("image/png", TestSupport.png(1));
        PngValidator.validate("image/png; charset=binary", TestSupport.png(1));
        assertThatThrownBy(() -> PngValidator.validate("image/png", new byte[40])).isInstanceOf(ApiException.class);
        byte[] truncated = java.util.Arrays.copyOf(TestSupport.png(1), 20);
        assertThatThrownBy(() -> PngValidator.validate("image/png", truncated)).isInstanceOf(ApiException.class);
    }

    @Test void cacheKeysIncludeModelVersion() {
        var cache = new PredictionCache(Duration.ofMinutes(1), 10);
        assertThat(cache.get("h1")).isEmpty();
        cache.put("h1", new EnginePrediction(1, false, 2, "v1", 3));
        assertThat(cache.get("h1")).isPresent();
        cache.put("h2", new EnginePrediction(1, false, 2, "v2", 3));  // new version wipes v1 entries
        assertThat(cache.get("h1")).isEmpty();
        assertThat(cache.get("h2")).isPresent();
        assertThat(cache.stats().hitCount()).isEqualTo(2);
    }

    @Test void cacheRespectsMaxSize() {
        var cache = new PredictionCache(Duration.ofMinutes(1), 2);
        for (int i = 0; i < 50; i++) cache.put("h" + i, new EnginePrediction(1, false, 2, "v1", 3));
        cache.cleanUp();
        long present = java.util.stream.IntStream.range(0, 50).filter(i -> cache.get("h" + i).isPresent()).count();
        assertThat(present).isLessThanOrEqualTo(2);
    }
}
