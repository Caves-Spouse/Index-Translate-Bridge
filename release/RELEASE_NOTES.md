# v1.0.0

首个版本：给沉浸式翻译用的 Android 本地代理，转发到 bilibili Index-Translate 官方接口。

## 功能

- 一键启动：点图标即启动前台服务，通知栏显示 `代理运行中 · 端口 xxxx`
- 端口自动探测：8080 被占用自动 +1 重试（最多 30 个），界面显示实际端口；重启时优先复用上次成功端口
- 只监听 `127.0.0.1`，不对外暴露；上游无需 API Key
- SSE 流式直通（边收边发，不缓冲）与完整 CORS 预检支持
- 自动注入 `model=Index-Translate-9B`、`temperature=0`、`chat_template_kwargs.enable_thinking=false`
- App 内一键复制地址、内置自检（请求 `/v1/models`）、运行日志、版本号显示
- 兼容 Android 14/15 前台服务类型要求（`specialUse`）

## 安装

1. 下载 `index-translate-bridge-1.0.0.apk`（32KB）
2. 手机点击安装，允许"未知来源"
3. 打开 App → 允许通知 → 复制地址 `http://127.0.0.1:8080/v1`

沉浸式翻译配置：服务=`OpenAI 兼容`，接口地址=复制的 URL，模型=`Index-Translate-9B`，
API Key 随意填（例如 `index`）。

详见 `使用说明.txt`（含荣耀/小米/OPPO 后台保活设置与报错对照表）。

## 校验

```
SHA256SUMS.txt 内附 APK 校验值
```
