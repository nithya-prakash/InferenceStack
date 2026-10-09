-- Metric: requests per API key id per UTC day. Unauthenticated requests are grouped as 'unauthenticated'.
select
    received_date,
    api_key_id,
    count(*)                                                  as requests,
    count(*) filter (where is_success)                        as successes,
    count(*) filter (where error_category = 'rate_limited')   as rate_limited
from {{ ref('stg_request_log') }}
group by received_date, api_key_id
