#!/usr/bin/env bash
# Обновление стека на сервере до тега. Вызывается из CI (deploy-backend.yml) по SSH
# или вручную на сервере:
#   bash infra/scripts/server-update.sh v0.1.0 [ghcr.io/owner/astana-hub-robot-api:v0.1.0]
# Без второго аргумента образ api собирается на сервере из backend/.
#
# Порядок: checkout тега → проверка infra/.env → бэкап Postgres (до миграций) → up -d →
# postgres healthy → api применяет миграции Alembic при старте (DB_AUTO_MIGRATE) → api healthy.
# PRE_UPDATE_BACKUP=0 — пропустить бэкап перед обновлением (не рекомендуется).
set -euo pipefail

TAG="${1:?usage: server-update.sh <tag> [api-image]}"
IMAGE="${2:-}"
APP_DIR="${APP_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
PRE_UPDATE_BACKUP="${PRE_UPDATE_BACKUP:-1}"
# api стартует только после healthy postgres, затем миграции + start_period healthcheck.
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-240}"

cd "$APP_DIR"
git fetch --tags --force origin
git checkout --force "refs/tags/$TAG"

cd infra
[[ -f .env ]] || { echo "Нет infra/.env — см. infra/README.md" >&2; exit 1; }

# Последнее значение KEY=... из .env (без source: в .env могут быть произвольные строки).
env_get() { sed -n "s/^$1=//p" .env | tail -n 1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"; }

pg_pass="$(env_get POSTGRES_PASSWORD)"
if [[ -z "$pg_pass" || "$pg_pass" == "hub-local-dev-only" ]]; then
  echo "POSTGRES_PASSWORD в infra/.env пустой или dev-значение из шаблона." >&2
  echo "Задайте: sed -i \"s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=\$(openssl rand -hex 24)/\" infra/.env" >&2
  echo "(до первого запуска postgres; после — сменить пароль в БД: ALTER USER ... PASSWORD)" >&2
  exit 1
fi
if [[ "$(env_get DATABASE_URL)" == sqlite* ]]; then
  echo "DATABASE_URL в infra/.env указывает на SQLite — рабочая БД теперь Postgres." >&2
  echo "Оставьте DATABASE_URL= пустым (compose соберёт URL из POSTGRES_*)." >&2
  exit 1
fi

# Бэкап до миграций: если postgres уже запущен (не первый деплой).
if [[ "$PRE_UPDATE_BACKUP" == "1" && -n "$(docker compose ps -q --status running postgres 2>/dev/null)" ]]; then
  echo "Бэкап Postgres перед обновлением..."
  bash "$APP_DIR/infra/scripts/backup.sh" || {
    echo "Бэкап не удался — обновление остановлено (PRE_UPDATE_BACKUP=0, чтобы пропустить)." >&2
    exit 1
  }
fi

if [[ -n "$IMAGE" ]]; then
  export API_IMAGE="$IMAGE"
  docker compose pull api postgres
  docker compose up -d --no-build --remove-orphans
else
  docker compose pull postgres
  docker compose up -d --build --remove-orphans
fi

# Ждём healthcheck api. Падение миграций = api завершается и перезапускается
# (restart: unless-stopped) — ловим по RestartCount и не ждём весь таймаут.
deadline=$((SECONDS + HEALTH_TIMEOUT_S))
while (( SECONDS < deadline )); do
  api_id="$(docker compose ps -q api)"
  if [[ -n "$api_id" ]]; then
    read -r status restarts < <(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.RestartCount}}' "$api_id" 2>/dev/null || echo "unknown 0")
    if [[ "$status" == "healthy" ]]; then
      echo "api healthy (миграции применены), версия $TAG"
      docker image prune -f >/dev/null
      exit 0
    fi
    if (( restarts > 0 )); then
      echo "api перезапускается (RestartCount=$restarts) — вероятно, ошибка миграции или старта." >&2
      break
    fi
  fi
  sleep 3
done
[[ "${status:-}" == "healthy" ]] || echo "api не стал healthy за ${HEALTH_TIMEOUT_S} с (статус: ${status:-unknown})" >&2
docker compose ps >&2
docker compose logs --tail 50 postgres >&2
docker compose logs --tail 100 api >&2
echo "Откат: bash infra/scripts/server-update.sh <предыдущий тег> [образ]; если миграция успела" >&2
echo "изменить схему — восстановить последний дамп (infra/README.md, «Восстановление»)." >&2
exit 1
