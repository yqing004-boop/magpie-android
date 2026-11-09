#!/bin/sh
# 一键发版：构建 → 验签 → 同步源码到开源 repo → commit/tag/push → 建 GitHub Release 传 APK。
#
# 用法：
#   scripts/release.sh <version_name> <version_code>
#   例：scripts/release.sh 1.0.27 27
#
# 依赖（都在容器里，一般不用动）：
#   ANDROID_JAR=/tmp/plat30/android-11/android.jar
#   ANDROID_SDK=/opt/android-sdk
#   开源 repo 默认在 ../magpie-android-repo（可用 REPO_DIR 覆盖）
#
# 说明：
#   - 不导出到手机 /sdcard/Download：容器里没有 /sdcard，那步由 App 内的 Root 工具做。
#   - GitHub 凭据走 ~/.aimini/git-credential-aimini（git credential fill 动态取，不落盘到脚本）。
#   - keystore / 内核二进制 / aapt2 不会进 repo（.gitignore 已排除）。
set -euo pipefail

VERSION_NAME="${1:-}"
VERSION_CODE="${2:-}"

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "用法：$0 <version_name> <version_code>" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export ANDROID_JAR="${ANDROID_JAR:-$HOME/.aimini/android-sdk/android-11/android.jar}"
export ANDROID_SDK="${ANDROID_SDK:-/opt/android-sdk}"

REPO_DIR="${REPO_DIR:-$ROOT/../magpie-android-repo}"
APK="$ROOT/dist/magpie-android-$VERSION_NAME.apk"
TAG="v$VERSION_NAME"

# 用哪个 build-tools 里的 apksigner/d8（挑第一个带 lib/apksigner.jar 的）
bt_dir() {
    for d in "$ANDROID_SDK"/build-tools/* "$ANDROID_SDK"/android-*; do
        [ -d "$d" ] || continue
        [ -f "$d/lib/apksigner.jar" ] && echo "$d" && return 0
    done
    return 1
}
BT="$(bt_dir || true)"

echo "==> 构建 $VERSION_NAME (code $VERSION_CODE)"
VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" sh scripts/build-apk-local.sh

echo "==> 验签"
if [ -n "$BT" ]; then
    java -jar "$BT/lib/apksigner.jar" verify "$APK"
else
    echo "警告：没找到 apksigner，跳过验签" >&2
fi

echo "==> SHA-256"
sha256sum "$APK"

echo "==> 同步源码到 $REPO_DIR"
[ -d "$REPO_DIR/.git" ] || { echo "找不到开源 repo：$REPO_DIR" >&2; exit 1; }
rm -rf "$REPO_DIR/app/src/main"
cp -r app/src/main "$REPO_DIR/app/src/main"
rm -rf "$REPO_DIR/app/src/main/assets/bin"
cp -f scripts/build-apk-local.sh scripts/build-magpie.sh scripts/sync-binary.sh \
    scripts/magpie-cli.sh scripts/magpie.config scripts/build-apk.sh \
    scripts/magpie.service scripts/status.sh "$REPO_DIR/scripts/" 2>/dev/null || true

cd "$REPO_DIR"

echo "==> commit + tag + push"
git add -A
if ! git diff --cached --quiet; then
    git commit -q -m "发布 $VERSION_NAME"
fi
if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "tag $TAG 已存在；如要重发请先 git tag -d $TAG && git push origin :refs/tags/$TAG" >&2
    exit 1
fi
git tag "$TAG"
git push -q origin main
git push -q origin "$TAG"

echo "==> 取 GitHub 凭据"
TOKEN="$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | sed -n 's/^password=//p')"
if [ -z "$TOKEN" ]; then
    echo "拿不到 GitHub 凭据（credential helper 没返回 token）" >&2
    exit 1
fi
OWNER="$(git remote get-url origin | sed -E 's#.*github.com[/:]([^/]+)/.*#\1#')"
REPO="$(git remote get-url origin | sed -E 's#.*github.com[/:][^/]+/([^/]+?)(\.git)?$#\1#')"
[ -n "$OWNER" ] && [ -n "$REPO" ] || { echo "解析 owner/repo 失败" >&2; exit 1; }

BODY="Magpie 的 Android 壳（非官方移植）。

本版：$VERSION_NAME

## 安装
下载本 Release 的 APK 直接安装。升级须用同一签名的 APK 覆盖安装。

## 说明
本项目基于 magpie（MIT）制作，仅供学习交流；内核版权归 magpie 原作者所有，如涉及侵权请联系删除。构建由 AI 辅助完成。"

echo "==> 创建 Release"
RELEASE_JSON="$(python3 -c '
import json,sys
print(json.dumps({"tag_name":sys.argv[1],"name":"Magpie for Android "+sys.argv[2],"body":sys.argv[3],"draft":False,"prerelease":False}))
' "$TAG" "$VERSION_NAME" "$BODY")"

RELEASE_ID="$(curl -sS -X POST \
    -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$OWNER/$REPO/releases" \
    -d "$RELEASE_JSON" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("id",""))')"

[ -n "$RELEASE_ID" ] || { echo "创建 Release 失败" >&2; exit 1; }
echo "    release id = $RELEASE_ID"

echo "==> 上传 APK（约 69MB，耐心等）"
curl -sS -X POST \
    -H "Authorization: token $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$APK" \
    "https://uploads.github.com/repos/$OWNER/$REPO/releases/$RELEASE_ID/assets?name=$(basename "$APK")" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print("    asset:", d.get("name"), d.get("size",""), "bytes"); print("    error:", d.get("message"))'

echo
echo "完成："
echo "  源码:   https://github.com/$OWNER/$REPO"
echo "  Release: https://github.com/$OWNER/$REPO/releases/tag/$TAG"
echo "  APK:    $APK"
echo "  SHA-256: $(sha256sum "$APK" | cut -d' ' -f1)"
echo
echo "下一步：在 App 内用 Root 工具把 APK 导出到 /sdcard/Download（容器里没有 /sdcard）。"
