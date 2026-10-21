#!/usr/bin/env bash
# 开发期功能测试：在 JVM 上直接跑 APK 内的 ProxyServer（与 APK 内代码完全一致）
# 用法: bash tools/jvmtest/run_test.sh
set -u
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$PROJECT_DIR/android/src/com/indextranslate/bridge"
OUT="$PROJECT_DIR/build_jvm"
LOG="$HOME/tmp/jvmtest.log"
mkdir -p "$OUT" "$HOME/tmp"

pass=0; fail=0
check() { # check <名称> <期望> <实际>
  if [ "$2" = "$3" ]; then printf '  \033[1;32mPASS\033[0m %s (%s)\n' "$1" "$3"; pass=$((pass+1));
  else printf '  \033[1;31mFAIL\033[0m %s 期望=%s 实际=%s\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}

echo "▶ 编译 ProxyServer/ProxyState/ProxyMain（release 8，纯 JDK）"
javac --release 8 -d "$OUT" "$SRC/ProxyState.java" "$SRC/ProxyServer.java" "$PROJECT_DIR/tools/jvmtest/ProxyMain.java" 2>&1 \
  | grep -v -e "obsolete" -e "suppress warnings" || true

echo "▶ 启动实例1（起始端口 8090）"
java -cp "$OUT" com.indextranslate.bridge.ProxyMain 8090 > "$LOG" 2>&1 &
P1=$!
for _ in $(seq 1 60); do grep -q "^PORT=" "$LOG" 2>/dev/null && break; sleep 0.2; done
PORT1=$(sed -n 's/^PORT=//p' "$LOG" | head -1)
[ -n "$PORT1" ] || { echo "启动失败"; cat "$LOG"; exit 1; }
echo "  实例1 端口 = $PORT1"

echo "▶ A. GET /v1/models"
CODE=$(curl -sS -m 20 -o "$HOME/tmp/models.json" -w '%{http_code}' "http://127.0.0.1:$PORT1/v1/models")
check "models HTTP 200" "200" "$CODE"
grep -q "Index-Translate-9B" "$HOME/tmp/models.json" && check "models 含 9B" "yes" "yes" || check "models 含 9B" "yes" "no"

echo "▶ B. OPTIONS 预检"
HDR=$(curl -sS -m 20 -D - -o /dev/null -X OPTIONS "http://127.0.0.1:$PORT1/v1/chat/completions" \
  -H "Origin: chrome-extension://abc" -H "Access-Control-Request-Method: POST" | tr -d '\r')
check "预检状态 204" "yes" "$(echo "$HDR" | grep -q '204' && echo yes || echo no)"
check "ACAO=*" "yes" "$(echo "$HDR" | grep -qi 'Access-Control-Allow-Origin: \*' && echo yes || echo no)"

echo "▶ C. 非流式翻译（不传 model，验证默认注入 9B）"
RESP=$(curl -sS -m 90 -X POST "http://127.0.0.1:$PORT1/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"请将以下文本翻译为English，直接输出翻译结果，不要进行任何解释。\n\n技术赋能创作，让交流跨越语言的界限。"}]}')
check "返回 model=9B" "yes" "$(echo "$RESP" | grep -q '"model":"Index-Translate-9B"' && echo yes || echo no)"
check "无 <think> 残留" "yes" "$(echo "$RESP" | grep -q '<think' && echo no || echo yes)"
TRANSLATION=$(echo "$RESP" | sed -n 's/.*"content":"\([^"]*\)".*/\1/p' | head -1)
echo "  译文: $TRANSLATION"

echo "▶ D. SSE 流式（统计分块到达时间）"
START=$(date +%s%3N)
curl -sS -N -m 90 -X POST "http://127.0.0.1:$PORT1/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"stream":true,"messages":[{"role":"user","content":"请将以下文本翻译为English，直接输出翻译结果，不要进行任何解释。\n\n今天天气真好，我们一起去公园散步吧。"}]}' \
  > "$HOME/tmp/sse.txt" 2>&1
ELAPSED=$(( $(date +%s%3N) - START ))
DATA_LINES=$(grep -c '^data: ' "$HOME/tmp/sse.txt" || true)
check "收到多个 data 分块" "yes" "$([ "$DATA_LINES" -ge 3 ] && echo yes || echo no)"
check "含 [DONE]" "yes" "$(grep -q '\[DONE\]' "$HOME/tmp/sse.txt" && echo yes || echo no)"
echo "  分块数=$DATA_LINES 总耗时=${ELAPSED}ms"

echo "▶ E. 上游错误透传（假模型名）"
CODE=$(curl -sS -m 40 -o "$HOME/tmp/err.json" -w '%{http_code}' -X POST "http://127.0.0.1:$PORT1/v1/chat/completions" \
  -H 'Content-Type: application/json' -d '{"model":"Not-A-Model","messages":[{"role":"user","content":"hi"}]}')
check "错误状态原样透传 400" "400" "$CODE"

echo "▶ F. 端口占用自动换端口（再起一个实例，起始端口也 8090）"
java -cp "$OUT" com.indextranslate.bridge.ProxyMain 8090 > "$HOME/tmp/jvmtest2.log" 2>&1 &
P2=$!
for _ in $(seq 1 60); do grep -q "^PORT=" "$HOME/tmp/jvmtest2.log" 2>/dev/null && break; sleep 0.2; done
PORT2=$(sed -n 's/^PORT=//p' "$HOME/tmp/jvmtest2.log" | head -1)
check "第二个实例换端口" "yes" "$([ -n "$PORT2" ] && [ "$PORT2" != "$PORT1" ] && echo yes || echo no)"
CODE=$(curl -sS -m 20 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT2/v1/models")
check "换端口后仍可用" "200" "$CODE"
echo "  实例2 端口 = $PORT2"

echo "▶ G. 只绑 127.0.0.1（局域网地址应连不上）"
LAN_IP=$(ifconfig 2>/dev/null | sed -n 's/.*inet \(addr:\)\{0,1\}\([0-9.]*\).*/\2/p' | grep -v '^127\.' | head -1)
CODE_LO=$(curl -sS -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT1/v1/models" 2>/dev/null || echo refused)
check "回环地址可达" "200" "$CODE_LO"
if [ -n "$LAN_IP" ]; then
  CODE=$(curl -sS -m 4 -o /dev/null -w '%{http_code}' "http://$LAN_IP:$PORT1/v1/models" 2>/dev/null || true)
  case "$CODE" in
    000|000refused|""|refused) CODE=refused ;;
  esac
  check "局域网地址不可达($LAN_IP)" "refused" "$CODE"
else
  check "局域网 IP 探测" "yes" "no"
fi

kill $P1 $P2 2>/dev/null
sleep 0.5
echo
printf '结果: \033[1;32m%d 通过\033[0m, \033[1;31m%d 失败\033[0m\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
