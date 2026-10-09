-- Metric: cache_hit_rate = cache hits / successful requests per UTC day (only successful predictions can be cached
-- or served from cache, so failed requests are not in the denominator). NULL when there were no successes.
select
    received_date,
    count(*) filter (where is_success)                         as successful_requests,
    count(*) filter (where cache_hit)                          as cache_hits,
    count(*) filter (where cache_hit)::numeric / nullif(count(*) filter (where is_success), 0) as cache_hit_rate
from {{ ref('stg_request_log') }}
group by received_date
