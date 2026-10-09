-- Deterministic fixture with hand-computed expectations (see expected.txt). Row 11 is 23:30 UTC on 2026-01-01 written with a +02 offset.
INSERT INTO request_log (request_id, received_at, api_key_id, http_status, outcome, error_category, cache_hit, gateway_latency_ms, engine_latency_ms, model_version, image_bytes, anomaly_score, is_anomalous) VALUES
('00000000-0000-0000-0000-000000000001', '2026-01-01 10:00:00+00', 'client-a', 200, 'success', NULL, FALSE, 10, 8, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000002', '2026-01-01 10:10:00+00', 'client-a', 200, 'success', NULL, FALSE, 20, 18, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000003', '2026-01-01 10:20:00+00', 'client-a', 200, 'success', NULL, FALSE, 30, 28, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000004', '2026-01-01 10:30:00+00', 'client-b', 200, 'success', NULL, TRUE, 1, NULL, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000005', '2026-01-01 10:40:00+00', 'client-b', 200, 'success', NULL, TRUE, 2, NULL, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000006', '2026-01-01 10:50:00+00', 'client-b', 200, 'success', NULL, TRUE, 3, NULL, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000007', '2026-01-01 11:05:00+00', 'client-a', 200, 'success', NULL, FALSE, 40, 38, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000008', '2026-01-01 11:10:00+00', NULL, 401, 'client_error', 'unauthorized', FALSE, 0.5, NULL, NULL, NULL, NULL, NULL),
('00000000-0000-0000-0000-000000000009', '2026-01-01 11:15:00+00', 'client-b', 429, 'client_error', 'rate_limited', FALSE, 0.3, NULL, NULL, NULL, NULL, NULL),
('00000000-0000-0000-0000-000000000010', '2026-01-01 11:20:00+00', 'client-a', 502, 'server_error', 'engine_unavailable', FALSE, 5, NULL, NULL, NULL, NULL, NULL),
('00000000-0000-0000-0000-000000000011', '2026-01-02 01:30:00+02', 'client-a', 200, 'success', NULL, FALSE, 50, 48, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000012', '2026-01-02 00:00:00+00', 'client-a', 200, 'success', NULL, FALSE, 100, 98, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000013', '2026-01-02 00:10:00+00', 'client-a', 200, 'success', NULL, FALSE, 200, 198, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000014', '2026-01-02 00:20:00+00', 'client-a', 200, 'success', NULL, FALSE, 300, 298, 'fixture-v1', 1000, 10.0, FALSE),
('00000000-0000-0000-0000-000000000015', '2026-01-02 00:30:00+00', 'client-b', 200, 'success', NULL, FALSE, 400, 398, 'fixture-v1', 1000, 10.0, FALSE);
