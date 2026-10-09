"""Client-side image preparation: ANY RGB-convertible image -> 224x224 RGB PNG bytes.

Ownership: this utility (Python/Pillow) owns decode-to-RGB, resize and crop. The serving
engine accepts only 224x224 RGB PNG and never resizes, so there is no second resize to
disagree with this one. Steps: convert('RGB') -> bilinear resize to 256x256 (PIL, antialiased)
-> center crop 224x224 -> lossless PNG.
"""
import io
import sys
from pathlib import Path

import numpy as np
from PIL import Image

RESIZE, SIZE = 256, 224


def prepare(img: Image.Image) -> Image.Image:
    img = img.convert("RGB").resize((RESIZE, RESIZE), Image.BILINEAR)
    off = (RESIZE - SIZE) // 2
    return img.crop((off, off, off + SIZE, off + SIZE))


def to_png_bytes(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def decode_serving(png: bytes) -> np.ndarray:
    """What the engine does with the bytes: PNG -> uint8 HWC (224,224,3) -> float32 NCHW, 0..255."""
    arr = np.asarray(Image.open(io.BytesIO(png)).convert("RGB"), dtype=np.uint8)
    assert arr.shape == (SIZE, SIZE, 3), arr.shape
    return arr.transpose(2, 0, 1)[None].astype(np.float32)


if __name__ == "__main__":
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.write_bytes(to_png_bytes(prepare(Image.open(src))))
