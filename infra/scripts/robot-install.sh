#!/usr/bin/env bash
# Сборка robotDebug и установка на робота поверх текущей версии (adb install -r).
#   infra/scripts/robot-install.sh 192.168.1.50              # собрать и поставить
#   infra/scripts/robot-install.sh 192.168.1.50 path/to.apk  # поставить готовый APK (например, из CI)
# Требуется подтверждение человека перед установкой на реальный робот (скрипт спрашивает).
set -euo pipefail

IP="${1:-${ROBOT_IP:-}}"
APK="${2:-}"
PORT="${ROBOT_ADB_PORT:-5555}"
[[ -n "$IP" ]] || { echo "usage: $0 <robot-ip> [apk]" >&2; exit 2; }
SERIAL="$IP:$PORT"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_DIR="$ROOT/android-app"

command -v adb >/dev/null || { echo "adb не найден: установите Android platform-tools" >&2; exit 1; }

if [[ -z "$APK" ]]; then
  echo "==> Сборка robotDebug"
  if [[ -x "$APP_DIR/gradlew" ]]; then
    (cd "$APP_DIR" && ./gradlew assembleRobotDebug)
  else
    (cd "$APP_DIR" && gradle assembleRobotDebug)
  fi
  # shellcheck disable=SC2012
  APK="$(ls -t "$APP_DIR"/app/build/outputs/apk/robot/debug/*.apk | head -n1)"
fi
[[ -f "$APK" ]] || { echo "APK не найден: $APK" >&2; exit 1; }

adb connect "$SERIAL" >/dev/null 2>&1 || true
adb -s "$SERIAL" get-state 2>/dev/null | grep -q device \
  || { echo "Робот $SERIAL недоступен — сначала infra/scripts/robot-connect.sh $IP" >&2; exit 1; }

echo "APK:   $APK"
echo "Робот: $SERIAL ($(adb -s "$SERIAL" shell getprop ro.serialno | tr -d '\r'))"
if [[ "${YES:-}" != "1" ]]; then
  read -r -p "Установить на робота? [y/N] " ans
  [[ "$ans" =~ ^[YyДд]$ ]] || { echo "Отменено."; exit 0; }
fi

echo "==> adb install -r"
adb -s "$SERIAL" install -r "$APK"

cat <<'MSG'

Установлено. ВАЖНО:
  - Запускайте приложение ТОЛЬКО через RobotOS Home на роботе — иначе шасси не выдаст
    авторизацию и RobotAPI (навигация, карта) не заработает. Не запускайте через
    `adb shell am start` и не из Android Studio Run.
  - НЕ используйте Apply Changes / Hot Reload в Android Studio — ломают RobotAPI
    (FAQ вендора). Обновлять только полной установкой этим скриптом.
  - Логи: adb -s <ip>:5555 logcat | grep -i astanahub
MSG
