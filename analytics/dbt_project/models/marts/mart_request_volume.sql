-- Metric: requests per UTC hour (all outcomes). Hours with no traffic have no row.
-- prev_active_hour_requests / pct_change compare with the previous hour THAT HAS TRAFFIC (lag over existing rows),
-- requests_ma3 is the mean over the current and up to 2 previous active hours.
with hourly as (
    select received_hour, count(*) as requests
    from {{ ref('stg_request_log') }}
    group by received_hour
)
select
    received_hour,
    requests,
    lag(requests) over w                                                    as prev_active_hour_requests,
    (requests - lag(requests) over w)::numeric / nullif(lag(requests) over w, 0) as pct_change,
    avg(requests) over (order by received_hour rows between 2 preceding and current row) as requests_ma3
from hourly
window w as (order by received_hour)
