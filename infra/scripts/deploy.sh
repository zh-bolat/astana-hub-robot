#!/usr/bin/env bash
# Первичная установка backend на чистый Ubuntu 22.04/24.04 VPS.
# Запускать на сервере от root (или через sudo):
#   curl -fsSL <raw-url>/infra/scripts/deploy.sh -o deploy.sh   # или scp
#   sudo REPO_URL=git@github.com:zh-bolat/astana-hub-robot.git DEPLOY_USER=deploy bash deploy.sh
#
# Что делает (идемпотентно):
#   1. Ставит Docker Engine + compose plugin, git, ufw, fail2ban, unattended-upgrades.
#   2. Создаёт пользователя DEPLOY_USER (в группе docker) для CI-деплоя по SSH.
#   3. Firewall: открыты только 22, 80, 443 (tcp) и 443/udp (HTTP/3).
#   4. Клонирует репозиторий в APP_DIR, создаёт infra/.env из шаблона (chmod 600).
# Сам стек не запускает: сначала заполните infra/.env, потом
#   cd $APP_DIR/infra && docker compose up -d
set -euo pipefail

REPO_URL="${REPO_URL:-git@github.com:zh-bolat/astana-hub-robot.git}"
APP_DIR="${APP_DIR:-/opt/astana-hub-robot}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"
BRANCH="${BRANCH:-main}"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

if [[ $EUID -ne 0 ]]; then
  echo "Запустите от root: sudo bash $0" >&2
  exit 1
fi
# shellcheck source=/dev/null
. /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
  echo "Скрипт рассчитан на Ubuntu (обнаружено: ${ID:-unknown})." >&2
  exit 1
fi

log "Пакеты"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl git ufw fail2ban unattended-upgrades

if ! command -v docker >/dev/null 2>&1; then
  log "Docker Engine (официальный репозиторий)"
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

log "Пользователь ${DEPLOY_USER}"
if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
  adduser --disabled-password --gecos "" "$DEPLOY_USER"
fi
usermod -aG docker "$DEPLOY_USER"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh"
touch "/home/$DEPLOY_USER/.ssh/authorized_keys"
chown "$DEPLOY_USER:$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh/authorized_keys"
chmod 600 "/home/$DEPLOY_USER/.ssh/authorized_keys"

log "Firewall: 22, 80, 443"
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable
# Порт api (8000) опубликован в compose только на 127.0.0.1, поэтому Docker не обходит ufw.

log "Репозиторий -> ${APP_DIR}"
if [[ ! -d "$APP_DIR/.git" ]]; then
  install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$APP_DIR"
  # Приватный репозиторий: у DEPLOY_USER должен быть deploy key (read-only) в GitHub.
  if ! sudo -u "$DEPLOY_USER" git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"; then
    cat >&2 <<MSG
Не удалось клонировать $REPO_URL.
Для приватного репозитория:
  sudo -u $DEPLOY_USER ssh-keygen -t ed25519 -N "" -f /home/$DEPLOY_USER/.ssh/id_ed25519
  cat /home/$DEPLOY_USER/.ssh/id_ed25519.pub   # -> GitHub: Settings -> Deploy keys (read-only)
  sudo -u $DEPLOY_USER ssh -o StrictHostKeyChecking=accept-new -T git@github.com
и запустите скрипт ещё раз.
MSG
    exit 1
  fi
else
  echo "Уже клонирован, пропускаю."
fi

ENV_FILE="$APP_DIR/infra/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  log "infra/.env из шаблона"
  install -m 600 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$APP_DIR/infra/.env.example" "$ENV_FILE"
  # Postgres (pgvector) поднимается всегда; профиль prod добавляет caddy.
  sed -i 's/^COMPOSE_PROFILES=.*/COMPOSE_PROFILES=prod/' "$ENV_FILE"
  # Заменяет dev-пароль из шаблона. DATABASE_URL пустой — compose соберёт его из POSTGRES_*.
  pg_pass="$(openssl rand -hex 24)"
  sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=${pg_pass}/" "$ENV_FILE"
  sed -i 's/^DATABASE_URL=.*/DATABASE_URL=/' "$ENV_FILE"
  sed -i 's/^DB_AUTO_MIGRATE=.*/DB_AUTO_MIGRATE=true/' "$ENV_FILE"
  token="$(openssl rand -hex 32)"
  sed -i "s/^ROBOT_TOKEN=.*/ROBOT_TOKEN=${token}/" "$ENV_FILE"
  echo "Сгенерирован ROBOT_TOKEN (тот же нужен в android-app/secrets.properties):"
  echo "  grep ROBOT_TOKEN $ENV_FILE"
fi

log "Готово. Дальше вручную:"
cat <<MSG
  1. Добавьте публичный ключ CI в /home/$DEPLOY_USER/.ssh/authorized_keys (см. infra/README.md).
  2. Заполните $ENV_FILE: DOMAIN, ACME_EMAIL, GEMINI_API_KEY, ...
  3. DNS: A-запись DOMAIN -> IP этого сервера.
  4. sudo -u $DEPLOY_USER bash -c 'cd $APP_DIR/infra && docker compose up -d --build'
     (api ждёт healthy postgres и применяет миграции Alembic при старте)
  5. curl -fsS https://<DOMAIN>/v1/health
  6. Бэкапы: cron с infra/scripts/backup.sh (см. infra/README.md)
MSG
