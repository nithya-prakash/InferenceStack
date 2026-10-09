"""Streamlit dashboard. Reads ONLY the dbt marts in schema `analytics`; all metrics are computed by dbt (SQL)."""
import os

import pandas as pd
import psycopg2
import streamlit as st

st.set_page_config(page_title="InferenceStack analytics", layout="wide")


def query(sql: str) -> pd.DataFrame:
    with psycopg2.connect(host=os.getenv("DB_HOST", "localhost"), port=os.getenv("DB_PORT", "5432"),
                          dbname=os.getenv("DB_NAME", "inferencestack"), user=os.getenv("DB_USER", "inferencestack"),
                          password=os.getenv("DB_PASSWORD", "")) as conn, conn.cursor() as cur:
        cur.execute(sql)
        return pd.DataFrame(cur.fetchall(), columns=[d[0] for d in cur.description])


@st.cache_data(ttl=30)
def load() -> dict:
    return {name: query(f"SELECT * FROM analytics.{name}") for name in (
        "mart_request_volume", "mart_latency_percentiles", "mart_error_rate",
        "mart_requests_per_key_daily", "mart_cache_hit_rate", "mart_outcomes_over_time")}


st.title("InferenceStack: request analytics")
try:
    d = load()
except Exception as e:  # missing marts, DB down, ...
    st.error(f"Cannot read the analytics marts ({type(e).__name__}). Has `dbt build` been run?")
    st.stop()

if d["mart_error_rate"].empty:
    st.info("No requests recorded yet.")
    st.stop()

err = d["mart_error_rate"].sort_values("received_date")
cache = d["mart_cache_hit_rate"].sort_values("received_date")
lat = d["mart_latency_percentiles"]
latest = err.iloc[-1]
lat_all = lat[(lat.scope == "all") & (lat.received_date == latest.received_date)]
c = st.columns(4)
c[0].metric("Requests (latest day)", int(latest.requests))
c[1].metric("Failure rate (latest day)", f"{float(latest.failure_rate):.1%}")
rate = cache.iloc[-1].cache_hit_rate
c[2].metric("Cache hit rate (latest day)", "n/a" if pd.isna(rate) else f"{float(rate):.1%}")
c[3].metric("p95 latency ms (latest day)", "n/a" if lat_all.empty else f"{float(lat_all.iloc[0].p95_ms):.1f}")
st.caption(f"Latest day: {latest.received_date} (UTC). Percentiles are over successful requests only.")

left, right = st.columns(2)
with left:
    st.subheader("Request volume per hour (UTC)")
    st.line_chart(d["mart_request_volume"].set_index("received_hour")[["requests", "requests_ma3"]])
    st.subheader("Failure rates per day")
    st.bar_chart(err.set_index("received_date")[["server_error_rate", "client_error_rate"]].astype(float))
    st.subheader("Cache hit rate per day")
    st.bar_chart(cache.set_index("received_date")[["cache_hit_rate"]].astype(float))
with right:
    st.subheader("Latency percentiles per day (all successful requests)")
    all_lat = lat[lat.scope == "all"].sort_values("received_date")
    st.line_chart(all_lat.set_index("received_date")[["p50_ms", "p95_ms", "p99_ms"]].astype(float))
    st.dataframe(lat.sort_values(["received_date", "scope"]), hide_index=True, width="stretch")
    st.subheader("Requests by API key id and day")
    per_key = d["mart_requests_per_key_daily"].pivot_table(index="received_date", columns="api_key_id", values="requests", fill_value=0)
    st.bar_chart(per_key)

st.subheader("Outcomes and error categories over time")
st.dataframe(d["mart_outcomes_over_time"].sort_values(["received_hour", "outcome", "error_category"]), hide_index=True, width="stretch")
