package com.inferencestack.gateway;

import static com.inferencestack.gateway.TestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class RateLimitIT extends GatewayTestBase {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.databaseProps(r);
        TestSupport.keyProps(r, 1000, 3);
        r.add("gateway.engine.url", ENGINE::url);
    }
    @Test void limitIsPerKeyAndReturns429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(post(port, KEY_B, "image/png", png(i)).statusCode()).isEqualTo(200);
        HttpResponse<String> limited = post(port, KEY_B, "image/png", png(9));
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(json(limited).at("/error/code").asText()).isEqualTo("rate_limited");
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
        assertThat(post(port, KEY_A, "image/png", png(9)).statusCode()).isEqualTo(200);  // other key unaffected
        assertThat(rows(5)).anySatisfy(r -> {
            assertThat(r.get("http_status")).isEqualTo(429);
            assertThat(r.get("api_key_id")).isEqualTo("client-b");
            assertThat(r.get("error_category")).isEqualTo("rate_limited");
        });
    }
}
