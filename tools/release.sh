#!/usr/bin/env bash
# 本地发布脚本：改版本号 → 跑单测 → 构建签名 APK → 校验产物 → 打 tag（可选直接发 Release）
#
# 用法：
#   ./tools/release.sh 1.0.1                         仅构建并产出 dist/ 下的安装包
#   ./tools/release.sh 1.0.1 --push                  构建 + 提交版本号 + 打 tag 并推送（由 CI 发布）
#   ./tools/release.sh 1.0.1 --upload                构建 + 用本地产物直接创建/更新 Release
#   ./tools/release.sh 1.0.1 --notes-file docs/releases/v1.0.1.md --push
#
# 选项：
#   --notes "文本"      直接给发布说明
#   --notes-file 文件   用文件内容作发布说明
#   --push              提交 version.properties、打 tag 并推送（推荐，产物由 CI 统一构建）
#   --upload            用本地构建的 APK 直接调 gh 创建 Release（需 gh 已登录）
#   --skip-test         跳过单元测试（不推荐）
#   --allow-dirty       允许工作区存在未提交改动
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

VERSION=""
NOTES=""
NOTES_FILE=""
DO_PUSH=0
DO_UPLOAD=0
RUN_TEST=1
ALLOW_DIRTY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --notes) NOTES="${2:-}"; shift 2 ;;
        --notes-file) NOTES_FILE="${2:-}"; shift 2 ;;
        --push) DO_PUSH=1; shift ;;
        --upload) DO_UPLOAD=1; shift ;;
        --skip-test) RUN_TEST=0; shift ;;
        --allow-dirty) ALLOW_DIRTY=1; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        -*) echo "未知参数：$1" >&2; exit 2 ;;
        *) VERSION="$1"; shift ;;
    esac
done

# ---------------------------------------------------------------- 0. 环境准备
if [ -z "${JAVA_HOME:-}" ] && [ -d "E:/AndroidTools/jdk17" ]; then
    export JAVA_HOME="E:/AndroidTools/jdk17"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "E:/AndroidTools/sdk" ]; then
    export ANDROID_HOME="E:/AndroidTools/sdk"
    export ANDROID_SDK_ROOT="E:/AndroidTools/sdk"
fi
[ -n "${JAVA_HOME:-}" ] || { echo "未找到 JAVA_HOME，请先安装 JDK 17 并设置环境变量" >&2; exit 1; }
[ -n "${ANDROID_HOME:-}" ] || { echo "未找到 ANDROID_HOME，请先安装 Android SDK（需 android-34）" >&2; exit 1; }

GRADLE_BIN="${GRADLE_BIN:-}"
if [ -z "$GRADLE_BIN" ]; then
    if [ -x "./gradlew" ]; then
        GRADLE_BIN="./gradlew"
    elif [ -x "E:/AndroidTools/gradle-8.7/bin/gradle" ]; then
        GRADLE_BIN="E:/AndroidTools/gradle-8.7/bin/gradle"
    else
        GRADLE_BIN="gradle"
    fi
fi
echo "Gradle：$GRADLE_BIN"

# ---------------------------------------------------------------- 1. 版本号
CUR_NAME=$(grep -E '^versionName=' version.properties | cut -d= -f2 | tr -d ' \r')
CUR_CODE=$(grep -E '^versionCode=' version.properties | cut -d= -f2 | tr -d ' \r')
[ -n "$VERSION" ] || VERSION="$CUR_NAME"

if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+(\.[0-9]+){1,3}$'; then
    echo "版本号格式非法：'$VERSION'，应形如 1.0.1" >&2
    exit 1
fi

IFS='.' read -r V_MAJOR V_MINOR V_PATCH _ <<<"$(printf '%s' "$VERSION" | awk -F. '{printf "%s.%s.%s.%s", $1, ($2+0), ($3+0), ($4+0)}')"
NEW_CODE=$(( V_MAJOR * 10000 + V_MINOR * 100 + V_PATCH ))

if [ "$NEW_CODE" -le "$CUR_CODE" ] && [ "$VERSION" != "$CUR_NAME" ]; then
    echo "versionCode 必须递增：$VERSION 对应 $NEW_CODE，当前已是 $CUR_CODE" >&2
    echo "如需重发同版本，请显式指定更高的版本号。" >&2
    exit 1
fi

echo "版本：$VERSION（versionCode $NEW_CODE），原为 $CUR_NAME（$CUR_CODE）"

# ---------------------------------------------------------------- 2. 工作区检查
if [ "$ALLOW_DIRTY" -eq 0 ] && [ -n "$(git status --porcelain)" ]; then
    # 只允许 version.properties 的改动
    DIRTY=$(git status --porcelain | grep -v 'version.properties' || true)
    if [ -n "$DIRTY" ]; then
        echo "工作区有未提交改动，请先提交或用 --allow-dirty：" >&2
        echo "$DIRTY" >&2
        exit 1
    fi
fi

if [ "$DO_PUSH" -eq 1 ] && git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null; then
    echo "tag v$VERSION 已存在，请先删除或改用新版本号" >&2
    exit 1
fi

# ---------------------------------------------------------------- 3. 写入版本号
cat > version.properties <<EOF
# 版本号唯一来源：本地构建与 CI 发布都从这里取值
# versionName 为语义化版本（会显示给用户并用于更新比较）
# versionCode 必须单调递增，否则设备无法覆盖安装
versionName=$VERSION
versionCode=$NEW_CODE
EOF

# ---------------------------------------------------------------- 4. 测试与构建
if [ "$RUN_TEST" -eq 1 ]; then
    echo "== 单元测试 =="
    "$GRADLE_BIN" --no-daemon :app:testDebugUnitTest
else
    echo "== 跳过单元测试（--skip-test）=="
fi

echo "== 构建 Release APK =="
"$GRADLE_BIN" --no-daemon :app:assembleRelease \
    -PversionName="$VERSION" -PversionCode="$NEW_CODE"

# ---------------------------------------------------------------- 5. 产物校验
SRC=$(find app/build/outputs/apk/release -maxdepth 1 -name '*.apk' | head -n1)
[ -n "$SRC" ] || { echo "未找到构建产物" >&2; exit 1; }
if printf '%s' "$SRC" | grep -q 'unsigned'; then
    echo "警告：产物未签名（缺少 release.jks / keystore.properties），无法覆盖安装旧版本" >&2
fi

mkdir -p dist
OUT="dist/ParentalGuard-v$VERSION.apk"
cp "$SRC" "$OUT"

echo "== 校验签名 =="
APKSIGNER=$(ls "$ANDROID_HOME"/build-tools/*/lib/apksigner.jar 2>/dev/null | sort -r | head -n1)
if [ -n "$APKSIGNER" ]; then
    "$JAVA_HOME/bin/java" -jar "$APKSIGNER" verify --verbose "$OUT" | head -20
else
    echo "未找到 apksigner，跳过签名校验"
fi

echo "== 校验包内版本号 =="
AAPT2=$(ls "$ANDROID_HOME"/build-tools/*/aapt2* 2>/dev/null | grep -E 'aapt2(\.exe)?$' | sort -r | head -n1)
if [ -n "$AAPT2" ]; then
    BADGING=$("$AAPT2" dump badging "$OUT" | grep -E '^package:' | head -n1)
    echo "$BADGING"
    printf '%s' "$BADGING" | grep -q "versionName='$VERSION'" || {
        echo "包内 versionName 与目标版本不一致，构建参数可能未生效" >&2; exit 1; }
    printf '%s' "$BADGING" | grep -q "versionCode='$NEW_CODE'" || {
        echo "包内 versionCode 与目标版本不一致" >&2; exit 1; }
else
    echo "未找到 aapt2，跳过包内版本校验"
fi

sha256sum "$OUT" | tee "$OUT.sha256"
ls -lh "$OUT"

# ---------------------------------------------------------------- 6. 发布说明
if [ -z "$NOTES" ]; then
    if [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ]; then
        NOTES=$(cat "$NOTES_FILE")
    elif [ -f "docs/releases/v$VERSION.md" ]; then
        NOTES=$(cat "docs/releases/v$VERSION.md")
        NOTES_FILE="docs/releases/v$VERSION.md"
    else
        PREV=$(git tag --sort=-v:refname | grep -vx "v$VERSION" | head -n1 || true)
        if [ -n "$PREV" ]; then
            NOTES="**变更内容（自 $PREV）**"$'\n\n'"$(git log --no-merges --pretty=format:'- %s' "$PREV..HEAD")"
        else
            NOTES="**变更内容**"$'\n\n'"$(git log --no-merges --pretty=format:'- %s')"
        fi
    fi
fi
printf '%s\n' "$NOTES" > dist/release-notes.md

# ---------------------------------------------------------------- 7. 提交与发布
if [ "$DO_PUSH" -eq 1 ]; then
    echo "== 提交版本号并打 tag =="
    git add version.properties
    git commit -m "发布 v$VERSION"
    git tag -a "v$VERSION" -m "ParentalGuard v$VERSION"
    git push origin HEAD
    git push origin "v$VERSION"
    echo "已推送 tag v$VERSION，CI 将构建并发布 Release"
fi

if [ "$DO_UPLOAD" -eq 1 ]; then
    command -v gh >/dev/null || { echo "未找到 gh CLI，请先安装并 gh auth login" >&2; exit 1; }
    echo "== 用本地产物发布 Release v$VERSION =="
    if gh release view "v$VERSION" >/dev/null 2>&1; then
        gh release upload "v$VERSION" "$OUT" "$OUT.sha256" --clobber
        gh release edit "v$VERSION" --title "ParentalGuard v$VERSION" --notes-file dist/release-notes.md
    else
        gh release create "v$VERSION" "$OUT" "$OUT.sha256" \
            --title "ParentalGuard v$VERSION" \
            --notes-file dist/release-notes.md \
            --latest
    fi
    gh release view "v$VERSION" --json tagName,name,url,assets
fi

cat <<EOF

完成。产物与校验值：
  $OUT
  $OUT.sha256
  发布说明：dist/release-notes.md

后续可选操作：
  推送 tag 由 CI 发布：  git push origin v$VERSION
  本地直接发 Release：   ./tools/release.sh $VERSION --upload
  核对线上发布结果：     gh release view v$VERSION
EOF
