# InferenceStack

Industrial vision inference platform: C++17 (ONNX Runtime) engine, Java 21 (Spring Boot) gateway, PostgreSQL/dbt analytics.
**Status:** Phase 0 (model), Phase 1 (C++ engine), Phase 2 (Java gateway) complete; analytics, dashboard, compose and CI not started.

## Model migration note
Early commits (`ff0898d`) exported a predictive-maintenance RUL CNN. That model was replaced by a PaDiM-style anomaly detector
for MVTec AD `bottle` (see `model/MODEL_CARD.md`). The RUL model, export script and artifacts were removed; they remain in git history
and in the separate PredictiveMaintenanceAI project. Nothing in the current tree uses the RUL model.

## Model checks
```bash
python -m venv .venv && .venv/bin/pip install -r model/requirements.txt
.venv/bin/python model/test_parity.py   # no dataset needed
```
The MVTec AD dataset (CC BY-NC-SA 4.0, non-commercial) is not included; see the model card for attribution and restrictions.
