# Magpie for Android（非官方移植）

把 [magpie 官方项目](https://github.com/yetone/magpie) 装进 Android App：打开即用 Web 管理界面，在手机上运行本地 AI 网关。**无需 Root、Magisk 或额外终端 App。** 本仓库是非官方 Android 外壳，不是 magpie 官方发布。

> APK 移植制作者：[酷安 @图图没耳朵](https://www.coolapk.com/u/1642567)。项目由 AI 辅助完成；包括部分代码、文档和排查工作，发布者仍应对发布内容负责。

## 亮点

- **安装即可用**：App 管理网关进程，WebView 打开 magpie 自带管理界面；可从菜单查看运行状态、日志、重启或停止服务。
- **App 内更新 magpie 内核**：从官方更新源获取 Android 二进制，不必每次为内核更新重装 APK；支持下载进度、断点续传、SHA-256 校验、运行前验证与备份回滚。*外壳界面或功能更新仍需安装新 APK。*
- **接入方便**：默认网关地址 `http://127.0.0.1:3425/v1`，在界面中可一键复制；网关端口和局域网访问可在设置中调整。
- **适配 Android 使用**：前台服务维持网关运行，并针对静态 Go 程序在 Android 上的 DNS 环境做了本地代理适配。
- **界面可调**：支持沉浸式界面、顶栏及内容区配色，以及 Android 12+ 的壁纸动态取色。

## 下载与使用

发布 Release 后，从本仓库 **Releases** 下载 APK，安装后打开 App。第一次启动会释放随 APK 附带的 magpie 二进制，之后从右上角菜单进入「检查 magpie 更新」「设置」「关于」。在 Release 上线前，本仓库不提供公开下载地址。

当前提供的 APK 为 Android **arm64** 版本；最低 Android 7.0（API 24）。其它设备或系统版本不保证兼容。APK 使用调试签名，后续更换签名密钥时无法直接覆盖安装，卸载重装可能导致应用数据丢失，请自行备份。安装来源、网络权限及后台运行限制受设备系统设置影响。

**安全提醒**：开启局域网访问前，请配置网关鉴权并确认网络环境；不要公开分享带 Web 管理密钥的地址。只从本仓库 Releases 获取 APK，并核对发布页 SHA-256。

## 工作原理

Android 外壳使用 WebView 访问 magpie 本地 Web UI，前台服务启动 `magpie serve`；默认 API 网关端口为 3425，Web UI 为 3430。App 内更新仅替换应用私有目录中的 magpie 二进制，不会直接升级 Android 外壳。项目未声称与 magpie 官方存在合作关系。

## 源码与构建

本仓库的 Android 源码在 `app/`。构建入口为 `scripts/build-apk-local.sh`，需要 JDK、Android SDK build-tools、可用的 Android platform `android.jar`、`aapt2` 和 `zipalign`，以及 `app/src/main/assets/bin/magpie.bin`。可在本仓库相邻位置准备 [yetone/magpie](https://github.com/yetone/magpie) 上游源码，用 `scripts/build-magpie.sh` 构建并通过 `scripts/sync-binary.sh` 同步；或获取可信的官方 Android 二进制。仓库不提交预置二进制、构建缓存或签名密钥；构建前需从可信的官方发布渠道获取适合 Android ABI 的 magpie 文件并按构建脚本的约定放置，核对来源与哈希。不要直接拿桌面 Linux GUI 版代替 Android 版。

```sh
ANDROID_JAR=/path/to/android.jar VERSION_NAME=1.0.19 VERSION_CODE=20 ./scripts/build-apk-local.sh
```

产物在 `dist/`。脚本默认生成/使用调试签名；正式发行请自行设置并安全保管签名，切勿将私钥提交到公开仓库。详细的实现背景见 [开发笔记](README.dev.md)。

## 来源、版权与反馈

magpie 核心及其自带 Web UI 来自 [yetone/magpie](https://github.com/yetone/magpie)，其源码使用 MIT 许可证；使用和再分发应保留上游版权与许可声明。此 Android 移植与上游项目无官方关联，也不代表上游作者提供技术支持。本项目附带的第三方工具与二进制按各自许可证授权，不能因为项目整体标注 MIT 就忽略各组件的许可要求。详见 [第三方声明](THIRD_PARTY_NOTICES.md)。

**侵权联系删除**：如果您认为本仓库内容或发布的 APK 侵犯您的版权、商标或其他权益，请通过 GitHub Issue 联系维护者，说明权利归属及涉及内容。收到通知后会核实并及时移除或调整相关内容。此声明不取代许可证要求，也不免除发布者的责任。

本项目由 **AI 辅助开发与整理文档**，并非官方 magpie Android 客户端。问题反馈请发本仓库 Issue；magpie 本体问题请参考上游项目渠道。
