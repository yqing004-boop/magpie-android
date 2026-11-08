# magpie 手机版（APK）

把 [yetone/magpie](https://github.com/yetone/magpie) 塞进一个 Android App：点图标就有，
不用 Termux，不用刷 Magisk。以后上游发新版，直接在 App 里拉下来换掉，不用重新装 APK。

```
┌─ App（这个仓库的 app/）────────────────────────────┐
│  MainActivity ── WebView ──→ http://127.0.0.1:3430 │
│       ⋮ 菜单 → 设置 / 检查更新                     │
│  GatewayService（前台服务）                        │
│       └─ exec filesDir/magpie/magpie serve         │
└────────────────────────────────────────────────────┘
                     ↑
         filesDir/magpie/magpie  ← 更新就是换掉这个 ELF
```

## 这是个壳，更新的是里面那个 ELF

magpie 本体是一个 Go 程序（`magpie serve`）：网关在 3425，Web UI 在网关 +5（3430），
页面是它自己内嵌的。App 只负责三件事——把 ELF 放出来、把进程拉起来、把 WebView 指过去。
所以：

- **功能更新、界面更新都来自上游**，壳几乎不用跟着改；
- **“在 App 里拉更新”= 换掉 `filesDir/magpie/magpie` 这个文件**，不是装新 APK。

## 在 App 里更新（重点）

入口：主界面右上角 **⋮ → 检查更新**，或 **设置 → 检查 magpie 更新**。
启动时还会静默查一次（一天最多一次），有新版本弹一句提示——只提示，不自动下载，
70 MB 别在你不知道的时候吃掉流量。

### 源

| 顺序 | 地址 | 说明 |
| --- | --- | --- |
| 1 | `https://usemagpie.ai/api/latest` | 官方清单：版本、更新说明、每个文件的 SHA-256。也是 `magpie update` 用的那个 |
| 2 | `https://api.github.com/repos/yetone/magpie-releases/releases/latest` | GitHub 官方 API，官方清单挂了才用（未认证限流 60 次/小时） |

设置页的「更新源」可以改成任何同格式的地址（自建反代、镜像站都行）。
资产固定取 **`magpie-cli-android-arm64`**（armv7 机器取 `magpie-cli-android-arm`）。

### 五步，一步都不省

1. **问版本**：清单里 `version` 跟本机 `magpie version` 比。本机是自建的 `android-1.0`
   这种非 semver 版本时，任何官方发布都算“有新版本”。
2. **下载**：写到 `filesDir/magpie/update/<asset>.part`，支持 `Range` 断点续传，
   下到一半断了下次接着下；进度条按字节走。
3. **校验**：边下边算 SHA-256，跟清单里的 `sha256` 比；再比 `size`。
   不对就删文件重来，绝不落盘。
4. **冒烟**：`magpie.<ver>.new version` 真跑一次。直接 exec 被系统拒就走
   `/system/bin/linker64`（和 GatewayService 启动时同一套回退）。跑不起来就放弃这次更新，
   旧版本原封不动。
5. **换位**：旧文件复制成 `magpie.bak`，新版 `rename` 覆盖。Linux 上 rename 覆盖正在执行的
   ELF 是原子的：老进程继续跑老 inode，重启后的新进程跑新文件，中间不空窗。
   然后 `GatewayService.restart()`，WebView 自己重新加载。

出问题就在更新页点 **回滚到上一个版本**：`magpie.bak` 换回来，再重启。

### 为什么不直接调 `magpie update`

本工程自建的二进制是 `GOOS=linux` 编的静态 ELF（为了不含 `/system/bin/linker64` 依赖）。
magpie 自己算资产名用的是 `runtime.GOOS`，于是会挑 **`magpie-cli-linux-arm64`**——
那是桌面 GUI 版，动态链接 glibc + GTK3 + WebKitGTK，Android 上直接段错误。
所以资产名必须由壳按 ABI 写死成 `magpie-cli-android-arm64`（官方那个是 `GOOS=android`
编译的 PIE，interpreter 是 `/system/bin/linker64`，壳的回退路径正好接住）。

### 更新过的二进制不会被 APK 冲掉

首启会把 `assets/bin/magpie.bin` 释放到 `filesDir/magpie/magpie`，并记下大小。
如果只比大小，用户更新完下次启动就会被“重新释放”覆盖回旧版。
所以 `GatewayService.ensureBinary()` 拿 **APK 自己的 `lastUpdateTime`** 当新鲜度标记：

- App 没升级过 → 不碰 `filesDir` 里的二进制（那是用户刚拉的新版）；
- App 升级过（换了 assets）→ 清掉记录，重新从 assets 释放。

## 关键参数

| 项 | 值 | 为什么 |
| --- | --- | --- |
| `targetSdk` | **28** | Android 10+ 对 `targetSdk≥29` 的 App 禁止 exec 私有目录里的文件（W^X）。magpie 是服务器，必须 exec 自己的 ELF。Termux 的 F-Droid 版也钉在 28。同时 28 还能省掉 Android 14 的前台服务类型声明 |
| `minSdk` | 24 | WebView + `startForegroundService` |
| 网关端口 | 3425（可改） | `MAGPIE_ADDR` |
| Web UI | 网关 + 5 = 3430 | `--web=127.0.0.1:3430`，WebView 带 `?k=<webKey>` 打开 |
| 数据目录 | `filesDir/magpie/data` | 二进制旁边放 `.portable`，magpie 就进 portable 模式 |
| HOME | `filesDir/home` | agent 的 `~/.claude`、`~/.codex` 都在这 |
| 二进制 | `assets/bin/magpie.bin`，不压缩 | 首启是拷贝不是解压 |

## 真机上踩到的六个坑（都已修在代码里）

套壳看着简单，跑起来才知道 Android 每个版本都在门口拦一道。按踩到的顺序：

| # | 现象 | 原因 | 修法 |
| --- | --- | --- | --- |
| 1 | 点图标秒退 | `activity_main.xml` 里那个 ⋮ 按钮只有 `minWidth`，没有 `layout_width` | 补上 `layout_width/height="48dp"`（`setContentView` 抛 `InflateException`） |
| 2 | 启动就崩，`SecurityException: startForeground … requires FOREGROUND_SERVICE` | Android 9+ 前台服务要声明权限，targetSdk 28 也躲不掉 | manifest 加 `FOREGROUND_SERVICE`（顺带 `POST_NOTIFICATIONS`） |
| 3 | 网页白屏，`net::ERR_CLEARTEXT_NOT_PERMITTED` | targetSdk 28 默认禁止明文 HTTP，而 WebView 打开的是 `http://127.0.0.1:3430` | `res/xml/network_security_config.xml` + `usesCleartextTraffic` |
| 4 | 网关起来了但一片 `lookup xxx on [::1]:53: connection refused` | CGO 关掉的 Go 读 `/etc/resolv.conf`，Android 没有；退回 `127.0.0.1:53`/`::1:53` 又没人监听 | App 内起本机 CONNECT 代理（`LocalProxy`），把 `HTTPS_PROXY` 塞进子进程环境，域名交给 Java → netd |
| 5 | 编译期 `onJSAlert does not override` | `WebChromeClient` 的方法名是 `onJsAlert` / `onJsConfirm` / `onJsPrompt`（小写 s），参数是 `JsResult` / `JsPromptResult` | 改签名 |
| 6 | `getMimeTypes()` / `setVerticalScrollbarEnabled()` 找不到 | 真名是 `getAcceptTypes()` / `setVerticalScrollBarEnabled()` | 改调用 |

第 4 条值得多说一句：为什么不用 root 起个 DNS 转发？`/proc/sys/net/ipv4/ip_unprivileged_port_start`
是 1024，普通 App 绑不了 53；也不能改 `/etc/resolv.conf`（只读 system 分区）。
代理是唯一不动系统、也不改 magpie 源码的路子，而且 magpie 更新到任何一版都继续有效。

---

## 界面（1.0.12）

顶栏是一张**矮**卡片（34dp 图标 / 16sp 标题 / 上下各 8dp 内边距），不是网页：鸟图标 + 名字 + 状态点（绿＝运行中 / 灰＝已停止）+ 网关胶囊 + 三个点。整个壳 `paddingTop = status_bar_height`，卡片不会钻进状态栏；卡片与内容之间只留 6dp。

下面那块 Web UI 是**四角 20dp 圆弧**：容器和 WebView 各自挂一个圆角 `ViewOutlineProvider` + `setClipToOutline`，布局变化时 `invalidateOutline()` —— 只裁容器的话，WebView 的滚动条和浮动按钮会画到圆角外面。

- **点胶囊** → 复制 `http://127.0.0.1:3425/v1`；长按 → 用浏览器打开
- **点三个点** → 底部弹层（重新加载界面 / 用浏览器打开 / 复制网关地址 / 重启网关 / 停止网关 / 查看日志 / 设置 / 检查 magpie 更新 / 忽略电池优化 / 关于）
- **关于**：外壳版本 + 内核版本、magpie 官方仓库（`github.com/yetone/magpie`）、APK 移植制作者（酷安 @图图没耳朵，`coolapk.com/u/1642567`）、MIT 许可与非官方声明；链接行点一下直接开浏览器
- **设置 → 外观**：莫奈取色（Android 12+，从系统壁纸取强调色）、**顶栏卡片颜色** 与 **内容区背景颜色** 两组 8 色板（第一块＝跟随系统/莫奈），换了立刻生效；状态栏图标会按背景深浅自动翻黑/翻白

> 内容区变色为什么要动页面的 CSS：那页 magpie 的每一个白块（顶栏、工具栏、卡片、底部条、输入框）都是**自己带背景色**的，只把 html/body 弄透明，它们照样白。所以壳是直接覆盖它自己的主题变量：
>
> ```css
> :root,html,body{
>   --bg: 你选的颜色;            /* 页面底 */
>   --card: 底色×0.14 + 白×0.86; /* 卡片，留一点白才有层次 */
>   --card-2 / --bar / --pill    /* 次级块、工具栏、胶囊 */
>   --fg / --muted / --faint / --line  /* 文字与分割线按底色深浅翻 */
> }
> ```
>
> 变量名是从二进制里的 CSS 里抠出来的（`grep -- '--[a-z]*:#'`）。选「跟随系统」就把注入的 `<style>` 摘掉，页面恢复原样。
>
> 另外 WebView 里的浮动按钮会画到圆角外面，壳在容器上盖了一层 `CornerMask`（`Path.Op.DIFFERENCE` 画四个角），用底色把溢出的部分压住。
- **沉浸**：状态栏、导航栏都透明，`SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|LAYOUT_HIDE_NAVIGATION` + 顶栏自己让出 `status_bar_height`，所以上下没有黑边；弹层底部还会让出 `navigation_bar_height`

外观只改外壳（顶栏、弹层、设置页）。**页面本身的深浅色跟随 magpie 自己的 Settings → 外观**，壳不越权去改它。

---

## 构建

### 路 A：没有 Gradle 也能出包（arm64 Linux / 本容器）

```sh
ANDROID_JAR=/path/to/android-30/android.jar ./scripts/build-apk-local.sh
# → dist/magpie-android-1.0.apk
```

手工五步：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → `zipalign` → `apksigner`。

- `scripts/tools/aarch64/` 里带了 aarch64 的 `aapt2`、`zipalign`（Google 只发 x86_64 的，
  arm64 机器上 AGP 会 `exec format error`）；
- `d8`、`apksigner` 是纯 Java，直接 `java -jar`；
- 要一份**带 `resources.arsc` 且能被 build-tools 33 的 aapt2 读**的 `android.jar`：
  API 30 那份可以，API 35 那份 arsc 版本太新，`aapt2 link` 会报
  `failed to load include path`；
- 签名默认用 `scripts/debug.keystore`（没有就自动生成，口令都是 `android`）。

### 路 B：有 x86_64 机器和 Android Studio 的 SDK

```sh
LANG=en_US.UTF-8 ./gradlew :app:assembleDebug    # 需要 JDK 17 + SDK 34 + gradle-wrapper.jar
```

> 仓库里没放 `gradle-wrapper.jar`（二进制），第一次跑要 `gradle wrapper` 生成，或直接用路 A。

---
