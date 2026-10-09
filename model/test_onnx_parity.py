"""Checks the exported ONNX against reference.json using only onnxruntime + numpy.
Run: .venv/bin/python model/test_onnx_parity.py"""
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

HERE = Path(__file__).resolve().parent
ref = json.loads((HERE / "test_data" / "reference.json").read_text())
sess = ort.InferenceSession(str(HERE / "artifacts" / ref["model_file"]), providers=["CPUExecutionProvider"])
X = np.array([c["window"] for c in ref["cases"]], dtype=np.float32)
exp = np.array([c["expected_rul"] for c in ref["cases"]], dtype=np.float32)

assert X.shape[1:] == (30, 14)
out = sess.run(None, {"window": X})[0]
assert out.shape == (len(X),) and out.dtype == np.float32
assert np.abs(out - exp).max() < ref["atol"], np.abs(out - exp).max()
assert out.min() >= 0 and out.max() <= 125
for i in range(len(X)):  # dynamic batch: batch=1 must match too
    assert abs(sess.run(None, {"window": X[i:i + 1]})[0][0] - exp[i]) < ref["atol"]
for bad in (np.zeros((1, 29, 14), np.float32), np.zeros((1, 30, 13), np.float32)):
    try:
        sess.run(None, {"window": bad}); raise SystemExit("bad shape accepted")
    except Exception as e:
        assert "SystemExit" not in type(e).__name__
print(f"OK: {len(X)} cases, max diff {np.abs(out - exp).max():.2e} < atol {ref['atol']}")
