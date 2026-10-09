package com.inferencestack.gateway.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.inferencestack.gateway.auth.ApiKeyRegistry;
import com.inferencestack.gateway.cache.PredictionCache;
import com.inferencestack.gateway.config.GatewayProperties;
import com.inferencestack.gateway.engine.EngineClient;
import com.inferencestack.gateway.engine.EnginePrediction;
import com.inferencestack.gateway.png.PngValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PredictController {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PredictResponse(@JsonProperty("request_id") String requestId,
                                  @JsonProperty("anomaly_score") double anomalyScore,
                                  @JsonProperty("is_anomalous") boolean anomalous,
                                  double threshold,
                                  @JsonProperty("model_version") String modelVersion,
                                  boolean cached,
                                  @JsonProperty("engine_inference_ms") Double engineInferenceMs) {}

    private final EngineClient engine;
    private final PredictionCache cache;
    private final int maxBytes;

    public PredictController(EngineClient engine, PredictionCache cache, GatewayProperties props) {
        this.engine = engine;
        this.cache = cache;
        this.maxBytes = props.maxImageBytes();
    }

    @PostMapping("/api/v1/predict")
    public PredictResponse predict(HttpServletRequest req) throws IOException {
        RequestInfo info = RequestInfo.of(req);
        byte[] body = readBounded(req);
        info.imageBytes = body.length;
        PngValidator.validate(req.getContentType(), body);

        String hash = ApiKeyRegistry.sha256Hex(body);
        Optional<EnginePrediction> hit = cache.get(hash);
        EnginePrediction p = hit.orElseGet(() -> {
            EnginePrediction fresh = engine.predict(body);
            cache.put(hash, fresh);
            return fresh;
        });

        info.cacheHit = hit.isPresent();
        info.modelVersion = p.modelVersion();
        info.score = p.anomalyScore();
        info.anomalous = p.anomalous();
        info.engineMs = hit.isPresent() ? null : p.inferenceMs();
        return new PredictResponse(info.requestId.toString(), p.anomalyScore(), p.anomalous(), p.threshold(),
            p.modelVersion(), hit.isPresent(), info.engineMs);
    }

    /** Reads at most maxBytes+1 so an oversized (or chunked, length-less) upload is cut off early. */
    private byte[] readBounded(HttpServletRequest req) throws IOException {
        if (req.getContentLengthLong() > maxBytes) throw tooLarge();
        byte[] body = req.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) throw tooLarge();
        return body;
    }

    private ApiException tooLarge() {
        return new ApiException(413, "payload_too_large", "image larger than " + maxBytes + " bytes");
    }
}
