#!/usr/bin/env bash
# End-to-end check of the whole Compose stack (builds everything, uses a throwaway project and volume).
# Usage: scripts/e2e.sh        Needs Docker and python3 on the host. Ports 8080/8501 must be free.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || ./scripts/init-env.sh
set -a; . ./.env; set +a
DC="docker compose -p is-e2e"
trap '$DC down -v >/dev/null 2>&1 || true' EXIT
$DC down -v >/dev/null 2>&1 || true
$DC up -d --build --wait --wait-timeout 300
echo "stack healthy"

curl -fs localhost:8080/health/ready | python3 -c "import sys,json; d=json.load(sys.stdin); assert d['status']=='ready', d; print('gateway ready, model', d['model_version'])"

# 1. Real request path: every reference PNG through gateway -> engine, compared with the Python reference scores.
python3 - <<'PY'
import json, os, urllib.request
ref = json.load(open("model/test_data/reference.json"))
for c in ref["cases"]:
    req = urllib.request.Request("http://localhost:8080/api/v1/predict", open("model/test_data/" + c["file"], "rb").read(),
                                 {"Content-Type": "image/png", "X-API-Key": os.environ["GATEWAY_API_KEY"]})
    r = json.load(urllib.request.urlopen(req))
    tol = ref["atol"] + ref["rtol"] * abs(c["score"])
    assert abs(r["anomaly_score"] - c["score"]) <= tol, (c["file"], r, c["score"])
print("score parity OK for", len(ref["cases"]), "reference images via gateway")
PY
test "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: image/png' --data-binary @model/test_data/png/black.png localhost:8080/api/v1/predict)" = 401 && echo "unauthenticated request rejected (401)"

# 2. Telemetry -> dbt -> marts -> dashboard.
$DC run --rm dbt build 2>&1 | grep -E "Done\.|ERROR|FAIL" | sed 's/\x1b\[[0-9;]*m//g'
N=$($DC exec -T postgres psql -U inferencestack -d inferencestack -tAc "select sum(requests) from analytics.mart_error_rate")
echo "requests in mart_error_rate: $N (expected 7: 6 predictions + 1 rejected)"; test "$N" = 7
curl -fs localhost:8501/_stcore/health >/dev/null && echo "dashboard healthy"
echo "E2E PASSED"
