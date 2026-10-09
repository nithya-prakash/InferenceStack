# Benchmarks (Phase 1)

Measured 2026-10-09 on one machine: Apple M4 (macOS 15.7, arm64), 16 GB. Docker Desktop VM: 10 CPUs, 7.75 GiB, Linux 6.12 aarch64.
Software: Python 3.12.14/3.12.15, torch 2.14.1, onnxruntime 1.31.0 (Python wheel and C++ release), engine built Release with g++ (Ubuntu 24.04).
Method: same input every call (`model/test_data/png/noise_seed7.png`), 30 warm-up calls, then 300 timed calls, 1 intra-op thread everywhere,
`time.perf_counter` in Python and `steady_clock` around `Session::Run` in C++. Single run per row (no repeats), so differences of a few percent are noise.

| Where | What | mean | median | p95 | p99 (ms) |
|---|---|---|---|---|---|
| macOS host | PyTorch (in-process, model call only) | 26.7 | 26.2 | 30.3 | 33.1 |
| macOS host | Python onnxruntime (in-process) | 28.5 | 28.4 | 29.3 | 29.7 |
| Linux container | Python onnxruntime (in-process) | 58.1 | 57.8 | 59.6 | 61.6 |
| Linux container | C++ engine, `inference_ms` (Run() only) | 58.8 | 58.5 | 60.5 | 66.1 |
| Linux container | C++ engine HTTP end-to-end (Python client, same network namespace) | 59.3 | 58.9 | 61.1 | 66.8 |

Throughput (C++ engine, 10 s, keep-alive clients, 1 intra-op thread per request): 1 client 16.9 req/s, 4 clients 51.5 req/s (client is Python threads, so the client may limit the high end).
Memory: engine process RSS 53.8 MB idle after startup, 97.4 MB after the benchmark (peak = current, `/proc/1/status`); Python ORT process RSS 103 MB after its run.

## What this does and doesn't show
- C++ and Python onnxruntime are the **same runtime** on the **same model**, so raw inference time is the same (58.8 vs 58.1 ms). C++ gives no speedup here; its value is a small, dependency-free server and low per-request overhead (PNG decode + HTTP + JSON cost about 0.5 ms).
- ORT was not faster than PyTorch on the macOS host with 1 thread (28.5 vs 26.7 ms). No speedup claim is made.
- Host vs container numbers are not comparable (the container runs inside a Linux VM, about 2x slower here). PyTorch was not run in the container.
- PyTorch timing refits the model from the dataset (same seed as `build_model.py`), so `data/mvtec/bottle` is required for `torch` mode.
- One input, one machine, a single run, CPU only, loopback networking.
- Bug found by this benchmark: before `set_tcp_nodelay(true)`, HTTP end-to-end was 99 ms vs 59 ms inference, a ~40 ms Nagle/delayed-ACK stall in the server. Fixed.

## Reproduce
```bash
.venv/bin/python benchmarks/bench.py torch        # host, needs data/
.venv/bin/python benchmarks/bench.py ort          # host
docker build -f engine/Dockerfile --target runtime -t is-engine . && docker run -d --name is-engine -p 8081:8081 is-engine
docker run --rm -v "$PWD":/w:ro --network container:is-engine python:3.12-slim sh -c \
  'pip install -q onnxruntime==1.31.0 numpy pillow && python -B /w/benchmarks/bench.py http && python -B /w/benchmarks/bench.py ort'
docker exec is-engine grep -E "VmRSS|VmHWM" /proc/1/status
```
