#!/usr/bin/env bash
# Запускает служебный процесс на устройстве через adb; команды читаются из stdin.
# Пример: printf 'start vless://...\nselftest\n' | ./scripts/run-server.sh
cd "$(dirname "$0")/.."
source ./env.sh
exec adb shell '
apk=$(pm path app.routerspot | head -1 | cut -d: -f2)
abi=$(getprop ro.product.cpu.abi); [ "$abi" = arm64-v8a ] && abi=arm64
CLASSPATH=$apk exec app_process -Djava.library.path=${apk%/*}/lib/$abi /system/bin app.routerspot.server.ShellServer
'
