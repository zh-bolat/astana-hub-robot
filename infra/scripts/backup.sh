#!/usr/bin/env bash
# Бэкап базы знаний на сервере (SQLite из volume api-data и/или Postgres).
# cron (deploy-пользователь), ежедневно в 03:30, хранить 14 дней:
#   30 3 * * * /opt/astana-hub-robot/infra/scripts/backup.sh >> /var/log/hub-backup.log 2>&1
# Копии вне сервера (Hetzner Storage Box / S3) — отдельной синхронизацией BACKUP_DIR.
set -euo pipefail

INFRA_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/astana-hub-robot}"
KEEP_DAYS="${KEEP_DAYS:-14}"
ts="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"
cd "$INFRA_DIR"

api_id="$(docker compose ps -q api || true)"
if [[ -n "$api_id" ]]; then
  # Консистентная копия SQLite через backup API Python (sqlite3 CLI в образе может не быть).
  docker compose exec -T api python -c "
import glob, sqlite3
for p in glob.glob('/data/*.db'):
    src = sqlite3.connect(p); dst = sqlite3.connect(p + '.bak'); src.backup(dst); dst.close(); src.close()
" && docker cp "$api_id":/data - | gzip > "$BACKUP_DIR/api-data-$ts.tar.gz"
  docker compose exec -T api sh -c 'rm -f /data/*.db.bak'
  echo "api-data -> $BACKUP_DIR/api-data-$ts.tar.gz"
fi

pg_id="$(docker compose ps -q postgres 2>/dev/null || true)"
if [[ -n "$pg_id" ]]; then
  docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
    > "$BACKUP_DIR/postgres-$ts.dump"
  echo "postgres -> $BACKUP_DIR/postgres-$ts.dump"
fi

find "$BACKUP_DIR" -type f -mtime +"$KEEP_DAYS" -delete
