# Java gateway (Spring Boot 3.5.16, Java 21)

`POST /api/v1/predict` (header `X-API-Key`, `Content-Type: image/png`, body = 224x224 RGB PNG, max 1 MiB) -> `{request_id, anomaly_score, is_anomalous, threshold, model_version, cached, engine_inference_ms?}`.
`GET /health/live` (process up), `GET /health/ready` (engine `/health` + database `SELECT 1`; 503 if either is down). Every response to /api/** has `X-Request-Id`.
Errors are always `{"request_id", "error": {"code", "message"}}`:

| Status | code | When |
|---|---|---|
| 400 | `empty_body`, `not_png` | body empty / not a PNG |
| 401 | `unauthorized` | missing or unknown key |
| 413 | `payload_too_large` | over `gateway.max-image-bytes` |
| 415 | `unsupported_media_type` | Content-Type is not image/png |
| 422 | `wrong_dimensions`, `unsupported_channels`, `unsupported_bit_depth`, `engine_rejected` | image violates the model contract |
| 429 | `rate_limited` | key over its limit (`Retry-After` header) |
| 502 | `engine_unavailable`, `engine_error` | engine unreachable / 5xx / invalid response |
| 504 | `engine_timeout` | engine slower than `gateway.engine.read-timeout` |

Request path: logging filter (request id, timing, telemetry) -> API-key filter (auth, rate limit) -> controller (read bounded body, PNG header check, cache lookup, engine call).

## Run the tests (Docker only; no local JDK/Maven needed)
```bash
gateway/run-tests.sh      # PostgreSQL 16 + real C++ engine + Maven, on a temporary Docker network
```
`mvn test` runs the unit tests; `mvn verify` also runs `*IT` integration tests (need `TEST_DB_URL`, optional `REAL_ENGINE_URL`).
Testcontainers was not used: the script starts PostgreSQL on a Docker network instead, which also works unchanged as a CI service container.

## Run locally
```bash
docker build -f engine/Dockerfile --target runtime -t is-engine . && docker build -t is-gateway gateway/
docker network create is-net
docker run -d --name pg --network is-net -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=inferencestack postgres:16
docker run -d --name engine --network is-net is-engine
docker run -d --name gw --network is-net -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev -e DB_URL=jdbc:postgresql://pg:5432/inferencestack \
  -e DB_USER=postgres -e DB_PASSWORD=pw -e ENGINE_URL=http://engine:8081 is-gateway
curl -s -X POST -H 'X-API-Key: dev-local-key-not-a-secret' -H 'Content-Type: image/png' --data-binary @model/test_data/png/noise_seed7.png localhost:8080/api/v1/predict
```
The `dev` profile enables one public, throwaway key (`dev-local-key-not-a-secret`). Never use it outside local development.

## Configuration (environment)
`DB_URL`, `DB_USER`, `DB_PASSWORD`, `ENGINE_URL`, and API keys as `GATEWAY_API_KEYS_0_ID`, `GATEWAY_API_KEYS_0_SHA256` (hex SHA-256 of the key: `printf '%s' "$KEY" | shasum -a 256`), `GATEWAY_API_KEYS_0_REQUESTS_PER_MINUTE`, then `_1_...`. The app refuses to start with no keys or malformed key config.
Other settings are in `application.yml` (`gateway.engine.*-timeout`, `gateway.cache.ttl/max-size`, `gateway.max-image-bytes`).

## Design decisions and limitations
- **Auth:** only SHA-256 digests are configured; comparison is `MessageDigest.isEqual` over all keys with no early exit. Plain API keys are bearer secrets: anyone who captures one can use it, so production needs TLS (not provided here), key rotation and revocation, which this version lacks. SHA-256 (not bcrypt) is fine only because keys are long random strings.
- **Rate limiting:** Bucket4j token bucket per key id, **in memory, per gateway instance**: not shared across instances, reset on restart. Unauthenticated requests are not rate limited.
- **Cache:** Caffeine, **per instance**, key = model version + SHA-256 of the exact PNG bytes, TTL and max size configurable, only successful engine results are stored. The engine's reported model version (every response and every `/health/ready` probe) is compared with the current one; a change drops the whole cache. A model swap that the gateway never observes (cache hits only, no readiness probe) keeps serving old entries until TTL, so keep TTL short or probe readiness. The same image encoded differently is a different key.
- **Telemetry:** one `request_log` row per /api/** request (Flyway `V1__request_log.sql`), parameterized insert, written synchronously after the response is produced. **Best effort:** if the insert fails, the client still gets its answer, the error is logged with the request id and counted, and that row is lost (no retry queue). No keys, headers or image bytes are stored; the key *id* is stored (NULL for unauthenticated requests).
- **Not done:** TLS, key rotation, distributed rate limit/cache, async or batched telemetry, metrics endpoint, retries to the engine (a failed call is reported, not retried).
