package com.inferencestack.gateway;

import static com.inferencestack.gateway.TestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class EngineTimeoutIT extends GatewayTestBase {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.databaseProps(r);
        TestSupport.keyProps(r, 1000, 1000);
        r.add("gateway.engine.url", ENGINE::url);
        r.add("gateway.engine.read-timeout", () -> "300ms");
    }
    @Test void slowEngineGives504AndIsLogged() throws Exception {
        ENGINE.mode = FakeEngine.Mode.SLOW;
        long t0 = System.nanoTime();
        var r = post(port, KEY_A, "image/png", png(1));
        assertThat(r.statusCode()).isEqualTo(504);
        assertThat(json(r).at("/error/code").asText()).isEqualTo("engine_timeout");
        assertThat((System.nanoTime() - t0) / 1e6).isLessThan(1200);  // did not wait for the 1.5 s engine
        var row = rows(1).get(0);
        assertThat(row.get("error_category")).isEqualTo("engine_timeout");
        assertThat(row.get("outcome")).isEqualTo("server_error");
    }
}
