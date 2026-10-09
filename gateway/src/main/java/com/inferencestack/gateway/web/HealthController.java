package com.inferencestack.gateway.web;

import com.inferencestack.gateway.cache.PredictionCache;
import com.inferencestack.gateway.engine.EngineClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness: the process is up. Readiness: the engine answers /health and the database accepts queries. */
@RestController
public class HealthController {
    private final EngineClient engine;
    private final PredictionCache cache;
    private final JdbcTemplate jdbc;

    public HealthController(EngineClient engine, PredictionCache cache, JdbcTemplate jdbc) {
        this.engine = engine;
        this.cache = cache;
        this.jdbc = jdbc;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() { return Map.of("status", "up"); }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean ok = true;
        try {
            String version = engine.health();
            if (!version.isEmpty()) cache.observeModelVersion(version);
            body.put("engine", "up");
            body.put("model_version", version);
        } catch (RuntimeException e) {
            body.put("engine", "down");
            ok = false;
        }
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            body.put("database", "up");
        } catch (RuntimeException e) {
            body.put("database", "down");
            ok = false;
        }
        body.put("status", ok ? "ready" : "not_ready");
        return ResponseEntity.status(ok ? 200 : 503).body(body);
    }
}
