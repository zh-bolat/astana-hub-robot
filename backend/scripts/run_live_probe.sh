#!/usr/bin/env bash
# Пробник живого режима сквозь сервер (scripts/live_probe.py) — внутри контейнера kurultai-api на сервере:
# ключи ElevenLabs и токен робота берутся из окружения контейнера, в вывод не попадают. Запуск из backend/.
#   ./scripts/run_live_probe.sh [ru|kk|all] [повторов]
set -euo pipefail
LANG_FILTER="${1:-all}"
REPEATS="${2:-1}"
ssh -i ~/.ssh/lab_key -o IdentitiesOnly=yes bolat@89.144.34.185 \
  "docker exec -i -e PYTHONUNBUFFERED=1 kurultai-robot-kurultai-api-1 python - '$LANG_FILTER' '$REPEATS' 2>&1 |
   grep -v '^{\"ts\"' | grep -v pipecat" < scripts/live_probe.py
