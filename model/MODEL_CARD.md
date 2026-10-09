# Model card: padim-bottle-v1

**Task:** image-level anomaly detection (good vs defective) for MVTec AD `bottle`. One category only.
**Method:** PaDiM-style (Defard et al., 2020). Frozen ImageNet ResNet18 (torchvision `IMAGENET1K_V1`) → layer2 + upsampled layer3
(384 ch, 28×28) → 64 randomly chosen channels (seed 0) → per-position Gaussian (mean, regularized covariance, eps 0.01) fitted on good images.
Score = max over positions of Mahalanobis distance. The fitted statistics are constants inside the ONNX graph (24 MB).

## Contract
- Input `image`: float32 `[batch,3,224,224]` NCHW, **raw pixel values 0..255, RGB**. Dynamic batch.
- Output `score` float32 `[batch]` (higher = more anomalous), `heatmap` float32 `[batch,28,28]`.
- `is_anomalous = score > threshold`; threshold is in `artifacts/model_meta.json` (not in the graph).

## Preprocessing ownership (no duplicated transforms)
| Step | Owner |
|---|---|
| Convert to RGB, resize 256×256 (PIL bilinear), center-crop 224, save PNG | Python client utility `prepare_image.py` |
| Validate PNG is 224×224 RGB, decode, HWC uint8 → CHW float32 (no scaling) | serving engine (C++, Phase 1) |
| `/255`, ImageNet mean/std normalization, everything after | inside the ONNX graph |
The engine never resizes (Pillow vs C++ resizers differ in pixels). PNG is lossless, so decoding is exact.

## Data, calibration, evaluation
- Fit: 167 good train images. Threshold: max score over 42 held-out good train images (seed 42) = see `model_meta.json`.
- Final evaluation on the official test split (63 defective, 20 good), used once, not for tuning: image AUROC 0.9873, TPR 0.889, FPR 0.000 at the threshold.
- Parity tests (`test_parity.py`) prove ONNX == PyTorch; they say nothing about accuracy.

## Limitations
One category, small test set (83 images; the threshold depends on 42 samples), clean lab images, CPU float32 only, no heatmap blur,
no robustness testing. Not suitable for real inspection decisions.

## License and attribution
Dataset: MVTec AD, © MVTec Software GmbH, **CC BY-NC-SA 4.0** (non-commercial, attribution, share-alike).
Bergmann et al., "MVTec AD – A Comprehensive Real-World Dataset for Unsupervised Anomaly Detection", CVPR 2019.
The statistics in the ONNX file are derived from that data, so the model artifact is for **non-commercial use** under the same license.
The dataset itself is not in this repo; committed test images are synthetic. ResNet18 ImageNet weights come from torchvision.

## Reproduce
`python model/build_model.py` (needs `data/mvtec/bottle`), then `python model/test_parity.py` (needs only the committed files).
Pinned versions: `model/requirements.txt`.
