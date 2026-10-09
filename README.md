# InferenceStack

A small, end-to-end ML serving platform for **industrial surface-defect detection**, built to show how a trained vision model can be wrapped in a testable, observable inference service using **C++17, Java 21 and SQL**.
A PaDiM-style anomaly detector (frozen ResNet18 features + per-position Gaussian statistics) is trained on defect-free MVTec AD `bottle` images, exported to ONNX, served by a C++ engine (ONNX Runtime), fronted by a Spring Boot gateway (auth, rate limit, cache, telemetry), and analysed with dbt and a Streamlit dashboard.

> **Status:** complete through Phase 4 on a single laptop. This is a **portfolio/learning project, not a production system** (no TLS, single instances, one product category, small test set). See [Known limitations](#known-limitations).
> **CI:** the workflow in `.github/workflows/ci.yml` passes `actionlint` but **has not been run on GitHub yet** (nothing has been pushed). Every job's commands were run locally; results are below.

## Architecture
```
Client --PNG/HTTP--> Java gateway (Spring Boot :8080) --PNG/HTTP--> C++ engine (ONNX Runtime :8081)
                       auth - rate limit - cache - validation            |
                            | JDBC (telemetry)                          '-> anomaly score
                            v
                      PostgreSQL 16  <--dbt (staging views -> mart tables)--  Streamlit dashboard (:8501, reads marts only)
```
| Part | Tech | Responsibility |
|---|---|---|
| `model/` | Python 3.12, PyTorch 2.14.1, ONNX 1.23.2 | fit, calibrate, export, reference outputs, parity tests |
| `engine/` | C++17, CMake, ONNX Runtime 1.31.0, cpp-httplib 0.18.3, nlohmann/json 3.11.3, GoogleTest 1.15.2 | validate PNG, run the model, `/health`, `/predict` |
| `gateway/` | Java 21, Spring Boot 3.5.16, Bucket4j 8.17.0, Caffeine, Flyway, JDBC | API keys, rate limit, cache, timeouts, request ids, telemetry |
| `analytics/` | PostgreSQL 16, dbt-postgres 1.11.0, Streamlit 1.65.0 | marts for volume, latency percentiles, errors, cache, per-key usage |
| root | Docker Compose, GitHub Actions | packaging, end-to-end check, CI |

## Quickstart (Docker only)
Prerequisites: Docker with Compose v2 (about 8 GB RAM for the Docker VM), `python3` for the example scripts. First build takes several minutes (downloads ONNX Runtime and C++/Maven dependencies).
```bash
./scripts/init-env.sh                 # creates a local .env with random secrets (gitignored)
docker compose up -d --build --wait   # engine, gateway, postgres, dbt (one-shot), dashboard
set -a; . ./.env; set +a
curl -s -X POST -H "X-API-Key: $GATEWAY_API_KEY" -H 'Content-Type: image/png' \
     --data-binary @model/test_data/png/noise_seed7.png localhost:8080/api/v1/predict
docker compose run --rm dbt build     # refresh the analytics marts after new traffic
open http://localhost:8501            # dashboard (macOS; otherwise open the URL)
docker compose down                   # add -v to also delete the database volume
```
Example response: `{"request_id":"...","anomaly_score":58.62,"is_anomalous":true,"threshold":18.29,"model_version":"padim-bottle-v1","cached":false,"engine_inference_ms":61.4}` (a second identical request returns `"cached":true`).
Prepare your own image (any size/format) with `python model/prepare_image.py in.jpg out.png` (needs Pillow). To fill the dashboard with data see [analytics/README.md](analytics/README.md) (`generate_traffic.py` uses the dev profile keys, so run it against a gateway started with `SPRING_PROFILES_ACTIVE=dev`, see [gateway/README.md](gateway/README.md)).

**Configuration (`.env`, see `.env.example`):** `POSTGRES_PASSWORD`, `GATEWAY_API_KEY` (client key), `GATEWAY_API_KEY_SHA256` (the only form the gateway sees). Gateway/engine/dbt settings are environment variables documented in each component's README. Only ports 8080 (gateway) and 8501 (dashboard) are published, bound to 127.0.0.1.

## Model contract
Input to the **API**: raw bytes of a **224x224, 8-bit RGB PNG** (`Content-Type: image/png`, max 1 MiB). Output: `anomaly_score` (higher = more anomalous; max Mahalanobis distance over a 28x28 feature grid), `is_anomalous = score > threshold` (threshold 18.2966, calibrated as the max score over 42 held-out good training images), `model_version`.
Preprocessing ownership (nothing is done twice): the Python utility `model/prepare_image.py` converts to RGB, resizes to 256 (bilinear) and center-crops 224; the C++ engine only validates and decodes the PNG into a CHW float32 tensor with raw 0..255 values; the ONNX graph does `/255`, ImageNet normalization and everything after. Details, limitations and license: [model/MODEL_CARD.md](model/MODEL_CARD.md).
**Accuracy (official MVTec `bottle` test split, 83 images, used once):** image AUROC 0.9873; at the threshold TPR 0.889 (56/63 defective found), FPR 0.000 (0/20 good flagged). This is one category and a small test set; parity tests (ONNX == PyTorch, max score diff 3e-5) say nothing about accuracy.
**Dataset license:** MVTec AD is CC BY-NC-SA 4.0 (non-commercial, attribution, share-alike). It is not in this repo; committed test images are synthetic; the model artifact derived from it is for non-commercial use. Bergmann et al., CVPR 2019.

## API
`POST /api/v1/predict` (header `X-API-Key`), `GET /health/live`, `GET /health/ready` on the gateway; the engine has `GET /health`, `POST /predict`. Errors are `{"request_id","error":{"code","message"}}` with 400/401/413/415/422/429/502/504; full table in [gateway/README.md](gateway/README.md), engine details in [engine/README.md](engine/README.md).

## Database and analytics
`request_log` (Flyway `gateway/src/main/resources/db/migration/V1__request_log.sql`): `request_id` UUID unique, `received_at timestamptz`, `api_key_id` (non-secret id, NULL if unauthenticated), `http_status`, `outcome`, `error_category`, `cache_hit`, `gateway_latency_ms`, `engine_latency_ms`, `model_version`, `image_bytes`, `anomaly_score`, `is_anomalous`, with CHECK constraints and indexes on time and (key, time). No API keys, headers or image bytes are stored. dbt builds `stg_request_log` and six marts (volume, latency percentiles, error rate, requests per key per day, cache hit rate, outcomes over time); definitions and data-quality decisions are in [analytics/README.md](analytics/README.md). The dashboard reads only the marts.

## Tests (all run locally in Docker; results observed)
| Command | What | Result |
|---|---|---|
| `python model/test_parity.py` | committed ONNX vs Python reference, shapes, batching, wrong shapes, image-prep contract | 6/6 pass |
| `docker build -f engine/Dockerfile --target test -t t . && docker run --rm t` | 25 GoogleTest cases: PNG validation, parity with reference, model loading failures, concurrency, HTTP endpoints | 25/25 pass on linux/arm64 and on linux/amd64 (the latter emulated on the M4 via buildx, not on real x86 hardware) |
| `gateway/run-tests.sh` | 19 JUnit tests: auth, validation, rate limit, cache + invalidation, engine errors/timeouts, telemetry, health, real engine parity | 19/19 pass |
| `analytics/scripts/verify.sh` | dbt build (44 nodes), marts vs hand-computed values (non-UTC session), dashboard test | all pass |
| `scripts/e2e.sh` | full Compose stack: health, 6 reference images through gateway vs Python scores, 401, dbt, dashboard | passed |
Not run: GitHub Actions itself (never pushed); the Gateway/analytics/e2e jobs were only run on arm64; no load test of the gateway; no fuzzing of the PNG decoder; no formatter/linter is configured (only `actionlint` was run on the workflow).

## Benchmarks (measured, single machine, see [benchmarks/README.md](benchmarks/README.md) for method)
Apple M4, Docker VM 10 CPUs, 1 intra-op thread, 30 warm-up + 300 timed calls, one run, latency in ms (mean / median / p95 / p99):
macOS host: PyTorch 26.7 / 26.2 / 30.3 / 33.1; Python ORT 28.5 / 28.4 / 29.3 / 29.7. Linux container: Python ORT 58.1 / 57.8 / 59.6 / 61.6; C++ engine raw `Run()` 58.8 / 58.5 / 60.5 / 66.1; C++ engine HTTP end-to-end 59.3 / 58.9 / 61.1 / 66.8. Throughput 16.9 req/s (1 client), 51.5 req/s (4 clients); engine RSS about 54 MB idle, 97 MB after load.
**C++ is not faster than Python ORT for inference** (same runtime, same model); the engine's value is a small dependency-free server with about 0.5 ms request overhead. Host and container numbers are not comparable.

## Design decisions
- **C++ for serving:** small static footprint, explicit ownership (RAII), thread-safe shared session; fail-fast startup so "ready" always means "model loaded". PNG-only fixed-size input keeps decode exact and parity testable; resizing lives in one place (Python).
- **Java for the API layer:** the cross-cutting concerns (auth, rate limiting, caching, request ids, timeouts, persistence) are what Spring Boot is good at. Keys are stored as SHA-256 digests and compared in constant time; telemetry is best effort (a DB outage must not take predictions down); cache keys include the model version and a version change drops the cache.
- **SQL/dbt for analytics:** one raw table owned by the gateway's migration, derived tables owned by dbt; percentiles computed from raw rows; metrics defined explicitly with tests and hand-computed fixtures.
- **Anomaly detection over a classifier:** MVTec's training set has no defective images, so a model of "normal" fits the data and gives a clean evaluation protocol.

## Known limitations
One MVTec category (`bottle`), 83 test images, threshold from 42 samples; no TLS; API keys are bearer secrets with no rotation/revocation; rate limit and cache are in-memory per gateway instance; telemetry rows can be lost if the insert fails; marts are full-refresh with no scheduling; the dashboard has no auth and Compose uses one database user for everything; Docker images run as non-root but are not hardened or scanned; ONNX Runtime prints two telemetry init warnings at startup (events are disabled in code). **Running in containers does not make this production-ready.**

## Troubleshooting
- `variable is not set` on `docker compose`: run `./scripts/init-env.sh`.
- Port 8080/8501 in use: stop the other service or change the published ports in `docker-compose.yml`.
- Dashboard says "Cannot read the analytics marts": run `docker compose run --rm dbt build`.
- First build very slow or fails to download: it fetches ONNX Runtime, C++ libraries (GitHub) and Maven artifacts; retry on a stable connection.
- Engine container restarts: check `docker compose logs engine` (a missing/corrupt model file exits with an error message).

## Future improvements
More MVTec categories or a multi-category model; heatmap output and localization; shared rate limit/cache (Redis) for multiple gateway instances; TLS and key rotation; incremental dbt models and scheduling; a read-only DB role for the dashboard; metrics/tracing; load testing and decoder fuzzing; INT8 quantization benchmark.

## Interview discussion points
See [docs/INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md).
