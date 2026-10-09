# Results for all 15 MVTec AD categories

Same method per category (see `model/MODEL_CARD.md`), Python evaluation only; **only `bottle` is served**. Produced by `python model/evaluate_all.py` on 2026-10-09 (data: `model/artifacts/eval_all_categories.json`). Test sets are MVTec's official splits.
Calibration = 20% of the good training images (`round(0.2 * n)`, seed 42), threshold = maximum score over them, test split scored once, 95% CI = percentile bootstrap over test images (2000 resamples, seed 0).
Mean AUROC **0.886**, lowest **0.724** (pill), 1,725 test images in total.

| Category | AUROC [95% CI] | Recall at threshold | FPR at threshold | Test images | Calibration images |
|---|---|---|---|---|---|
| bottle (served) | 0.987 [0.966, 1.000] | 0.889 | 0.000 | 83 (63 defective) | 42 |
| cable | 0.829 [0.762, 0.890] | 0.000 | 0.000 | 150 (92 defective) | 45 |
| capsule | 0.832 [0.709, 0.939] | 0.431 | 0.130 | 132 (109 defective) | 44 |
| carpet | 0.962 [0.924, 0.990] | 0.876 | 0.036 | 117 (89 defective) | 56 |
| grid | 0.805 [0.707, 0.895] | 0.123 | 0.000 | 78 (57 defective) | 53 |
| hazelnut | 0.777 [0.682, 0.869] | 0.143 | 0.025 | 110 (70 defective) | 78 |
| leather | 0.991 [0.975, 1.000] | 0.891 | 0.000 | 124 (92 defective) | 49 |
| metal_nut | 0.957 [0.917, 0.987] | 0.538 | 0.000 | 115 (93 defective) | 44 |
| pill | 0.724 [0.611, 0.828] | 0.064 | 0.000 | 167 (141 defective) | 53 |
| screw | 0.757 [0.672, 0.834] | 0.101 | 0.000 | 160 (119 defective) | 64 |
| tile | 0.922 [0.865, 0.969] | 0.488 | 0.000 | 117 (84 defective) | 46 |
| toothbrush | 0.950 [0.879, 0.994] | 0.867 | 0.250 | 42 (30 defective) | 12 |
| transistor | 0.920 [0.861, 0.967] | 0.325 | 0.000 | 100 (40 defective) | 43 |
| wood | 0.967 [0.928, 0.994] | 0.767 | 0.000 | 79 (60 defective) | 49 |
| zipper | 0.910 [0.836, 0.971] | 0.387 | 0.062 | 151 (119 defective) | 48 |

Reading: the threshold rule is conservative (near-zero false positives on most categories, but low recall on many, e.g. cable 0.000, pill 0.064); no per-category threshold tuning was done; toothbrush was calibrated on only 12 images. The ResNet18 / 64-channel configuration was chosen for size and speed, not tuned per category.
