# Инфраструктура Astana Hub Robot

Доставка: backend на VPS (Docker Compose + Caddy HTTPS), CI в GitHub Actions, APK на робота
через `adb` или облачную панель OrionStar. Стек и решения — `../docs/stack.md`, план — `../docs/roadmap.md`
(шаги 0.4, 1.8–1.10, 8.1, 8.3).

```
infra/
├── docker-compose.yml      api + postgres 16/pgvector (всегда) + caddy (профиль prod)
├── docker-compose.dev.yml  локально: БД на 127.0.0.1:5432, api с hot reload
├── caddy/Caddyfile         HTTPS по $DOMAIN → api:8000
├── .env.example            шаблон infra/.env (сам .env — вне git)
└── scripts/
    ├── deploy.sh           первичная установка на чистый Ubuntu VPS
    ├── server-update.sh    обновление стека до тега (вызывает CI)
    ├── backup.sh           бэкап Postgres (pg_dump -Fc + проверка pg_restore --list)
    ├── robot-connect.sh    adb connect + версии Android/AgentOS
    └── robot-install.sh    сборка robotDebug + adb install -r
.github/workflows/
├── backend.yml             uv, ruff, pytest (+ service pgvector), сборка образа (по изменениям backend/)
├── android.yml             JDK 17, emulatorDebug + robotDebug, APK — артефакты (по изменениям android-app/)
├── gitleaks.yml            поиск секретов на каждый push/PR
└── deploy-backend.yml      по тегу v*: образ в GHCR → SSH на VPS → pull/up
```

## 1. Где лежат секреты

| Секрет | Где хранится | Кто использует |
|---|---|---|
| `GEMINI_API_KEY`, `DEEPSEEK_API_KEY`, `SENTRY_DSN` | `infra/.env` на сервере (chmod 600) | backend |
| `ROBOT_TOKEN` | `infra/.env` на сервере **и** `android-app/secrets.properties` (одно значение) | робот → backend |
| `DOMAIN`, `ACME_EMAIL`, `POSTGRES_PASSWORD` | `infra/.env` на сервере (пароль генерирует `deploy.sh`) | caddy, postgres, api |
| `AGENT_APP_ID`, `BACKEND_URL` | `android-app/secrets.properties` (локально) | сборка APK |
| `SSH_HOST`, `SSH_USER`, `SSH_KEY`, `SSH_KNOWN_HOSTS` | GitHub → Settings → Secrets → Actions (лучше в environment `production`) | deploy-backend |
| `AGENT_APP_ID`, `ROBOT_TOKEN`, `BACKEND_URL` | GitHub Secrets | android.yml генерирует `secrets.properties` |
| Пароль ADB робота | не хранится: динамический, по SN | человек у робота |

Ничего из этого не коммитится: `.env`, `secrets.properties`, `local.properties`, `*.jks`, `*.db`
в `.gitignore`, а `gitleaks` в CI валит сборку при утечке. Ключи LLM никогда не попадают в APK.

## 2. Локальный запуск backend

Рабочая БД — **PostgreSQL 16 + pgvector** (`docs/db-schema.md`), локально и на сервере одинаково.
SQLite — только для тестов backend без Docker.

```bash
cp infra/.env.example infra/.env     # COMPOSE_PROFILES пустой; POSTGRES_PASSWORD=hub-local-dev-only — только локально
cd infra
alias dc='docker compose -f docker-compose.yml -f docker-compose.dev.yml'
```

**Поднять БД локально** (порт только на `127.0.0.1:5432`; занят — задайте `POSTGRES_PORT` в `.env`):
```bash
dc up -d --wait postgres
```

**Весь стек** (api на http://127.0.0.1:8000/v1/health, перезапуск при правках `backend/app`):
```bash
dc up --build            # api ждёт healthy postgres и сам применяет миграции (DB_AUTO_MIGRATE=true)
```
Без dev-файла (`docker compose up --build`) — то же, но без порта БД и без hot reload.
С эмулятора Android backend доступен как `http://10.0.2.2:8000/v1`.

**Подключиться psql**:
```bash
dc exec postgres psql -U hub -d hub                                    # из контейнера
psql postgresql://hub:hub-local-dev-only@127.0.0.1:5432/hub            # с ноутбука / DBeaver
```

**Миграции и сид из YAML** — внутри контейнера api или с ноутбука через `uv`:
```bash
dc run --rm api python -m app.cli db upgrade      # миграции Alembic
dc run --rm api python -m app.cli kb seed-yaml    # база знаний из backend/knowledge/*.yaml

# backend вне Docker (БД из compose):
cd ../backend
export DATABASE_URL=postgresql+psycopg://hub:hub-local-dev-only@127.0.0.1:5432/hub
uv run python -m app.cli db upgrade && uv run python -m app.cli kb seed-yaml
uv run uvicorn app.main:app --reload
```

**Тесты backend с Postgres** (как в CI):
```bash
dc exec postgres createdb -U hub hub_test
TEST_POSTGRES_URL=postgresql+psycopg://hub:hub-local-dev-only@127.0.0.1:5432/hub_test uv run pytest -q
```

**Сбросить локальную БД**: `dc down -v` (удаляет тома `pg-data`, `api-data`).

## 3. Сервер на VPS (Hetzner)

1. **Аренда** (делает человек): Hetzner Cloud → CX22/CPX21 (2 vCPU / 4 GB), Ubuntu 24.04, локация
   Falkenstein/Helsinki, при создании добавить свой SSH-ключ. Запомнить IPv4.
2. **Домен**: у регистратора создать A-запись, напр. `robot-api.<домен>` → IPv4 сервера
   (и AAAA → IPv6, если нужен). Дождаться `dig +short robot-api.<домен>`.
3. **Установка** (на сервере от root):
   ```bash
   scp infra/scripts/deploy.sh root@<IP>:/root/
   ssh root@<IP> 'REPO_URL=git@github.com:zh-bolat/astana-hub-robot.git bash /root/deploy.sh'
   ```
   Скрипт ставит Docker, ufw (открыты только 22/80/443), fail2ban, создаёт пользователя `deploy`,
   клонирует репозиторий в `/opt/astana-hub-robot` и создаёт `infra/.env` с `COMPOSE_PROFILES=prod`,
   сгенерированными `ROBOT_TOKEN` и `POSTGRES_PASSWORD` (dev-пароль из шаблона заменяется). Репозиторий приватный → скрипт подскажет, как добавить
   deploy key (GitHub → Settings → Deploy keys, read-only), затем запустить повторно.
4. **Заполнить `/opt/astana-hub-robot/infra/.env`**: `DOMAIN`, `ACME_EMAIL`, `GEMINI_API_KEY`, …
5. **Первый запуск**:
   ```bash
   sudo -u deploy bash -c 'cd /opt/astana-hub-robot/infra && docker compose up -d --build'
   curl -fsS https://robot-api.<домен>/v1/health    # {"status":"ok",...}
   ```
   Сертификат Let's Encrypt Caddy получает сам при первом запросе (нужны DNS и открытый 80/443).
   api стартует после healthy postgres и применяет миграции Alembic. Начальная база знаний —
   синк из Google-таблицы (`KNOWLEDGE_SHEET_ID`); YAML-сид только для разработки.
6. **Бэкапы**: `crontab -e` под `deploy`:
   `30 3 * * * /opt/astana-hub-robot/infra/scripts/backup.sh >> $HOME/hub-backup.log 2>&1`
   (по умолчанию в `/var/backups/astana-hub-robot`, создать каталог с владельцем `deploy`:
   `sudo install -d -m 700 -o deploy -g deploy /var/backups/astana-hub-robot`;
   копию вне сервера — Hetzner Storage Box / S3). Также включить Hetzner Backups (снимки VPS).
   Дамп `postgres-<время>.dump` (`pg_dump -Fc`, права 600) пишется во временный файл и
   переименовывается только после проверки `pg_restore --list`. `server-update.sh` делает такой же
   бэкап перед каждым обновлением (до миграций).
7. **Восстановление** из дампа (стек остановлен, кроме postgres):
   ```bash
   cd /opt/astana-hub-robot/infra
   docker compose stop api
   docker compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --force "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
   docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner' \
     < /var/backups/astana-hub-robot/postgres-<время>.dump
   docker compose up -d api
   ```

## 4. CI/CD (GitHub Actions)

- `backend.yml`, `android.yml` запускаются только при изменениях в своих папках; `gitleaks.yml` — всегда.
- APK обоих flavors — во вкладке Actions → прогон → Artifacts (`apk-robot-debug-<sha>`), хранятся 14 дней.
- **Деплой**: `git tag v0.1.0 && git push origin v0.1.0` → образ `ghcr.io/zh-bolat/astana-hub-robot-api:v0.1.0`
  → SSH на сервер → `infra/scripts/server-update.sh` (checkout тега, проверка `.env`, бэкап Postgres,
  `docker compose pull`, `up -d`, миграции при старте api, ожидание healthy api до 240 с; при
  перезапусках api — ошибка миграции — сразу падает с логами). Повторный деплой/откат: Actions → deploy-backend → Run workflow → тег.
  Вручную на сервере: `bash infra/scripts/server-update.sh v0.1.0` (соберёт образ на месте).

Настройка (один раз):
1. Ключ для CI: `ssh-keygen -t ed25519 -N "" -f ci_deploy -C github-ci`; `ci_deploy.pub` → на сервер
   в `/home/deploy/.ssh/authorized_keys`; приватный `ci_deploy` → секрет `SSH_KEY`; файлы удалить.
2. Секреты: `SSH_HOST` (IP/домен), `SSH_USER` (`deploy`), `SSH_KEY`,
   `SSH_KNOWN_HOSTS` (вывод `ssh-keyscan -H <IP>`; без него — доверие при первом подключении, warning).
3. Environment `production` (Settings → Environments) — можно включить required reviewers,
   чтобы деплой ждал подтверждения.
4. Для `android.yml`: секреты `AGENT_APP_ID`, `ROBOT_TOKEN`, `BACKEND_URL` (необязательны — без них
   соберётся APK с пустыми значениями).
5. Settings → Branches: защитить `main`, required checks `backend / test`, `android / build`, `gitleaks / scan`.

## 5. Робот: подключение по ADB (шаг 0.4)

Делает человек у робота. По умолчанию ADB на роботе выключен.
1. Получить **динамический пароль**: SN робота → техподдержка OrionStar
   (`global_business@orionstar.com`) или партнёрская страница https://wp.orionstar.com/public/dynpass/.
   Пароль зависит от даты/времени, которые показаны на экране ввода.
2. На роботе: **свайп одним пальцем сверху вниз → быстро много раз тапнуть по часам** →
   экран ввода пароля → ввести пароль.
3. Включить **«Enable debugging»** (启用调试) → появится **«Persistent debugging»** (持久调试) — включить тоже.
   Без Persistent режим отладки сбрасывается при перезагрузке. Выключение Enable debugging скрывает и
   выключает Persistent.
4. **Перезагрузить робота** — настройки применяются только после перезагрузки.
5. Ноутбук в той же сети, что и робот (или кабель micro-USB в разъём на голове):
   ```bash
   infra/scripts/robot-connect.sh <IP робота>
   ```
   Покажет модель, SN, версию Android (`ro.build.version.release`) и версии пакетов OrionStar
   (нужна AgentOS V12.6 / RobotAPI 12.2.1). IP — в настройках Wi-Fi робота.

## 6. Установка APK

```bash
infra/scripts/robot-install.sh <IP робота>                  # соберёт robotDebug и поставит (adb install -r)
infra/scripts/robot-install.sh <IP робота> app-robot-debug.apk   # готовый APK из артефакта CI
```
Скрипт спрашивает подтверждение перед установкой.

- **Запуск — только через RobotOS Home** на роботе. При запуске иначе (`adb shell am start`,
  Run из Android Studio) шасси не авторизует приложение и RobotAPI не работает.
- **Не использовать Apply Changes / Hot Reload** — ломает RobotAPI. Только полная установка.
- Логи: `adb -s <IP>:5555 logcat --pid=$(adb -s <IP>:5555 shell pidof kz.astanahub.robot)`.

## 7. Автозапуск (шаг 8.1)

1. В манифесте главной Activity (делает frontend) — второй intent-filter:
   ```xml
   <intent-filter>
       <action android:name="action.orionstar.default.app" />
       <category android:name="android.intent.category.DEFAULT" />
   </intent-filter>
   ```
2. На роботе (человек): **свайп тремя пальцами сверху вниз → Settings → Developer settings →
   программа автозапуска** (开机启动程序) → выбрать «Astana Hub Robot».
3. Перезагрузить робота, убедиться, что приложение стартует само.

## 8. Обновление APK в эксплуатации (шаг 8.3)

- **Один робот, есть доступ к сети**: `robot-install.sh` с APK из артефакта CI (≈ 2–3 мин).
- **Удалённо, без ADB**: облачная панель OrionStar — **ORM** (установка на один робот) или
  **EO** (пакетная установка на несколько). Загрузить APK → выбрать робота → установить.
  Доступ к панели выдаёт OrionStar (ждём вместе с appId).
- Перед обновлением поднять `versionCode` (иначе `install -r` откажет при downgrade).
  Для релизов нужен release-ключ подписи (`*.jks`, хранится вне git; в CI — base64 в GitHub Secrets) —
  пока используются debug-сборки.
