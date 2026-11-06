#!/usr/bin/env bash
# То же, что publish.sh, для macOS: собирает APK и публикует его релизом на git.home (Gitea).
#
#   ./scripts/publish-mac.sh            сборка + тег + релиз
#   ./scripts/publish-mac.sh --dry-run  только сборка, без тега и публикации
#
# Пока собирается debug-вариант (подписан отладочным ключом) и публикуется как пре-релиз
# с тегом v<versionName>-debug.<номер>. Подписанный release-вариант добавим позже.
#
# Нужен токен Gitea с правом write:repository (Настройки → Приложения → Создать токен):
# переменная GITEA_TOKEN или файл ~/.config/routerspot/gitea-token.
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://git.home/api/v1
REPO=coder_gosha/RouterSpot
DRY_RUN=false
[ "${1:-}" = "--dry-run" ] && DRY_RUN=true

die() { echo "ошибка: $*" >&2; exit 1; }

# --- Проверки до сборки ---
if ! $DRY_RUN; then
    TOKEN=${GITEA_TOKEN:-$(cat ~/.config/routerspot/gitea-token 2>/dev/null || true)}
    [ -n "$TOKEN" ] || die "нет токена: задайте GITEA_TOKEN или ~/.config/routerspot/gitea-token"
    [ -z "$(git status --porcelain)" ] || die "есть незакоммиченные изменения"
fi

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ -n "$version" ] || die "не найден versionName в app/build.gradle.kts"
commit=$(git rev-parse --short HEAD)
# Номер сборки — следующий после последнего опубликованного тега этой версии.
git fetch -q --tags origin || true
last=$(git tag -l "v$version-debug.*" | sed 's/.*debug\.//' | sort -n | tail -1)
tag="v$version-debug.$(( ${last:-0} + 1 ))"

published=$(git tag --points-at HEAD -l "v*-debug.*" | head -1)
if ! $DRY_RUN && [ -n "$published" ]; then
    die "этот коммит уже опубликован как $published"
fi

# --- Сборка ---
source ./env-mac.sh
./scripts/fetch-deps-mac.sh
./gradlew --console=plain assembleDebug

mkdir -p build/publish
apk=build/publish/RouterSpot-$tag.apk
cp app/build/outputs/apk/debug/app-debug.apk "$apk"
sha=$(shasum -a 256 "$apk" | cut -d' ' -f1)
echo "APK: $apk ($(du -h "$apk" | cut -f1), sha256 $sha)"

$DRY_RUN && { echo "dry-run: публикация пропущена"; exit 0; }

# --- Тег и релиз ---
prev=$(git describe --tags --abbrev=0 HEAD 2>/dev/null || true)
changes=$(git log --format='- %s' ${prev:+"$prev"..}HEAD)

git tag -a "$tag" -m "RouterSpot $version debug ($commit)"
git push -q origin "$tag"

body=$(python3 -c '
import json, sys
tag, version, commit, sha, changes = sys.argv[1:]
print(json.dumps({
    "tag_name": tag,
    "name": f"RouterSpot {version} debug ({commit})",
    "body": "Отладочная сборка, подписана debug-ключом.\n\n"
            f"SHA-256: `{sha}`\n\n## Изменения\n{changes}",
    "prerelease": True,
}))' "$tag" "$version" "$commit" "$sha" "$changes")

release=$(curl -fsS -X POST "$API/repos/$REPO/releases" \
    -H "Authorization: token $TOKEN" -H "Content-Type: application/json" -d "$body")
id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$release")

curl -fsS -o /dev/null -X POST "$API/repos/$REPO/releases/$id/assets?name=$(basename "$apk")" \
    -H "Authorization: token $TOKEN" -F "attachment=@$apk;type=application/vnd.android.package-archive"

echo "Опубликовано: http://git.home/$REPO/releases/tag/$tag"
