# Index-Translate 本地代理桥（Android）

[![build-apk](https://github.com/Caves-Spouse/Index-Translate-Bridge/actions/workflows/build.yml/badge.svg)](https://github.com/Caves-Spouse/Index-Translate-Bridge/actions/workflows/build.yml)

给**沉浸式翻译 / Immersive Translate** 用的本地 OpenAI 兼容代理，把请求转发到
bilibili 官方 **Index-Translate** 翻译模型（默认 `Index-Translate-9B`）。

目的：**扩展直连上游被 CORS / 反爬拦掉（412）**、**思考文本污染译文**。

- 29KB 单 APK，**零第三方依赖**（纯 Java + JDK 标准库），不需要 Termux / root / 账号
- 一键启动：点图标即启动前台服务，通知栏显示端口
- **端口自动探测**：8080 被占用自动 +1 重试，界面显示实际端口
- **只监听 `127.0.0.1`**，不对外暴露；上游无需 API Key
- SSE 流式直通、完整 CORS 预检支持、自动注入 `enable_thinking=false`

支持 Android 7.0+（minSdk 24，targetSdk 34）。

---

## 下载 / 安装

到 [Releases](https://github.com/Caves-Spouse/Index-Translate-Bridge/releases/latest) 下载：

| 文件 | 说明 |
|---|---|
| `index-translate-bridge-1.0.1.apk` | 安装包，29KB，Android 7.0+ |
| `USAGE-zh.txt` | 使用说明（GitHub 不接受中文资产名，故用此名） |
| `SHA256SUMS.txt` | 校验值，`sha256sum -c SHA256SUMS.txt` |

传到手机点击安装即可；提示「未知来源 / 未经 Play Protect 扫描」属正常现象，选「仍要安装」。
记得去设置-耗电详细-允许后台运行。否则可能造成翻译服务中断。

详细步骤（含荣耀/小米/OPPO 后台保活设置、报错对照表）见
[`release/使用说明.txt`](release/使用说明.txt)。

## 使用（三步）

1. 打开「Index翻译桥」→ 允许通知 → 界面显示 `● 运行中 · 端口 8080`
2. 点「复制地址」，得到 `http://127.0.0.1:8080/v1`
3. 沉浸式翻译里填：

| 配置项 | 值 |
|---|---|
| 翻译服务 | `OpenAI 兼容` / `自定义 OpenAI` |
| 接口地址 | `http://127.0.0.1:8080/v1`（以 App 内实际端口为准） |
| 模型 | `Index-Translate-9B` |
| API Key | 随意，例如 `index` |

可用模型：`Index-Translate-9B`（默认）、`Index-Translate-2B`（更快）、
`Index-Translate-35B-A3B`（更强）；上游还接受 `index-mt-9b`、`index-mt-2b`、
`index-mt-2b-syllable`、`index-mt-9b-syllable` 等别名。

## 为什么需要代理

1. 浏览器扩展直连 `https://index-translate.bilibili.com/v1` 过不了上游的 CORS/反爬校验；
2. 该模型在未显式关闭思考时会输出 CoT 文本，扩展拿到的不是译文。

代理负责补 CORS 响应头、注入浏览器缺失的请求头，并强制
`chat_template_kwargs.enable_thinking=false` + `temperature=0`。

## 从源码构建

**不需要 Gradle / Android SDK Manager / Google Maven**，手动链一条命令出包：

```bash
pkg install -y openjdk-17 aapt2 d8 apksigner zip unzip     # Termux
# 平台桩（26MB，仅需一次）
mkdir -p tools/sdk/platforms/android-34
curl -L -o tools/sdk/platforms/android-34/android.jar \
  https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
# 镜像: https://cdn.jsdelivr.net/gh/Sable/android-platforms@master/android-34/android.jar

bash build.sh                        # → dist/index-translate-bridge-1.0.0.apk
bash build.sh --release --bump        # 用正式密钥构建并递增 versionCode
bash tools/make_release.sh 1.1.0     # 生成 release/v1.1.0/ 发布包
node tools/make_icon.js              # 重新生成图标
```

构建链：`aapt2 compile/link` → `javac --release 8` → `d8` → `zip` → `apksigner`。
`--release 8` 会把 API 面锁在 Java 8，误用 Android 上不存在的 JDK 新 API（如 `String.strip()`）
会直接编译失败。

### 签名

- `build.sh` 默认用 `tools/bridge.keystore`（口令 `android`，首次构建自动生成）——**仅供自用测试**。
- 正式发布用 `--release`，从 **未入库** 的 `tools/keystore.properties` 读取：
  ```properties
  storeFile=tools/release.keystore
  storePassword=***
  keyAlias=release
  keyPassword=***
  ```
- 换签名 = 已装用户必须卸载重装；同一签名 = 可直接覆盖升级。
  **务必备份 `release.keystore` 与口令**，丢失即无法再发升级版。

## 测试

不需要手机，两套套件各 13 项，覆盖端口自动切换、SSE 直通、CORS 预检、默认参数注入、
上游错误透传、仅监听 127.0.0.1：

```bash
bash tools/jvmtest/run_test.sh    # 直接跑 APK 内同一份 ProxyServer.java（JVM）
bash tools/nodetest/run_test.sh   # Node 版代理（Termux/桌面场景）
```

GitHub Actions 在境外、可能访问不到上游，此时联网用例会被标为 `SKIP`（环境变量
`ALLOW_UPSTREAM_SKIP=1`），不会把 CI 判红。

## 发布流程（维护者）

CI 只负责"能构建 + 能跑测试"，它产出的包用 CI 临时密钥签名，**不能对外发**。
正式包必须用发布密钥在本机打，然后手动上传到 Release：

```bash
bash tools/make_release.sh 1.1.0        # 自动 +1 versionCode，产出 release/v1.1.0/
git tag v1.1.0 && git push origin main --tags
# 然后在 GitHub 网页 Draft a new release 上传 release/v1.1.0/ 下三个文件
```

注意：GitHub Releases 的资产名**不支持中文字符**——上传 `使用说明.txt` 会被改成
`default.txt`，所以对外统一用 `USAGE-zh.txt` 这个名字。

## 目录结构

```
.
├── android/                    # APK 源码
│   ├── AndroidManifest.xml     # INTERNET + FOREGROUND_SERVICE(specialUse) + POST_NOTIFICATIONS
│   └── src/com/indextranslate/bridge/
│       ├── MainActivity.java   # 单页 UI：状态/地址/复制/自检/日志/版本
│       ├── ProxyService.java   # 前台服务，复用上次成功端口
│       ├── ProxyServer.java    # 纯 JDK HTTP 代理（SSE 直通、CORS、参数注入）
│       └── ProxyState.java     # 进程内状态与环形日志
├── proxy/                      # 阶段一：Node 版代理（零依赖），start.sh 一键启动
├── tools/
│   ├── jvmtest/ nodetest/       # 功能测试
│   ├── make_icon.js             # 手写 PNG 生成图标
│   └── make_release.sh          # 打发布包
├── release/使用说明.txt         # 面向普通用户的说明书
├── build.sh
└── .github/workflows/build.yml  # CI：构建 + 测试 + tag 时自动发 Release
```

## 已知边界

- 只监听 `127.0.0.1`：只在同一台手机上生效，电脑/其它设备连不到（刻意的安全设计）。
- 不做 API Key 管理（上游公共端点无需鉴权，公共资源请勿滥用）。
- 未做 `zipalign`（Termux 无该包）：对 minSdk 24+ 直装无影响，仅影响上架商店。
- 未上架应用商店；`foregroundServiceType=specialUse` 属自用类目。
- 上游若限流或下线，代理会原样透传错误码，需等待上游恢复。

## 免责声明

本项目仅为协议转发工具，不托管、不修改、不缓存翻译内容；上游服务由 bilibili 提供，
其可用性与条款变化不由本项目负责。请合理使用公共接口。

## License

[MIT](LICENSE)
