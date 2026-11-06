#!/usr/bin/env bash
# Сборка debug-APK на macOS. Результат: app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
cd "$(dirname "$0")/.."
source ./env-mac.sh
./scripts/fetch-deps-mac.sh
exec ./gradlew --console=plain assembleDebug "$@"
