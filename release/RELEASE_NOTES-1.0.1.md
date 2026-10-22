# v1.0.1 — 省流量与低延迟

修正 v1.0.0 的一个落差：README 里写着的「gzip 省流量」当时只存在于代码分支，**发布的 APK 并不含**
该实现。这一版把代理桥的传输层优化真正打进正式包。

## 新增 / 修正

- **响应压缩**：非流式响应走 gzip 透传，实测 6499B → 476B（省约 93%）
- **请求体压缩**：请求体 ≥512B 自动 `Content-Encoding: gzip`；上游目前拒收压缩请求体时
  自动回退明文一次并记住，不影响译文
- **连接复用**：与上游保持 keep-alive，不再每条请求重做 TCP + TLS 握手（连续翻译稳定在个位数毫秒）
- **调试开关**：`-Dindex.bridge.gzip=0` 关掉传输层压缩，`-Dindex.bridge.gzip.upload=0`
  只关请求体压缩（Node 版对应 `DISABLE_GZIP=1` / `DISABLE_GZIP_UPLOAD=1`）

## 未改动

- 只监听 `127.0.0.1`、端口自动探测、CORS 预检、SSE 流式直通、参数注入
  （`model=Index-Translate-9B`、`temperature=0`、`enable_thinking=false`）全部照旧
- 零第三方依赖（纯 Java + JDK 标准库），体积仍 29KB，不需要 Gradle
- Android 7.0+（minSdk 24，targetSdk 34）；同签名可直接覆盖升级（versionCode 1 → 2）

## 下载

- `index-translate-bridge-1.0.1.apk`（正式签名）
- `USAGE-zh.txt`（使用说明）
- `SHA256SUMS.txt`
