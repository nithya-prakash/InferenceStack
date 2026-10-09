#!/usr/bin/env python3
"""Synthetic traffic against a running gateway (dev profile). Stdlib only, deterministic for a given --seed.

Every request goes through the real gateway/engine/database path, and uses the public dev keys
`synthetic-load` / `synthetic-limited` (ids visible in the telemetry), so synthetic rows are identifiable and no real
key is needed. With --image-dir, success/cache_hit traffic replays real images (the request pattern is still simulated).
Mixture (weights, relative): success (new image), cache_hit (resend an earlier image), invalid (garbage,
wrong size, grayscale, wrong content-type, missing/wrong key), rate_limited (burst on the 5/min key).
Downstream failures are NOT simulated by the gateway: with --engine-container NAME the script really stops that Docker
container, sends --outage-requests requests (gateway answers 502), then starts it again and waits for readiness.

  generate_traffic.py --requests 300 [--url http://localhost:8080] [--engine-container is-engine]
"""
import argparse
import pathlib
import collections
import http.client
import random
import struct
import subprocess
import time
import urllib.parse
import zlib

LOAD_KEY, LIMITED_KEY = "synthetic-load-key-not-a-secret", "synthetic-limited-key-not-a-secret"


def png(w=224, h=224, channels=3, seed=0):
    color_type = {1: 0, 3: 2}[channels]
    rows = b"".join(b"\x00" + bytes((x * 3 + y * 5 + seed * 37 + c * 11) % 256 for x in range(w) for c in range(channels)) for y in range(h))

    rows = rows[:1] + seed.to_bytes(4, "big") + rows[5:]  # stamp the seed into the first pixels so every seed is a distinct image

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, color_type, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


class Client:
    def __init__(self, url):
        u = urllib.parse.urlparse(url)
        self.host, self.port = u.hostname, u.port or 80

    def call(self, method, path, body=None, key=None, ctype="image/png"):
        conn = http.client.HTTPConnection(self.host, self.port, timeout=30)
        headers = {"Content-Type": ctype}
        if key:
            headers["X-API-Key"] = key
        conn.request(method, path, body, headers)
        r = conn.getresponse(); r.read(); conn.close()
        return r.status


def main():
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("--url", default="http://localhost:8080")
    a.add_argument("--requests", type=int, default=300)
    a.add_argument("--key", default=LOAD_KEY, help="API key for normal traffic (default: public dev key)")
    a.add_argument("--limited-key", default=LIMITED_KEY, help="key with a tiny rate limit (dev profile only)")
    a.add_argument("--image-dir", help="directory of prepared 224x224 RGB PNGs (see prepare_replay_images.py) used for success/cache_hit traffic instead of generated images")
    a.add_argument("--seed", type=int, default=42)
    a.add_argument("--mix", default="success=60,cache_hit=25,invalid=10,rate_limited=5", help="relative weights")
    a.add_argument("--delay-ms", type=int, default=0)
    a.add_argument("--engine-container")
    a.add_argument("--outage-requests", type=int, default=5)
    a = a.parse_args()
    rng, c = random.Random(a.seed), Client(a.url)
    mix = {k: float(v) for k, v in (p.split("=") for p in a.mix.split(","))}
    seen, counts, nxt = [], collections.Counter(), 0
    pool = [f.read_bytes() for f in sorted(pathlib.Path(a.image_dir).glob("*.png"))] if a.image_dir else []
    used = 0

    def send(label, *args, **kw):
        counts[(label, c.call(*args, **kw))] += 1

    for _ in range(a.requests):
        kind = rng.choices(list(mix), weights=list(mix.values()))[0]
        if kind == "cache_hit" and not seen:
            kind = "success"
        if kind == "success":
            if pool and used < len(pool):    # replay each real image once (first sight = engine call), then reuse
                used += 1; seen.append(pool[used - 1])
            elif pool:
                seen.append(rng.choice(pool))  # pool exhausted: repeats become cache hits, labelled "success" here
            else:
                nxt += 1; seen.append(png(seed=nxt))
            send(kind, "POST", "/api/v1/predict", seen[-1], a.key)
        elif kind == "cache_hit":
            send(kind, "POST", "/api/v1/predict", rng.choice(seen), a.key)
        elif kind == "invalid":
            bad = rng.choice([("garbage", b"not an image", a.key, "image/png"), ("wrong_size", png(64, 64), a.key, "image/png"),
                              ("grayscale", png(channels=1), a.key, "image/png"), ("wrong_type", png(), a.key, "text/plain"),
                              ("no_key", png(), None, "image/png"), ("bad_key", png(), "wrong-key", "image/png")])
            send("invalid:" + bad[0], "POST", "/api/v1/predict", bad[1], bad[2], bad[3])
        else:  # rate_limited: burst on the 5/min key; the first requests of a minute succeed, the rest get 429
            for _ in range(8):
                nxt += 1
                send(kind, "POST", "/api/v1/predict", png(seed=nxt), a.limited_key)
        if a.delay_ms:
            time.sleep(a.delay_ms / 1000)

    if a.engine_container:
        subprocess.run(["docker", "stop", a.engine_container], check=True, capture_output=True)
        for _ in range(a.outage_requests):
            nxt += 1
            send("engine_outage", "POST", "/api/v1/predict", png(seed=nxt), a.key)
        subprocess.run(["docker", "start", a.engine_container], check=True, capture_output=True)
        for _ in range(60):
            try:
                if c.call("GET", "/health/ready") == 200:
                    break
            except OSError:
                pass
            time.sleep(1)

    for (label, status), n in sorted(counts.items()):
        print(f"{label:22s} HTTP {status}: {n}")
    print("total requests sent:", sum(counts.values()))


if __name__ == "__main__":
    main()
