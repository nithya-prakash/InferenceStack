#!/usr/bin/env python3
"""Prepares the MVTec `bottle` test images (good + defective) for traffic replay: model/prepare_image.py pipeline -> data/replay/*.png.
Run: .venv/bin/python analytics/scripts/prepare_replay_images.py   (needs data/mvtec/bottle; output is gitignored)"""
import sys
from pathlib import Path

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "model"))
from PIL import Image
from prepare_image import prepare

out = ROOT / "data/replay"
out.mkdir(parents=True, exist_ok=True)
n = 0
for d in sorted((ROOT / "data/mvtec/bottle/test").iterdir()):
    for p in sorted(d.glob("*.png")):
        prepare(Image.open(p)).save(out / f"bottle_{d.name}_{p.stem}.png")
        n += 1
print(f"wrote {n} prepared images to {out}")
