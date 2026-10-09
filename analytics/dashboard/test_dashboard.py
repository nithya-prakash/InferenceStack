"""Headless check of the dashboard against a database that already holds the dbt marts (see scripts/verify.sh)."""
import sys

from streamlit.testing.v1 import AppTest

at = AppTest.from_file("/app/app.py", default_timeout=60).run()
assert not at.exception, at.exception
assert not at.error, [e.value for e in at.error]
metrics = {m.label: m.value for m in at.metric}
print(metrics)
expected = {"Requests (latest day)": "4", "Failure rate (latest day)": "0.0%",
            "Cache hit rate (latest day)": "0.0%", "p95 latency ms (latest day)": "385.0"}
assert metrics == expected, (metrics, expected)
assert len(at.dataframe) >= 2
print("DASHBOARD OK")
sys.exit(0)
