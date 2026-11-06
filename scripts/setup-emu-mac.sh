#!/usr/bin/env bash
# Ставит на macOS эмулятор и образ AOSP Android 13 (arm64, без Google-сервисов), создаёт AVD для emu-mac.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
source ./env-mac.sh

AVD=RouterSpot_API33
IMAGE="system-images;android-33;default;arm64-v8a"

sdkmanager --install emulator platform-tools "platforms;android-35" "$IMAGE"
if avdmanager list avd -c | grep -qx "$AVD"; then
    echo "AVD $AVD уже есть"
    exit 0
fi
echo no | avdmanager create avd -n "$AVD" -k "$IMAGE" -d pixel_5
printf 'hw.ramSize=2048\nhw.keyboard=yes\n' >> "$HOME/.android/avd/$AVD.avd/config.ini"
echo "AVD $AVD создан, запуск: ./emu-mac.sh"
