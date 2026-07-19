FROM python:3.12-slim AS dependencies

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock

FROM dependencies AS test
COPY requirements-dev.txt requirements-dev.lock ./
RUN pip install --no-cache-dir -r requirements-dev.lock
COPY app ./app
COPY tests ./tests
RUN python -m pytest -q --cov=app --cov-report=term-missing --cov-fail-under=74

FROM dependencies AS runtime
COPY app ./app

RUN mkdir -p /data
RUN useradd --create-home --uid 10001 appuser && chown -R appuser:appuser /app /data
USER appuser
EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
