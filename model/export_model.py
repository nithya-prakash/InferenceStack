"""Phase 0: export the PredictiveMaintenanceAI 1D-CNN to ONNX and write reference data.

ONNX graph = (x - mean) / std  ->  CNN  ->  clamp(0, 125). Raw sensor values go in, RUL
in cycles comes out; no consumer re-applies normalization. The reference outputs come from
the ORIGINAL pipeline (cnn_rul._predict: numpy normalization + torch model + np.clip).

Run: .venv/bin/python model/export_model.py   (original repo read-only at ../PredictiveMaintenanceAI)
"""
import hashlib
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
SRC = Path(__file__).resolve().parents[2] / "PredictiveMaintenanceAI"
sys.path.insert(0, str(SRC))

import numpy as np  # noqa: E402
import onnxruntime as ort  # noqa: E402
import torch  # noqa: E402
from torch import nn  # noqa: E402

from ml.data.cmapss import DEFAULT_DIR, RUL_CAP, SENSORS, load_test  # noqa: E402
from ml.deep.cnn_rul import CNN, WINDOW, _predict, make_windows  # noqa: E402

OUT = Path(__file__).resolve().parent
ONNX_PATH = OUT / "artifacts" / "cnn_rul.onnx"
REF_PATH = OUT / "test_data" / "reference.json"
ATOL = 1e-3  # cycles; justified in the report from the measured max diff (float32 conv reordering)
DATA_DIR = SRC / DEFAULT_DIR


class Served(nn.Module):
    """Normalization + CNN + output clip, so the graph takes raw sensor windows."""

    def __init__(self, cnn, mean, std):
        super().__init__()
        self.cnn = cnn
        self.register_buffer("mean", torch.from_numpy(mean))
        self.register_buffer("std", torch.from_numpy(std))

    def forward(self, x):
        return self.cnn((x - self.mean) / self.std).clamp(0.0, float(RUL_CAP))


def load_checkpoint():
    ck = torch.load(SRC / "models" / "cnn_rul.pt", weights_only=False)  # own file; holds numpy arrays
    cnn = CNN()
    cnn.load_state_dict(ck["state_dict"])
    return cnn.eval(), ck["mean"], ck["std"]


def build_cases(df_test):
    """Real FD001 windows (+ padding and clip edge cases). Returns (name, raw window, label)."""
    df_test = df_test.assign(rul_capped=0.0)  # labels unused here; load_test has no RUL column
    X, _, _ = make_windows(df_test, last_only=True)  # each engine's last cycle, 100 engines
    cases = [("last_cycle_engine_%d" % (i + 1), X[i], None) for i in (0, 1, 2, 49, 99)]
    g = df_test[df_test.engine_id == 1].sort_values("cycle")
    early_X, _, _ = make_windows(g.head(40))  # one window per cycle of the first 40 cycles
    cases.append(("early_full_window_cycle40", early_X[-1], None))
    cases.append(("short_history_pad_cycle10", early_X[9], None))  # first 20 rows are padding
    cases.append(("short_history_pad_cycle1", early_X[0], None))
    rng = np.random.default_rng(42)
    mean, std = df_test[SENSORS].mean().to_numpy(np.float32), df_test[SENSORS].std().to_numpy(np.float32)
    ones = np.ones((WINDOW, 1), np.float32)
    cases.append(("synthetic_noise_seed42", (mean + rng.normal(size=(WINDOW, 14)) * std).astype(np.float32), None))
    cases.append(("synthetic_extreme_high", (mean + 8 * std).astype(np.float32) * ones, None))
    cases.append(("synthetic_extreme_low", (mean - 8 * std).astype(np.float32) * ones, None))
    return cases


def main():
    cnn, mean, std = load_checkpoint()
    served = Served(cnn, mean, std).eval()
    ONNX_PATH.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        served, (torch.zeros(2, WINDOW, 14),), str(ONNX_PATH), input_names=["window"], output_names=["rul"],
        dynamic_axes={"window": {0: "batch"}, "rul": {0: "batch"}}, opset_version=17, dynamo=False,
    )
    sess = ort.InferenceSession(str(ONNX_PATH), providers=["CPUExecutionProvider"])

    cases = build_cases(load_test(DATA_DIR))
    X = np.stack([c[1] for c in cases]).astype(np.float32)
    ref = _predict(cnn, X, mean, std)  # original pipeline
    with torch.no_grad():
        raw = cnn(torch.from_numpy((X - mean) / std)).numpy()
    onnx_out = sess.run(None, {"window": X})[0]
    single = np.array([sess.run(None, {"window": X[i:i + 1]})[0][0] for i in range(len(X))])  # batch=1
    d_batch, d_single = np.abs(ref - onnx_out).max(), np.abs(ref - single).max()
    print(f"cases={len(X)} max|torch-onnx| batch={d_batch:.3e} single={d_single:.3e} atol={ATOL}")
    assert max(d_batch, d_single) < ATOL, "ONNX parity failed"

    REF_PATH.parent.mkdir(parents=True, exist_ok=True)
    REF_PATH.write_text(json.dumps({
        "model_file": ONNX_PATH.name, "onnx_sha256": hashlib.sha256(ONNX_PATH.read_bytes()).hexdigest(),
        "input": {"name": "window", "dtype": "float32", "shape": ["batch", WINDOW, 14], "features": SENSORS},
        "output": {"name": "rul", "dtype": "float32", "shape": ["batch"], "unit": "cycles", "range": [0, RUL_CAP]},
        "atol": ATOL, "max_abs_diff_observed": float(max(d_batch, d_single)),
        "cases": [{"name": c[0], "window": X[i].tolist(), "expected_rul": float(ref[i]),
                   "unclipped_torch_rul": float(raw[i])} for i, c in enumerate(cases)],
    }))
    for c, r, o, u in zip(cases, ref, onnx_out, raw):
        print(f"{c[0]:28s} torch={r:8.3f} onnx={o:8.3f} unclipped={u:9.3f}")
    print("onnx bytes:", ONNX_PATH.stat().st_size, "ref bytes:", REF_PATH.stat().st_size)


if __name__ == "__main__":
    main()
