#!/usr/bin/env bash
# Verifies analytics against hand-computed expectations. Usage (repo root): analytics/scripts/verify.sh
# Starts a throwaway PostgreSQL 16, applies the gateway's migration, loads tests/fixture.sql, runs `dbt build`
# (all models + tests) with a NON-UTC session time zone, compares every mart with tests/expected.txt, then
# runs the dashboard headlessly (Streamlit AppTest) against the marts.
set -euo pipefail
cd "$(dirname "$0")/../.."
NET=is-an-test; PG=is-an-pg
cleanup() { docker rm -f $PG >/dev/null 2>&1 || true; docker network rm $NET >/dev/null 2>&1 || true; }
trap cleanup EXIT; cleanup
docker network create $NET >/dev/null
docker build -q -f analytics/Dockerfile.dbt -t is-dbt analytics/dbt_project >/dev/null
docker build -q -t is-dashboard analytics/dashboard >/dev/null
docker run -d --name $PG --network $NET -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=inferencestack postgres:16 >/dev/null
for i in $(seq 1 30); do docker exec $PG pg_isready -U postgres -q && break; sleep 1; done; sleep 2
psql_() { docker exec -i $PG psql -U postgres -d inferencestack -v ON_ERROR_STOP=1 -q "$@"; }
psql_ < gateway/src/main/resources/db/migration/V1__request_log.sql
psql_ < analytics/tests/fixture.sql
ENVS=(-e DB_HOST=$PG -e DB_USER=postgres -e DB_PASSWORD=pw -e DB_NAME=inferencestack -e PGTZ=America/New_York)
docker run --rm --network $NET "${ENVS[@]}" is-dbt build

q() { echo "== $1"; psql_ -tA -F, -c "$2"; }
{
q volume "select received_hour, requests, prev_active_hour_requests, round(pct_change,4), to_char(requests_ma3,'FM9990.000') from analytics.mart_request_volume order by 1"
q latency "select received_date, scope, sample_size, to_char(p50_ms::numeric,'FM9990.000'), to_char(p95_ms::numeric,'FM9990.000'), to_char(p99_ms::numeric,'FM9990.000') from analytics.mart_latency_percentiles order by 1,2"
q errors "select received_date, requests, server_errors, client_errors, round(server_error_rate,4), round(client_error_rate,4), round(failure_rate,4) from analytics.mart_error_rate order by 1"
q per_key "select received_date, api_key_id, requests, successes, rate_limited from analytics.mart_requests_per_key_daily order by 1,2"
q cache "select received_date, successful_requests, cache_hits, round(cache_hit_rate,4) from analytics.mart_cache_hit_rate order by 1"
q outcomes "select received_hour, outcome, error_category, requests from analytics.mart_outcomes_over_time order by 1,2,3"
} > /tmp/actual_marts.txt
diff -u analytics/tests/expected.txt /tmp/actual_marts.txt && echo "MARTS MATCH EXPECTED VALUES"
docker run --rm --network $NET -e DB_HOST=$PG -e DB_USER=postgres -e DB_PASSWORD=pw -e DB_NAME=inferencestack is-dashboard python /app/test_dashboard.py
