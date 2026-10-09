# Analytics: PostgreSQL + dbt + Streamlit

**Layers.** Raw: `public.request_log`, created and owned by the gateway's Flyway migration (`gateway/src/main/resources/db/migration/V1__request_log.sql`: primary key, UNIQUE request_id, CHECK constraints, `timestamptz`, indexes on `received_at` and `(api_key_id, received_at)`). There is deliberately no second copy of that migration here: one schema, one owner. Derived: dbt writes `analytics_staging` (views) and `analytics` (mart tables) and never modifies the raw table.

## Verify (hand-computed cases; Docker only)
```bash
analytics/scripts/verify.sh
```
Starts PostgreSQL 16, applies the migration, loads `tests/fixture.sql` (15 rows, including one written with a +02 offset), runs `dbt build` (staging + 6 marts + tests = 44 nodes) with a **non-UTC session time zone** (`PGTZ=America/New_York`), diffs every mart against `tests/expected.txt` (values computed by hand before running dbt, using the metric definitions below), then runs the dashboard headlessly (Streamlit `AppTest`) against the marts.

## Run against the real stack
```bash
# gateway on :8080 with SPRING_PROFILES_ACTIVE=dev (see gateway/README.md), then:
python3 analytics/scripts/generate_traffic.py --requests 300 --engine-container <engine container name>
docker build -f analytics/Dockerfile.dbt -t is-dbt analytics/dbt_project
docker run --rm --network <net> -e DB_HOST=<pg> -e DB_USER=postgres -e DB_PASSWORD=<pw> is-dbt build      # models + tests
docker build -t is-dashboard analytics/dashboard
docker run -d --network <net> -p 8501:8501 -e DB_HOST=<pg> -e DB_USER=postgres -e DB_PASSWORD=<pw> is-dashboard   # http://localhost:8501
```
`dbt` env: `DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD`. Versions: dbt-postgres 1.11.0 (dbt-core 1.12.5), Streamlit 1.65.0, psycopg2-binary 2.9.13, pandas 3.0.6, PostgreSQL 16.

## Metrics (all UTC; `received_date` / `received_hour` are derived with `AT TIME ZONE 'UTC'`, so results do not depend on the DB session time zone)
| Mart | Definition |
|---|---|
| `mart_request_volume` | requests per hour (all outcomes); `pct_change` and `requests_ma3` use window functions over hours **that have traffic** (empty hours have no row) |
| `mart_latency_percentiles` | p50/p95/p99 of `gateway_latency_ms` of **successful** requests per day, from the raw rows of each group via `percentile_cont ... GROUP BY GROUPING SETS`: scope `all`, `cache_hit`, `engine_served`. Never an average of percentiles. `sample_size` is shown because p95/p99 of tiny groups are not meaningful |
| `mart_error_rate` | per day, denominator = all requests: `server_error_rate` (5xx), `client_error_rate` (4xx), `failure_rate` (both); NULL-safe via `nullif` |
| `mart_requests_per_key_daily` | requests, successes, rate-limited per API key id and day (`unauthenticated` for requests with no valid key) |
| `mart_cache_hit_rate` | cache hits / **successful** requests per day (only successes can be cached); NULL if no successes |
| `mart_outcomes_over_time` | requests per hour, outcome and error category |

**Data-quality decisions.** Duplicates: `request_id` is UNIQUE in the raw table and `unique`-tested in dbt; staging does not silently dedupe, so a duplicate fails the build. Failed requests are kept in volume, error and per-key marts but excluded from latency percentiles (they fail fast and would flatter them). dbt tests: not-null/unique/accepted values/range checks on source and marts, `is_success = (error_category = 'none')`, cache hits only on successes, p50 ≤ p95 ≤ p99, rates within [0,1], and a singular test that every mart accounts for exactly the staging row count. There is no dimension table, so no `relationships` tests.

## Results observed (real stack, 396 synthetic requests, seed 42)
`dbt build`: 44/44 pass. Marts: 396 requests, 5 server errors (engine stopped: gateway answered 504 after its 5 s read timeout), 140 client errors (101 rate-limited, 12 not-PNG, 11 unauthorized, 8 wrong size, 5 grayscale, 3 wrong content-type), cache hit rate 25.5% (64/251), latency over successes p50/p95/p99 = 60.5/78.6/95.4 ms (engine-served 60.9/85.0/97.7, cache hits 0.6/1.3/1.6) on a single M4 laptop Docker VM, one run. The counts match what the generator reported sending.

## Traffic generator
`scripts/generate_traffic.py` (stdlib only, deterministic per `--seed`) sends a weighted mix through the real gateway: new images, repeated images (cache hits), invalid requests (garbage, wrong size, grayscale, wrong content-type, missing/wrong key), and bursts on a 5-requests/minute key (429s). It uses the public dev keys `synthetic-load` / `synthetic-limited`, so synthetic rows are identifiable by key id. Downstream failures are real, not mocked: `--engine-container NAME` stops that container, sends a few requests, restarts it and waits for readiness.

## Limitations
- All marts are full-refresh tables (`dbt build` recomputes everything); no incremental models, scheduling or backfill. Generated traffic all lands within one hour/day, so the time charts are thin until the stack runs for longer.
- The dashboard is a read-only view with a 30 s cache; no auth, no TLS. It uses `DB_USER` credentials from the environment (the dev examples use the postgres superuser; use a read-only role in anything real).
- The dashboard tests check metric values and that it renders without exceptions, not its visual layout; I did not screenshot it.
