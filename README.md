# StudyOnTV · 答题才能看电视

一个装在 Android TV 上的家长控制 App：**孩子想打开视频 App，先做 5 道题；做对了才放行若干分钟**。原生 Java 实现，不注册桌面（HOME），用无障碍（Accessibility）遮罩在目标 App 前拦一道，配套一个手机端 App 和一个局域网网页控制台。

> 本仓库由 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) fork 而来（fork 时改名 StudyOnTV）。上游是一套「电视去广告 / 精简」工具集，本仓库只保留并在其思路上重写了「家长控制」这部分，代码与上游不共用。

---

## 为什么不做成 Home 桌面（重要经验）

最直觉的做法是把自己注册成电视的桌面（`CATEGORY_HOME`），开机即拦截。**在这台飞利浦 TPV 固件上会直接把电视搞黑屏**：

- 该 ROM 的 HOME 候选固定为 3 个（`com.ktcp.launcher`、`com.tpv.xmic.oobe`、`com.android.settings/.FallbackHome`）；
- 任何第三方 App 注册 HOME 后候选变成 4 个，`ActivityManagerService.chooseBestActivity()` 内部按 3 个元素的数组取下标 3；
- 于是 `system_server` 抛 `IndexOutOfBoundsException: Index: 3, Size: 3`，在 `startHomeActivityLocked` 里崩溃，表现为开机黑屏、桌面起不来。

所以本项目 **v1.2 起不再注册 HOME**，改用无障碍服务监听窗口切换：只要前台出现受限 App，就立刻盖一层答题遮罩。代价是必须由家长手动开启无障碍服务（覆盖安装后还会被系统清掉，需要重开一次），但最坏情况只是「不拦截」，不会把电视弄成砖。

`build_apk.ps1` 里保留了一道安全闸：打包后会用 `aapt2 dump xmltree` 检查 Manifest，**一旦发现 `android.intent.category.HOME` 就拒绝出包**。

---

## 和上游 cleanyourtv 的区别

上游解决的是「电视桌面太乱、广告太多」，本项目解决的是「孩子自己开电视看视频没人管」。本地新增的能力：

| 能力 | 说明 |
|---|---|
| 答题拦截 | 无障碍遮罩，5 题一轮，答对达到通过线才放行 N 分钟；到期自动重新拦 |
| 家长 PIN | 4 位数字，首次进家长页时自行设置；所有写操作（网页/手机端）都要 `X-Pin` |
| 题库 | 多学科分组、每学科独立统计、掌握阈值（答对 N 次算掌握）、达标自动移出题池 |
| AI 解题思路 | 接入 Agnes（OpenAI 兼容）给「提示」不给答案，客户端做答案泄露拦截、缓存与超时降级 |
| 语音朗读 | 接入 MiMo TTS，MP3 缓存后本地播放；没有可用 TTS 时退化为纯文字 |
| 局域网网页控制台 | 电视上开一个只读+受 PIN 保护的小 HTTP 服务，手机浏览器可直接改配置、传题库 |
| 手机家长 App | `parent_app`，Android 原生，连电视的局域网服务做同样的操作 |
| 题库更新 | 网页上传 / `adb push` / U 盘三种方式，导入前统一校验，旧库自动备份可回退 |
| 远程看画面 | 附 `scrcpy` 启动脚本，电脑上直接操作电视排查问题 |

---

## 目录结构

```
quiz_launcher/        电视端 App（com.family.quizlauncher，原生 Java）
  app/src/main/       Java 源码、资源、assets（含网页控制台页面）
  DESIGN.md           设计文档：拦截机制、API 契约、实测数据、踩坑记录
  INSTALL.md          安装 / 校验 / 排障 / 救援手册
  build_apk.ps1       不依赖 Gradle 的打包脚本（aapt2 + javac + d8 + apksigner）
parent_app/           手机端家长 App（com.family.quizparent）
tv_view/              两个 Windows 便利脚本：看电视.bat、修复拦截.bat
题库/                 题库格式说明 + 一份示例题库
docs/                 AI 提示与语音方案的接口验证报告（Agnes / MiMo 实测）
```

---

## 快速开始

### 1. 构建

需要 **JDK 17+** 和 **Android SDK**（build-tools 里有 `aapt2`，platforms 里有 `android-33` 的 `android.jar`）。

```powershell
cd quiz_launcher
powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
```

脚本会自动找 SDK / JDK（也认 `ANDROID_HOME`、`JAVA_HOME`），产物是 `quizhome-debug.apk`。手机端同理，在 `parent_app` 目录跑同一个脚本。

### 2. 安装

```powershell
adb connect <电视IP>:5555
adb install -r quizhome-debug.apk
```

### 3. 开启无障碍（关键一步，覆盖安装后必须重做）

两种方式任选：

- **脚本**：把 `tv_view\修复拦截.bat` 里的 `TV_IP` 改成你的电视 IP，双击运行，看到「已修复」即可。它同时需要同目录下有 `adb.exe`（自备 platform-tools）。
- **命令行**：

```powershell
adb shell settings put secure enabled_accessibility_services com.family.quizlauncher/com.family.quizlauncher.QuizAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

也可以在电视「设置 → 无障碍 → 已下载的服务」里手动打开「答题家长控制」。

### 4. 家长页设置 PIN

电视上打开 App 的「家长设置」，第一次会要求设置 4 位 PIN。这个 PIN 同时是网页/手机端的登录凭据。

### 5. 题库导入

三选一，详见 [题库/README.md](题库/README.md)：

- 电视「家长设置 → 题库 / 局域网更新」页，页面会显示 `http://<电视IP>:8080/`，手机浏览器打开后粘贴 JSON、填 PIN、上传；
- `adb push 题库.json /sdcard/Android/data/com.family.quizlauncher/files/questions_import.json`；
- U 盘根目录放 `questions_import.json`。

不导入也能用：默认是内置的口算题。

### 6. AI 提示与语音（可选）

需要自己去服务商控制台申请 Key，然后填在**家长页**（加密存在设备上，推荐）或 `quiz_launcher/app/src/main/assets/ai_defaults.json`（会被打进 APK，不推荐）。不给 Key 时 AI 提示和语音都自动降级，不影响答题拦截。

### 7. 远程看画面 / 操作电视

`tv_view\看电视.bat` 用同目录的 `adb.exe` + `scrcpy.exe` 把电视画面投到电脑上（仓库不附带这些二进制，请从 [scrcpy 官方发布页](https://github.com/Genymobile/scrcpy/releases) 下载后放进同一目录）。改脚本顶部的 `TV_IP` 即可。

---

## 常见故障

| 现象 | 处理 |
|---|---|
| 电视黑屏 / 桌面起不来 | 先确认没有第三方 App 注册了 HOME（`adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME` 应该只有 3 个）。救援顺序见 [INSTALL.md](quiz_launcher/INSTALL.md) 第 6 节：拉起原桌面 → 重新启用原桌面 → 清空无障碍绑定 → 卸载本 App → 重启。 |
| 覆盖安装后不拦截了 | 系统会清掉无障碍绑定，重跑 `修复拦截.bat` 或上面两条 `settings put` 命令。 |
| 遮罩不弹 | 检查无障碍是否仍开启；另外无障碍只在**窗口切换**时收到事件，开服务前就停在前台的 App 不会被立刻拦，回桌面再进一次即可。 |
| 开机后拦截迟到十几秒 | 属于正常现象：开机早期无障碍服务还没被系统拉起，存在一段空窗。 |
| 网页打不开 | 服务只在「题库 / 局域网更新」页打开时运行，退出即关闭；端口默认 8080，被占用时依次尝试 8080–8089。 |

---

## 已知限制

- **不注册 HOME**：拦截依赖无障碍，家长必须手动开启；被系统关掉就完全失效（不会黑屏）。
- **覆盖安装会清空无障碍绑定**：每次 `adb install -r` 后都要重新开启。
- **低内存会被系统杀**：本机 1.8 GB RAM，服务被杀后到重新拉起之间约有 2 秒空窗。
- **明文 HTTP 仅限家庭局域网**：控制台没有 TLS，只应在自己家 Wi-Fi 内使用，不要把 8080 暴露到公网。
- **PIN 只是防误触级别**：4 位数字，孩子知道密码就能改配置，别当成安全边界。
- **答题可被猜中**：三选一的选择题反复试也能过；把题库换成需要动笔的题型更有效。

---

## 安全与隐私

- **仓库里没有任何 API Key**：`ai_defaults.json` 的 `llm` / `tts` 字段都是空字符串，Key 由使用者自己填在设备上；日志、UI、HTTP 响应里只会出现掩码。
- **题库、答题记录、统计只存在电视本地**（`/sdcard/Android/data/com.family.quizlauncher/files/`），不上传任何服务器。
- **不做联网上报**，除了你主动开启的 AI 提示 / 语音功能会请求对应服务商接口。
- 如果你曾经把 Key 写进过公开仓库或聊天记录，请立刻去控制台**轮换（吊销并重新生成）**——泄露过的 Key 删掉文件也不等于收回。

---

## 致谢

- 思路与电视精简生态来自上游 [cleanyourtv](https://github.com/Gathin23/cleanyourtv)。
- 远程投屏使用 [scrcpy](https://github.com/Genymobile/scrcpy)（Apache-2.0），仓库不附带其二进制。
- AI 提示与语音分别对接 Agnes 与 MiMo 的开放接口，实测记录见 [docs/](docs/)。
