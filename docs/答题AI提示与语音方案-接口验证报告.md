# 答题时的 AI 思路提示 + 语音朗读：接口验证与接入方案

- 验证时间：2026-11-04 22:00–22:15（北京时间）
- 验证环境：Windows 10 + Python 3.11（urllib）+ 家庭宽带，**未连接、未修改那台飞利浦电视**
- 目标设备约束：58PUF7695_T3 / Android 8.0.0 / API 26 / 1.84 GB 内存（现有只读快照确认）
- 说明：本文所有耗时均为实测；两个 Key 只在临时脚本中使用，文中已脱敏；脚本位于系统临时目录、不在 `quiz_launcher` 工程内，验证后已删除。

---

## 0. 结论速览

| 项目 | 结论 |
|---|---|
| Agnes 3.0 Flash | **可用**。OpenAI 兼容接口，`Bearer` 鉴权，中文正常，`response_format: json_object` 实测生效。但**延迟波动大**（0.9–14.3 s，题库题目 2.7–3.6 s），**免费档有速率限制**（10 并发实测 2 次 429）。 |
| MiMo TTS v2.5 | **可用**。纯文本 POST，无需签名/时间戳/临时 token，中文正常。非流式 1.6–4.3 s 返回整段音频；流式首包音频 **0.67 s**。输出 24 kHz 单声道：WAV(PCM16)、MP3、PCM16。 |
| 格式兼容 | WAV 为 24 kHz / 16 bit / 单声道 PCM，MP3 为 24 kHz / 单声道，**均属 Android 8 原生 MediaPlayer 支持格式**；未在真机播放（按约束未连电视）。 |
| 电视离线 TTS | 该电视只装了 **`com.svox.pico`（PicoTts）**，且无中文语音包；`com.google.android.tts`、讯飞等均未安装。**中文离线朗读实际不可用**。 |
| 关键风险 | 模型会主动泄露答案：实测 `18 ÷ 3` 的 `pitfall` 出现“误算成 6 或 9”，另一次直接写出“三六十八”。**必须在客户端做答案泄露拦截**，不能只靠 prompt。 |
| 推荐架构 | 提示：Agnes 非流式 + `json_object` + 本地校验/缓存/超时降级；语音：MiMo MP3（家长设置页填 Key）→ 本地音频缓存 → 系统 TTS（有中文才用）→ 纯文字。**Key 不进 APK**。 |

---

## 1. Agnes 3.0 Flash 实测

### 1.1 接口事实

| 项目 | 实测值 / 文档值 |
|---|---|
| Base URL | `https://api.agnes-ai.cn/v1` |
| 调用地址 | `POST https://api.agnes-ai.cn/v1/chat/completions` |
| 鉴权 | `Authorization: Bearer sk-****`（Header，实测通过；未验证 query 传参） |
| 模型名 | `agnes-3.0-flash` |
| 请求体 | OpenAI Chat Completions：`model`、`messages`、`temperature`、`max_tokens`、`stream` |
| JSON 约束 | `response_format: {"type":"json_object"}` 实测生效；`json_schema` 也被接受（HTTP 200），但**不做严格约束** |
| 流式 | `stream:true` 返回 `text/event-stream`，逐 token 的 `delta.content` |
| 中文 | 支持（默认中文提问即中文作答） |
| 上下文 / 最大输出 | 512K / 65,536 token（文档） |

请求样例（Key 脱敏）：

```bash
curl -sS https://api.agnes-ai.cn/v1/chat/completions \
  -H "Authorization: Bearer sk-****" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "agnes-3.0-flash",
    "messages": [
      {"role": "system", "content": "你是作业教练，只给思路不给答案，只输出JSON"},
      {"role": "user", "content": "年级：小学二年级\n学科：数学\n题目：62 - 25 = ?"}
    ],
    "temperature": 0.2,
    "max_tokens": 300,
    "response_format": {"type": "json_object"}
  }'
```

### 1.2 实测结果（同一台机器、同一网络）

| # | 用例 | HTTP | 耗时 | 备注 |
|---|---|---|---|---|
| A | 普通非流式，`max_tokens=400` | 200 | **5.80 s** | 返回 756 B JSON；中文正常 |
| B | `response_format=json_object` | 200 | **8.72 s** | 693 B，JSON 合法 |
| C | 同题第二次 | 200 | **12.07 s** | 波动明显 |
| D | 非法模型 `gpt-4o` | **404** | 0.26 s | `model_not_found`：“当前分组不支持模型 gpt-4o” |
| E | 非法 Key | **401** | 0.23 s | “无效的令牌”，`type: AgnesAI_error` |
| F | `stream=true` | 200 | 首包 **14.54 s** / 总 18.42 s | SSE 正常，但本次首包比非流式更慢 |
| G | `json_schema` 严格模式 | 200 | 3.04 s | **schema 未被严格约束**，且输出变成英文，`steps` 写出 `Calculate: 12 ÷ 3` |
| H | 极短请求（60 token） | 200 | **0.94 s** | 输出 26 token |
| I | 6 并发 | 200×6 | 2.09 / 2.18 / 2.42 / 3.37 / 10.83 / 14.28 s | 未触发 429 |
| J | 10 并发突发 | 200×8、**429×2** | 0.40 / 0.54 s（429 立即返回） | 见 1.3 |
| K | 真实题库 4 题（串行，间隔 3 s） | 200×4 | 3.60 / 2.85 / 3.17 / 2.69 s | 题库题面见 `questions.json` |
| L | 端到端（Agnes→MiMo，62-25） | 200×2 | Agnes 3.56 s + TTS 4.30 s | 见第 4 节 |

**延迟结论**：提示类请求（约 80–160 输出 token）在正常时段 **约 2.7–3.6 s**，冷启动或高负载时可到 **8–17 s**。不能假设“1 秒内返回”。

### 1.3 限流与错误码（实测报文）

10 并发突发时 2 个请求返回 429，响应体原文：

```json
{"error":{"code":"","message":"您已达到免费用户的 API 速率限制。升级 Token Plan 即可解锁更高限额，继续不间断使用 API。 (request id: ...)","type":"AgnesAI_error"}}
```

- 响应头**没有** `Retry-After`、`X-RateLimit-*`，无法据此退避，只能自行按时间片重试。
- 停止突发、间隔 3 s 串行后恢复正常（200）。
- 结论：这是**免费档速率限制**，适合家庭低频使用（一次答题会话 4–5 次调用），但**绝不能并发发题**。

### 1.4 response_format 的真实表现

- `json_object`：返回内容是纯 JSON 字符串，无 Markdown 包裹，`Content-Type: application/json`。
- `json_schema`：接口**接受参数**，但实测既没严格约束字段，也出现**语言漂移**（中文题面 → 英文答案），并且把 `12 ÷ 3` 写进 `steps`。
- 建议：只用 `json_object`，字段约束靠 system prompt + 客户端校验。

### 1.5 答案泄露实测（重要）

同一道 `18 ÷ 3 = ?`（正确答案 `6`，不下发），三次调用中：

```json
{"hint":"想一想：3个几加起来才等于18？试着数一数吧。",
 "steps":["回忆3的乘法口诀，看看哪一句得数是18","用除法的关系思考：18里面有几个3","用画图或数数的方式辅助理解"],
 "concept":"表内除法",
 "pitfall":"容易把18除以3误算成6或9，口诀背错",
 "need_more_info":false}
```

`pitfall` 直接写出“6”。另一轮返回：

```json
"hint":"想想几个3连加才到18，或者回忆一下三六十八这句口诀。",
"pitfall":"容易记混口诀，比如把三六十八记成三六十九或者二九十八"
```

“三六十八”等价于直接给答案，而且**会被语音朗读出来**。因此：

1. prompt 必须显式禁止“口诀原文 / 错误结果举例 / 选项内容”；
2. 客户端必须做**答案与选项泄露检测**，命中就丢弃该条提示，走本地兜底。

---

## 2. MiMo TTS v2.5 实测

### 2.1 接口事实

| 项目 | 实测值 |
|---|---|
| 调用地址 | `POST https://api.xiaomimimo.com/v1/chat/completions` |
| 鉴权 | `Authorization: Bearer sk-****` |
| 模型 | `mimo-v2.5-tts`（预置音色）；另有 `-voicedesign` / `-voiceclone` |
| 请求体 | OpenAI 兼容 + 自定义 `audio` 对象：`{"audio":{"format":"wav|mp3|pcm16","voice":"冰糖"}}` |
| 待合成文本位置 | 必须在 `messages[].role = "assistant"` 的 `content` |
| 风格控制 | 可选 `role="user"` 的自然语言指令；也可在文本里加 `(温柔)` 等标签 |
| 签名/时间戳/临时 token | **不需要**，一次 POST 直接返回 base64 音频 |
| 返回结构 | `choices[0].message.audio.data`（base64），`message.content` 为空字符串 |
| 流式 | `stream:true` 时 `choices[0].delta.audio.data` 逐块返回 |
| 中文 | 支持，`冰糖 / 茉莉 / 苏打 / 白桦` 为中文音色 |
| 试用额度 | 文档与响应头均未给出明确数字；本 key 实测约 20 次调用未触发额度/限流错误 |

请求样例（Key 脱敏）：

```bash
curl -sS https://api.xiaomimimo.com/v1/chat/completions \
  -H "Authorization: Bearer sk-****" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "mimo-v2.5-tts",
    "messages": [
      {"role": "user", "content": "温柔、鼓励，语速稍慢，像老师在旁边轻声提示"},
      {"role": "assistant", "content": "先看看个位上的二够不够减五，想一想是否需要从十位借一再计算哦。"}
    ],
    "audio": {"format": "mp3", "voice": "冰糖"}
  }'
```

### 2.2 实测结果

| # | 用例 | HTTP | 耗时 | 音频 |
|---|---|---|---|---|
| A | WAV，43 token 文本 | 200 | **2.14 s** | 314,924 B / **6.56 s** |
| B | WAV + 自然语言风格指令 | 200 | **2.62 s** | 437,804 B / 9.12 s |
| C | MP3 | 200 | **1.99 s** | 46,896 B / 6.14 s（61 kbps） |
| D | PCM16（裸流） | 200 | **2.09 s** | 307,200 B / 6.4 s |
| E | 720 字长文本 | 200 | **105.79 s** | 22,817,324 B / ≈475 s |
| F | 英文音色 `Mia` | 200 | **1.85 s** | 145,964 B / 3.04 s |
| G | 真实提示文本（62 字）三次 | 200×3 | 3.65 / 3.51 / 3.38 s | 576–630 KB / 12–13 s |
| H | 流式（32 字） | 200 | 首包 **0.67 s** / 总 1.82 s | 5.8 s PCM |
| I | 流式第二次 | 200 | 首包 **0.70 s** / 总 2.17 s | 6.9 s PCM |
| J | 同样文本非流式 | 200 | **1.60 s** | 整段返回 |
| K | 4 并发（MP3） | 200×4 | 1.12 / 1.15 / 1.39 / 2.28 s | 12–16 KB |

### 2.3 音频格式（ffprobe 实测）

| 格式 | 实测参数 |
|---|---|
| `wav` | `RIFF/WAVE`，`pcm_s16le`，**24000 Hz，1 声道，16 bit**（字节率 48,000） |
| `mp3` | **24000 Hz，1 声道**，约 61 kbps |
| `pcm16` | 裸 PCM16LE，24000 Hz，单声道（无文件头） |

- 单声道 24 kHz 的 WAV PCM 与 MP3 都在 Android 8 原生 MediaPlayer 的格式范围内，**可以直接 `setDataSource(File)` + `prepare()` 播放**。这次没有在电视上实放（未连接设备），建议接入时先跑一次 5 秒试听。
- 体积：WAV 约 48 KB/s，MP3 约 7.6 KB/s。**同一条提示，MP3 比 WAV 小 6–7 倍**，缓存和内存都更友好。
- 网络流量：返回值是 base64，MP3 16 KB 音频 ≈ 22 KB JSON；WAV 315 KB 音频 ≈ 420 KB JSON，一次性进 `String` 再解码，峰值会多占 1 MB 以上。

### 2.4 限制与错误

| 用例 | 结果 |
|---|---|
| 非法 Key | **401** `{"error":{"code":"401","message":"Invalid API Key","type":"invalid_key"}}` |
| 非法模型 `mimo-v2.5-tts-xxx` | **400** `Unsupported model mimo-v2.5-tts-xxx` |
| 空字符串 | 200，但返回 0.8 s 静音音频（38,444 B）——**调用前必须判空** |
| 文本长度 | 未发现硬上限；720 字可合成，但耗时 105.79 s、音频 22.8 MB |
| 响应头限流信息 | 未返回 `X-RateLimit-*`；4 并发未见 429 |

**建议**：单条朗读文本 **≤ 80 字**（实测 7–14 s 音频、1.6–4.3 s 等待），超过就拆句或只读 `hint`。

### 2.5 Android 8 兼容性判断

- 协议层：HTTPS + Bearer，无需特殊签名，Android 8 的 `HttpURLConnection` 可直接调用。
- 播放层：MP3/WAV 两种容器都支持 `MediaPlayer`；24 kHz 采样率对 `MediaPlayer` 无额外要求。
- 风险点：低端电视的 MediaPlayer 对 **24 kHz MP3** 支持依赖芯片解码器，极少数定制固件可能只认 44.1 kHz。接入时保留 WAV 兜底（体积换兼容性）。

---

## 3. 提示 JSON 契约

### 3.1 契约定义

```json
{
  "hint": "一句话启发，20~40 字，不含答案",
  "steps": ["第一步，指出方法，不含结果", "第二步，提示自查方式"],
  "concept": "考查的知识点",
  "pitfall": "常见错误，不含数字与错误结果举例",
  "need_more_info": false
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `hint` | string | 是 | 20–40 字；不含正确答案、不含选项文本、不含计算结果 |
| `steps` | string[] | 是 | 1–3 条；每条 ≤ 40 字；不出现“= 结果” |
| `concept` | string | 是 | ≤ 20 字，用于家长复盘 |
| `pitfall` | string | 是 | 禁止“容易算成 X”这类错误结果举例；禁止数字 |
| `need_more_info` | boolean | 是 | 仅接受 `true/false`；字符串 `"无"` 一律按 `false` 处理 |

**下发内容边界**：只发题干，**不发 `options`、不发 `answer`、不发 `answerText()`**。当前题库 `questions.json` 的选项里就含正确值，发出去等于送答案。

### 3.2 System Prompt（可直接使用）

> 注：下划线部分条款在实测版本中已验证；第 2 条的口诀禁令是在实测泄露后追加的加固条款。

```text
你是"作业教练"，陪伴小学生写作业。你只给启发式提示，绝不给出答案。

硬性规则：
1. 不计算、不给出任何算式的结果；不说出任何一个选项内容；不出现"答案是""选X"等表述。
2. 禁止使用"三六十八"这类包含结果的乘法口诀原文；禁止"容易算成6或9"这类错误结果举例。
3. 只输出一个 JSON 对象，不要 Markdown、不要代码块、不要任何解释性前后缀。
4. 字段固定为：hint（字符串，一句话启发，20~40 字）、steps（字符串数组，1~3 条，每条都不含结果）、
   concept（字符串，考查的知识点）、pitfall（字符串，常见错误，不含数字）、need_more_info（布尔值）。
5. 全部使用简体中文，语气温和、鼓励，不夹英文。
6. 题目信息不足或无法判断时，need_more_info 置为 true，hint 说明缺少什么信息。
```

User 消息模板：

```text
年级：小学二年级
学科：数学
题目：{{question.text}}
```

### 3.3 真实返回样例（62 - 25 = ?，正确答案 37 未下发，Agnes 耗时 3.56 s）

```json
{
  "hint": "先看看个位上的2够不够减5，想一想是否需要从十位借1再计算哦。",
  "steps": [
    "观察个位数字是否足够直接相减",
    "回忆退位减法的计算步骤",
    "尝试分步计算个位和十位"
  ],
  "concept": "100以内数的退位减法",
  "pitfall": "个位不够减时忘记从十位退1，导致十位计算错误",
  "need_more_info": false
}
```

该样例未出现答案 `37`，可安全朗读。

### 3.4 前端容错策略（必须实现）

| 情况 | 处理 |
|---|---|
| HTTP 非 200（401/404/429/5xx） | 不弹错误码给孩子；走本地兜底提示；429 额外进入冷却 |
| 超时 | 硬超时后显示“提示还没准备好，先自己想一想”，答题不受影响 |
| 非 JSON / 带 Markdown 包裹 | 去 BOM、去 ``` 包裹、用**括号配对扫描**取第一个完整 `{...}`；仍失败则视为不可用 |
| 字段缺失 | `hint` 缺失 → 用前两条 `steps` 拼接；`steps` 缺失 → 空数组；`concept/pitfall` 缺失 → 隐藏对应行 |
| 类型不符 | `need_more_info` 只认 `Boolean.TRUE`；`"无"/"false"/0` 均转 `false`；`steps` 非数组则丢弃 |
| 超长 | `hint` 截 60 字，`steps` 截 3 条 / 每条 40 字，去换行 |
| 答案泄露 | 命中正确答案（阿拉伯数字或中文数字）或任一选项文本 → 丢弃该提示，降级本地模板 |
| 连续失败 3 次 | 熔断 10 分钟，只显示本地兜底提示，避免每次都等 10 秒 |

**答案泄露检测建议**：
1. 禁词集合 = `answerText()` + 正确答案数字 + 正确答案的中文写法；
2. 对 `hint/steps/pitfall` 逐字段检测，命中即整条丢弃（因为语音会读出来）；
3. 额外规则：`pitfall` 中只要出现任何阿拉伯数字就清空该字段（正常错误描述不需要数字）；
4. 陷阱：`三六十八` 这类中文口诀无法靠数字匹配，必须靠 prompt 禁令 + “出现连续中文数字 ≥2 字且不在题干中”时丢弃。

### 3.5 “提示不可用”不阻断答题

- 提示按钮是**可选项**，答题选项始终可点，不出现覆盖式 loading。
- 本地兜底提示按题型预置（无需 Key、0 延迟）：
  - 加法：“把两个数合起来，可以从大的数往后数。”
  - 减法：“先看个位够不够减，不够就向十位借一。”
  - 乘法：“想一想对应的乘法口诀，先确定有几个相同的数相加。”
  - 除法：“想一想几个几相加等于总数，或者用乘法倒推。”
- 提示面板显示“AI 提示（可能较慢）”与“本地提示”，家长可在设置里关闭 AI 提示。

---

## 4. 语音方案对比与推荐

### 4.1 电视端离线 TTS 可用性（基于已有只读快照）

现有 `tv_adb_tool/备份/20261104_201436/包列表_启用.csv`（该机只读体检结果）显示：

```
com.svox.pico,/system/app/PicoTts/PicoTts.apk,启用
```

- 这是 AOSP 自带的 PicoTts，**全机唯一 TTS 引擎**；没有 `com.google.android.tts`、没有讯飞/度小量等中文引擎。
- PicoTts 自带语音数据有限（常见为英语等少数语种），**没有中文普通话语音包**；`TextToSpeech.setLanguage(Locale.CHINA)` 预计返回 `LANG_NOT_SUPPORTED`，强行 speak 会是英文腔读中文或直接无声。
- 按任务约束未连接电视做实机 `TextToSpeech` 探测；接入时应先调用一次语言可用性检查，再决定是否启用系统 TTS。

### 4.2 对比表

| 维度 | MiMo TTS v2.5 | Android 系统 TTS（本机 PicoTts） |
|---|---|---|
| 中文可用性 | **可用**（冰糖/茉莉/苏打/白桦） | **不可用**（无中文语音数据） |
| 断网表现 | 不可用（纯在线） | 可用但无中文；英文可读 |
| 延迟 | 非流式 1.6–4.3 s；流式首包 0.67 s | 本地合成，通常 < 300 ms（若语言可用） |
| 音质 | 自然、可带情绪/风格 | 机械音，中文缺失 |
| 内存/CPU | 仅解码 12–50 KB MP3 | 引擎常驻，约 10–30 MB（本机引擎很小） |
| Key 是否进 APK | 不需要（放本地设置） | 完全不需要 |
| 缓存 | 可按文本缓存 MP3，重复朗读 0 成本 | 无需缓存 |
| 实现复杂度 | HTTP + base64 + 文件 + MediaPlayer | `TextToSpeech` 初始化 + 语言检查 |
| 适合场景 | 中文提示朗读首选 | 仅作“有中文引擎”的备选 |

### 4.3 推荐与降级顺序

**推荐：MiMo TTS 作为中文朗读主方案，MP3 格式，音色 `冰糖`，只朗读 `hint`（可选朗读 steps）。**

降级顺序（逐级失败才往下走）：

1. **本地音频缓存命中**（同一提示文本 SHA-1 命中）→ 0 延迟直接播；
2. **MiMo 在线合成**（Key 已配置且联网）→ 写入缓存后播放；
3. **Android 系统 TTS**（仅当 `isLanguageAvailable(Locale.CHINA) >= LANG_AVAILABLE`；本机判定为不可用，实际会跳过）；
4. **纯文字**：提示照常显示，不发声，不报错、不阻断答题。

失败判定：网络异常、401（Key 失效）、429、超时 15 s、音频字节为 0、MediaPlayer `onError`。

### 4.4 缓存策略

- Key：`sha1(text + voice + format)`；目录 `getCacheDir()/tts/`；
- 上限：30 条，LRU 淘汰（MP3 约 1.5 MB 封顶；不要缓存 WAV，30 条会到 9 MB）；
- 同一题再次答错时直接复用音频；
- 家长关闭 AI 提示时，缓存一并清理。

---

## 5. 密钥管理

### 5.1 三种方案对比

| 方案 | 风险 | 复杂度 | 断网 | 评价 |
|---|---|---|---|---|
| Key 写进 APK | 反编译（apktool/jadx）即可拿到；APK 一旦转发即泄露；无法单独吊销 | 最低 | 无影响 | **不推荐** |
| 家长设置页填写，存本地 | Key 只在本机；反编译拿不到；泄露面小；可随时清除 | 低（可复用现有家长页 + 局域网设置页） | 无影响 | **推荐（v1）** |
| 局域网小代理 | Key 只在 PC/NAS；电视只有内网地址，无 Key | 中（代理需常开；多一个故障点） | 代理可缓存离线音频 | 多电视/高安全要求时选 |

### 5.2 存储建议

- 位置：沿用 `Prefs` 的 `SharedPreferences`（`quiz_home`，`MODE_PRIVATE`），新增 `llm_key` / `tts_key` / `tts_voice` / `ai_hint_enabled`。
- `MODE_PRIVATE` **不是加密**：root 设备或备份可直接读。建议再加一层 **Android Keystore AES/GCM**（API 23+，API 26 可用）：Keystore 内生成不可导出密钥，SP 里只存密文。
- 输入方式：电视遥控器打字很痛苦。可复用现有 `QuestionHttpServer`（设置页局域网服务）加一个 `POST /key`，**用电脑浏览器填 Key**，退出设置页即停服务（符合现有“无常驻 Service”约定）。
- 禁止：`Log` 打印 Authorization；把 Key 写进 `BuildConfig`、`strings.xml`、`assets`、`DESIGN.md`、Git 仓库。
- Key 已在本会话明文出现过，**建议验证完即到控制台轮换/作废**，再填入电视。

---

## 6. 性能预算（1.84 GB / 可用约 0.77 GB）

| 项目 | 预算 / 建议 |
|---|---|
| Agnes 连接超时 | 3 s |
| Agnes 读取超时 | **10 s**（实测最长 14.3 s，10 s 后走兜底更符合儿童耐心） |
| 总等待上限 | 13 s；UI 立即显示“正在想提示…”，可取消 |
| TTS 超时 | 15 s；流式可选，非流式实现更简单 |
| 并发 | **同时最多 1 个 Agnes + 1 个 TTS**；禁止并发出题（已实测 429） |
| 提示缓存 | 内存 LRU 100 条（约 100 KB）+ 磁盘 JSON 200 条；按题目文本哈希 |
| 预取 | 答完当前题后，后台预取**下一题**的提示；进入答题页时预取第 1 题 |
| TTS 缓存 | 30 条 MP3，约 1.5 MB |
| 单次内存峰值 | JSON ≈ 1 KB；MP3 base64 ≈ 22 KB；MediaPlayer 解码缓冲 < 1 MB；**总峰值 < 5 MB** |
| 长音频风险 | 720 字 → 105 s / 22.8 MB，**禁止整段朗读**；只读 `hint`（≈6–8 s 音频） |
| 可行性判断 | **可行**。瓶颈是网络延迟不是内存；体验关键是“可取消 + 本地兜底 + 预取”，而不是更快模型 |

---

## 7. 电视端实现接口清单（方法签名级）

建议新增包 `com.family.quizlauncher.ai`，零第三方依赖，仅用 `HttpURLConnection`、`org.json`、`MediaPlayer`、`TextToSpeech`。

```java
public final class HintRequest {
    public HintRequest(String question, String grade, String subject);
}

public final class Hint {
    public final String hint;
    public final List<String> steps;
    public final String concept;
    public final String pitfall;
    public final boolean needMoreInfo;
}

public interface HintCallback {
    void onHint(Hint hint, boolean fromCache);
    void onUnavailable(int reason);   // 0=offline 1=auth 2=rate 3=parse 4=timeout
}

public final class HintClient {
    public HintClient(Context context);
    public void request(HintRequest req, HintCallback cb);  // 异步，内部单线程
    public void cancel();
    public void close();
}

public final class HintGuard {
    public static Hint sanitize(String rawResponse, String questionText, String correctAnswerText);
    public static boolean leaks(String text, String correctAnswerText, String[] options);
}

public final class HintCache {
    public static HintCache get(Context context);
    public Hint get(String questionText);
    public void put(String questionText, Hint hint);
    public void prefetch(HintRequest req);
    public void clear();
}

public interface SpeechEngine {
    boolean isAvailable();
    void speak(String text, String cacheKey);
    void stop();
    void release();
}

public final class MimoTtsEngine implements SpeechEngine { /* 在线 + 文件缓存 */ }
public final class AndroidTtsEngine implements SpeechEngine { /* 语言可用性检查 */ }
public final class SpeechRouter {           // 按降级顺序调度
    public void speakHint(Hint hint, boolean readSteps);
    public void stop();
    public void release();
}

public final class ApiKeys {
    public static boolean hasLlmKey(Context c);
    public static boolean hasTtsKey(Context c);
    public static String getLlmKey(Context c);
    public static String getTtsKey(Context c);
    public static void saveLlmKey(Context c, String key);
    public static void saveTtsKey(Context c, String key);
    public static void clearAll(Context c);
}
```

现有工程的接入点（**本任务未改动代码**）：

- `QuizActivity`：题目区增加“思路提示”按钮；`onHintClicked()` 调 `HintClient.request(...)`，回调后填面板；选项按钮始终可点。
- `Prefs`：新增 `getLlmKey/setLlmKey`、`getTtsKey/setTtsKey`、`isAiHintEnabled/setAiHintEnabled`、`getTtsVoice/setTtsVoice`。
- `ParentActivity`：新增“AI 提示 / 语音朗读 / Key 填写”区块，保存前走 PIN 校验。
- `QuestionHttpServer`：新增 `POST /key` 与 `GET /settings`，仅在设置页运行，退出即停。

---

## 8. 未验证项与边界（避免误读）

1. **未连接电视**：MediaPlayer 实机播放、Android 8 的 TLS 握手、`TextToSpeech` 语言探测均未在真机执行。
2. 系统 TTS 的“无中文”结论基于现有只读包列表快照（2026-11-04 20:14），未跑 `isLanguageAvailable`。
3. MiMo 官方文档未给出“单次文本上限”和“试用额度”具体数字；实测 720 字可合成，但不能据此当作无限制。
4. Agnes 流式首包 14.54 s 只测到一次，不能排除当时处于限流恢复期；结论只用于“别指望流式救延迟”。
5. `json_schema` 虽返回 200，但未严格生效，**不要用于强制字段**。
6. 以上耗时均为家庭宽带环境，电视 Wi-Fi 可能更慢；建议在电视上把读取超时放宽到 10 s，并把提示设为非阻塞。

---

## 9. 一句话落地建议

提示走“**Agnes 非流式 + json_object + 13 s 硬超时 + 答案泄露拦截 + 预取缓存 + 本地兜底**”，语音走“**MiMo MP3 + 家长填 Key + 缓存 + 系统 TTS/纯文字降级**”，Key 一律不进 APK。
