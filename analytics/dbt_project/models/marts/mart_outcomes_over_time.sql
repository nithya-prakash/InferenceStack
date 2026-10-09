-- Metric: request count per UTC hour, outcome and error category ('none' for successes).
select received_hour, outcome, error_category, count(*) as requests
from {{ ref('stg_request_log') }}
group by received_hour, outcome, error_category
