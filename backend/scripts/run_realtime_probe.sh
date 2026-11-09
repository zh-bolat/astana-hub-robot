#!/usr/bin/env bash
# Пробник Realtime с инструкциями движка: склеивает app/kurultai/realtime/instructions.py и пробник и запускает
# в контейнере kurultai-api на сервере (ключ — из .env сервера, в вывод не попадает). Запуск из backend/.
set -euo pipefail
{ cat app/kurultai/realtime/instructions.py; grep -v '^from __future__ import annotations$' scripts/realtime_probe.py; } |
  ssh -i ~/.ssh/lab_key -o IdentitiesOnly=yes bolat@89.144.34.185 \
    'k=$(sed -n "s/^KURULTAI_OPENAI_API_KEY=//p" ~/kurultai-robot/infra/.env);
     docker exec -i -e KURULTAI_OPENAI_API_KEY="$k" -e PYTHONUNBUFFERED=1 kurultai-robot-kurultai-api-1 python - 2>&1 |
     grep -v "^{\"ts\"" | grep -v pipecat'
