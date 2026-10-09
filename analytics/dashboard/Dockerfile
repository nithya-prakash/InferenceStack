# docker build -t is-dashboard analytics/dashboard
FROM python:3.12-slim
RUN pip install --no-cache-dir streamlit==1.65.0 psycopg2-binary==2.9.13 pandas==3.0.6
WORKDIR /app
COPY app.py test_dashboard.py ./
ENV STREAMLIT_BROWSER_GATHER_USAGE_STATS=false
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=3 CMD python -c "import urllib.request as u; u.urlopen('http://localhost:8501/_stcore/health', timeout=2)"
EXPOSE 8501
CMD ["streamlit", "run", "app.py", "--server.port=8501", "--server.address=0.0.0.0", "--server.headless=true"]
