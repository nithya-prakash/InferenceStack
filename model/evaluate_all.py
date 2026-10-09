"""Evaluate the same PaDiM-style method on every MVTec AD category (Python only; nothing here is served).

Per category: fit on ~80% of the good training images, calibrate the threshold (max score) on the other ~20% (seed 42,
n_val = round(0.2 * n_train); this equals the 42 used for `bottle`), then score the official test split once.
95% CI: percentile bootstrap over test images (2000 resamples, seed 0). Writes model/artifacts/eval_all_categories.json.
Run: .venv/bin/python model/evaluate_all.py [category ...]    (needs data/mvtec/<category>; not committed)
"""
import json
import sys
import time
from pathlib import Path

sys.dont_write_bytecode = True
import numpy as np
import torch
from sklearn.metrics import roc_auc_score

sys.path.insert(0, str(Path(__file__).parent))
from build_model import SEED, load
from padim import Backbone, fit

ROOT = Path(__file__).resolve().parents[1] / "data/mvtec"
OUT = Path(__file__).resolve().parent / "artifacts/eval_all_categories.json"
CATEGORIES = ["bottle", "cable", "capsule", "carpet", "grid", "hazelnut", "leather", "metal_nut", "pill", "screw",
              "tile", "toothbrush", "transistor", "wood", "zipper"]


def bootstrap_ci(y, s, n=2000, seed=0):
    rng, vals = np.random.default_rng(seed), []
    for _ in range(n):
        i = rng.integers(0, len(y), len(y))
        if 0 < y[i].sum() < len(i):
            vals.append(roc_auc_score(y[i], s[i]))
    return float(np.percentile(vals, 2.5)), float(np.percentile(vals, 97.5))


def evaluate(cat):
    d = ROOT / cat
    train = sorted((d / "train/good").glob("*.png"))
    n_val = round(0.2 * len(train))
    perm = np.random.default_rng(SEED).permutation(len(train))
    val_p, fit_p = [train[i] for i in perm[:n_val]], [train[i] for i in perm[n_val:]]
    test = [(p, int(t.name != "good")) for t in sorted((d / "test").iterdir()) for p in sorted(t.glob("*.png"))]
    X_fit, X_val = load(fit_p), load(val_p)
    X_test, y = load([p for p, _ in test]), np.array([l for _, l in test])
    model = fit(Backbone(), X_fit)
    with torch.no_grad():
        s_val = model(torch.from_numpy(X_val))[0].numpy()
        s = np.concatenate([model(torch.from_numpy(X_test[i:i + 32]))[0].numpy() for i in range(0, len(X_test), 32)])
    thr = float(s_val.max())
    lo, hi = bootstrap_ci(y, s)
    return {"category": cat, "train_fit": len(fit_p), "calibration": len(val_p), "test": len(y), "test_defective": int(y.sum()),
            "auroc": float(roc_auc_score(y, s)), "auroc_ci95": [lo, hi],
            "tpr": float((s[y == 1] > thr).mean()), "fpr": float((s[y == 0] > thr).mean()), "threshold": thr}


if __name__ == "__main__":
    cats = sys.argv[1:] or CATEGORIES
    results = []
    for c in cats:
        t0 = time.time()
        r = evaluate(c)
        results.append(r)
        print(f"{c:11s} AUROC {r['auroc']:.4f} [{r['auroc_ci95'][0]:.3f}, {r['auroc_ci95'][1]:.3f}] TPR {r['tpr']:.3f} FPR {r['fpr']:.3f} "
              f"(fit {r['train_fit']}, cal {r['calibration']}, test {r['test']}) {time.time()-t0:.0f}s", flush=True)
    if len(cats) == len(CATEGORIES):
        aurocs = [r["auroc"] for r in results]
        summary = {"mean_auroc": float(np.mean(aurocs)), "min_auroc": float(min(aurocs)), "test_images": sum(r["test"] for r in results)}
        OUT.write_text(json.dumps({"method": "see model/MODEL_CARD.md; split rule in evaluate_all.py", "summary": summary,
                                   "categories": results}, indent=1))
        print(summary)
