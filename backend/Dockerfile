FROM python:3.13-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8080

WORKDIR /app

COPY pyproject.toml README.md ./
COPY src ./src

RUN pip install --no-cache-dir . \
    && useradd --create-home --uid 10001 tvde

USER 10001

EXPOSE 8080

CMD ["sh", "-c", "exec uvicorn tvde_contract.cloud_main:app --host 0.0.0.0 --port ${PORT} --timeout-keep-alive 5 --workers 1"]
