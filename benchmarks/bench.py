"""Latency/throughput benchmark. Modes (how each is run: benchmarks/README.md):
  torch  PyTorch scorer in-process (refits from data/mvtec with the same seed as build_model.py), 1 thread
  ort    Python onnxruntime in-process, 1 intra-op thread
  http   POST a PNG to a running engine: client-side end-to-end AND the engine's own inference_ms
Boundary for torch/ort: the model call only (input already decoded). Same input every call: model/test_data/png/noise_seed7.png.
"""
import argparse
import http.client
import json
import platform
import sys
import threading
import time
from pathlib import Path

sys.dont_write_bytecode = True
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
PNG = ROOT / "model/test_data/png/noise_seed7.png"


def summarize(ms):
    a = np.array(ms)
    return {"n": len(a), "mean_ms": round(float(a.mean()), 3), "median_ms": round(float(np.median(a)), 3),
            "p95_ms": round(float(np.percentile(a, 95)), 3), "p99_ms": round(float(np.percentile(a, 99)), 3)}


def measure(fn, n, warmup):
    for _ in range(warmup):
        fn()
    out = []
    for _ in range(n):
        t = time.perf_counter(); fn(); out.append((time.perf_counter() - t) * 1e3)
    return out


def rss_mb():  # Linux only: resident set size of this process
    p = Path("/proc/self/status")
    for line in (p.read_text().splitlines() if p.exists() else []):
        if line.startswith("VmRSS"):
            return round(int(line.split()[1]) / 1024, 1)


def load_input():
    from PIL import Image
    return np.asarray(Image.open(PNG).convert("RGB"), dtype=np.float32).transpose(2, 0, 1)[None].copy()


def mode_ort(a):
    import onnxruntime as ort
    o = ort.SessionOptions(); o.intra_op_num_threads = 1
    s = ort.InferenceSession(str(ROOT / "model/artifacts/padim_bottle.onnx"), o, providers=["CPUExecutionProvider"])
    x = load_input()
    return {"ort_python": summarize(measure(lambda: s.run(["score"], {"image": x}), a.n, a.warmup)), "rss_mb_after": rss_mb()}


def mode_torch(a):
    import torch
    torch.set_num_threads(1)
    sys.path.insert(0, str(ROOT / "model"))
    import build_model as bm
    from padim import Backbone, fit
    train = sorted((bm.BOTTLE / "train/good").glob("*.png"))
    perm = np.random.default_rng(bm.SEED).permutation(len(train))
    model = fit(Backbone(), bm.load([train[i] for i in perm[bm.N_VAL:]]))
    x = torch.from_numpy(load_input())

    def f():
        with torch.no_grad():
            model(x)
    return {"torch": summarize(measure(f, a.n, a.warmup))}


def connect(host, port):  # TCP_NODELAY: http.client sends headers and body separately; without it Nagle + delayed ACK adds ~40 ms
    import socket
    c = http.client.HTTPConnection(host, int(port)); c.connect()
    c.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return c


def mode_http(a):
    body = PNG.read_bytes()
    host, port = a.host.split(":")

    def one(conn, engine_ms=None):
        conn.request("POST", "/predict", body, {"Content-Type": "image/png"})
        r = conn.getresponse(); d = r.read()
        assert r.status == 200, d
        if engine_ms is not None:
            engine_ms.append(json.loads(d)["inference_ms"])
    conn = connect(host, port)
    for _ in range(a.warmup):
        one(conn)
    e2e, eng = [], []
    for _ in range(a.n):
        t = time.perf_counter(); one(conn, eng); e2e.append((time.perf_counter() - t) * 1e3)
    res = {"http_e2e_sequential": summarize(e2e), "engine_inference_ms_reported": summarize(eng)}
    for clients in (1, 4):  # throughput: N threads with keep-alive connections for a fixed duration
        done, stop = [0] * clients, time.time() + a.seconds

        def work(k):
            c = connect(host, port)
            while time.time() < stop:
                one(c); done[k] += 1
        ts = [threading.Thread(target=work, args=(k,)) for k in range(clients)]
        [t.start() for t in ts]; [t.join() for t in ts]
        res[f"throughput_{clients}_clients_req_per_s"] = round(sum(done) / a.seconds, 1)
    return res


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["torch", "ort", "http"])
    p.add_argument("--n", type=int, default=300)
    p.add_argument("--warmup", type=int, default=30)
    p.add_argument("--host", default="127.0.0.1:8081")
    p.add_argument("--seconds", type=int, default=10)
    a = p.parse_args()
    fn = {"torch": mode_torch, "ort": mode_ort, "http": mode_http}[a.mode]
    print(json.dumps({"mode": a.mode, "platform": platform.platform(), "python": platform.python_version(),
                      "warmup": a.warmup, **fn(a)}, indent=1))
