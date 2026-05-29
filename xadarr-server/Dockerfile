FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server.py .
COPY web/ ./web/

ENV DATA_DIR=/data
ENV PORT=7979

VOLUME ["/data"]
EXPOSE 7979

CMD ["gunicorn", "--bind", "0.0.0.0:7979", "--workers", "2", "--threads", "4", "--timeout", "120", "server:app"]
