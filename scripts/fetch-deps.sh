#!/usr/bin/env bash
# Скачивает готовые сборки: Xray-core (исполняемый файл) и hev-socks5-tunnel (AAR).
# Результат не коммитится (см. .gitignore). Запуск: ./scripts/fetch-deps.sh
set -euo pipefail
cd "$(dirname "$0")/.."

XRAY_VERSION=v26.10.31
HEV_VERSION=2.19.3

declare -A XRAY_ABI=([arm64-v8a]=arm64-v8a [x86_64]=amd64)
for abi in "${!XRAY_ABI[@]}"; do
    out=app/src/main/jniLibs/$abi/libxray.so
    [ -f "$out" ] && continue
    mkdir -p "$(dirname "$out")"
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/xray.zip" \
        "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/Xray-android-${XRAY_ABI[$abi]}.zip"
    python3 -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); open(sys.argv[2],'wb').write(z.read('xray'))" "$tmp/xray.zip" "$out"
    rm -rf "$tmp"
    echo "xray $XRAY_VERSION -> $out"
done

hev=app/libs/hev-socks5-tunnel.aar
if [ ! -f "$hev" ]; then
    mkdir -p app/libs
    curl -fsSL -o "$hev" \
        "https://github.com/heiher/hev-socks5-tunnel/releases/download/$HEV_VERSION/hev-socks5-tunnel.aar"
    echo "hev-socks5-tunnel $HEV_VERSION -> $hev"
fi
