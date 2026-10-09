"""Fit PaDiM on MVTec 'bottle', evaluate, export ONNX, write metadata + Python reference outputs.

Needs the dataset (data/mvtec/bottle, gitignored). Outputs (committed):
  artifacts/padim_bottle.onnx, artifacts/model_meta.json, test_data/png/*.png, test_data/reference.json
Run: .venv/bin/python model/build_model.py
"""
import hashlib
import io
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import numpy as np
import torch
from PIL import Image
from sklearn.metrics import roc_auc_score

sys.path.insert(0, str(Path(__file__).parent))
from padim import Backbone, fit
from prepare_image import decode_serving, prepare, to_png_bytes

HERE = Path(__file__).resolve().parent
BOTTLE = HERE.parent / "data/mvtec/bottle"
ONNX, META, PNG_DIR, REF = (HERE / "artifacts/padim_bottle.onnx", HERE / "artifacts/model_meta.json",
                            HERE / "test_data/png", HERE / "test_data/reference.json")
VERSION = "padim-bottle-v1"
RTOL, ATOL = 1e-4, 1e-3  # scores are ~5-50; observed PyTorch-vs-ORT diff is ~1e-6 relative
SEED, N_VAL = 42, 42


def load(paths):  # through the exact serving path: prepare -> PNG bytes -> decode
    return np.concatenate([decode_serving(to_png_bytes(prepare(Image.open(p)))) for p in paths])


def synthetic_pngs():
    """Deterministic 224x224 RGB images (name -> PNG bytes). No dataset images are committed."""
    rng = np.random.default_rng(7)
    xs = np.linspace(0, 255, 224, dtype=np.uint8)
    noise = np.kron(rng.integers(0, 256, (28, 28, 3), dtype=np.uint8), np.ones((8, 8, 1), np.uint8))
    checker = (((np.arange(224)[:, None] // 16 + np.arange(224)[None, :] // 16) % 2) * 255).astype(np.uint8)
    imgs = {"noise_seed7": noise, "black": np.zeros((224, 224, 3), np.uint8),
            "white": np.full((224, 224, 3), 255, np.uint8),
            "gradient": np.stack([np.tile(xs, (224, 1))] * 3, -1),
            "checker": np.stack([checker] * 3, -1),
            "mid_gray": np.full((224, 224, 3), 128, np.uint8)}
    return {k: to_png_bytes(Image.fromarray(v)) for k, v in imgs.items()}


def export(model):
    import onnxruntime as ort
    torch.onnx.export(model, (torch.zeros(2, 3, 224, 224),), str(ONNX), input_names=["image"],
                      output_names=["score", "heatmap"], opset_version=17, dynamo=False,
                      dynamic_axes={"image": {0: "batch"}, "score": {0: "batch"}, "heatmap": {0: "batch"}})
    return ort.InferenceSession(str(ONNX), providers=["CPUExecutionProvider"])


def main():
    train = sorted((BOTTLE / "train/good").glob("*.png"))
    perm = np.random.default_rng(SEED).permutation(len(train))
    val_p, fit_p = [train[i] for i in perm[:N_VAL]], [train[i] for i in perm[N_VAL:]]
    test_p = [(p, int(d.name != "good")) for d in sorted((BOTTLE / "test").iterdir()) for p in sorted(d.glob("*.png"))]
    X_fit, X_val = load(fit_p), load(val_p)
    X_test, y_test = load([p for p, _ in test_p]), np.array([y for _, y in test_p])

    model = fit(Backbone(), X_fit)
    with torch.no_grad():
        s_val, s_test = (model(torch.from_numpy(x))[0].numpy() for x in (X_val, X_test))
    thr = float(s_val.max())  # calibration: max score on held-out GOOD training images
    pred = s_test > thr
    sess = export(model)
    o_test = sess.run(None, {"image": X_test})[0]
    metrics = {"image_auroc_python": float(roc_auc_score(y_test, s_test)),
               "image_auroc_onnx": float(roc_auc_score(y_test, o_test)),
               "tpr_at_threshold": float(pred[y_test == 1].mean()), "fpr_at_threshold": float(pred[y_test == 0].mean()),
               "test_images": len(y_test), "test_defective": int(y_test.sum())}
    print(json.dumps(metrics, indent=1))

    PNG_DIR.mkdir(parents=True, exist_ok=True)
    cases = []
    for name, png in synthetic_pngs().items():
        (PNG_DIR / f"{name}.png").write_bytes(png)
        x = torch.from_numpy(decode_serving(png))
        with torch.no_grad():
            score, heat = model(x)
        cases.append({"file": f"png/{name}.png", "score": float(score[0]), "heatmap": heat[0].tolist()})
    sha = hashlib.sha256(ONNX.read_bytes()).hexdigest()
    REF.write_text(json.dumps({"model_version": VERSION, "onnx_sha256": sha, "rtol": RTOL, "atol": ATOL,
                               "note": "expected values from the PyTorch scorer on decode_serving(PNG)", "cases": cases}))
    META.write_text(json.dumps({
        "model_version": VERSION, "onnx_sha256": sha, "category": "bottle", "threshold": thr,
        "input": {"name": "image", "dtype": "float32", "layout": "NCHW", "shape": ["batch", 3, 224, 224], "range": "0..255 raw pixels"},
        "output": {"score": "float32 [batch], max Mahalanobis distance", "heatmap": "float32 [batch,28,28]"},
        "split": {"seed": SEED, "fit_good": len(fit_p), "calibration_good": len(val_p), "test": "official MVTec test split"},
        "metrics_official_test_split": metrics,
        "license": "MVTec AD: CC BY-NC-SA 4.0 (non-commercial); see MODEL_CARD.md"}, indent=1))
    print("threshold", thr, "onnx MB", ONNX.stat().st_size / 1e6)


if __name__ == "__main__":
    main()
