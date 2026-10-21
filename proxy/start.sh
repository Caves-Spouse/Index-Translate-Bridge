#!/usr/bin/env bash
# Index-Translate 本地代理 —— 一键启动
# 用法:
#   bash start.sh              # 从 8080 开始自动找空闲端口
#   bash start.sh 9000         # 指定起始端口
#   PORT=9000 bash start.sh
#   MODEL=Index-Translate-9B bash start.sh
#
# Termux 里也可以: chmod +x start.sh && ./start.sh

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXY_JS="$SCRIPT_DIR/index-translate-proxy.js"

# 起始端口：命令行参数 > 环境变量 > 8080
if [ $# -ge 1 ] && [ -n "${1:-}" ]; then
  export PORT="$1"
fi
: "${PORT:=8080}"
export PORT

# 模型：默认 9B
: "${DEFAULT_MODEL:=Index-Translate-9B}"
export DEFAULT_MODEL

if ! command -v node >/dev/null 2>&1; then
  echo "[错误] 未找到 node。Termux 中请先执行: pkg install nodejs" >&2
  echo "       （APK 版不需要 node，本脚本仅用于 Termux/桌面环境）" >&2
  exit 1
fi

if [ ! -f "$PROXY_JS" ]; then
  echo "[错误] 缺少代理脚本: $PROXY_JS" >&2
  exit 1
fi

NODE_VER="$(node -v 2>/dev/null || echo unknown)"
case "$NODE_VER" in
  v1[6-9].*|v[2-9][0-9].*) ;;
  *) echo "[提示] 检测到 node $NODE_VER，建议 Node >= 16" >&2 ;;
esac

exec node "$PROXY_JS"
