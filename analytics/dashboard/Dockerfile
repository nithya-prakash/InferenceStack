# docker build -t is-dashboard analytics/dashboard
FROM python:3.12-slim
RUN pip install --no-cache-dir streamlit==1.65.0 psycopg2-binary==2.9.13 pandas==3.0.6
WORKDIR /app
COPY app.py test_dashboard.py ./
ENV STREAMLIT_BROWSER_GATHER_USAGE_STATS=false
EXPOSE 8501
CMD ["streamlit", "run", "app.py", "--server.port=8501", "--server.address=0.0.0.0", "--server.headless=true"]
