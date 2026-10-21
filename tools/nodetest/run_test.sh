#!/usr/bin/env bash
# 阶段一（Node 版代理）功能测试
# 用法: bash tools/nodetest/run_test.sh
set -u
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG="$HOME/tmp/nodetest.log"
mkdir -p "$HOME/tmp"

pass=0; fail=0
check() {
  if [ "$2" = "$3" ]; then printf '  \033[1;32mPASS\033[0m %s (%s)\n' "$1" "$3"; pass=$((pass+1));
  else printf '  \033[1;31mFAIL\033[0m %s 期望=%s 实际=%s\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}

echo "▶ 启动 Node 代理（起始端口 8070）"
PORT=8070 node "$PROJECT_DIR/proxy/index-translate-proxy.js" > "$LOG" 2>&1 &
P1=$!
for _ in $(seq 1 60); do grep -q "代理地址" "$LOG" 2>/dev/null && break; sleep 0.2; done
PORT1=$(sed -n 's/.*127\.0\.0\.1:\([0-9]*\)\/v1.*/\1/p' "$LOG" | head -1)
[ -n "$PORT1" ] || { echo "启动失败"; cat "$LOG"; exit 1; }
echo "  实例1 端口 = $PORT1"

echo "▶ A. GET /v1/models"
CODE=$(curl -sS -m 20 -o "$HOME/tmp/n_models.json" -w '%{http_code}' "http://127.0.0.1:$PORT1/v1/models")
check "models HTTP 200" "200" "$CODE"
check "列出 9B" "yes" "$(grep -q 'Index-Translate-9B' "$HOME/tmp/n_models.json" && echo yes || echo no)"

echo "▶ B. OPTIONS 预检"
HDR=$(curl -sS -m 20 -D - -o /dev/null -X OPTIONS "http://127.0.0.1:$PORT1/v1/chat/completions" \
  -H "Origin: chrome-extension://abc" -H "Access-Control-Request-Method: POST" | tr -d '\r')
check "预检 204" "yes" "$(echo "$HDR" | grep -q '204' && echo yes || echo no)"
check "ACAO=*" "yes" "$(echo "$HDR" | grep -qi 'Access-Control-Allow-Origin: \*' && echo yes || echo no)"

echo "▶ C. 非流式翻译（不传 model/temperature，验证默认注入）"
RESP=$(curl -sS -m 90 -X POST "http://127.0.0.1:$PORT1/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"请将以下文本翻译为English，直接输出翻译结果，不要进行任何解释。\n\n技术赋能创作，让交流跨越语言的界限。"}]}')
check "默认模型=9B" "yes" "$(echo "$RESP" | grep -q '"model":"Index-Translate-9B"' && echo yes || echo no)"
check "无 think 残留" "yes" "$(echo "$RESP" | grep -q '<think' && echo no || echo yes)"
echo "  译文: $(echo "$RESP" | sed -n 's/.*"content":"\([^"]*\)".*/\1/p' | head -1)"

echo "▶ D. SSE 流式"
curl -sS -N -m 90 -X POST "http://127.0.0.1:$PORT1/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"stream":true,"messages":[{"role":"user","content":"请将以下文本翻译为English，直接输出翻译结果，不要进行任何解释。\n\n今天天气真好，我们一起去公园散步吧。"}]}' \
  > "$HOME/tmp/n_sse.txt" 2>&1
LINES=$(grep -c '^data: ' "$HOME/tmp/n_sse.txt" || true)
check "多个 data 分块" "yes" "$([ "$LINES" -ge 3 ] && echo yes || echo no)"
check "含 [DONE]" "yes" "$(grep -q '\[DONE\]' "$HOME/tmp/n_sse.txt" && echo yes || echo no)"
echo "  分块数=$LINES"

echo "▶ E. 上游错误透传（假模型名）"
CODE=$(curl -sS -m 40 -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT1/v1/completions" \
  -H 'Content-Type: application/json' -d '{"model":"Not-A-Model","prompt":"hi"}')
check "错误码透传（400/404）" "yes" "$([ "$CODE" = "400" ] || [ "$CODE" = "404" ] && echo yes || echo no)"

echo "▶ F. 未知路径 404"
CODE=$(curl -sS -m 15 -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT1/v1/unknown" -d '{}')
check "unknown path 404" "404" "$CODE"

echo "▶ G. 端口占用自动切换（再起实例，起始端口同样 8070）"
PORT=8070 node "$PROJECT_DIR/proxy/index-translate-proxy.js" > "$HOME/tmp/nodetest2.log" 2>&1 &
P2=$!
for _ in $(seq 1 60); do grep -q "代理地址" "$HOME/tmp/nodetest2.log" 2>/dev/null && break; sleep 0.2; done
PORT2=$(sed -n 's/.*127\.0\.0\.1:\([0-9]*\)\/v1.*/\1/p' "$HOME/tmp/nodetest2.log" | head -1)
check "换端口成功" "yes" "$([ -n "$PORT2" ] && [ "$PORT2" != "$PORT1" ] && echo yes || echo no)"
CODE=$(curl -sS -m 15 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT2/v1/models")
check "新端口可用" "200" "$CODE"
echo "  实例2 端口 = $PORT2"

echo "▶ H. 仅绑 127.0.0.1"
LAN_IP=$(ifconfig 2>/dev/null | sed -n 's/.*inet \(addr:\)\{0,1\}\([0-9.]*\).*/\2/p' | grep -v '^127\.' | head -1)
if [ -n "$LAN_IP" ]; then
  CODE=$(curl -sS -m 4 -o /dev/null -w '%{http_code}' "http://$LAN_IP:$PORT1/v1/models" 2>/dev/null || true)
  case "$CODE" in 000|000refused|refused|"") CODE=refused ;; esac
  check "局域网不可达($LAN_IP)" "refused" "$CODE"
else
  echo "  （跳过：无局域网 IP）"
fi

kill $P1 $P2 2>/dev/null
sleep 0.5
echo
printf '结果: \033[1;32m%d 通过\033[0m, \033[1;31m%d 失败\033[0m\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
