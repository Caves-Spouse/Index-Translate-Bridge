#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Index-Translate 本地代理桥 —— 纯 Termux / 纯 JDK APK 构建脚本
# 无需 Gradle、无需 Android SDK Manager、无需 Google Maven。
#
# 用法:
#   bash build.sh                      # 用默认(调试)密钥构建，versionCode 不变
#   bash build.sh --release            # 用发布密钥(tools/keystore.properties)构建
#   bash build.sh --release --bump     # 发布构建并把 versionCode +1 并写回
#   bash build.sh --version-name 1.1.0 --version-code 5
#   ANDROID_JAR=/path/android.jar bash build.sh
#   APP_DIR=./someapp bash build.sh     # 构建其它目录下的应用
#
# 依赖（Termux 包）: openjdk-17+ (javac/keytool)、aapt2、d8、apksigner、zip
# 平台桩: tools/sdk/platforms/android-34/android.jar
# ---------------------------------------------------------------------------
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP_DIR="${APP_DIR:-$PROJECT_DIR/android}"
ANDROID_JAR="${ANDROID_JAR:-$PROJECT_DIR/tools/sdk/platforms/android-34/android.jar}"
OUT_DIR="${OUT_DIR:-$PROJECT_DIR/dist}"
BUILD_DIR="${BUILD_DIR:-$PROJECT_DIR/build}"
APP_NAME="${APP_NAME:-index-translate-bridge}"
MIN_SDK="${MIN_SDK:-24}"
TARGET_SDK="${TARGET_SDK:-34}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
CODE_FILE="$PROJECT_DIR/.version-code"
KEYSTORE_PROPERTIES="$PROJECT_DIR/tools/keystore.properties"
JAVA_SOURCE_RELEASE="${JAVA_SOURCE_RELEASE:-8}"
MODE="debug"

# versionCode 默认读 .version-code，缺省为 1
VERSION_CODE="${VERSION_CODE:-$(cat "$CODE_FILE" 2>/dev/null || echo 1)}"

# ---------------------------------------------------------------- 参数
while [ $# -gt 0 ]; do
  case "$1" in
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --app-name)     APP_NAME="$2"; shift 2 ;;
    --min-sdk)      MIN_SDK="$2"; shift 2 ;;
    --target-sdk)   TARGET_SDK="$2"; shift 2 ;;
    --release)      MODE="release"; shift ;;
    --bump)
      VERSION_CODE=$(( $(cat "$CODE_FILE" 2>/dev/null || echo 0) + 1 ))
      echo "$VERSION_CODE" > "$CODE_FILE"
      echo "versionCode 已递增 -> $VERSION_CODE"
      shift ;;
    -h|--help)      sed -n '2,18p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- 签名配置
if [ "$MODE" = "release" ]; then
  if [ -f "$KEYSTORE_PROPERTIES" ]; then
    # shellcheck disable=SC1090
    KEYSTORE="$PROJECT_DIR/$(sed -n 's/^storeFile=//p' "$KEYSTORE_PROPERTIES" | head -1)"
    KS_PASS="$(sed -n 's/^storePassword=//p' "$KEYSTORE_PROPERTIES" | head -1)"
    KS_ALIAS="$(sed -n 's/^keyAlias=//p' "$KEYSTORE_PROPERTIES" | head -1)"
    KS_KEY_PASS="$(sed -n 's/^keyPassword=//p' "$KEYSTORE_PROPERTIES" | head -1)"
  else
    echo "  ✗ --release 需要 $KEYSTORE_PROPERTIES（含 storeFile/storePassword/keyAlias/keyPassword）" >&2
    exit 1
  fi
  : "${KS_KEY_PASS:=$KS_PASS}"
else
  KEYSTORE="${KEYSTORE:-$PROJECT_DIR/tools/bridge.keystore}"
  KS_ALIAS="${KS_ALIAS:-bridge}"
  KS_PASS="${KS_PASS:-android}"
  KS_KEY_PASS="$KS_PASS"
fi

# ---------------------------------------------------------------- 前置检查
fail=0
for tool in javac keytool aapt2 d8 apksigner zip; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "  ✗ 缺少 $tool（Termux: pkg install openjdk-17 aapt2 d8 apksigner zip）" >&2
    fail=1
  fi
done
[ -f "$ANDROID_JAR" ] || { echo "  ✗ 缺少 android.jar: $ANDROID_JAR" >&2; fail=1; }
[ -f "$APP_DIR/AndroidManifest.xml" ] || { echo "  ✗ 缺少 manifest: $APP_DIR/AndroidManifest.xml" >&2; fail=1; }
[ -d "$APP_DIR/src" ] || { echo "  ✗ 缺少源码目录: $APP_DIR/src" >&2; fail=1; }
[ "$fail" -eq 0 ] || { echo "构建终止：依赖不完整" >&2; exit 1; }

step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$1"; }
ok()   { printf '  \033[1;32m✓\033[0m %s\n' "$1"; }

START_TS=$(date +%s)
printf '构建模式: %s  |  versionName=%s versionCode=%s  |  minSdk=%s targetSdk=%s\n' \
  "$MODE" "$VERSION_NAME" "$VERSION_CODE" "$MIN_SDK" "$TARGET_SDK"

# 幂等：每次清空中间产物（保留 keystore 与 dist）
step "0/6 清理中间产物"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"/{gen,classes,dex} "$OUT_DIR"
ok "build 目录已重建"

# ---------------------------------------------------------------- 1 资源
step "1/6 aapt2 compile（资源 → res.zip）"
if [ -d "$APP_DIR/res" ]; then
  aapt2 compile --dir "$APP_DIR/res" -o "$BUILD_DIR/res.zip"
  ok "资源编译完成"
else
  ok "无 res 目录，跳过"
fi

# ---------------------------------------------------------------- 2 链接
step "2/6 aapt2 link（生成 APK 骨架 + R.java）"
LINK_ARGS=(-o "$BUILD_DIR/base.apk"
  -I "$ANDROID_JAR"
  --manifest "$APP_DIR/AndroidManifest.xml"
  --min-sdk-version "$MIN_SDK"
  --target-sdk-version "$TARGET_SDK"
  --version-code "$VERSION_CODE"
  --version-name "$VERSION_NAME"
  --java "$BUILD_DIR/gen"
  --auto-add-overlay)
[ -f "$BUILD_DIR/res.zip" ] && LINK_ARGS+=(-R "$BUILD_DIR/res.zip")
[ -d "$APP_DIR/assets" ] && LINK_ARGS+=(-A "$APP_DIR/assets")
aapt2 link "${LINK_ARGS[@]}"
ok "骨架 APK: $(du -h "$BUILD_DIR/base.apk" | cut -f1)"

# ---------------------------------------------------------------- 3 编译
step "3/6 javac（Java → .class，release $JAVA_SOURCE_RELEASE）"
JAVA_FILES=$(find "$APP_DIR/src" "$BUILD_DIR/gen" -name '*.java' 2>/dev/null | sort)
[ -n "$JAVA_FILES" ] || { echo "  ✗ 没有找到 Java 源文件" >&2; exit 1; }
# --release 锁死语言级别与 API 面，避免误用 Android 上不存在的 JDK 新 API
javac --release "$JAVA_SOURCE_RELEASE" \
      -classpath "$ANDROID_JAR" \
      -d "$BUILD_DIR/classes" \
      $JAVA_FILES 2>&1 | grep -v -e "source value 8 is obsolete" \
                                -e "target value 8 is obsolete" \
                                -e "To suppress warnings about obsolete options" || true
ok "$(find "$BUILD_DIR/classes" -name '*.class' | wc -l | tr -d ' ') 个 class"

# ---------------------------------------------------------------- 4 dex
step "4/6 d8（.class → classes.dex，min-api $MIN_SDK）"
d8 --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
   --output "$BUILD_DIR/dex" $(find "$BUILD_DIR/classes" -name '*.class')
ok "classes.dex: $(du -h "$BUILD_DIR/dex/classes.dex" | cut -f1)"

# ---------------------------------------------------------------- 5 打包
step "5/6 打包（dex 注入骨架 APK）"
cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unsigned.apk"
rm -f "$BUILD_DIR/unsigned.apk.idsig"
(cd "$BUILD_DIR" && zip -q -j unsigned.apk dex/classes.dex)
ok "unsigned.apk: $(du -h "$BUILD_DIR/unsigned.apk" | cut -f1)"

# ---------------------------------------------------------------- 6 签名
step "6/6 apksigner（签名 + 自检）"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -v -keystore "$KEYSTORE" -storetype PKCS12 \
    -storepass "$KS_PASS" -keypass "$KS_KEY_PASS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Index-Translate Bridge, O=Local Build, C=CN" >/dev/null 2>&1
  ok "已生成签名密钥: $KEYSTORE"
fi
APK_PATH="$OUT_DIR/$APP_NAME-$VERSION_NAME.apk"
rm -f "$APK_PATH" "$APK_PATH.idsig"
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_KEY_PASS" \
  --min-sdk-version "$MIN_SDK" --v1-signing-enabled true \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK_PATH" "$BUILD_DIR/unsigned.apk"
apksigner verify --print-certs "$APK_PATH" | head -3
ok "签名通过"

END_TS=$(date +%s)
step "构建完成（$((END_TS - START_TS))s）"
printf '  APK   : %s\n' "$APK_PATH"
printf '  大小  : %s\n' "$(du -h "$APK_PATH" | cut -f1)"
printf '  包名  : %s\n' "$(aapt2 dump packagename "$APK_PATH" 2>/dev/null || echo '?')"
printf '  版本  : %s (versionCode %s)\n' "$VERSION_NAME" "$VERSION_CODE"
printf '  SHA256: %s\n' "$(sha256sum "$APK_PATH" | cut -d' ' -f1)"
printf '\n安装: 拷到手机任意目录点击安装；或 adb install -r "%s"\n' "$APK_PATH"
