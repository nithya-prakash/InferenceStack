# Interview guide: InferenceStack

## 60-second pitch
"I took an anomaly-detection model for industrial defects, exported it to ONNX, and built the serving platform around it: a C++ ONNX Runtime engine, a Spring Boot gateway with auth, rate limiting, caching and telemetry, and a dbt/PostgreSQL analytics layer with a dashboard. I verified every layer: ONNX-vs-PyTorch parity, C++ against Python references, hand-computed analytics, and an end-to-end Docker Compose check. I also measured it, and C++ turned out not to be faster than Python for the model itself; the benefit is a small, predictable server."

## Decisions you should be able to defend
1. **PaDiM-style anomaly detection, one category.** Training data contains only good images; evaluation on the official test split gave image AUROC 0.987. Weakness: 83 test images, threshold from 42 samples.
2. **Everything model-related inside the ONNX graph.** Normalization and scoring live in the graph, so C++ and Java can't drift from the reference; only resizing sits outside (Python utility) to avoid two resize implementations disagreeing.
3. **PNG, fixed 224x224, raw bytes over HTTP.** Lossless (exact pixels), 33% smaller than base64 JSON, easy to validate at the header level before allocating memory.
4. **Fail-fast engine; `/health` means ready.** A model that failed to load never serves a healthy status.
5. **Gateway concerns.** Hashed keys with constant-time compare; per-key token bucket (in-memory, per instance); cache key = model version + SHA-256 of image bytes, cleared when the engine reports a new version; 502/504 for upstream failure; telemetry best effort.
6. **Analytics.** Percentiles from raw rows (never averaged), UTC bucketing independent of session time zone, fixture with hand-computed expectations, dbt tests for invariants.

## Things that went wrong (good stories)
- Benchmark showed 99 ms end-to-end vs 59 ms inference: the server lacked TCP_NODELAY (Nagle + delayed ACK, ~40 ms). Fixed and re-measured.
- Integration tests were silently not running (failsafe not bound); a superclass `@DynamicPropertySource` overrode subclass settings so the first rate-limit test saw 200 instead of 429.
- The traffic generator's "unique" images repeated after 256 seeds, so the simulated engine outage was served from cache.
- First model file name collision overwrote an old reference file; I flagged it and removed the obsolete model in a separate, approved commit.

## Questions and short answers
1. *Why isn't the C++ engine faster?* Same ONNX Runtime kernels do the work; C++ removes interpreter/dependency overhead, not compute.
2. *Why compare floats with a tolerance?* Different kernels reorder operations; observed difference 3e-5 on scores of 5-60, tolerance rtol 1e-4 / atol 1e-3.
3. *Why does a model swap not serve stale cache entries?* Version is part of the key and a change clears the cache; caveat: the gateway must observe the new version.
4. *How would you scale the gateway horizontally?* Move the rate limiter and cache to a shared store (Redis), keep telemetry async/batched, put TLS and key management in front.
5. *How do you know the dashboard numbers are right?* dbt computes them; a fixture with hand-computed values (including a timezone-offset row) is diffed against every mart; the dashboard only displays mart columns.
6. *What would you do before calling this production-ready?* TLS, secrets management, key rotation, load and fuzz tests, monitoring/alerting, image scanning, more categories and a larger evaluation, a real deployment target.
