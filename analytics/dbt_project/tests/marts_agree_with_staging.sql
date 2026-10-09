-- Singular test: every mart must account for exactly the same requests as staging (returns rows on mismatch).
with s as (select count(*) as n from {{ ref('stg_request_log') }})
select 'volume' as mart from s where (select sum(requests) from {{ ref('mart_request_volume') }}) <> n
union all select 'error_rate' from s where (select sum(requests) from {{ ref('mart_error_rate') }}) <> n
union all select 'per_key' from s where (select sum(requests) from {{ ref('mart_requests_per_key_daily') }}) <> n
union all select 'outcomes' from s where (select sum(requests) from {{ ref('mart_outcomes_over_time') }}) <> n
union all select 'latency_all' from s where (select sum(sample_size) from {{ ref('mart_latency_percentiles') }} where scope = 'all')
                                         <> (select count(*) from {{ ref('stg_request_log') }} where is_success)
