package com.inferencestack.gateway;

import static com.inferencestack.gateway.TestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Gateway -> real C++ engine -> real model, checked against the Python reference scores. Needs REAL_ENGINE_URL. */
@EnabledIfEnvironmentVariable(named = "REAL_ENGINE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealEngineIT {
    @LocalServerPort int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.databaseProps(r);
        TestSupport.keyProps(r, 1000, 1000);
        r.add("gateway.engine.url", () -> System.getenv("REAL_ENGINE_URL"));
    }

    @Test void scoresMatchPythonReference() throws Exception {
        Path testData = Path.of(System.getenv().getOrDefault("MODEL_TEST_DATA", "../model/test_data"));
        JsonNode ref = JSON.readTree(Files.readString(testData.resolve("reference.json")));
        assertThat(ref.get("cases").size()).isGreaterThanOrEqualTo(5);
        for (JsonNode c : ref.get("cases")) {
            var r = post(port, KEY_A, "image/png", Files.readAllBytes(testData.resolve(c.get("file").asText())));
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            double expected = c.get("score").asDouble();
            assertThat(json(r).get("anomaly_score").asDouble()).isCloseTo(expected,
                org.assertj.core.data.Offset.offset(ref.get("atol").asDouble() + ref.get("rtol").asDouble() * Math.abs(expected)));
            assertThat(json(r).get("model_version").asText()).isEqualTo("padim-bottle-v1");
        }
        assertThat(get(port, "/health/ready").statusCode()).isEqualTo(200);
    }
}
