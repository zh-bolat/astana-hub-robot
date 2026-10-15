#!/usr/bin/env bash
# Обновление стека на сервере до тега. Вызывается из CI (deploy-backend.yml) по SSH
# или вручную на сервере:
#   bash infra/scripts/server-update.sh v0.1.0 [ghcr.io/owner/astana-hub-robot-api:v0.1.0]
# Без второго аргумента образ api собирается на сервере из backend/.
set -euo pipefail

TAG="${1:?usage: server-update.sh <tag> [api-image]}"
IMAGE="${2:-}"
APP_DIR="${APP_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

cd "$APP_DIR"
git fetch --tags --force origin
git checkout --force "refs/tags/$TAG"

cd infra
[[ -f .env ]] || { echo "Нет infra/.env — см. infra/README.md" >&2; exit 1; }

if [[ -n "$IMAGE" ]]; then
  export API_IMAGE="$IMAGE"
  docker compose pull api
  docker compose up -d --no-build --remove-orphans
else
  docker compose up -d --build --remove-orphans
fi

# Ждём healthcheck api (до ~90 с).
for _ in $(seq 1 30); do
  status="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose ps -q api)" 2>/dev/null || echo unknown)"
  if [[ "$status" == "healthy" ]]; then
    echo "api healthy, версия $TAG"
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 3
done
echo "api не стал healthy за 90 с" >&2
docker compose logs --tail 100 api >&2
exit 1
