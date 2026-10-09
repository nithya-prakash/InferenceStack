package com.inferencestack.gateway;

import static com.inferencestack.gateway.TestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;

import com.fasterxml.jackson.databind.JsonNode;
import com.inferencestack.gateway.telemetry.TelemetryRepository;
import java.awt.image.BufferedImage;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class PredictIT extends GatewayTestBase {
    @MockitoSpyBean TelemetryRepository telemetry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.databaseProps(r);
        TestSupport.keyProps(r, 1000, 1000);
        r.add("gateway.engine.url", ENGINE::url);
    }

    @Test void validRequestReturnsPredictionAndRequestId() throws Exception {
        HttpResponse<String> r = post(port, KEY_A, "image/png", png(1));
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode j = json(r);
        assertThat(j.get("request_id").asText()).isEqualTo(r.headers().firstValue("X-Request-Id").orElseThrow());
        UUID.fromString(j.get("request_id").asText());
        assertThat(j.get("anomaly_score").isNumber()).isTrue();
        assertThat(j.get("model_version").asText()).isEqualTo("fake-v1");
        assertThat(j.get("cached").asBoolean()).isFalse();
        assertThat(j.get("engine_inference_ms").asDouble()).isEqualTo(1.5);
    }

    @Test void invalidBodiesAreRejectedBeforeTheEngine() throws Exception {
        Object[][] cases = {
            {"image/png", new byte[0], 400, "empty_body"},
            {"image/png", "not an image at all, just text!!!!!!!!!!!!!!!!".getBytes(), 400, "not_png"},
            {"application/json", png(1), 415, "unsupported_media_type"},
            {"image/png", png(64, 64, BufferedImage.TYPE_INT_RGB, 1), 422, "wrong_dimensions"},
            {"image/png", png(224, 224, BufferedImage.TYPE_BYTE_GRAY, 1), 422, "unsupported_channels"},
            {"image/png", png(224, 224, BufferedImage.TYPE_INT_ARGB, 1), 422, "unsupported_channels"},
            {"image/png", new byte[2 * 1024 * 1024], 413, "payload_too_large"},
        };
        for (Object[] c : cases) {
            HttpResponse<String> r = post(port, KEY_A, (String) c[0], (byte[]) c[1]);
            assertThat(r.statusCode()).as((String) c[3]).isEqualTo(c[2]);
            assertThat(json(r).at("/error/code").asText()).isEqualTo(c[3]);
            assertThat(json(r).get("request_id").asText()).isNotEmpty();
        }
        assertThat(ENGINE.predictCalls.get()).isZero();
    }

    @Test void missingOrInvalidKeyIs401() throws Exception {
        assertThat(post(port, null, "image/png", png(1)).statusCode()).isEqualTo(401);
        assertThat(post(port, "wrong-key", "image/png", png(1)).statusCode()).isEqualTo(401);
        assertThat(post(port, "", "image/png", png(1)).statusCode()).isEqualTo(401);
        assertThat(ENGINE.predictCalls.get()).isZero();
        assertThat(rows(3)).allSatisfy(row -> {
            assertThat(row.get("http_status")).isEqualTo(401);
            assertThat(row.get("api_key_id")).isNull();
            assertThat(row.get("error_category")).isEqualTo("unauthorized");
        });
    }

    @Test void identicalImageIsServedFromCache() throws Exception {
        byte[] img = png(2);
        assertThat(json(post(port, KEY_A, "image/png", img)).get("cached").asBoolean()).isFalse();
        JsonNode second = json(post(port, KEY_B, "image/png", img));  // cache is shared across clients
        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.has("engine_inference_ms")).isFalse();
        assertThat(ENGINE.predictCalls.get()).isEqualTo(1);
        json(post(port, KEY_A, "image/png", png(3)));  // different image = miss
        assertThat(ENGINE.predictCalls.get()).isEqualTo(2);
        assertThat(rows(3)).extracting(r -> r.get("cache_hit")).containsExactly(false, true, false);
    }

    @Test void modelVersionChangeInvalidatesCache() throws Exception {
        byte[] img = png(4);
        post(port, KEY_A, "image/png", img);
        ENGINE.modelVersion = "fake-v2";
        assertThat(get(port, "/health/ready").statusCode()).isEqualTo(200);  // readiness probe observes the new version
        JsonNode after = json(post(port, KEY_A, "image/png", img));
        assertThat(after.get("cached").asBoolean()).isFalse();
        assertThat(after.get("model_version").asText()).isEqualTo("fake-v2");
        assertThat(ENGINE.predictCalls.get()).isEqualTo(2);
    }

    @Test void engineFailuresAreMappedAndNeverCached() throws Exception {
        byte[] img = png(5);
        Map<FakeEngine.Mode, Object[]> expect = Map.of(
            FakeEngine.Mode.HTTP_500, new Object[] {502, "engine_error"},
            FakeEngine.Mode.BAD_JSON, new Object[] {502, "engine_error"},
            FakeEngine.Mode.REJECT_422, new Object[] {422, "engine_rejected"});
        for (var e : expect.entrySet()) {
            ENGINE.mode = e.getKey();
            HttpResponse<String> r = post(port, KEY_A, "image/png", img);
            assertThat(r.statusCode()).as(e.getKey().name()).isEqualTo(e.getValue()[0]);
            assertThat(json(r).at("/error/code").asText()).isEqualTo(e.getValue()[1]);
        }
        ENGINE.mode = FakeEngine.Mode.OK;
        assertThat(json(post(port, KEY_A, "image/png", img)).get("cached").asBoolean()).isFalse();  // failures left nothing behind
    }

    @Test void telemetryRowHasExpectedFieldsAndNoSecrets() throws Exception {
        post(port, KEY_A, "image/png", png(6));
        Map<String, Object> row = rows(1).get(0);
        assertThat(row.get("api_key_id")).isEqualTo("client-a");
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(row.get("outcome")).isEqualTo("success");
        assertThat(row.get("error_category")).isNull();
        assertThat(row.get("cache_hit")).isEqualTo(false);
        assertThat(row.get("model_version")).isEqualTo("fake-v1");
        assertThat((Double) row.get("engine_latency_ms")).isEqualTo(1.5);
        assertThat((Double) row.get("gateway_latency_ms")).isPositive();
        assertThat(row.get("image_bytes")).isNotNull();
        assertThat(row.get("received_at")).isNotNull();
        assertThat(row.toString()).doesNotContain(KEY_A);
    }

    @Test void telemetryFailureDoesNotBreakTheRequest() throws Exception {
        Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
            .when(telemetry).insert(any(), anyInt(), any(), anyDouble());
        assertThat(post(port, KEY_A, "image/png", png(7)).statusCode()).isEqualTo(200);
        Mockito.reset(telemetry);
    }

    @Test void healthEndpoints() throws Exception {
        assertThat(get(port, "/health/live").statusCode()).isEqualTo(200);
        HttpResponse<String> ready = get(port, "/health/ready");
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(json(ready).get("status").asText()).isEqualTo("ready");
    }
}
