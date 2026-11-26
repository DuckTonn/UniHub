FROM python:3.11-slim
LABEL org.opencontainers.image.source="https://github.com/DuckTonn/UniHub"

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

USER 10001:10001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
