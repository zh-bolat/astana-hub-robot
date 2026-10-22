# Настройка сервера (VPS) — как сделано и как обслуживать

Сервер беты: **On1x Cloud (Нидерланды), `89.144.34.185`**, hostname `OrionStar.on1x.cloud`.
Ubuntu 24.04 LTS · 4 vCPU · 5.7 ГБ RAM · 63 ГБ диск · swap 2 ГБ. Настроен 2026-10-21.

> Это бета-сервер. Перед продом: переезд на надёжного хостера (Hetzner/Netcup), новые токены и пароли.

## Итоговое состояние
| Что | Настройка |
|---|---|
| Пользователи | `bolat`, `daniyal`, `aziza` — группы `sudo` и `docker`; временные пароли с обязательной сменой при первом входе |
| SSH | root — только по ключу; вход разрешён только `root bolat daniyal aziza`; 3 попытки, 30 с на вход |
| Firewall (ufw) | входящие: 22 (с ограничением частоты), 80, 443/tcp, 443/udp; всё остальное — запрещено |
| fail2ban | 5 ошибок за 10 мин → бан на 1 ч; 3 бана за сутки → бан на неделю |
| Обновления | автоматические обновления безопасности ежедневно, перезагрузка при необходимости в 04:30 |
| Время | Asia/Almaty, синхронизация NTP |
| Docker | Docker CE + Compose plugin из официального репозитория; логи 5×10 МБ на контейнер; `live-restore` |
| Снаружи видно | только порт 22 (80/443 откроются, когда запустится Caddy) |

## Шаги (что и зачем)

### 0. Разведка (только чтение)
Перед изменениями смотрим ОС, ресурсы, пользователей, настройки SSH, открытые порты, firewall:
```bash
cat /etc/os-release; nproc; free -h; df -h /; swapon --show
sshd -T | grep -E '^(permitrootlogin|passwordauthentication) '
ss -tlnp; ufw status
```
Было: вход под root по паролю открыт всему интернету, firewall выключен.

### 1. Обновление системы и утилиты
```bash
export DEBIAN_FRONTEND=noninteractive
apt-get update && apt-get -y upgrade
apt-get -y install ufw fail2ban unattended-upgrades ca-certificates curl gnupg git htop jq
cat /var/run/reboot-required.pkgs   # если файл есть — нужна перезагрузка
systemctl reboot
```
Зачем: образ хостера мог быть старым — закрываем известные уязвимости до того, как открыть сервис. Новое ядро работает только после перезагрузки.

### 2. Часовой пояс и swap
```bash
timedatectl set-timezone Asia/Almaty
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab          # swap после перезагрузки
echo 'vm.swappiness=10' > /etc/sysctl.d/99-swappiness.conf  # swap только в крайнем случае
```
Зачем: логи во времени Астаны; swap спасает от убийства процессов (база, сервер) при нехватке RAM.

### 3. Пользователи
```bash
adduser --disabled-password --gecos "Daniyal" daniyal
echo 'daniyal:<пароль>' | chpasswd
chage -d 0 daniyal             # обязать сменить пароль при первом входе
usermod -aG sudo daniyal       # админ-права через sudo (с паролем и журналом)
install -d -m 700 -o daniyal -g daniyal /home/daniyal/.ssh
```
- Имена строчные — так принято в Linux (`adduser` в Ubuntu не принимает заглавные).
- Каждый — отдельный пользователь: видно, кто что делал (`/var/log/auth.log`, `journalctl _COMM=sudo`), доступ отзывается у одного человека, не у всех.

### 4. SSH — `/etc/ssh/sshd_config.d/00-hardening.conf`
```
PermitRootLogin prohibit-password
PasswordAuthentication yes        # временно — пока у daniyal/aziza нет ключей
KbdInteractiveAuthentication no
PubkeyAuthentication yes
MaxAuthTries 3
LoginGraceTime 30
X11Forwarding no
AllowUsers root bolat daniyal aziza
```
Файл с префиксом `00` применяется раньше настроек хостера (`50-cloud-init.conf`). Применение безопасно:
```bash
sshd -t && systemctl reload ssh   # проверить конфиг, перечитать без разрыва текущих сессий
```
**Правило:** меняя SSH, всегда держите открытой вторую сессию и проверяйте вход новой — иначе можно запереть себя. Если заперлись — веб-консоль (VNC) в панели хостера.

### 5. Firewall (ufw)
```bash
ufw default deny incoming; ufw default allow outgoing
ufw limit 22/tcp                  # SSH, >6 подключений за 30 с с одного IP — блок
ufw allow 80/tcp; ufw allow 443/tcp; ufw allow 443/udp
ufw --force enable
```
⚠️ **Docker обходит ufw** для опубликованных портов (`ports:` в compose пишет свои правила iptables). Поэтому в `infra/docker-compose.yml` backend опубликован только на `127.0.0.1`, Postgres не опубликован вовсе, наружу — только Caddy (80/443). Никогда не публикуйте порты базы/сервера на `0.0.0.0`.

### 6. fail2ban и автообновления
`/etc/fail2ban/jail.d/astanahub.local` — jail `sshd` (5 ошибок/10 мин → 1 ч) и `recidive` (3 бана/сутки → неделя).
`/etc/apt/apt.conf.d/20auto-upgrades` + `52astanahub-unattended` — ежедневные обновления безопасности, перезагрузка в 04:30.
При включении fail2ban сразу нашёл в журнале **157 неудачных попыток входа** и забанил 4 IP — боты атакуют любой новый сервер в первые минуты.

### 7. Docker
```bash
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release; echo $VERSION_CODENAME) stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update && apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```
`/etc/docker/daemon.json`:
```json
{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "5" }, "live-restore": true }
```
- Официальный репозиторий: свежий Docker и `docker compose` v2 (на нём наш compose).
- Ротация логов: без неё логи контейнеров растут бесконечно и забивают диск.
- `live-restore`: при обновлении/перезапуске Docker контейнеры продолжают работать.
- Группа `docker` = права root (через Docker можно получить доступ ко всей системе) — только для админов.

### 8. Проверка снаружи
С другого компьютера: открыт только 22. Регулярно повторять после изменений.

## Обслуживание (шпаргалка)

**Первый вход и смена пароля**
```bash
ssh daniyal@89.144.34.185       # введёт временный пароль → сразу попросит новый
```

**Добавить SSH-ключ** (у себя: `ssh-keygen -t ed25519`; на сервер — только `.pub`)
```bash
ssh-copy-id -i ~/.ssh/id_ed25519.pub daniyal@89.144.34.185
# или админом: echo 'ssh-ed25519 AAAA… daniyal@laptop' >> /home/daniyal/.ssh/authorized_keys
```

**Добавить / удалить пользователя**
```bash
adduser --gecos "Имя" newuser && usermod -aG sudo,docker newuser
# + добавить имя в AllowUsers (00-hardening.conf) → sshd -t && systemctl reload ssh
deluser --remove-home newuser   # и убрать из AllowUsers
```

**fail2ban**
```bash
fail2ban-client status sshd                 # кто забанен
fail2ban-client set sshd unbanip 1.2.3.4    # разбанить (например, себя)
```

**Firewall**: `ufw status numbered`, `ufw delete <N>`.

**Состояние сервера**: `htop`, `free -h`, `df -h`, `docker ps`, `docker stats`, `journalctl -u docker -n 100`, `systemctl --failed`.

**Обновления вручную**: `apt update && apt upgrade`, после — проверить `/var/run/reboot-required`.

## Что осталось сделать
1. **Ключи для всех** → `PasswordAuthentication no`, затем `PermitRootLogin no` (после проверки sudo у bolat).
2. **Деплой проекта**: пользователь `deploy` для CI (+ в `AllowUsers`), клон репозитория в `/opt/astana-hub-robot` (deploy key), `infra/.env`, `docker compose up -d` — см. `infra/README.md` и `infra/scripts/deploy.sh`.
3. **Домен** с A-записью на `89.144.34.185` → Caddy сам получит HTTPS-сертификат.
4. **Бэкапы вне сервера** и мониторинг (`/v1/health`, диск, бэкапы) — волна 2.
