#!/usr/bin/env bash
# Запуск эмулятора AOSP Android 13 без Google-сервисов. ./emu.sh [--headless] [доп. флаги emulator, напр. -wipe-data]
source "$(dirname "$0")/env.sh"
args=(-avd RouterSpot_API33 -no-audio -no-boot-anim -no-snapshot)
if [ "$1" = --headless ]; then
    args+=(-no-window -gpu swiftshader_indirect)
    shift
fi
exec emulator "${args[@]}" "$@"
