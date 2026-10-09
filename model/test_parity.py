"""Standalone tests: committed ONNX vs committed Python reference. No dataset or torch needed.
Run: .venv/bin/python model/test_parity.py"""
import hashlib
import io
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from prepare_image import decode_serving, prepare, to_png_bytes

HERE = Path(__file__).resolve().parent
ref = json.loads((HERE / "test_data/reference.json").read_text())
meta = json.loads((HERE / "artifacts/model_meta.json").read_text())
onnx_path = HERE / "artifacts/padim_bottle.onnx"
sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])


def run(x):
    return sess.run(None, {"image": x})


def test_artifact_identity():
    sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    assert sha == ref["onnx_sha256"] == meta["onnx_sha256"]
    assert meta["threshold"] > 0


def test_contract():
    i, o = sess.get_inputs()[0], sess.get_outputs()
    assert (i.name, i.type, i.shape[1:]) == ("image", "tensor(float)", [3, 224, 224])
    assert [x.name for x in o] == ["score", "heatmap"]


def test_parity_with_python_reference():
    for c in ref["cases"]:
        x = decode_serving((HERE / "test_data" / c["file"]).read_bytes())
        score, heat = run(x)
        assert score.shape == (1,) and score.dtype == np.float32 and heat.shape == (1, 28, 28)
        assert np.isfinite(score).all() and np.isfinite(heat).all()
        np.testing.assert_allclose(score[0], c["score"], rtol=ref["rtol"], atol=ref["atol"], err_msg=c["file"])
        np.testing.assert_allclose(heat[0], np.array(c["heatmap"]), rtol=ref["rtol"], atol=ref["atol"], err_msg=c["file"])


def test_batching_consistent():
    X = np.concatenate([decode_serving((HERE / "test_data" / c["file"]).read_bytes()) for c in ref["cases"]])
    batch = run(X)[0]
    for i in range(len(X)):
        np.testing.assert_allclose(run(X[i:i + 1])[0][0], batch[i], rtol=1e-4)


def test_wrong_shape_rejected():
    for shape in [(1, 3, 128, 128), (1, 1, 224, 224)]:
        try:
            run(np.zeros(shape, np.float32))
        except Exception:
            continue
        raise AssertionError(f"shape {shape} accepted")


def test_prepare_image_contract():
    src = Image.fromarray(np.random.default_rng(1).integers(0, 256, (900, 600, 4), dtype=np.uint8), "RGBA")
    out = prepare(src)
    assert out.size == (224, 224) and out.mode == "RGB"
    png = to_png_bytes(out)
    assert png == to_png_bytes(prepare(src))                       # deterministic
    np.testing.assert_array_equal(decode_serving(png)[0].transpose(1, 2, 0), np.asarray(out))  # PNG lossless


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
        print("PASS", t.__name__)
