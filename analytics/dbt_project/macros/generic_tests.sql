{# Passes when no row violates the SQL expression. #}
{% test expression_is_true(model, expression, column_name=none) %}
select * from {{ model }} where not ({{ expression }})
{% endtest %}

{# Passes when the column combination is unique (a tiny stand-in for dbt_utils, to avoid a package dependency). #}
{% test unique_combination(model, columns) %}
select {{ columns | join(', ') }}, count(*) as n from {{ model }} group by {{ columns | join(', ') }} having count(*) > 1
{% endtest %}
