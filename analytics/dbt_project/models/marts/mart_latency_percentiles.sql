-- Metric: gateway latency (ms) of SUCCESSFUL requests per UTC day, computed from the raw rows of each group
-- (never by averaging smaller groups' percentiles). scope: all successful / served from cache / served by the engine.
-- sample_size is exposed because p95/p99 of a handful of requests are not meaningful.
select
    received_date,
    case when grouping(cache_hit) = 1 then 'all'
         when cache_hit then 'cache_hit'
         else 'engine_served' end                                  as scope,
    count(*)                                                       as sample_size,
    percentile_cont(0.50) within group (order by gateway_latency_ms) as p50_ms,
    percentile_cont(0.95) within group (order by gateway_latency_ms) as p95_ms,
    percentile_cont(0.99) within group (order by gateway_latency_ms) as p99_ms
from {{ ref('stg_request_log') }}
where is_success
group by grouping sets ((received_date, cache_hit), (received_date))
