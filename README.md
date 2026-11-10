# Magpie for Android：在手机上运行 magpie AI 网关

> **非官方 Android 客户端 / Android APK。** 把 magpie 的本地 AI 网关装进手机：无需 Root、无需 Magisk、无需 Termux 或其他终端 App。
>
> 作者： [酷安 @图图没耳朵](https://www.coolapk.com/u/1642567)｜[酷安发布帖](https://www.coolapk.com/feed/74938122?s=ZjIxZmUyYjcxOTEwNDdnNmFmMWM1ZTd6a1665)｜

[![GitHub Release](https://img.shields.io/github/v/release/yqing004-boop/magpie-android?label=Release)](https://github.com/yqing004-boop/magpie-android/releases/latest)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/yqing004-boop/magpie-android/releases/latest)
[![License](https://img.shields.io/badge/upstream-MIT-blue.svg)](https://github.com/yetone/magpie/blob/main/LICENSE)

## 下载

**[下载最新版 APK（GitHub Releases）](https://github.com/yqing004-boop/magpie-android/releases/latest)**

当前只提供 **Android arm64 / arm64-v8a** 包，最低 Android 7.0（API 24）。下载后直接安装；升级请覆盖安装，不要先卸载，否则 Magpie 的本地配置、账号和数据可能被清除。

> 本项目不是 yetone/magpie 官方发布，也不代表上游作者提供技术支持。只建议从本仓库的 Releases 下载 APK，并核对 Release 页面给出的 SHA-256。

## 它是什么

Magpie 是一个本地 AI 网关：你可以在一个地址上管理供应商、账号、模型和路由，再让本机或局域网里的客户端按 OpenAI / Anthropic / Gemini 等 API 格式调用。

这个仓库做的是 **Android 运行壳**，不是只读的远程监控器：前台服务直接在手机应用私有目录运行 `magpie serve`，WebView 展示 magpie 原生管理界面。默认 API 地址是：

```text
http://127.0.0.1:3425/v1
```

## 适合什么场景

- 想在手机上直接管理 magpie 的供应商、账号、模型、路由、请求和用量；
- 想给手机上的 AI 客户端提供本地 OpenAI 兼容网关；
- 想在同一 Wi‑Fi 下给其他手机或电脑提供网关服务；
- 不想每次上游更新内核都重新下载整个 Android APK。

## 核心特性

- **无需 Root**：不依赖 Magisk、Termux 或额外终端 App。
- **前台服务托管网关**：启动后在手机里运行 magpie 网关，并提供状态、日志、重启与停止入口；异常退出时会退避重启，连续失败会发通知提醒。
- **原生 Web 管理界面**：供应商、路由、网关、用量、插件等页面仍使用 magpie 自带 Web UI。
- **App 内更新内核**：从官方更新源下载 Android arm64 内核，提供断点续传、SHA-256 校验、运行前验证与回滚；内核更新不需要重装 APK。
- **局域网接入**：顶栏「局域网」入口可查看并复制局域网 Base URL 与网关密钥；远端客户端仍必须携带已启用的网关密钥。
- **Material 3 外壳**：TonalSpot / SPEC 2025 配色、八种种子色、Android 12+ 动态取色。
- **后台页面内存管理**：可选择从不、15 分钟、30 分钟或自定义时间；后台释放 WebView 不会主动停止网关，回到 App 后自动恢复页面。
- **首次后台运行引导**：提示通知权限、电池优化、自启动和最近任务锁定等系统设置，并支持“以后再说”和“不再提示”。
- **Android DNS 适配**：为静态 Go 内核提供本地 HTTP CONNECT 代理，避免 Android 环境中 `/etc/resolv.conf` 缺失造成的域名解析问题。

## 界面预览

> 截图中的账号、密钥、局域网 IP 和用量均已打码或使用示例信息。不要把自己的网关密钥或带 `?k=` 的管理页地址公开发布。

| 路由与账号选择 | 局域网共享 | 请求与用量 |
| --- | --- | --- |
| ![Magpie Android 路由页面](screenshots/routing.jpg) | ![Magpie Android 局域网共享弹窗](screenshots/lan-share.jpg) | ![Magpie Android 请求与用量页面](screenshots/usage.jpg) |

## 快速开始

1. 从 [Releases](https://github.com/yqing004-boop/magpie-android/releases/latest) 下载并安装 APK；
2. 打开 App，等待顶部状态显示「运行中」；
3. 在原生 magpie 页面中添加供应商或登录账号；
4. 在需要调用模型的客户端中填入 `http://127.0.0.1:3425/v1`；
5. 如需跨设备使用：先在壳的设置开启「允许局域网访问」，再到 magpie 网页的「网关 → 局域网共享」开启共享并确认网关密钥；顶栏「局域网」可复制给另一台设备使用的 Base URL 与密钥。


## 更新说明

这里有两种不同更新：

| 更新对象 | 入口 | 是否需要重装 APK |
| --- | --- | --- |
| **magpie 内核** | App 右上角菜单 →「检查 magpie 更新」 | 不需要；仅替换应用私有目录中的内核二进制 |
| **Android 外壳** | 设置 →「检查 App 更新（GitHub）」或 [GitHub Releases](https://github.com/yqing004-boop/magpie-android/releases/latest) | 需要下载新版 APK 并覆盖安装 |

内核更新会进行下载进度、断点续传、SHA-256、版本冒烟测试与备份回滚。外壳升级必须保持同一签名，才能在不卸载的情况下保留数据。

## 工作原理

```text
Android App
 ├─ MainActivity：WebView → magpie Web UI（默认 3430）
 ├─ GatewayService：前台服务 → magpie serve（默认 API 3425）
 ├─ LocalProxy：为 Android 上的静态 Go 内核提供 DNS/HTTP 代理适配
 └─ Updater：下载、校验、替换、回滚官方 Android arm64 内核
```

默认 API 网关端口为 `3425`，Web UI 为 `3430`。App 的数据在应用私有目录中；开启局域网访问时必须使用网关密钥，不要把密钥或 Web 管理密钥公开给不可信的人。

## 源码与构建

Android 源码在 `app/`，详细构建记录见 [README.dev.md](README.dev.md)。本仓库不提交内核二进制、构建缓存、Android 工具二进制或签名密钥；构建前需要从可信来源准备 Android arm64 的 magpie 内核。

```sh
ANDROID_JAR=/path/to/android.jar \
VERSION_NAME=1.0.28 VERSION_CODE=28 \
sh scripts/build-apk-local.sh
```

产物在 `dist/`。发布者必须安全保管自己的签名密钥，**绝不要把 keystore 提交到公开仓库**。

## 来源、许可证与反馈

- magpie 核心与内嵌 Web UI 来自 [yetone/magpie](https://github.com/yetone/magpie)，版权归上游作者所有，使用 MIT License；APK 内保留上游 MIT 许可证全文。
- 本项目是独立、非官方的 Android 外壳；第三方工具和二进制遵循各自许可证，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。
- 问题反馈请发 [GitHub Issues](https://github.com/yqing004-boop/magpie-android/issues)。
- **侵权联系删除**：如认为本仓库内容或 APK 涉及版权、商标或其他权益问题，请通过 GitHub Issue 联系维护者并说明权利归属及涉及内容；收到后会核实并及时处理。
