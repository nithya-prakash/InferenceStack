-- One row per request, typed and with UTC time buckets. No deduplication on purpose: request_id is UNIQUE in the
-- raw table and tested; a duplicate should fail loudly instead of being silently dropped.
select
    request_id,
    received_at,
    date_trunc('hour', received_at at time zone 'UTC') as received_hour,   -- UTC wall-clock hour (timestamp, no zone)
    (received_at at time zone 'UTC')::date             as received_date,   -- UTC day, independent of the DB session time zone
    coalesce(api_key_id, 'unauthenticated')            as api_key_id,
    http_status,
    outcome,
    coalesce(error_category, 'none')                   as error_category,
    outcome = 'success'                                as is_success,
    cache_hit,
    gateway_latency_ms,
    engine_latency_ms,
    model_version,
    anomaly_score,
    is_anomalous
from {{ source('gateway', 'request_log') }}
