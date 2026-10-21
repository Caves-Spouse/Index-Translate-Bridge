#!/usr/bin/env bash
# 生成对外发布包：release/v<版本>/ 内含 APK + 使用说明 + SHA256SUMS
# 用法: bash tools/make_release.sh 1.0.0      # 版本号（默认 1.0.0）
#       bash tools/make_release.sh 1.1.0 --no-bump
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

VERSION_NAME="${1:-1.0.0}"
BUMP="${2:---bump}"

echo "▶ 发布构建 v$VERSION_NAME（$BUMP）"
bash build.sh --release --version-name "$VERSION_NAME" $BUMP

APK="dist/index-translate-bridge-$VERSION_NAME.apk"
[ -f "$APK" ] || { echo "构建产物缺失: $APK" >&2; exit 1; }

OUT="release/v$VERSION_NAME"
rm -rf "$OUT"
mkdir -p "$OUT"
cp "$APK" "$OUT/"
cp "release/使用说明.txt" "$OUT/"
(cd "$OUT" && sha256sum ./*.apk > SHA256SUMS.txt)

echo
echo "▶ 发布包内容 ($OUT)"
ls -lh "$OUT" | tail -n +2
echo
echo "SHA256: $(cut -d' ' -f1 "$OUT/SHA256SUMS.txt")"
echo
cat <<EOF
下一步（发布到 GitHub Releases）:
  1. git add -A && git commit -m "release v$VERSION_NAME"
  2. git tag v$VERSION_NAME && git push origin main --tags
  3. 打 tag 后 CI 会自动构建并创建 Release；
     也可手动: gh release create v$VERSION_NAME "$OUT"/*.apk "$OUT/使用说明.txt" --notes-file release/RELEASE_NOTES.md
EOF
