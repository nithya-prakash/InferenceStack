# C++ inference engine

`GET /health` -> `{"status":"ready","model_version":...}` (the process exits at startup if the model cannot load, so it never reports ready without a model).
`POST /predict` with `Content-Type: image/png`, body = 224x224 8-bit RGB PNG (prepare with `model/prepare_image.py`), max 1 MiB.
Response: `{"anomaly_score","is_anomalous","threshold","model_version","inference_ms"}`.
Errors: `{"error":{"code","message"}}` with 400 (empty/not PNG/corrupt), 413 (too large), 415 (wrong Content-Type), 422 (wrong size/channels/bit depth), 500 (inference failure).

## Build, test, run (Docker; no local toolchain needed; run from the repo root)
```bash
docker build -f engine/Dockerfile --target test -t is-engine-test . && docker run --rm is-engine-test   # 25 GoogleTest cases
docker build -f engine/Dockerfile --target runtime -t is-engine .
docker run -d --name is-engine -p 8081:8081 is-engine
curl -s localhost:8081/health
curl -s -X POST -H 'Content-Type: image/png' --data-binary @model/test_data/png/noise_seed7.png localhost:8081/predict
```
Config (env): `ENGINE_MODEL_DIR` (default `model/artifacts`), `ENGINE_PORT` (8081), `ENGINE_INTRA_OP_THREADS` (1).

## Design notes
- Concurrency: cpp-httplib uses a thread pool; `Ort::Session::Run` is thread-safe and each request owns its buffers, so the model is shared without locks (tested with 8 threads and 6 HTTP clients).
- Preprocessing: the engine only validates, decodes PNG and converts HWC uint8 -> CHW float32 (0..255). Scaling/normalization are inside the ONNX graph; resizing is the Python client's job.
- Versions: ONNX Runtime 1.31.0, nlohmann/json 3.11.3, cpp-httplib 0.18.3, GoogleTest 1.15.2 (CMake FetchContent), stb_image 2.30 (vendored). C++17, CMake >= 3.20.
- ONNX Runtime telemetry events are disabled in code; the library still prints two init warnings at startup.
