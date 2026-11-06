# 安装、开启、关闭与救援（v2.9 无障碍拦截版 + 学科题库 + 掌握统计 + 局域网控制服务）

> 目标设备：Philips 58PUF7695_T3，Android 8.0.0（API 26）。
> 原始桌面（回滚锚点）：`com.ktcp.launcher/.activity.LauncherMainActivity`
> 本应用：`com.family.quizlauncher`（普通 App + 1 个 `AccessibilityService`）
> APK：`quiz_launcher/quizhome-debug.apk`（versionCode 12 / versionName 2.9，固定 `debug.keystore` 签名）
> v2.5 起自带内置默认 AI Key（`assets/ai_defaults.json`），装好即可在线提示，无需再录入。
> v2.6 起选项按字符数自适应字号与列数、高度随内容，长句不再出现省略号或半行裁切。
> v2.7 起提示按钮并入题干行、左上角有「‹ 返回」角标（遥控器可聚焦），底部不再常驻按钮与说明文字。
> v2.8 起家长设置页新增「局域网控制服务」开关：开启后电视常驻前台服务（通知「家长控制服务运行中」），手机同 Wi‑Fi 可调用 `/api/*`；关闭即释放端口、通知消失。
> v2.9 起题库支持 `subjects` 多学科（旧格式自动视为单学科），新增每题 `answered/correct/wrong` 统计与「掌握即移出」（`mastered.json` 备份、可恢复），并提供 `GET /api/stats`、`POST /api/remove-question|restore-question|reset-stats`。
>
> ## ⛔ 硬约束：不得修改电视的任何网络配置（血的教训）
>
> **只允许只读查询网络状态**，禁止执行任何会改变链路的命令，包括但不限于：
> `ip link set`、`ip addr`、`ifconfig ... up/down`、修改 MTU、修改 IP/网关、修改 DNS/代理、
> `svc wifi enable|disable`、`settings put global http_proxy`、`nmcli`/`cmd wifi` 写操作。
>
> 2026-11 的 v2.9 联调中，执行 `ip link set wlan0 mtu 1200` 后电视立即掉线，只能断电重启恢复。
> 排查网络问题一律用只读命令：`ip link show wlan0`（看 MTU 是否为重启后的 1500）、
> `ip addr show wlan0`、`ping`、`adb connect`、`curl -sS -o /dev/null -w "%{size_download}"`。
> 重启、改网络配置必须由用户手动操作，代理不得代为执行。
>
> **检查清单（每次联调前确认）**
> 1. [ ] 未执行任何 `ip link set` / `ip addr` / MTU / DNS / 代理 / WiFi 开关写命令
> 2. [ ] 电视离线时只报告，不尝试重启或修网络
> 3. [ ] 大响应验证用跨机请求（PC 与平板），并在本机 `nc` 之外单独记录字节数
>
> **升级后必须重新绑定一次无障碍**：实测 `adb install -r` 之后系统仍沿用旧的 `ServiceInfo`
> （`dumpsys accessibility` 里 `capabilities=8`、label 变成类名），v2.3 的自愈检查因此失效。
> 用下面的「关闭再开启」让它重新读取配置（最终值不变）：
>
> ```powershell
> adb -s 192.168.1.50:5555 shell settings put secure enabled_accessibility_services null
> adb -s 192.168.1.50:5555 shell settings put secure enabled_accessibility_services com.family.quizlauncher/com.family.quizlauncher.QuizAccessibilityService
> adb -s 192.168.1.50:5555 shell settings put secure accessibility_enabled 1
> ```
>
> 正确状态应为 `Service[label=答题家长控制, capabilities=9, ...]`（9 = 读取窗口内容 + 按键过滤）。

**一句话：** 装 APK → 在电视「设置 → 无障碍」里手动打开「答题家长控制」→ 从原桌面点受限视频 App 会先弹答题遮罩。**本应用不注册 HOME，不要设为默认桌面。**

---

## 0. 前提

- 电视与电脑在同一局域网，电视已开启网络 ADB（历史上是 `192.168.1.50:5555`，IP 可能变化）。
- 电脑有 `adb`（本机路径 `H:\Android\Sdk\platform-tools\adb.exe`）。
- 下面的命令都用 `-s 192.168.1.50:5555` 指定电视，避免误操作到模拟器。

```powershell
adb connect 192.168.1.50:5555
adb devices
```

看到 `192.168.1.50:5555   device` 再继续。

---

## 1. 安装

```powershell
adb -s 192.168.1.50:5555 install -r "<项目目录>\quiz_launcher\quizhome-debug.apk"
```

成功输出 `Success`。

常见失败：

| 报错 | 处理 |
| --- | --- |
| `INSTALL_FAILED_USER_RESTRICTED` | 电视：设置 → 安全 / 应用安装 → 允许安装未知来源应用，然后重试 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 清理电视空间后重试 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 装过不同签名的同包名版本：`adb shell pm uninstall com.family.quizlauncher` 后重装（会清空放行记录与密码） |
| `device offline` / `unauthorized` | 重新 `adb connect`；电视上出现授权弹窗时点「允许」 |

---

## 1.1 AI 提示：内置 Key 与手机填写（v2.5）

- 内置默认 Key 在 `app/src/main/assets/ai_defaults.json`（Agnes `llm`、MiMo `tts`），换 Key 只改这一处后重新构建。
- 优先级：家长设置 / 手机网页填写的 Key（Keystore 加密）＞ 内置默认；「清除 Key」只清用户填写的，随后回落到内置默认。
- 手机填写入口：家长设置 → 「AI 解析设置（手机填写）」，页面上会显示 `http://<电视IP>:8080/ai`。
  手机浏览器打开该地址，输入家长密码（PIN）后可「保存」「测试连接」「清除 Key」；电视端页面关闭即停服务。
- 「测试连接」：Agnes 真实发一道测试题（约 15~60 秒，返回可解析 JSON + 中文），MiMo 真实合成一小段语音（约 1~5 秒，返回音频字节数）。
- 想看当前状态：家长设置页显示「已内置（掩码）」或「已配置（掩码）」，以及最近一次测试结果与时间。
- 网页与日志都只出现掩码（前后各 4 位），不会回显完整 Key。

---

## 2. 开启无障碍服务（必须由家长在电视上手动操作）

**不要用 adb 远程开启**——无障碍授权应当由设备主人在系统界面上确认。

> **本机已知问题（v2.2 已规避）**：这台 ROM 删掉了 `com.android.providers.userdictionary`，
> 系统设置的无障碍页 `Settings$AccessibilitySettingsActivity` 一打开就崩（`SecurityException: Failed to find provider user_dictionary`）。
> 所以 v2.2 的「开启无障碍（查看方法）」按钮不再尝试跳转，而是直接弹出下面 2.1 的手动路径 + 可复制的 adb 命令。
> 若电视上点开系统无障碍页黑一下又回到桌面，就是这个问题，不是本应用崩。

### 2.1 操作路径

1. 电视回到原桌面，按遥控器进入「设置」。
2. 找到「无障碍」/「辅助功能」/「更多设置 → 无障碍」（不同定制系统位置略有差异）。
3. 在「已下载的服务」/「服务」列表里找到 **「答题家长控制」**。
4. 选中它，按确定键打开开关，弹出确认框时选「允许 / 确定」。
5. 返回，打开本应用（桌面里的「答题家长控制」）→「家长设置」。

### 2.2 预期现象

- 家长设置页顶部显示绿色 **「无障碍服务：运行中（拦截已生效）」**。
- 此时按 Home 回原桌面，点任意一个受限视频 App（默认列表：未来电视、银河奇异果、小电视、系统视频演示、本地视频播放器）→ **立刻盖一层全屏答题页**，标题会写「《应用名》需要答题才能打开」。
- 答对到及格线（默认 4/5）→ 遮罩消失，应用可以继续看；并在 `minutes`（默认 30 分钟）内不再拦这个 App。
- 答错/按返回 → 遮罩消失并回到原桌面，受限 App 不会被留在前台。

若显示红色 **「未开启（受限应用不会被拦截）」**：说明系统没记住授权，回到第 1 步重新打开一次。

### 2.3 只读核查（不改任何设置）

```powershell
# 应用是否装上
adb -s 192.168.1.50:5555 shell pm list packages | findstr quizlauncher

# 系统里记录了哪些无障碍服务（只读）
adb -s 192.168.1.50:5555 shell settings get secure enabled_accessibility_services

# 无障碍总开关
adb -s 192.168.1.50:5555 shell settings get secure accessibility_enabled

# HOME 候选必须仍然是 3 个
adb -s 192.168.1.50:5555 shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

期望：

- `pm list packages` 出现 `package:com.family.quizlauncher`；
- 手动开启后 `enabled_accessibility_services` 里包含 `com.family.quizlauncher/com.family.quizlauncher.QuizAccessibilityService`；
- HOME 候选始终只有 `com.ktcp.launcher`、`com.tpv.xmic.oobe`、`com.android.settings/.FallbackHome` **三个**。

---

## 3. 日常使用

- **家长设置入口**：原桌面里的「答题家长控制」图标。首次进入要求设置 4 位家长密码（遥控器数字键盘，输入 4 位后按「确定」），之后每次进入都要输入。
- 设置页可调：
  - 放行时长（**1–120 分钟**：滑杆最左一格是 1 分钟，往右每格 +5，最右 120 分钟）；
  - 及格线（1–5 题，每轮共抽 5 题）；
  - 受限应用列表（点一行切换「受限（红）/ 放行」）；
  - 「题库管理（局域网更新）」、「修改家长密码」、「答题记录」；
  - 「测试答题」：练习模式，**不写放行时间、不记录成绩**，用来确认题目和按键是否正常；
  - 「打开无障碍设置」：直接跳到系统无障碍页，便于重新开启。
- 遮罩内操作：方向键选答案 → 确定键提交 → 每轮 5 题；页面上会显示是哪个 App、通过后放行多少分钟、当前是否已有放行。返回键/Home 键会直接把你送回原桌面。
- **放行是按应用独立的**：在 A 应用上答对只解锁 A，B 应用仍要答题。
- **放行只到关机为止**：电视关机/重启后所有放行立即作废，哪怕还没到时长；待机休眠不算重启，回来仍然有效。这是刻意设计（v2.1），避免「昨天答了一次、今天开机接着看」。

### 换题库（局域网，不用重装）

1. 家长设置 →「题库管理（局域网更新）」。
2. 页面大字显示 `http://<电视IP>:8080`（被占用则顺延 8081…8089，以页面为准）。
3. 手机连同一个 Wi-Fi，浏览器打开该地址，输入 4 位 PIN。
4. 选择题库 JSON 文件或粘贴 JSON →「上传并校验」。
5. 通过后自动：备份旧题库为 `questions.bak` → 写新题库 → 立即生效。
6. 传错了点「回退到上一版」。
7. 退出该页 HTTP 服务立即关闭（不常驻）。

备用方式 A（adb push）：

```powershell
adb -s 192.168.1.50:5555 push questions_import.json /sdcard/Android/data/com.family.quizlauncher/files/questions_import.json
```

再次打开家长设置页会弹「发现待导入题库」，选「导入」。

备用方式 B（U 盘）：把 `questions.json` / `questions_import.json` 放 U 盘根目录，插到电视，打开家长设置页按提示导入。

JSON 结构见 `DESIGN.md` 第 7 节。

---

## 4. 临时关闭拦截（孩子要自己用、或出现异常时）

### 4.1 推荐：系统界面里关（可逆、不影响其他无障碍服务）

电视「设置 → 无障碍 → 已下载的服务 → 答题家长控制」→ 关闭开关。

关闭后本应用完全不拦截，受限 App 照常打开；家长设置页会显示红色「未开启」。

### 4.2 命令行关（谨慎）

`enabled_accessibility_services` 是一个冒号分隔的**总表**，直接置空会把电视上其他无障碍服务一起关掉。先读再改：

```powershell
adb -s 192.168.1.50:5555 shell settings get secure enabled_accessibility_services
```

如果原值只有本应用一项（或为空），可以安全地置空：

```powershell
adb -s 192.168.1.50:5555 shell settings put secure enabled_accessibility_services ""
adb -s 192.168.1.50:5555 shell settings put secure accessibility_enabled 0
```

如果原值里还有别的服务，请把本应用那一段删掉后，把剩余部分原样写回，不要直接置空。

### 4.3 强制停止

```powershell
adb -s 192.168.1.50:5555 shell am force-stop com.family.quizlauncher
```

注意：实测部分系统在 `force-stop` 后会连带清空 `enabled_accessibility_services`，即服务被系统停用，需要在系统设置里重新开启。

---

## 5. 彻底卸载

顺序建议：先关无障碍 → 再卸载。

```powershell
# 1) 系统设置里关闭无障碍（推荐），或按 4.2 的谨慎方式处理
# 2) 卸载
adb -s 192.168.1.50:5555 uninstall com.family.quizlauncher
```

U 盘 / adb 安装的版本也可以在电视「设置 → 应用 → 答题家长控制 → 卸载」里手动卸载。

卸载后会一并删除：家长密码、受限列表、放行记录、答题记录、导入的题库（`Android/data/com.family.quizlauncher/`）。

---

## 6. 黑屏 / 异常救援（本机曾实测）

本应用 v1.2 起**不注册 HOME**，因此不会像 v1.0/v1.1 那样把 HOME 候选变成 4 个、拖垮 `system_server`。若仍出现电视黑屏或桌面起不来，按下面顺序处理，任何一步恢复即可停：

```powershell
# 1) 直接拉起原桌面
adb -s 192.168.1.50:5555 shell am start -n com.ktcp.launcher/.activity.LauncherMainActivity

# 2) 把默认桌面改回原桌面
adb -s 192.168.1.50:5555 shell cmd package set-home-activity com.ktcp.launcher/.activity.LauncherMainActivity

# 3) 原桌面包若被禁用，重新启用
adb -s 192.168.1.50:5555 shell pm enable com.ktcp.launcher

# 4) 关闭本应用的拦截（先看 4.1 / 4.2）
adb -s 192.168.1.50:5555 shell settings put secure enabled_accessibility_services ""

# 5) 仍异常时卸载本应用（不会影响系统桌面）
adb -s 192.168.1.50:5555 uninstall com.family.quizlauncher

# 6) 最后才重启
adb -s 192.168.1.50:5555 reboot
```

### 6.1 「只有设置界面、没有桌面」（2026-11-04 实测）

原因不是黑屏，而是原桌面 `com.ktcp.launcher` 的任务栈停在自己的**应用管理页**（`AppManagerActivity`），
`LauncherHomeActivity` 只是压在同一个任务栈下面，所以看起来"没有桌面"。

```powershell
# 看真实前台与窗口焦点（不要看截图猜）
adb -s 192.168.1.50:5555 shell dumpsys activity activities | findstr /i "mResumedActivity"
adb -s 192.168.1.50:5555 shell dumpsys window windows | findstr /i "mCurrentFocus ACCESSIBILITY_OVERLAY"

# 拉起桌面（实测这一条即可恢复，前台变成 LauncherHomeActivity）
adb -s 192.168.1.50:5555 shell am start -n com.ktcp.launcher/.activity.LauncherMainActivity
```

若 `mCurrentFocus` 里出现 `ACCESSIBILITY_OVERLAY`，说明是本应用的遮罩还盖着：v2.2 起可以遥控器
**连按 3 次返回键**强制回桌面，或**等 2 分钟无操作**自动撤销；旧版本则用 `am force-stop` 后重设无障碍开关。

**注意**：不要用 `cmd package resolve-activity` 排查，本 ROM 在这条命令上会抛 `IndexOutOfBoundsException`。改用：

```powershell
adb -s 192.168.1.50:5555 shell dumpsys activity activities | findstr /i "mResumedActivity"
adb -s 192.168.1.50:5555 shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

正常状态：前台是 `com.ktcp.launcher/...`，HOME 候选 3 个。

**如果 ADB 连不上**：

1. 断电 10 秒再上电，观察是否回到原桌面（本应用不会阻止开机）。
2. 遥控器盲按 Home / 返回；能进系统设置就去「应用 → 默认应用 → 主屏幕」选回原桌面，再去「无障碍」关掉「答题家长控制」。
3. 不要恢复出厂设置（会清空电视数据）；先想办法重新开启网络 ADB，再走上面的命令。

---

## 7. 构建（可选）

本机已产出 `quizhome-debug.apk`，正常无需重建。改源码后：

```powershell
powershell -ExecutionPolicy Bypass -File "<项目目录>\quiz_launcher\build_apk.ps1"
```

脚本不依赖 Gradle，直接调用本机 SDK 的 `aapt2 / javac / jar / d8 / zipalign / apksigner`，产物为 `quiz_launcher\quizhome-debug.apk`。

**构建内置安全闸门**：打包后会执行

```powershell
aapt2 dump xmltree --file AndroidManifest.xml quizhome-debug.apk | Select-String "category.HOME"
```

一旦命中就直接抛错、拒绝产出 APK；同时检查无障碍服务已声明。因此不会误打包出会触发本机 HOME 崩溃的版本。

也可以把 `quiz_launcher` 作为 Gradle 工程用 Android Studio 打开（`AGP 8.7.3`，`compileSdk 33`，`minSdk 26`，`targetSdk 30`）；本机未装独立 Gradle，故未附 `gradlew`。

## 8. v2.9 使用要点（学科题库 / 掌握统计）

- 题库格式：整库 `{"version":3,"title":"...","subjects":[{"key","name","questions":[...]}]}`；只含 1 个学科的文件按 key 合并替换该学科，其他学科保留；旧格式（顶层 `questions` 或纯数组）视为单学科，学科名取 `title`。上传前会备份 `questions.bak`，可用「回退上一版」恢复。
- 题目 `id`：缺省时按规范化题干（去首尾空白、合并空白、转小写）做 SHA-256 取前 8 字节生成，跨设备稳定；手写 id 时请保证全库唯一。
- 家长页「答题与掌握」：拖动阈值（1–10，默认 3）、开关「掌握后自动移出」、点学科行切换启用（全部未启用时视为全部启用）。
- 「题库统计」页：按学科汇总题数/已掌握/已移出，逐题显示 `答 N · 对 N · 错 N`；点某行可「移出此题 / 恢复此题」，底部可「恢复全部已移出」「重置统计」。移出只维护 id 名单，不改 `questions.json`，因此回退题库不会丢统计与备份。
- 手机端联调：`GET /api/summary` 的 `subjects` 与 `stats`、`GET /api/stats`、`POST /api/settings`（`masteredThreshold`/`autoRemove`/`enabledSubjects`）、`POST /api/remove-question|restore-question|reset-stats` 字段与 v2.9 契约一致；写操作需 `X-Pin`。

## 9. 跨机大响应（电视 → 手机/PC）丢包说明与验证

这台电视**发送方向**对大报文丢包（ping 1400/700 字节 100% 丢，650/600/500 字节基本不丢），且无 ICMP 反馈，所以「整包一次写」时客户端只收到响应头、body 为 0 字节；本机 `nc 127.0.0.1` 始终正常。重启后 `ip link show wlan0` 已是 `mtu 1500`，**不是上次 MTU 1200 的残留**。

已做的应用层规避（详见 `DESIGN.md` §13.7）：MSS=256、`TCP_NODELAY`、每条连接独立线程、`Accept-Encoding: gzip` 时压缩、≤700 字节整包写 / 更大会 256 字节 +150ms 分块、`Connection: close`、`Cache-Control: no-store`、UTF-8。

跨机验证（PC → 电视，`curl --compressed`）：

```powershell
curl.exe -sS --compressed -o NUL -w "%{http_code} %{size_download} %{time_total}`n" http://192.168.1.50:8080/api/summary
curl.exe -sS --compressed -o NUL -w "%{http_code} %{size_download} %{time_total}`n" http://192.168.1.50:8080/api/stats
```

实测结果：`/api/summary` 5/5 成功（635B 压缩 / 2904B 原文，约 0.3s）；`/api/stats` 3/5（1015B，约 0.53s）；`/` 3/3（1733B，约 0.97s）。链路仍会随机丢包，**手机端必须把「空响应 / body 截断」当作可重试错误（建议重试 2 次、间隔 1s）**，不要设置 `Accept-Encoding: identity`。

## 10. 开机广告只读排查结论（2026-11-05，未执行任何 disable）

- 包名里没有任何 `boot` / `advert` / `promotion` / `recommend` 字样；`/system/media` 只有 `bootanimation.zip` 与 `audio`，`/data/local` 只有 `tmp`，`/cache` 只有 `backup`、`backup_stage`、`lost+found`、`recovery` —— **没有落地的开机广告视频/素材**。
- 真正可疑的是预装合作方包 `com.tpv.launcherpartner`（`/system/app/ebony2K19_Tencent_OpenService_nf/...apk`，Tencent OpenService），声明了 `BOOT_COMPLETED` 接收器；另有 `cn.com.goldroid.tv.appstore.philips.hisiv810`（应用商店）、`com.ktcp.autoupgrade`（升级）。
- 系统设置里没有「开机广告」开关（`settings list global|secure` 里含 `ad` 的只有 `adb_enabled`、`airplane_mode_*` 这类）。
- 三类清单与建议命令见交付报告；**本次未执行任何 `pm disable/enable`**。

## 11. v3.0 使用要点（U 盘拦截 / 严格模式）

> 版本：`versionCode 13` / `versionName 3.0`。APK 构建与安装方式不变（第 1 节）。
> **每次 `install -r` 后仍要按文首的「关闭再开启无障碍」写回一次配置**，否则可能沿用旧的 ServiceInfo。

- **U 盘视频拦截**：真机取证 U 盘视频实际由厂商媒体中心 `com.tpv.xmic.contentbrowser.ebony2k15` 播放
  （`VideoPlayerActivity`，接收 `MEDIA_MOUNTED`），v2.9 名单里没有它，因此完全漏拦。
  v3.0 已把它与系统 USB 自动播放器 `org.droidtv.usbautoplayer` 加入默认受限名单，
  并对已装系统播放器做关键词兜底（只收播放器，排除 `providers/storage/vold` 等组件）。
  升级老设备时会做一次性迁移，把新增包并进现有名单；家长已手动关掉的老 5 项不会被强行改回。
- **严格模式（家长页 / 答题记录页）**：
  - 家长页新增「严格模式（只放行白名单）」面板：开关、「编辑白名单」、「恢复默认白名单」；
    答题记录页右上角也有「严格模式（需 PIN）」按钮。
  - 开启后，白名单之外的所有前台应用都要答题；关闭后恢复按受限名单拦截。
  - 白名单默认含桌面、系统设置、SystemUI、安装器、OOBE、自身、`com.android.providers.*`、输入法；
    这些同时还是内置硬白名单，**即使白名单被删空也不会把电视锁死**。
  - 家长页会在白名单缺少桌面/设置时给出黄色警告；仍有 3 次返回键逃生与 120 秒看门狗。
- **API**（复用 v2.9 的 `X-Pin` 鉴权）：
  ```powershell
  # 只切严格模式
  curl.exe -sS --compressed -X POST -H "X-Pin: <PIN>" -H "Content-Type: application/json" `
    -d '{"enabled":true}' http://192.168.1.50:8080/api/strict
  # 或与阈值/学科一起写
  curl.exe -sS --compressed -X POST -H "X-Pin: <PIN>" -H "Content-Type: application/json" `
    -d '{"strictMode":true,"allowList":["com.ktcp.launcher","com.android.settings","com.android.providers.*"]}' `
    http://192.168.1.50:8080/api/settings
  # 回显在 /api/summary.stats
  curl.exe -sS --compressed http://192.168.1.50:8080/api/summary
  ```
  `allowList` 为空数组会被拒绝（`400`），避免「一个都不放行」。
