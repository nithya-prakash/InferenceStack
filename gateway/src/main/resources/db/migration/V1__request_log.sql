-- One row per completed /api/** request. No API keys and no image bytes are stored.
CREATE TABLE request_log (
    id                 BIGSERIAL PRIMARY KEY,
    request_id         UUID             NOT NULL UNIQUE,
    received_at        TIMESTAMPTZ      NOT NULL,
    api_key_id         TEXT,                                  -- non-secret key identifier; NULL if unauthenticated
    http_status        SMALLINT         NOT NULL CHECK (http_status BETWEEN 100 AND 599),
    outcome            TEXT             NOT NULL CHECK (outcome IN ('success', 'client_error', 'server_error')),
    error_category     TEXT,                                  -- NULL on success
    cache_hit          BOOLEAN          NOT NULL DEFAULT FALSE,
    gateway_latency_ms DOUBLE PRECISION NOT NULL CHECK (gateway_latency_ms >= 0),
    engine_latency_ms  DOUBLE PRECISION CHECK (engine_latency_ms >= 0),  -- engine-reported inference time; NULL on cache hit/failure
    model_version      TEXT,
    image_bytes        INTEGER CHECK (image_bytes >= 0),
    anomaly_score      DOUBLE PRECISION,
    is_anomalous       BOOLEAN,
    CONSTRAINT error_category_matches_outcome CHECK ((outcome = 'success') = (error_category IS NULL))
);
CREATE INDEX idx_request_log_received_at ON request_log (received_at);
CREATE INDEX idx_request_log_key_time ON request_log (api_key_id, received_at);
