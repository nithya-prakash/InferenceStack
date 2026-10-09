package com.inferencestack.gateway.telemetry;

import com.inferencestack.gateway.web.RequestInfo;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryRepository {
    private static final String INSERT = """
        INSERT INTO request_log (request_id, received_at, api_key_id, http_status, outcome, error_category, cache_hit,
                                 gateway_latency_ms, engine_latency_ms, model_version, image_bytes, anomaly_score, is_anomalous)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private final JdbcTemplate jdbc;

    public TelemetryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(RequestInfo i, int status, Instant receivedAt, double gatewayMs) {
        String outcome = status < 400 ? "success" : status < 500 ? "client_error" : "server_error";
        jdbc.update(INSERT, i.requestId, Timestamp.from(receivedAt), i.apiKeyId, status, outcome,
            status < 400 ? null : (i.errorCategory == null ? "unknown" : i.errorCategory), i.cacheHit, gatewayMs, i.engineMs,
            i.modelVersion, i.imageBytes, i.score, i.anomalous);
    }
}
