#!/usr/bin/env bash
# То же, что fetch-deps.sh, для системного bash 3.2 в macOS (в нём нет declare -A).
# Версии берутся из fetch-deps.sh. Запуск: ./scripts/fetch-deps-mac.sh
set -euo pipefail
cd "$(dirname "$0")/.."

XRAY_VERSION=$(sed -n 's/^XRAY_VERSION=//p' scripts/fetch-deps.sh)
HEV_VERSION=$(sed -n 's/^HEV_VERSION=//p' scripts/fetch-deps.sh)

# abi:имя в релизах Xray
for pair in arm64-v8a:arm64-v8a x86_64:amd64; do
    abi=${pair%:*}
    out=app/src/main/jniLibs/$abi/libxray.so
    [ -f "$out" ] && continue
    mkdir -p "$(dirname "$out")"
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/xray.zip" \
        "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/Xray-android-${pair#*:}.zip"
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
