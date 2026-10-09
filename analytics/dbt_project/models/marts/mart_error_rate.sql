-- Metric (per UTC day, denominator = all requests that day):
--   server_error_rate = 5xx / requests (gateway or engine problems)
--   client_error_rate = 4xx / requests (bad input, auth, rate limiting)
--   failure_rate      = (4xx + 5xx) / requests
select
    received_date,
    count(*)                                         as requests,
    count(*) filter (where outcome = 'server_error') as server_errors,
    count(*) filter (where outcome = 'client_error') as client_errors,
    count(*) filter (where outcome = 'server_error')::numeric / nullif(count(*), 0) as server_error_rate,
    count(*) filter (where outcome = 'client_error')::numeric / nullif(count(*), 0) as client_error_rate,
    count(*) filter (where not is_success)::numeric / nullif(count(*), 0)           as failure_rate
from {{ ref('stg_request_log') }}
group by received_date
