package com.inferencestack.gateway.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inferencestack.gateway.config.GatewayProperties;
import com.inferencestack.gateway.web.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import org.springframework.stereotype.Component;

/** Talks HTTP to the C++ engine. Every failure is turned into an ApiException with a stable error code. */
@Component
public class EngineClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final java.time.Duration readTimeout;

    public EngineClient(GatewayProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.baseUri = URI.create(props.engine().url().replaceAll("/+$", ""));
        this.readTimeout = props.engine().readTimeout();
        this.http = HttpClient.newBuilder().connectTimeout(props.engine().connectTimeout())
            .version(HttpClient.Version.HTTP_1_1).build();
    }

    public EnginePrediction predict(byte[] png) {
        HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("/predict")).timeout(readTimeout)
            .header("Content-Type", "image/png").POST(HttpRequest.BodyPublishers.ofByteArray(png)).build();
        HttpResponse<String> res = send(req);
        if (res.statusCode() == 200) return parse(res.body());
        if (res.statusCode() == 400 || res.statusCode() == 422)  // gateway pre-validation missed it; engine says the image is bad
            throw new ApiException(422, "engine_rejected", "engine rejected the image: " + errorMessage(res.body()));
        throw new ApiException(502, "engine_error", "engine returned HTTP " + res.statusCode());
    }

    /** Model version reported by the engine's /health, or throws ApiException if it is not ready. */
    public String health() {
        HttpResponse<String> res = send(HttpRequest.newBuilder(baseUri.resolve("/health")).timeout(readTimeout).GET().build());
        if (res.statusCode() != 200) throw new ApiException(502, "engine_error", "engine health returned HTTP " + res.statusCode());
        try {
            return mapper.readTree(res.body()).path("model_version").asText("");
        } catch (IOException e) {
            throw new ApiException(502, "engine_error", "engine health response is not valid JSON");
        }
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ApiException(504, "engine_timeout", "engine did not respond in time");
        } catch (IOException e) {
            throw new ApiException(502, "engine_unavailable", "cannot reach the engine");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "engine_unavailable", "request interrupted");
        }
    }

    private EnginePrediction parse(String body) {
        try {
            JsonNode j = mapper.readTree(body);
            if (!j.path("anomaly_score").isNumber() || !j.path("is_anomalous").isBoolean() || !j.path("threshold").isNumber()
                || !j.path("model_version").isTextual() || !j.path("inference_ms").isNumber())
                throw new IOException("missing or mistyped field");
            double score = j.get("anomaly_score").asDouble();
            if (!Double.isFinite(score)) throw new IOException("non-finite score");
            return new EnginePrediction(score, j.get("is_anomalous").asBoolean(), j.get("threshold").asDouble(),
                j.get("model_version").asText(), j.get("inference_ms").asDouble());
        } catch (IOException e) {
            throw new ApiException(502, "engine_error", "invalid response from engine");
        }
    }

    private String errorMessage(String body) {
        try {
            return mapper.readTree(body).path("error").path("message").asText("unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
