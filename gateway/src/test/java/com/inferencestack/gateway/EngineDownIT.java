package com.inferencestack.gateway;

import static com.inferencestack.gateway.TestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class EngineDownIT extends GatewayTestBase {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.databaseProps(r);
        TestSupport.keyProps(r, 1000, 1000);
        r.add("gateway.engine.url", () -> "http://127.0.0.1:1");
    }
    @Test void predictGives502() throws Exception {
        var r = post(port, KEY_A, "image/png", png(1));
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(json(r).at("/error/code").asText()).isEqualTo("engine_unavailable");
    }

    @Test void readinessIs503ButLivenessIs200() throws Exception {
        var ready = get(port, "/health/ready");
        assertThat(ready.statusCode()).isEqualTo(503);
        assertThat(json(ready).get("engine").asText()).isEqualTo("down");
        assertThat(json(ready).get("database").asText()).isEqualTo("up");
        assertThat(get(port, "/health/live").statusCode()).isEqualTo(200);
    }
}
