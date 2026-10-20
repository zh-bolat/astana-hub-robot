#!/usr/bin/env bash
# Бэкап Postgres (база знаний, лог вопросов) на сервере: pg_dump -Fc из контейнера postgres.
# cron (deploy-пользователь), ежедневно в 03:30, хранить 14 дней:
#   30 3 * * * /opt/astana-hub-robot/infra/scripts/backup.sh >> $HOME/hub-backup.log 2>&1
# Копии вне сервера (Hetzner Storage Box / S3) — отдельной синхронизацией BACKUP_DIR.
#
# Дамп пишется во временный файл, проверяется `pg_restore --list` (тем же pg_restore из
# образа pgvector) и только потом переименовывается в postgres-<время>.dump.
# Восстановление — infra/README.md, раздел «Восстановление».
set -euo pipefail
umask 077

INFRA_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/astana-hub-robot}"
KEEP_DAYS="${KEEP_DAYS:-14}"
ts="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"
cd "$INFRA_DIR"

if [[ -z "$(docker compose ps -q --status running postgres 2>/dev/null)" ]]; then
  echo "$(date -Is) postgres не запущен — бэкап невозможен" >&2
  exit 1
fi

final="$BACKUP_DIR/postgres-$ts.dump"
tmp="$(mktemp "$BACKUP_DIR/.postgres-$ts.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$tmp"

# Проверка: архив читается и содержит объекты (pg_restore из того же образа, через stdin).
toc="$(docker compose exec -T postgres pg_restore --list < "$tmp")"
entries="$(grep -cv '^;' <<<"$toc" || true)"
if (( entries == 0 )); then
  echo "$(date -Is) дамп пустой или повреждён: $tmp" >&2
  exit 1
fi

mv "$tmp" "$final"
trap - EXIT
echo "$(date -Is) postgres -> $final ($(du -h "$final" | cut -f1), объектов в TOC: $entries)"

# api-data-*.tar.gz — старые бэкапы SQLite (до перехода на Postgres), удаляются по тому же сроку.
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'postgres-*.dump' -o -name 'api-data-*.tar.gz' \) \
  -mtime +"$KEEP_DAYS" -delete
