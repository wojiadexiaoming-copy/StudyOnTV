# 电视答题家长控制（QuizHome）设计说明

> 版本：v2.9（v2.8 + 多学科题库、每题答题统计与「掌握即移出」、`/api/stats` 等扩展、电视端 UI 卡片化重做）；v2.8 = v2.7 + 常驻前台控制服务与手机端 `/api/*`：摘要、设置、放行、撤销、清记录；家长页可开关服务；v2.7 = v2.6 + 提示并入题干行、左上角返回角标、删除底部按钮与说明行，选项视口由 346px 增至 482px；v2.6 = v2.5 + 选项按字符数自适应，彻底消除文字裁切与省略号；v2.5 = v2.4 + 手机网页填 Key / 测试连接 + 内置默认 Key；v2.4 = v2.3 无障碍拦截版 + 安全区布局 + 英文原文大小写 + 音频焦点/静音 + AI 提示与语音 + 答错复盘页；v1.2 为移除 HOME 注册的安全版
> 目标：Philips 58PUF7695_T3 / Android 8.0.0（API 26）
>
> **重要事故结论（2026-11-04）**：本机 ROM 的 `PackageManagerService.chooseBestActivity`
> 在 HOME 候选达到 4 个时抛 `IndexOutOfBoundsException: Index: 3, Size: 3`，
> `system_server` 在 `startHomeActivityLocked` 中崩溃，表现为开机黑屏。
> 因此 v1.2 起本应用**不再注册 `CATEGORY_HOME`**；v2.0 继续遵守这条铁律，
> 改用「普通 App + AccessibilityService」实现真正的前台应用拦截。
> 在本机上，任何第三方 Home 都会把候选数从 3 变成 4，都会触发该系统 bug；
> 应用内的 try/catch 无法阻止系统进程崩溃。

## 1. 目标与约束

给 Philips 58PUF7695_T3（Android 8.0.0 / API 26 / 国内定制系统）做一个轻量的「答题家长控制」应用，让"看动画片"先经过答题关卡。

> v1.2 说明：该机 ROM 不允许第三方 Home（第 4 个 HOME 候选会让 `system_server` 崩溃），因此本应用不注册 HOME。
> v2.0 说明：改为普通 App + `AccessibilityService`，监听前台窗口变化，在受限应用上盖一层全屏答题遮罩。
> 这样孩子无论从原桌面、最近任务还是语音助手打开受限 App，都会被拦截；代价是必须由家长手动开启无障碍服务。

硬约束：

- 不能 root，不能改系统分区，不能预装。
- 只使用公开 API（`minSdk 26`），不引入 AndroidX、GMS、Compose。
- 电视只有遥控器：所有交互必须能用方向键 + 确定键完成。
- 内存只有 1.84GB，可用约 0.77GB，界面和进程要尽量轻。
- 必须随时能退回原桌面 `com.ktcp.launcher/.activity.LauncherMainActivity`。

## 2. 选型：为什么用「答题关卡」而不是「禁用预装」

| 维度 | 本方案：答题关卡（v1 桌面入口 / v2 无障碍遮罩） | `pm disable-user` 禁用预装视频 App |
| --- | --- | --- |
| 可逆性 | v2 只需关闭无障碍服务或卸载 App，完全恢复；不碰系统包 | 需要逐条 `pm enable`，忘了包名很难恢复 |
| 生效范围 | v2 覆盖所有启动路径（桌面 / 最近任务 / 语音），但需家长手动开启无障碍 | 全局禁用，但可能连带禁用系统组件导致异常 |
| 风险 | 不改系统状态；最坏是服务被关掉导致不拦截（不会黑屏） | 定制系统可能把被禁用的 App 当作依赖，出现黑屏/无法开机 |
| 家长控制 | 可随时勾选受限 App、调时长与及格线 | 只能整体禁用/启用，粒度粗 |
| 孩子体验 | 有明确目标（答题换时间），可引导学习 | 直接"应用消失"，容易引发对抗且无教育意义 |
| 升级/OTA | 完全不影响 | 系统 OTA 后可能恢复预装或被覆盖 |
| 实现成本 | 单进程 + SharedPreferences，APK 约 71KB | 需要持续 ADB 维护，且每次恢复都依赖电脑 |

结论：答题关卡属于"软约束"，可逆、可控、成本低，适合家庭场景。v1.x 的缺点是拦截只限于从桌面的入口
（已在 v1.2 移除 HOME 注册，见第 4.1 节）；v2.0 改用无障碍遮罩后，只要服务处于开启状态，所有启动路径都会被拦。

## 3. 模块划分

包名：`com.family.quizlauncher`，全部为 Java（避免额外引入 Kotlin 运行时）。

```
app/src/main/java/com/family/quizlauncher/
├── ParentActivity.java      // 主界面：家长设置（服务状态、时长、及格线、受限应用、题库/记录入口）
├── QuizAccessibilityService.java // v2 拦截核心：窗口监听、白名单、防抖、遮罩生命周期
├── QuizOverlay.java         // v2 全屏答题遮罩（TYPE_ACCESSIBILITY_OVERLAY）
├── OverlayRootView.java     // 遮罩根视图：吞返回键/HOME 键，防按键穿透
├── BootCleanupReceiver.java // v2.1 开机清空放行记录（第二道保险）
├── QuizActivity.java        // 答题关卡（遮罩失败时的降级 Activity + 测试答题）
├── HomeActivity.java        // 旧版应用网格，仅保留内部实现，不再作为入口
├── QuestionBankActivity.java// 题库管理：局域网 HTTP 服务 + 题库状态
├── QuestionHttpServer.java  // 手写 ServerSocket HTTP 服务（仅局域网；v2.5 增加 /ai 页面与 save/test/clear）
├── AiSettingsActivity.java  // v2.5：AI 解析设置（手机填写）页，随页面启停局域网服务
├── ai/ApiKeys.java          // v2.5：Key 唯一入口（用户 Key > 内置默认，集中读 assets/ai_defaults.json）
├── QuestionImporter.java    // 题库校验、备份、回退、U 盘/adb 导入
├── RecordsActivity.java     // 答题记录（最近 60 条）
├── PinDialog.java           // 遥控器数字键盘密码框（3×4，校验/设置两种模式）
├── Prefs.java               // SharedPreferences 唯一读写入口（含每包放行）
├── QuestionBank.java        // 题库加载（外部文件 → 自定义 → assets）
├── Question.java            // 题目模型
├── AppEntry.java            // 应用项模型
├── AppRepository.java       // 枚举可启动应用 + 默认受限名单
├── Launcher.java            // 启动第三方 App / 回桌面（带兜底 Intent）
└── Ui.java                  // dp 换算
```

资源与数据：

- `res/layout/activity_parent.xml`：服务状态与引导、两个 SeekBar + 应用开关列表 + 题库/密码/记录入口。
- `res/layout/overlay_quiz.xml`：v2 全屏遮罩（应用名、放行说明、题目、选项、结果页）。
- `res/xml/accessibility_service_config.xml`：只监听 `typeWindowStateChanged`，`canRetrieveWindowContent=false`，不限制 `packageNames`。
- `res/layout/activity_quiz.xml`：降级答题 Activity / 测试答题。
- `res/layout/activity_bank.xml`：大字显示局域网地址、服务状态、题库版本/题数、操作按钮。
- `res/layout/dialog_pin.xml` + 动态生成键盘。
- `assets/questions.json`：内置 36 道小学算术题。
- `assets/ai_defaults.json`：v2.5 内置默认 Key（Agnes / MiMo），集中一处便于替换；用户填写的 Key 优先，清除后回落到这里。
- `res/layout/activity_ai_settings.xml`：v2.5 AI 解析设置页（局域网地址、状态、清除 Key）。
- `drawable/tile_bg.xml`、`option_bg.xml`、`button_bg.xml`：焦点态高亮（蓝框），适配遥控器焦点导航。

## 4. 与系统 Home 的关系（v2 的核心约束）

### 4.1 铁律：不注册 HOME / DEFAULT

`AndroidManifest.xml` 里只有 `ParentActivity` 带 `ACTION_MAIN + CATEGORY_LAUNCHER`（普通应用入口），
**没有任何 `CATEGORY_HOME` / `CATEGORY_DEFAULT` 组合**。构建脚本 `build_apk.ps1` 在打包后会执行：

```powershell
aapt2 dump xmltree --file AndroidManifest.xml quizhome-debug.apk | Select-String "category.HOME"
```

一旦命中就直接 `throw`，拒绝产出 APK。

原因（2026-11-04 真机黑屏事故）：

```text
*** FATAL EXCEPTION IN SYSTEM PROCESS: android.ui
java.lang.IndexOutOfBoundsException: Index: 3, Size: 3
  at com.android.server.pm.PackageManagerService.chooseBestActivity(PackageManagerService.java:6613)
  at com.android.server.am.ActivityManagerService.startHomeActivityLocked(ActivityManagerService.java:4091)
```

本机 ROM 的 HOME 候选固定为 3 个（`com.ktcp.launcher`、`com.tpv.xmic.oobe`、`com.android.settings/.FallbackHome`）。
任何第三方注册 HOME 都会变成 4 个，`chooseBestActivity` 内部按 3 个元素的数组取下标 3，直接越界。
崩溃发生在 `system_server`，应用内 try/catch 完全无效。

### 4.2 那怎么拦截应用？

v2 用 `AccessibilityService` 监听 `TYPE_WINDOW_STATE_CHANGED`，从 `AccessibilityEvent.getPackageName()` 拿前台包名。
这是 API 26 上普通应用能拿到的、最接近「全局前台应用」的信号，不需要 root，也不需要 `SYSTEM_ALERT_WINDOW`。

- 服务配置 `canRetrieveWindowContent=false`：只读事件里的包名，不抓取界面内容，权限最小。
- `packageNames` 故意留空：受限名单是家长在运行时随时勾选的，写死包名过滤会漏掉新加入的 App；服务本身只做一次包名字符串比较，成本极低。
- 服务只监听 `typeWindowStateChanged`，不加 `typeWindowContentChanged`：内容变化事件频率高、对拦截没有帮助。

### 4.3 HOME 键与「返回桌面」

应用自己不注册 HOME，但可以「请求」系统回桌面：

```java
Intent home = new Intent(Intent.ACTION_MAIN);
home.addCategory(Intent.CATEGORY_HOME);
home.addFlags(FLAG_ACTIVITY_NEW_TASK);
startActivity(home);
```

失败再显式启动本机原桌面 `com.ktcp.launcher/.activity.LauncherHomeActivity`。
`startActivity` 会触发系统的 HOME 解析，但此时候选仍是 3 个，不会触发上述 ROM bug。

## 5. 拦截状态机（v2）

```
                 ┌──────────────────────────────┐
                 │ SERVICE_OFF（无障碍未开启）    │  受限 App 照常打开（不拦截）
                 └───────────────┬──────────────┘
              家长在系统设置里开启 │
                                 ▼
                 ┌──────────────────────────────┐
                 │ MONITORING（监听窗口事件）     │
                 └───────┬───────────────┬──────┘
    前台包在受限名单 │               │ 前台包在白名单 / 非受限 / 放行有效
    且无有效放行     │               │
                （500ms 防抖）        └──► 忽略，继续监听
                     ▼
         ┌────────────────────────────┐
         │ OVERLAY_QUIZ（全屏答题遮罩） │  WindowManager
         │ TYPE_ACCESSIBILITY_OVERLAY │  吞 BACK/HOME，焦点在选项上
         └───┬────────────┬───────────┘
    答对达标 │            │ 未通过 / 返回键 / 取消
             ▼            ▼
   ┌─────────────────┐  ┌──────────────────────┐
   │ GRANT_PASS      │  │ DISMISS + goHome     │
   │ pass_until_<pkg>│  │ 回原桌面，不留受限 App│
   │ = now + minutes │  └──────────────────────┘
   └────────┬────────┘
            ▼
   移除遮罩 → 受限 App 恢复焦点，可继续看
            ▼
   到期后（已在前台则定时复查）→ 重新 OVERLAY_QUIZ
```

关键规则：

- 每轮固定抽 5 题（题库不足 5 题则取全部），答对 `required` 题即通过（默认 4/5）。
- 通过：写 `pass_until_<包名> = now + minutes`，**按应用单独放行**，不再是一次解锁全部。
- 未通过 / 取消 / 返回键：移除遮罩并调用 `Launcher.goHome()`，孩子回到桌面而不是留在受限 App 里。
- 遮罩窗口添加失败（`addView` 抛异常）：`try/catch` 后降级启动 `QuizActivity`；Activity 也起不来时直接回桌面。
- 服务被系统杀掉或家长关闭无障碍：`onUnbind` / `onInterrupt` / `onDestroy` 清理遮罩和所有延时任务；
  重新连接时 `onServiceConnected` 重建状态，旧遮罩随进程消亡，不会残留。
- 放行到期时如果孩子一直停留在该 App，服务会在放行时间点后复查一次（单次 `postDelayed`，不轮询），到期即重新弹遮罩。
- **放行只在本开机会话内有效（v2.1）**：`grantPass` 同时写三份数据——绝对到期时间 `pass_until_<pkg>`、
  开机计数 `pass_boot_<pkg>`（`Settings.Global.BOOT_COUNT`）、计时基线 `pass_elapsed_<pkg>`
  （`SystemClock.elapsedRealtime()`）。判定有效时，只要开机计数变了，或 `elapsedRealtime` 比记录时还小
  （说明计时器归零过），就直接认定是上一次开机留下的记录，**清空全部 `pass_until_*` 并拒绝放行**。
- `BootCleanupReceiver`（`BOOT_COMPLETED`）只是第二道保险：被 `am force-stop` 过的应用重启后收不到开机广播
  （实测确认），所以主逻辑必须在 `Prefs` 里，不能依赖广播。
- 待机休眠不会让 `elapsedRealtime` 归零，所以「待机 30 分钟回来」仍算同一个会话，这是期望行为。

### 5.0 遮罩逃生口（v2.2，防止「只有遮罩没有桌面」）

1. **看门狗**：遮罩出现后连续 120 秒没有任何按键/触摸，自动撤销遮罩并 `Launcher.goHome()`。
   计时只在遮罩自己收到真实按键/触摸时重置，所以「窗口盖在最上层但按键进不来」这种卡死场景一定会被超时解开。
2. **可见的关闭路径**：答题界面底部常驻「回桌面」按钮，遥控器方向键可达；结果页也有「回桌面」。
3. **服务侧 3 次返回键逃生**：配置 `canRequestFilterKeyEvents="true"`，`onKeyEvent` 在遮罩显示期间
   统计 3 秒内的返回键连按，满 3 次直接 `QuizOverlay.dismissActive()` + `Launcher.goHome()`。
   这条路径走按键过滤，**即使遮罩窗口丢了焦点也能生效**。
4. **异常自撤销**：`addView` 失败 → 撤残留窗口并降级 `QuizActivity` → 再兜底回桌面；
   `onInterrupt` / `onUnbind` / `onDestroy` 都会清理遮罩与全部延时任务。
5. **v2.3 遮罩自愈（关键）**：实测本机 ROM 的低内存杀手会把本应用进程直接杀掉
   （`am_kill ... lowram app pss is 23197`），进程一死遮罩就消失，而目标 App 仍在前台。
   - 服务重连后 600ms 主动查一次前台（`getWindows()` + 焦点窗口包名），受限包还开着且未放行 → 立刻补弹；
     顺带解决了"服务开启时目标 App 已在前台不触发拦截"的老问题。
   - 遮罩存活期间每 1.5 秒复查一次，遮罩意外消失且确认目标包仍在前台才补弹；
     查不到前台或已切到别的窗口就停止守护，**绝不把答题遮罩误盖到桌面上**。
   - 为此无障碍配置需要 `canRetrieveWindowContent="true"` 与 `flagRetrieveInteractiveWindows`（只读取窗口包名，不读取内容）。

### 5.1 硬白名单（永不拦截）

| 类别 | 包名 |
| --- | --- |
| 自身 | `com.family.quizlauncher`（防自锁：否则遮罩/降级 Activity 会无限触发） |
| 原桌面 | `com.ktcp.launcher`、`com.android.tv.launcher`、`com.google.android.tvlauncher` |
| 系统设置 | `com.android.settings`、`com.android.tv.settings` |
| SystemUI | `com.android.systemui` |
| 包安装器 | `com.android.packageinstaller`、`com.google.android.packageinstaller` |
| OOBE | `com.tpv.xmic.oobe` |
| 系统 | `android` |
| 输入法 | 由 `InputMethodManager.getEnabledInputMethodList()` 动态获取，避免不同电视输入法包名不同 |

### 5.2 防抖与内存

- 窗口事件先记到 `pendingPackage`，`Handler.postDelayed(..., 500ms)` 后再判断一次；
  判断时要求「包名仍是当前前台」「遮罩未显示」，避免连续事件反复弹窗。
- 遮罩只持有一份 View + 5 道题 + 一个 Handler，不缓存 Bitmap、不做常驻轮询。
- 服务本身无数据库、无线程池；HTTP 服务仍只在题库页可见时存在。

### 5.3 选项自适应（v2.6）

v2.5 及以前：选项固定「≤3 个一行、否则两列」，行高固定 46/64dp，`maxLines=2` + `ellipsize=END`。
真机反馈：长句（如 `They are sharing books and toys.`）在 2 列里被压成两行后仍放不下，末尾出现省略号或半行裁切。

v2.6 改为「先量字数，再决定字号/列数/换行」：

1. **显示宽度加权**：`displayWeight = Σ(char >= 0x2E80 ? 2 : 1)`（中文/全角按 2，英文/数字/空格按 1），
   取当前题全部选项的最大值 `maxWeight`。
2. **字号档位**：`≤8 → 30sp`、`≤14 → 26sp`、`≤22 → 22sp`、`更长 → 20sp`。
3. **列数**：`≤8 → 3 列`、`≤14 → 2 列`、` >14 → 1 列全宽`；列数不超过选项数，末行空位用等权重 `View` 占位。
4. **换行**：按钮一律 `setSingleLine(false)`、`setMaxLines(Integer.MAX_VALUE)`、`setEllipsize(null)`，
   按钮/行/选项容器全部 `wrap_content`，**任何情况下都不会出现 `…` 或半行**。
5. **最小高度**：3 列 56dp、2 列（短选项 56dp / 长选项 46dp）、1 列 48dp；只当可点区域下限，不限制内容高度。
6. **高度分配**：题干 `ScrollView` 仍是 `wrap_content`（长题干自身滚动），选项 `ScrollView` 为
   `layout_weight=1` 拿走剩余全部高度；选项总高超出时由该滚动区滚动，焦点移到哪一项就自动滚到哪一项，
   最后一排再按下方向键仍按 `lastRowButtons` 规则跳到「提示」按钮（v2.7 起位于题干行右侧）。

实测（电视 1080p / 320dpi，2px = 1dp）：6 个 50 字上下英文选项 = 1 列 6 行，每行 `h=96px`、文字 `textH=47px`、
`lines=1`，视口 346px，连续 5 次方向键下可聚焦第 6 项并成功提交。

### 5.4 布局精简与焦点路径（v2.7）

v2.6 底部常驻「不会？看提示」「回桌面」+ 一行操作说明（62dp 按钮 + 6dp 间距），既挤占选项高度，
又让说明行长期占屏。v2.7 改为：

1. **提示按钮并入题干行**：`[题干 ScrollView weight=1][提示 96dp×46dp]`，题干过长时自己换行/滚动，
   提示按钮固定不被挤压；文字由「不会？看提示」缩为「提示」。
2. **删除底部整行操作区**（提示、回桌面、说明文字），底部不再常驻任何按钮或按键说明。
3. **左上角返回角标**：`overlay_exit` 44dp 高、`‹ 返回`、`layout_gravity=top|start`，
   只占安全区左上角（正文标题行左侧留 84dp 起始内边距避让），点按 = 撤罩回原桌面（`cancelToHome`，与 BACK 同路径）。
   提示面板打开时隐藏，结果/复盘页可见。
4. **结果/复盘页**去掉 66dp 底部大按钮，改为紧凑的可聚焦文本「按确定键继续 / 按确定键开始观看」；
   原有的功能提示文案（「看完再继续，按确定键进入下一题」）保留。
5. **焦点路径**（显式 `nextFocus*` + 按键处理，避免默认搜索卡在滚动区）：
   - 选项 ↑ → 提示；提示 ↑ → 返回角标（`DPAD_UP` 在 hintButton 上由 `KeyHandler` 接管）
   - 选项（最后一排）↓ → 提示；提示 ↓ → 第一个选项；提示 ← → 角标；角标 ↓/→ → 提示
   - 结果页：初始焦点在「按确定键…」，↑ 到角标；`DPAD_CENTER` 由 `KeyHandler` 兜底，焦点在角标时让给角标（退出）
6. **逃生路径两条**：角标（遥控器可聚焦 + 确定键）与 BACK，均已实测回 `LauncherHomeActivity`；
   看门狗（120s 无输入自动撤罩）与「连按 3 次返回」逻辑未改动。

实测视口：`viewportH` 由 v2.6 的 **346px（173dp）** 增至 **482px（241dp）**，多出 136px（+39%）全部给选项滚动区。

## 6. 为什么用 `TYPE_ACCESSIBILITY_OVERLAY`，而不是后台起 Activity

| 方案 | 结果 |
| --- | --- |
| 后台 `startActivity(QuizActivity)` | Android 8 起后台启动 Activity 被严格限制；从无障碍服务启动也可能被系统拦截或闪一下就被压回，体验不可靠，且会把目标 App 压到后台、答完还要重新拉起 |
| `SYSTEM_ALERT_WINDOW` 悬浮窗 | 需要额外 `SYSTEM_ALERT_WINDOW` 权限（电视上还要去设置里授权），且部分 ROM 对「应用在上层绘制」有限制 |
| **`TYPE_ACCESSIBILITY_OVERLAY`（本方案）** | API 22+ 原生支持；无障碍服务被系统绑定后即可添加；不需要 `SYSTEM_ALERT_WINDOW`；窗口可获取焦点，遥控器方向键/确定键直接可用；目标 App 保持在前台，答完移除遮罩即恢复，不需要重新启动 |

实现要点：

- `WindowManager.LayoutParams` 使用 `TYPE_ACCESSIBILITY_OVERLAY`，`MATCH_PARENT × MATCH_PARENT`，`FLAG_LAYOUT_IN_SCREEN`。
- 不加 `FLAG_NOT_FOCUSABLE`，保证遥控器按键进入遮罩；加 `FLAG_ALT_FOCUSABLE_IM`，避免输入法窗口抢焦点。
- 根视图 `OverlayRootView` 重写 `dispatchKeyEvent`：BACK / HOME 直接消费并转成「回桌面」，不会漏给下层受限 App。
- 答题期间所有按键都在遮罩窗口内处理，下层应用收不到按键。


## 7. 数据存储

全部存在 `SharedPreferences` 文件 `quiz_home.xml`，无数据库、无文件 IO 依赖：

| Key | 类型 | 含义 | 默认值 |
| --- | --- | --- | --- |
| `initialized` | boolean | 是否已写入默认受限名单 | false |
| `restricted` | StringSet | 受限应用包名集合 | 5 个实测视频 App |
| `minutes` | int | 通过后放行分钟数 | 30（范围 **1–120**） |
| `required` | int | 及格线（答对题数） | 4（范围 1–5） |
| `pass_until_<包名>` | long | **v2 每包放行**到期时间戳（毫秒）；无该 key = 该应用未放行 | 无 |
| `pass_boot_<包名>` | int | v2.1 放行时的 `Settings.Global.BOOT_COUNT`（开机会话标识） | 无 |
| `pass_elapsed_<包名>` | long | v2.1 放行时的 `SystemClock.elapsedRealtime()`（重启归零） | 无 |
| `expire_at` | long | v1.x 全局放行到期时间（legacy，v2 不再读写，仅保留兼容） | 0 |
| `pin` | String | 家长密码（4 位） | 空，首次进入设置页要求设置 |
| `records` | String | 最近 60 条答题记录（JSON 数组字符串） | `[]` |
| `custom_questions` | String | 自定义题库 JSON（预留） | 空 |
| `ignored_import_path` | String | 家长选择「忽略」的待导入文件 | 空 |
| `ignored_import_mtime` | long | 该文件的修改时间，用于避免重复提示 | -1 |

答题记录字段：`t`（时间戳）、`pkg`（包名）、`label`（应用名）、`correct`（答对数）、`total`（题数）、`pass`（是否通过）。

题库文件（都在应用专属外部目录，卸载 App 会一起删除）：

| 路径 | 作用 |
| --- | --- |
| `/sdcard/Android/data/com.family.quizlauncher/files/questions.json` | 当前生效的外部题库 |
| 同目录 `questions.bak` | 每次导入前的上一版备份，用于「回退到上一版」 |
| 同目录 `questions_import.json` | adb push 导入箱，启动时提示导入 |

题库加载优先级（`QuestionBank.load`）：

1. `files/questions.json`（局域网导入或 `adb push`，无需重装）
2. `SharedPreferences.custom_questions`
3. `assets/questions.json`（内置 36 题）
4. 代码内置 6 题兜底（防止 assets 被破坏）

JSON 结构：

```json
{
  "questions": [
    {"q": "3 + 5 = ?", "options": ["7", "8", "9"], "answer": 1}
  ]
}
```

`answer` 支持下标（从 0 开始）或正确选项文本；选项数量不限（界面会横向排列）。

## 8. 局域网题库自更新（v1.1 追加）

目标：家长用手机换题，不重装 APK、不用电脑、不依赖云端。

### 8.1 为什么是手写 ServerSocket

| 候选 | 结论 | 依据 |
| --- | --- | --- |
| `com.sun.net.httpserver` | **不可用** | 它属于 JDK，不属于 Android 运行时。已直接检查本机 `H:\Android\Sdk\platforms\android-33\android.jar`，其中没有 `com/sun/net/httpserver` 包；API 26 的 Android 运行时同样不提供。写了也会在电视上 `NoClassDefFoundError`。 |
| NanoHTTPD | 不采用 | 功能足够，但会引入第三方依赖和额外体积，与"60KB 级、无第三方库"的约束冲突。 |
| 手写 `ServerSocket` | **采用** | `java.net.ServerSocket` 在 Android 上可用；只需支持 4 个固定路由，代码约 300 行，零依赖。 |

服务实现要点：

- 端口从 `8080` 开始尝试，被占用就顺延到 `8081…8089`；页面上大字显示实际 `http://<电视IP>:<端口>`。
- 单线程 accept，一次处理一个请求，`SO_TIMEOUT=15s`；请求头上限 16KB，请求体上限 `1MB`。
- 只支持 `GET /`、`GET /status`、`POST /upload`、`POST /rollback`，其余 404/405。
- IP 通过 `NetworkInterface` 枚举，优先 `isSiteLocalAddress()` 的 IPv4（192.168.x / 10.x / 172.16–31.x），找不到再退回任意非回环 IPv4。
- 页面每 2 秒刷新一次地址与题库状态，切换 Wi-Fi 后地址会自动更新。
- 二维码：**没有内置**。生成二维码要么引入第三方库，要么自己实现 QR 编码，都会明显增加体积和出错面；当前用 34sp 大字地址 + 手机手动输入即可。电视端也可用 `adb shell` 查看地址。

### 8.2 生命周期与 Android 8 后台限制

服务不是 `Service`，而是 `QuestionBankActivity` 内的一个普通线程：

- `onResume`：确认已设置 4 位 PIN 后 `start()`；未设置则先弹「设置家长密码」。
- `onPause`（退出页面、按 Home、锁屏、被其他 Activity 覆盖）：立即 `stop()` 并关闭 `ServerSocket`。
- 因此不需要前台 Service、不触发 Android 8 的后台执行限制，也不会常驻耗电；代价是**只有停留在「题库管理」页时手机才能上传**。

### 8.3 上传页与接口

| 方法/路径 | 作用 | 鉴权 |
| --- | --- | --- |
| `GET /`或`/index.html` | 深色上传页：显示当前题库来源、题数、版本；文件选择/粘贴 JSON；上传与回退按钮 | 无（只读，不含隐私） |
| `GET /status` | 返回 `{running,port,count,imported}` | 无 |
| `POST /upload` | 校验并导入 JSON | `X-Pin` 必须等于电视上的 4 位 PIN |
| `POST /rollback` | 用 `questions.bak` 覆盖当前题库 | 同上 |

`POST` 未设置 PIN 时返回 400 并提示先在电视上设置；PIN 错误返回 403。页面上的 PIN 只随本次请求头发送，不写入日志。

### 8.4 校验、备份与回退

校验在写盘之前完成，任何一条不合格都整体拒绝，并返回"第 N 题……"的人话错误：

- 顶层可以是 `{"version":...,"questions":[...]}` 或直接数组；
- 题数 5–500；题干取 `q` 或 `question`，非空且 ≤200 字；
- `options` 必须是 2–6 个字符串/数字，单个非空且 ≤60 字；
- `answer` 支持 0 起始下标，或与某个选项文本完全一致；
- `difficulty` / `category` 可选，但必须是字符串或数字；
- 整体 ≤1MB。

导入顺序：先校验 → 把当前 `questions.json`（没有则把 assets 内置题库）复制为 `questions.bak` → 再写新文件。导入失败不会动旧题库。页面上的「回退到上一版」就是把 `questions.bak` 复制回 `questions.json`。

### 8.5 备用导入路径（U 盘 / adb push）

家长设置页每次 `onResume` 检查一次（`ParentActivity.checkPendingImport`）：

1. `Android/data/com.family.quizlauncher/files/questions_import.json`（方便 `adb push`）；
2. `/storage/<U盘卷>/questions_import.json` 或 `questions.json`，以及 `/mnt/usb`、`/storage/usb0` 等常见挂载点。

发现文件后弹「导入 / 忽略」：

- 导入：走与网页上传完全相同的校验、备份流程；
- 处理完把文件改名为 `*.imported` 或 `*.invalid`；改名失败（U 盘只读等）则记录路径+mtime 到 Prefs，避免反复提示；
- 选择「忽略」也记录路径+mtime，只有文件被替换（mtime 变化）才会再次提示。

### 8.6 权限与安全边界

新增权限：

| 权限 | 用途 | 说明 |
| --- | --- | --- |
| `INTERNET` | 允许 `ServerSocket` 监听 | 普通权限，安装即授予；代码中没有任何向外发起的 HTTP/DNS 请求 |
| `READ_EXTERNAL_STORAGE`（maxSdk 32） | 读取 U 盘题库文件 | 应用专属外部目录不需要此权限 |
| `WRITE_EXTERNAL_STORAGE`（maxSdk 28） | Android 8/9 处理 U 盘文件改名 | Android 10+ 已废弃该权限，故限制 maxSdk |

安全边界：

- 服务只监听本机端口，不主动连接任何外部地址，不做统计、不上传题库；
- 写操作必须带 PIN；`/api/*` 在 PIN 未设置时一律 403 并提示先设置 PIN；
- 请求体先按 `Content-Length` 判断，超 1MB 直接 413，避免内存打满；
- 题库上传 / AI 页的服务仍随页面退出关闭；v2.8 的局域网控制服务由前台 Service 常驻，家长页可随时关闭并释放端口；
- `AndroidManifest` 只声明无障碍 Service、v2.8 的 `ControlService`（前台服务）与开机清放行的广播，仍然不注册 HOME、不声明默认桌面。

### 8.7 手机端控制服务（v2.8）

Android 8 的后台限制会回收普通进程，因此手机要"随时控制"，HTTP 监听必须放进前台服务：

- `ControlService`：`startForeground()` + 低优先级通知（「家长控制服务运行中」，渠道 `quiz_control`，importance=LOW）；
- `START_STICKY`；进程被系统回收后重建时 `onCreate/onStartCommand` 会重新绑定 `ServerSocket`；
- 家长设置页提供「开/关局域网控制服务」按钮，状态实时显示（运行中/关闭）；`Prefs.control_enabled` 记录开关，回到家长页时若开关为开但服务没跑会自动拉起；
- 关闭服务 → `onDestroy` 停止 `ServerSocket`，端口立即释放，通知消失；
- 端口仍是 8080 起顺延，`/api/summary` 的 `port` 字段告诉手机端实际端口。

接口（全部沿用 `QuestionHttpServer` 的 1MB 上限与 15s 超时）：

| 方法/路径 | 作用 | 鉴权 |
| --- | --- | --- |
| `GET /api/summary` | 摘要：version/port/minutes/passLine/restricted/passes/questions/records（records 最多 60 条，最新在前；passes 只含仍有效的） | 无（不含任何 Key） |
| `POST /api/settings` | `{"minutes","passLine","restricted"}`，字段可缺省，只改传入项 | `X-Pin` |
| `POST /api/pass` | `{"pkg","minutes"}` → `Prefs.grantPass`（带开机会话烙印）；`minutes ≤ 0` 或缺失用当前设置 | `X-Pin` |
| `POST /api/revoke` | `{"pkg"}` → 清除该包放行 | `X-Pin` |
| `POST /api/clear-records` | 清空答题记录 | `X-Pin` |

`/api/*` 错误统一为 `{"ok":false,"error":"中文原因"}`：PIN 错误或未设置 PIN → 403；JSON 非法/字段越界/缺 `pkg` → 400。成功返回摘要并附加 `"ok":true`。`/upload`、`/rollback`、`/ai*` 行为与 v2.7 完全一致。

### 8.8 为什么不用云端

- 家庭场景只需要"手机 → 电视"这一段局域网，云端会引入账号、服务器、HTTPS 证书、隐私与长期维护成本；
- 电视是 Android 8 定制系统，云端 SDK 往往要求 GMS 或更高 API；
- 断网时仍要能换题，局域网方案天然离线可用；
- 题库是家庭自编内容，没有必要离开家门。

## 9. 默认数据来源（本机实测）

- 设备：`58PUF7695_T3`，Android 8.0.0，API 26。
- 原始桌面（回滚锚点）：`com.ktcp.launcher/.activity.LauncherMainActivity`。
- 从 `tv_adb_tool/备份/20261104_201436/包列表_全部.csv` 中挑选的默认受限应用：
  - `com.cibn.tv`（未来电视 / CNTV 内容端）
  - `com.newtv.cboxtv`（银河奇异果 / 爱奇艺 TV）
  - `com.xiaodianshi.tv.yst`（小电视）
  - `com.gitvdemo.video`（系统视频演示应用）
  - `com.hisilicon.android.videoplayer`（本地视频播放器）
- 其余应用默认"放行、直接打开"，家长可在设置页随时增删。

## 10. 已知限制与旁路风险（v2）

1. **依赖无障碍服务**：服务未开启、被家长在系统设置里关闭、被系统/杀毒软件强制停止时，拦截完全失效，受限 App 照常打开；家长设置页会把状态显示为红色「未开启（受限应用不会被拦截）」。
2. **开启服务时已在前台的 App 不会立刻被拦**：无障碍只在窗口发生切换时收到事件。若开服务前孩子已经停留在受限 App，需要先回桌面再重新进入（或等服务收到下一次 `TYPE_WINDOW_STATE_CHANGED`）。这是事件驱动方案的固有边界。
3. **只认窗口事件里的包名**：全屏系统弹窗、部分厂商的"应用直达"入口如果由非受限包名的进程承载内容，可能绕过判断；反之，少数定制系统用同一个包名承载多个功能时，可能被误判（白名单即为缓解手段）。
4. **白名单之外的系统界面可能被误拦**：当前硬白名单只覆盖自身、桌面、设置、SystemUI、包安装器、OOBE、输入法。若本机还有其他必须随时可用的系统组件（例如电视管家的密码弹窗），需要加入 `STATIC_WHITELIST` 后重建。
5. **家长密码是 4 位数字**：首次进入设置页时设置，之后可修改；孩子知道密码后即可改配置。它只是"防误触"级别，不是强安全机制。
6. **答题可被猜中**：三选一的算术题，孩子反复试也可能通过；可通过题库换成需要动笔的题型（题库结构通用）。
7. **放行是每包独立的，但不做每日总量**：每个受限 App 各有一份 `pass_until_<pkg>`，通过一次只解锁该 App；仍没有"每天总共只能看多久"的上限。
8. **不做防卸载**：孩子若会使用系统设置卸载本 App，关卡即失效；可配合系统"应用锁"。
9. **局域网服务只在题库页打开时可用**：退出页面即关闭，这是有意的安全取舍；需要换题时家长必须先把电视停在「题库管理」页。
10. **HTTP 是明文**：只在家庭局域网使用，传输内容是非敏感题库；不做 HTTPS（自签证书会让手机浏览器报警，复杂度和收益不成比例）。
11. **手机必须与电视同网段**：电视插网线、手机连 4G，或路由器开了 AP 隔离/访客网络时，手机访问不到该地址；此时改用 U 盘或 adb push。
12. **`addView` 失败降级路径未在真机触发过**：遮罩添加异常时代码会降级为 `QuizActivity`，再兜底回桌面，但正常设备上很难人工制造该异常，属于"有实现、未实跑"的分支。
13. **本机系统无障碍设置页本身会崩（v2.2 已规避）**：该 ROM 移除了 `com.android.providers.userdictionary`，
   `com.android.settings` 的 `Settings$AccessibilitySettingsActivity` 在 `onResume` 里注册 `user_dictionary`
   的 `ContentObserver` 必然抛 `SecurityException`，整个设置进程崩溃。这是**系统进程自己崩**，应用侧
   `try/catch` 抓不到，只能"不跳过去"。v2.2 跳转前用 `resolveContentProvider("user_dictionary")` 预检，
   缺失时改为弹出「遥控器手动路径 + 可复制的 adb 命令」提示框。
14. **本应用进程会被本机低内存杀手回收（v2.3 已补自愈）**：实测 `am_kill [0,pid,com.family.quizlauncher,900,lowram app pss is 23197]`，
    进程重启后无障碍服务会在约 2 秒内自动重连，v2.3 在重连后 600ms 复查前台并补弹遮罩；
    期间存在约 2 秒的"无遮罩"窗口，属于系统层限制，应用侧无法完全消除。

## 11. 性能与资源

- 单进程；v2 常驻的只有 1 个 `AccessibilityService`（系统绑定，无自建线程），`Activity` 均为用完即走。
- **无轮询**：窗口事件先经 500ms 防抖，放行到期只用一次 `postDelayed` 复查，没有 `Timer` / 循环线程。
- 遮罩期间只持有 1 份 View 树 + 5 道题对象 + 1 个 `Handler`，不加载 Bitmap、不读磁盘。
- 局域网 HTTP 线程只在题库页可见时存在，页面一退出就关闭。
- 不依赖 AndroidX / GMS，APK 约 71KB（含 36 题内置题库）。
- 应用图标由 `PackageManager` 在 `onResume` 时枚举一次并缓存于本次构建的 View 树中；配置变化时靠签名比对避免重复重建。
- 计时全部用 `System.currentTimeMillis()`，不依赖网络时间。

## 12. 后续可扩展

- 每日总时长配额（在 `pass_until_<pkg>` 之外再加一份按天的累计）。
- 题库内置编辑器（当前已支持手机网页上传 JSON）。
- 答题结果语音播报、错题本。
- 白名单可在家长设置页可视化维护（当前写在代码常量里）。

## 13. v2.9 学科题库、每题统计与掌握移出（字段契约）

### 13.1 题库模型

- `Question`：新增 `id`、`subjectKey`、`subjectName`。`id` 缺省时由**规范化题干**（`trim` → 合并连续空白 → 小写）的 SHA-256 前 8 字节生成，形如 `q02cf971fbe13f9ec`，稳定可复现，用于统计与去重。
- `QuestionBank.Subject { key, name, List<Question> questions }`；`QuestionBank.Bank { title, subjects, all(), find(key), total() }`。
- 整库格式：`{"version":3,"title":"总题库","subjects":[{"key":"english","name":"英语","questions":[...]}]}`。
- 兼容旧格式：顶层 `questions` 数组或纯数组；旧格式视为**单学科**，学科名取 `title`（无 `title` 用「默认题库」）。学科 `key` 缺省时按 `name` 派生（小写字母数字，非法字符转 `-`，全中文则 `subject1/2/...`）。
- 抽题 `QuestionBank.pick(context, 5)`：只从**启用学科**（`enabledSubjects`，空=全部）的题池合并，排除已移出 id；随机打乱后按规范化题干去重取前 5，不足再补。启用学科全空或题池为空时回退到全部学科（防锁死）。

### 13.2 导入校验（`QuestionImporter`）

数组/旧格式 → `validateOld`；`subjects` → `validateNew`。规则：学科 key 唯一、每学科 ≥1 题、全库 5–500 题、每题 2–6 个选项、选项 ≤60 字、`answer` 必须是合法下标或匹配的选项文本、单文件 ≤1MB。错误信息精确到「第 X 学科第 Y 题」（旧格式为「第 Y 题」）。

单学科文件（新格式只有 1 个学科）按 key 合并：同学科替换、新 key 追加；当前库非新格式则整体替换。合并结果重新校验通过才落盘，写盘前先备份为 `questions.bak`。

### 13.3 每题统计与掌握

- 存储：SharedPreferences `quiz_stats`（`qstats_counts`：id → answered/correct/wrong；`qstats_removed_items`：完整题对象数组，便于恢复时还原题目）。
- 外部备份：`/sdcard/Android/data/com.family.quizlauncher/files/mastered.json`，`items[]` 含 `id/subject/subjectName/q/options/answer/removedAt/correct`。
- 判定：`correct >= masteredThreshold`（默认 3，范围 1–10）即「已掌握」；若 `autoRemove=true`，在**答对那一刻**把该 id 加入移出名单并写 `mastered.json`。移出只维护名单，不改 `questions.json`，因此「回退上一版题库」不会破坏统计与备份。
- 统计页与家长页读取同一份数据：学科汇总（题数/已掌握/已移出）与逐题 `answered/correct/wrong`。

### 13.4 API 最终契约（手机端按此对接）

```json
GET /api/summary
{
  "ok": true, "version": "2.9", "port": 8080,
  "bank": {"title": "总题库", "total": 12},
  "subjects": [{"key":"english","name":"英语","count":6,"mastered":0,"removed":0}],
  "stats": {"threshold":3,"autoRemove":false,"enabledSubjects":[]},
  "...": "v2.8 的 minutes/passLine/restricted/pass 等字段保持不变"
}
```

```json
GET /api/stats
{
  "subjects": [{"key":"english","name":"英语","count":6,"mastered":0,"removed":0}],
  "threshold": 3, "autoRemove": false, "enabledSubjects": [],
  "questions": [
    {"id":"en-001","subject":"english","q":"apple 的中文意思是？",
     "answered":0,"correct":0,"wrong":0,"mastered":false,"removed":false}
  ]
}
```

- `POST /api/settings` 新增可传字段：`masteredThreshold`（数字 1–10）、`autoRemove`（布尔）、`enabledSubjects`（学科 key 数组，`[]`=全部；未知 key 返回 400「未知学科：x」）。返回完整 summary。
- `POST /api/remove-question {"id":"..."}`：手动移出，找不到返回 400「题库里没有这道题」。
- `POST /api/restore-question {"id":"..."}`：从移出名单恢复，不在名单返回 400。
- `POST /api/reset-stats`：清零 `answered/correct/wrong`，保留移出名单。
- `POST /upload`：同时接受新旧格式；`POST /rollback` 行为不变。
- 写操作仍要求 `X-Pin`，未设 PIN 一律 403；JSON 错误体仍为 `{"ok":false,"error":"中文"}`。

### 13.5 UI 重做（标准 View + 自绘 drawable）

- 设计 token：深色底 `#0E141B`、卡片 `card_bg`（16dp 圆角 + 1dp 描边）、强调色 `accent`（焦点/按钮）、`ok`/`danger` 做对错反馈；所有按钮 `button_bg` 为 24dp 圆角胶囊。
- 答题页：左上角圆形返回角标（`‹`）+ 「第 X / N 题」+ 6dp 细进度条（两段 View 权重）+ 题干卡片（提示胶囊同行降权）+ 选项卡片（左侧 5dp 色条、字母角标 A–F、可换行、聚焦 2dp 强调描边 + 1.02 放大）。
- 反馈：正确绿色勾/红色叉条；答错进入复盘页，「✕ 你选的」「✓ 正确答案」两张卡分层 + 提示入口 + 继续按钮。
- 家长设置页、题库管理页、统计页统一 `card_bg` 分区，不再裸 TextView 堆叠；`StatsActivity` 为 v2.9 新增页面。
- 截图：`quiz_launcher/screenshots/v29_01_quiz.png` ~ `v29_06_stats.png`。

### 13.6 联调安全红线（2026-11 事故后新增）

- **严禁修改电视网络配置**：`ip link set`、改 MTU、改 IP/网关/DNS/代理、WiFi 开关均属禁止操作；曾因 `wlan0 mtu 1200` 使电视直接掉线。只允许只读查询（`ip link show`、`ip addr show`、`ping`、`curl` 字节数统计）。
- 电视离线时只报告「等待用户重启」，代理不得代为重启、不得尝试网络修复。
- 大响应（`/api/summary`、`/api/stats`、`/`）必须由**另一台机器**做跨机验证并记录 `size_download`，本机 `nc` 通过不算数。

### 13.7 跨机大响应「200 头到达、body 0 字节」的根因与规避（2026-11-05）

**根因（已用只读 ping 二分验证，非 MTU 残留，重启后 `wlan0` 就是 1500）**：
这台电视 **发送方向**（电视 → PC/平板）对较大的 IP 报文几乎必然丢失，且没有 ICMP 反馈（PMTU 黑洞）：

| ping 负载 | 实际 IP 包 | 结果 |
| --- | --- | --- |
| 1400 / 700 | 1428 / 728 | 100% 丢包 |
| 650 / 600 / 500 | 678 / 628 / 528 | 0% 丢包（偶发 1/10） |
| 550 | 578 | 25% 丢包（不稳定） |

反向（PC → 电视）完全正常（`adb install` 146KB 一次成功）。因此任何「一次写出全部 body」的实现都会表现为：客户端收到响应头（第一个小报文），body 全是 0 字节；本机 `curl 127.0.0.1` 永远正常。

**应用层规避（`QuestionHttpServer` 已实现，全部只作用于本进程 socket，不动设备网络配置）**：

1. 接受连接后 `setTcpNoDelay(true)`、`setSendBufferSize(8192)`、`setSoLinger(true, 3)`；
2. 用 `Os.setsockoptInt(fd, IPPROTO_TCP, TCP_MAXSEG=2, 256)` 把本连接 MSS 压到 256（用 `ParcelFileDescriptor.fromSocket` 取 fd 后**绝不能 close**，它包装的就是 socket 自身 fd，close 会直接关掉连接）；
3. 每条连接单独线程处理，避免慢发阻塞其他请求；
4. 支持 gzip：请求头含 `Accept-Encoding: gzip` 且 body > 400 字节时压缩（`Content-Encoding: gzip` + `Vary`）；Android `HttpURLConnection`/OkHttp 默认就会带该头并透明解压；
5. 写出策略：`payload ≤ 700` 字节一次写完（≤3 个小报文，实测最稳）；否则 256 字节/块 + 150ms 间隔；
6. 所有响应 `Connection: close` + `Cache-Control: no-store` + UTF-8 `OutputStream`，无 PrintWriter/BufferedWriter 混用、无 chunked。

**实测（PC → 电视 `http://192.168.1.50:8080`，`curl --compressed`）**：
`/api/summary` 635B(压缩)/2904B(原文) 5/5 成功，0.3s；`/api/stats` 1015B 3/5，0.53s；`/` 1733B 3/3，0.97s。
失败样本统一表现为「只收到第一个 256B 块后连接被关」，属链路随机丢包，**客户端必须把空响应/截断响应当作可重试错误**（建议重试 2 次，间隔 1s）。

**还原说明**：`/api/summary` 现在默认 gzip；若手机端自行设置 `Accept-Encoding: identity`，会走非压缩分支（2904B，成功率更低），不要这么做。

## 14. v3.0：U 盘播放漏拦与严格模式

### 14.1 现场取证（2026-11-05，全程只读）

U 盘插着播放视频时：

| 项目 | 结果 |
| --- | --- |
| `dumpsys activity activities` 的 `mResumedActivity` / `mFocusedActivity` | `com.tpv.xmic.contentbrowser.ebony2k15/com.tpv.xmic.mediacenter.player.videoplayer.VideoPlayerActivity` |
| `dumpsys window windows` 的 `mFocusedApp` | 同上（媒体中心视频播放页） |
| `dumpsys media_session` | 只有 telecom 会话，播放器未注册 MediaSession（所以不能靠媒体会话识别） |
| `dumpsys package com.tpv.xmic.contentbrowser.ebony2k15` | 声明 `android.intent.action.MEDIA_MOUNTED` 与 `BOOT_COMPLETED` 接收器，且有 `CATEGORY_LAUNCHER` 入口 |
| `pm list packages -f` 关键词筛选 | 命中 10 个；真正相关的播放器为上面的 ebony2k15 与 `org.droidtv.usbautoplayer`（`/system/app/cita2K19_USBAutoPlayer/cita2K19_USBAutoPlayer.apk`） |
| `logcat -d` grep USB/vold | 本次缓冲里没有挂载日志（已滚掉），无法据此还原拉起链 |

结论：v2.9 只拦「桌面图标型视频 App」，U 盘走的是厂商媒体中心（独立 Activity + MEDIA_MOUNTED 自起），名单里没有它，所以完全不拦。

### 14.2 默认受限名单（v3.0）

保留 v2.9 的 5 个：`com.cibn.tv`、`com.newtv.cboxtv`、`com.xiaodianshi.tv.yst`、`com.gitvdemo.video`、`com.hisilicon.android.videoplayer`。

新增：
- `com.tpv.xmic.contentbrowser.ebony2k15`（实测 U 盘视频播放器）
- `org.droidtv.usbautoplayer`（系统 USB 自动播放器）

关键词兜底（`AppRepository.defaultRestricted`）：再扫描已安装包，命中 `video|movie|player|usb|filemanager|fileexplorer` 的系统播放器一并纳入；显式排除 `com.android.providers.*`、`externalstorage`、`storagemanager`、`sharedstoragebackup`、`settings`、`systemui`、`packageinstaller`、桌面与自身，绝不碰存储/权限组件。

**升级迁移**：老设备已 `initialized`，`initRestricted` 不会覆盖旧名单。新增一次性迁移 `Prefs.ensureV30Defaults()`：只在首次运行 v3.0 时把「新增的默认包」并进现有名单（用 `defaults_seal=v3` 标记），不强行改回家长手动关掉的老 5 项。

家长页的「恢复默认受限列表」同样调用新的 `defaultRestricted(Context)`。

### 14.3 严格模式（只放行白名单）

- 设置项：`strictMode`（默认 `false`）、`allowList`（默认 13 条，见 `AppRepository.DEFAULT_ALLOW`，支持 `com.android.providers.*` 前缀匹配）。
- 判定顺序（`QuizAccessibilityService.shouldGate`）：硬白名单（自身/桌面/设置/SystemUI/安装器/OOBE/输入法/`com.android.providers.*`）→ 有效放行 → 严格模式看白名单 / 普通模式看受限名单。
- 防自锁：硬白名单优先级最高，即使白名单被删光，桌面、设置、SystemUI、安装器与输入法仍然放行；家长页会提示「白名单缺少桌面/设置」；家长页与统计页都有开关（统计页需 PIN）；3 次返回键逃生与 120s 看门狗保留。
- API：`POST /api/settings` 接受 `strictMode`（bool）与 `allowList`（字符串数组，拒绝空数组）；`POST /api/strict {"enabled":bool}`；`GET /api/summary` 的 `stats` 回显 `strictMode`、`allowList`。

### 14.4 真机实测（2026-11-05）

| 项目 | 结果 |
| --- | --- |
| 升级迁移 | `/api/summary.restricted` 回读 7 个，含 `com.tpv.xmic.contentbrowser.ebony2k15`、`org.droidtv.usbautoplayer`；原 5 个保留 |
| 无障碍 | `Service[label=答题家长控制, capabilities=9, eventTypes=TYPE_WINDOW_STATE_CHANGED]` |
| U 盘播放 | `am start` 打开 `/storage/sda1/00 天线宝宝向前冲/英文版/01_万圣节南瓜.mp4` → `mFocusedApp` 为 `ebony2k15/VideoPlayerActivity`，出现 `WM.LayoutParams{... ty=2032 ...}` 遮罩，`mCurrentFocus` 为本应用 |
| 看门狗 | 遮罩无人操作 >120s 自动撤销并回 `com.ktcp.launcher`，无残留 `ACCESSIBILITY_OVERLAY` |
| 服务 | `/status` 200 / 55B（`running=true, port=8080, count=15`） |
| 跨机响应 | `/api/summary` 200 / **795B**（gzip，约 0.52s），首请求偶发 empty reply，重试 1 次成功 |
| `/api/strict` | 无 PIN → 403 / 75B，路由与鉴权正常；**未用正确 PIN 做写入/严格模式实测（用户未提供 PIN）** |
| 截图 | 未能取到：电视→PC 大文件传输仍受「大报文黑洞」影响，`adb pull`/`exec-out screencap`(94KB) 均挂起，未生成有效 PNG |
