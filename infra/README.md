# Инфраструктура Astana Hub Robot

Доставка: backend на VPS (Docker Compose + Caddy HTTPS), CI в GitHub Actions, APK на робота
через `adb` или облачную панель OrionStar. Стек и решения — `../docs/stack.md`, план — `../docs/roadmap.md`
(шаги 0.4, 1.8–1.10, 8.1, 8.3).

```
infra/
├── docker-compose.yml      api + caddy (профиль prod) + postgres (профиль postgres)
├── caddy/Caddyfile         HTTPS по $DOMAIN → api:8000
├── .env.example            шаблон infra/.env (сам .env — вне git)
└── scripts/
    ├── deploy.sh           первичная установка на чистый Ubuntu VPS
    ├── server-update.sh    обновление стека до тега (вызывает CI)
    ├── backup.sh           бэкап базы знаний (SQLite volume / pg_dump)
    ├── robot-connect.sh    adb connect + версии Android/AgentOS
    └── robot-install.sh    сборка robotDebug + adb install -r
.github/workflows/
├── backend.yml             uv, ruff, pytest, сборка образа (по изменениям backend/)
├── android.yml             JDK 17, emulatorDebug + robotDebug, APK — артефакты (по изменениям android-app/)
├── gitleaks.yml            поиск секретов на каждый push/PR
└── deploy-backend.yml      по тегу v*: образ в GHCR → SSH на VPS → pull/up
```

## 1. Где лежат секреты

| Секрет | Где хранится | Кто использует |
|---|---|---|
| `GEMINI_API_KEY`, `DEEPSEEK_API_KEY`, `SENTRY_DSN` | `infra/.env` на сервере (chmod 600) | backend |
| `ROBOT_TOKEN` | `infra/.env` на сервере **и** `android-app/secrets.properties` (одно значение) | робот → backend |
| `DOMAIN`, `ACME_EMAIL`, `POSTGRES_PASSWORD` | `infra/.env` на сервере | caddy, postgres |
| `AGENT_APP_ID`, `BACKEND_URL` | `android-app/secrets.properties` (локально) | сборка APK |
| `SSH_HOST`, `SSH_USER`, `SSH_KEY`, `SSH_KNOWN_HOSTS` | GitHub → Settings → Secrets → Actions (лучше в environment `production`) | deploy-backend |
| `AGENT_APP_ID`, `ROBOT_TOKEN`, `BACKEND_URL` | GitHub Secrets | android.yml генерирует `secrets.properties` |
| Пароль ADB робота | не хранится: динамический, по SN | человек у робота |

Ничего из этого не коммитится: `.env`, `secrets.properties`, `local.properties`, `*.jks`, `*.db`
в `.gitignore`, а `gitleaks` в CI валит сборку при утечке. Ключи LLM никогда не попадают в APK.

## 2. Локальный запуск backend

```bash
cp infra/.env.example infra/.env     # COMPOSE_PROFILES оставить пустым
cd infra
docker compose up --build            # только api, http://127.0.0.1:8000/v1/health
docker compose --profile postgres up # + postgres 16 (когда backend перейдёт с SQLite)
```
С эмулятора Android backend доступен как `http://10.0.2.2:8000/v1`.

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
   клонирует репозиторий в `/opt/astana-hub-robot` и создаёт `infra/.env` с `COMPOSE_PROFILES=prod`
   и сгенерированным `ROBOT_TOKEN`. Репозиторий приватный → скрипт подскажет, как добавить
   deploy key (GitHub → Settings → Deploy keys, read-only), затем запустить повторно.
4. **Заполнить `/opt/astana-hub-robot/infra/.env`**: `DOMAIN`, `ACME_EMAIL`, `GEMINI_API_KEY`, …
5. **Первый запуск**:
   ```bash
   sudo -u deploy bash -c 'cd /opt/astana-hub-robot/infra && docker compose up -d --build'
   curl -fsS https://robot-api.<домен>/v1/health    # {"status":"ok",...}
   ```
   Сертификат Let's Encrypt Caddy получает сам при первом запросе (нужны DNS и открытый 80/443).
6. **Бэкапы**: `crontab -e` под `deploy`:
   `30 3 * * * /opt/astana-hub-robot/infra/scripts/backup.sh >> $HOME/hub-backup.log 2>&1`
   (по умолчанию в `/var/backups/astana-hub-robot`, создать каталог с владельцем `deploy`;
   копию вне сервера — Hetzner Storage Box / S3). Также включить Hetzner Backups (снимки VPS).

## 4. CI/CD (GitHub Actions)

- `backend.yml`, `android.yml` запускаются только при изменениях в своих папках; `gitleaks.yml` — всегда.
- APK обоих flavors — во вкладке Actions → прогон → Artifacts (`apk-robot-debug-<sha>`), хранятся 14 дней.
- **Деплой**: `git tag v0.1.0 && git push origin v0.1.0` → образ `ghcr.io/zh-bolat/astana-hub-robot-api:v0.1.0`
  → SSH на сервер → `infra/scripts/server-update.sh` (checkout тега, `docker compose pull`, `up -d`,
  ожидание healthcheck). Повторный деплой/откат: Actions → deploy-backend → Run workflow → тег.
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
