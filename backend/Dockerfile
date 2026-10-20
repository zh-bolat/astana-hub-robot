FROM python:3.12-slim-bookworm AS build
COPY --from=ghcr.io/astral-sh/uv:0.11.16 /uv /bin/uv

ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never UV_NO_CACHE=1
WORKDIR /app

# Сначала зависимости (кешируемый слой), затем код
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY app ./app
COPY knowledge ./knowledge
COPY migrations ./migrations
COPY alembic.ini ./
RUN uv sync --frozen --no-dev


FROM python:3.12-slim-bookworm

RUN groupadd --system app && useradd --system --gid app --home-dir /app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /app /app
# Изменяемые данные api — в /data (docker volume): снимок базы знаний, кеш TTS.
# База данных — Postgres (DATABASE_URL задаёт compose); миграции Alembic — при старте
# (DB_AUTO_MIGRATE=true по умолчанию; ошибка миграции → процесс завершается с ненулевым кодом).
# Каталог создаётся в образе с владельцем app — именованный volume наследует владельца.
RUN mkdir -p /data && chown app:app /data

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    KNOWLEDGE_SNAPSHOT_PATH=/data/knowledge_snapshot.json \
    TTS_CACHE_DIR=/data/tts_cache \
    KNOWLEDGE_DIR=/app/knowledge \
    TRUSTED_PROXIES=172.16.0.0/12,127.0.0.1

USER app
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/v1/health', timeout=2).status == 200 else 1)"

# X-Forwarded-* принимаются только от доверенных прокси (TRUSTED_PROXIES, по умолчанию сети docker
# и localhost) — тот же список приложение использует для IP в access-логе.
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers --forwarded-allow-ips \"$TRUSTED_PROXIES\""]
