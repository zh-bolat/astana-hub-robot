#!/usr/bin/env bash
# Подключение к роботу GreetingBot Nova по Wi-Fi ADB и проверка версий.
#   infra/scripts/robot-connect.sh 192.168.1.50        # или ROBOT_IP=... в окружении
# Предварительно на роботе вручную: динамический пароль -> Enable debugging +
# Persistent debugging -> перезагрузка (см. infra/README.md, раздел «Робот»).
# Скрипт только читает информацию, настройки робота не меняет.
set -euo pipefail

IP="${1:-${ROBOT_IP:-}}"
PORT="${ROBOT_ADB_PORT:-5555}"
[[ -n "$IP" ]] || { echo "usage: $0 <robot-ip>  (или ROBOT_IP=...)" >&2; exit 2; }
command -v adb >/dev/null || { echo "adb не найден: установите Android platform-tools" >&2; exit 1; }

SERIAL="$IP:$PORT"
echo "==> adb connect $SERIAL"
out="$(adb connect "$SERIAL" 2>&1 || true)"
echo "$out"
if ! adb -s "$SERIAL" get-state 2>/dev/null | grep -q device; then
  cat >&2 <<MSG
Робот не подключён. Проверьте:
  - ноутбук и робот в одной сети, ping $IP;
  - на роботе включены Enable debugging и Persistent debugging, робот перезагружен
    (Enable debugging без Persistent сбрасывается при перезагрузке);
  - если "unauthorized" — подтвердите ключ RSA на экране робота;
  - альтернативно — кабель micro-USB в разъём на голове робота.
MSG
  exit 1
fi

rsh() { adb -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }

echo
echo "==> Робот $SERIAL"
printf '  Модель:            %s\n' "$(rsh getprop ro.product.model)"
printf '  SN:                %s\n' "$(rsh getprop ro.serialno)"
printf '  Android:           %s (SDK %s)\n' "$(rsh getprop ro.build.version.release)" "$(rsh getprop ro.build.version.sdk)"
printf '  Сборка ROM:        %s\n' "$(rsh getprop ro.build.display.id)"

echo
echo "==> Пакеты OrionStar (AgentOS / RobotOS) и их версии (нужна AgentOS V12.6, RobotAPI 12.2.1):"
pkgs="$(rsh pm list packages | sed 's/^package://' | grep -iE 'orionstar|ainirobot|agentos' || true)"
if [[ -z "$pkgs" ]]; then
  echo "  не найдены"
else
  while read -r p; do
    v="$(rsh dumpsys package "$p" | grep -m1 versionName | sed 's/.*versionName=//')"
    printf '  %-50s %s\n' "$p" "${v:-?}"
  done <<< "$pkgs"
fi

echo
echo "==> Наше приложение (kz.astanahub.robot):"
v="$(rsh dumpsys package kz.astanahub.robot | grep -m1 versionName | sed 's/.*versionName=//')"
echo "  ${v:-не установлено}"

echo
echo "Готово. Для команд: export ANDROID_SERIAL=$SERIAL"
echo "Логи приложения:   adb -s $SERIAL logcat --pid=\$(adb -s $SERIAL shell pidof kz.astanahub.robot)"
