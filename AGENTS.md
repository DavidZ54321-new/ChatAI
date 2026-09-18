# ChatAI

单模块 Jetpack Compose + Material 3 的 AI 聊天应用，视觉落地仓库根目录的 `DESIGN.md`
（Anthropic/Claude 设计系统：暖奶油画布 + 珊瑚主色 + 衬线标题 + 深色产品面）。
注意 `DESIGN.md` 是**营销页**文档，第 588 行明确 chat bubbles / message tools / file chips 等
「out of scope」——气泡、缩略图、思考行这类产品面以真实产品观感为准，别硬套营销页 token
（尤其：coral 只给主 CTA 与整块 callout，**用户气泡用中性面色**）。

架构是**手写 ServiceLocator + 单向数据流**（无 Hilt、无导航库，单 Activity 两屏）：

```
ChatAiApp (Application)  →  懒加载单例：AppDatabase / SettingsRepository / ChatApi / ChatRepository
MainActivity             →  ChatAITheme + 主题模式 + 路由（Chat ↔ Settings）
data/model               →  Conversation / Message / ChatConfig / Attachment / ToolSource / ConversationTitle
data/db                  →  Room 2（schema v4）：ConversationEntity / MessageEntity / DAO / Mappers / Migrations / ToolCallCodec
data/ai                  →  纯逻辑（可 JVM 单测）：ContextBuilder / StreamAccumulator / ToolCallAccumulator / AgentLoop / ReasoningPreview / ReasoningDuration
data/prefs               →  SettingsRepository（DataStore，key 只存本机）
data/net                 →  ChatApi 接口 + OpenAiCompatibleChatApi（okhttp-sse + callbackFlow）
data/web                 →  联网工具：WebSearchProvider / DeepSeekNativeSearchProvider（Anthropic web_search）/ WebFetcher / HttpWebFetcher / HtmlToText / WebTools
data/media               →  图片压缩与私有目录存储（ImageCompressor / AttachmentStore）
data/ChatRepository      →  唯一业务入口：落库 → 组上下文 → 有界 Agent 循环（流式 → 工具 → 再流式）
ui/chat                  →  ChatScreen / ChatViewModel / ChatUiState / Composer / MessageItems / ReasoningBlock / ToolCallBlock / Attachments / ChatMetrics
ui/drawer                →  ConversationDrawer
ui/settings              →  SettingsScreen / SettingsViewModel
ui/md                    →  MessageMarkdown（mikepenz）+ LatexSplitter + latex/（vendored Kai，Apache-2.0）
ui/theme                 →  设计系统（Color / ChatColors / Type / Theme / SpikeMark）
```

两条不该踩第二次的约定：

- **视觉尺寸统一走 `ui/chat/ChatMetrics.kt`**（相对**视窗**而非父容器）：消息里图片缩略图 =
  视窗宽 20% 的正方形、顶消散尾巴 = 视窗高 4%（按钮行内不透明）、底消散引导 =
  视窗高 3%（贴 Composer 上沿）。调观感只动这些常量，各配纯函数单测。
- **思考耗时的口径**：DB 里 `messages.reasoning_ms`（毫秒，**NULL = 未测量**，与「0ms 瞬间完成」
  区分开）。测量用 `SystemClock.elapsedRealtime()`（单调钟，墙钟被 NTP 跳会落库荒谬值），
  口径 = 回合开始 → **最后一个 reasoning 增量**（不是第一个正文增量：输出顺序不保证）。
  UI 侧的秒数和落库值是同一个测量，别再用墙钟自己算一遍。


## Build & test

```pwsh
.\gradlew.bat assembleDebug        # build only, no device needed
.\gradlew.bat installDebug         # build + install to the connected device
.\gradlew.bat test                 # JVM unit tests (app/src/test)
.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.net.ChatStreamTest"
.\gradlew.bat lint                 # AGP default; no formatter or typecheck task is configured
```

单测全是 JVM 测试（246 个）：网络层用 MockWebServer，其余是纯函数（错误映射、压缩尺寸、
能力表、LaTeX 分段、Markdown 行内公式、思考摘要/耗时格式化、视觉度量、Room 映射往返、
工具调用累加/编解码、Agent 决策、HTML→文本、搜索响应解析）。

## AGP 9 DSL — differs from most examples you'll find

AGP 9.4.0 / Gradle 9.6.0 / **Kotlin 2.4.10**（AGP 9 内置 Kotlin）/ KSP 2.3.12。AGP 9 的 DSL：

- `compileSdk { version = release(37) }` — not `compileSdk = 37`
- R8 is `buildTypes { release { optimization { enable = false } } }` — not
  `isMinifyEnabled` / `minifyEnabled`
- R8 keep rules live in `app/src/main/keepRules/*.keep` (currently `rules.keep`),
  **not** `proguard-rules.pro`

Kotlin 版本必须与 `markdown-renderer 0.45.0` 自带的 stdlib 对齐（2.4.10）；根
`build.gradle.kts` 用 `buildscript { classpath(...) { version { strictly(...) } } }`
把内置 Kotlin 钉住，KSP 在 AGP 9 下原生工作（**不需要** `android.disallowKotlinSourceSets=false`）。

## Versions

All dependency and plugin versions live in `gradle/libs.versions.toml` and are
referenced via `libs.*` in `app/build.gradle.kts`. There are no hardcoded versions
in the build script — keep it that way.

## DeepSeek / OpenAI 兼容端点的事实（2026-09 实测，勿凭记忆改）

我们在真接口上逐条验证过（`https://api-docs.deepseek.com/zh-cn`）：

- 模型：`deepseek-flash`（V4.1，**支持图片**，思考默认开，1M 上下文）/ `deepseek-v4-pro`
  （**不支持图片**）。旧名 `deepseek-chat`、`deepseek-v4-flash*` 仍可用但被静默重定向到 Flash。
- baseUrl 归一化后 `https://api.deepseek.com/v1/chat/completions` 与不带 `/v1` 都可用。
- **图片只能出现在 `user`/`tool` 消息**，放 system 会 400（`Image in system message is unsupported`）。
  内容块是标准 OpenAI 形状：`{"type":"text"}` / `{"type":"image_url","image_url":{"url","detail"}}`。
- **必须内联 base64 data URL**：外部 URL 方式连它自家 CDN 都 `Failed to download image`（防盗链）。
- 服务端会拒绝过小的图片（1×1 报 `unsupported image`），格式按内容嗅探（JPEG/PNG/GIF/WebP）。
- `detail: "low"` 实测把同一张图的输入 token 从 **1029 → 199**；单图 token 上限 1024。
- 思考模式**默认开启且 effort=high**，思维链走 `reasoning_content` 与 `content` 同级；
  `reasoning_effort` 取 `none|low|high|max`（`none` 即关闭思考）——这是标准字段，
  不要发 `thinking` 之类厂商专有字段。
- 思考模式下 `temperature`/`top_p` 不生效（服务端静默忽略）；`frequency_penalty`/`presence_penalty` 已废弃。
- **`max_tokens` 太小 + 思考开启 = 思维链吃光额度、`content` 为空**（实测 `finish_reason=length`）。
  默认不发送 `max_tokens`，让服务端用 64K 默认值。
- `finish_reason` 新增 `insufficient_system_resource` / `aborted`；错误码 401/402/422/429/500/503
  映射见 `data/net/ApiErrorMapper.kt`（纯函数 + 单测）。
- 流式：每个 chunk 只带 1~2 个字符、`content: null` 与 `reasoning_content: null` 交替出现，
  **usage 挂在最后一个带 `finish_reason` 的 chunk 上**（不是单独一块），以 `data: [DONE]` 收尾。
- `GET /models` 是标准 OpenAI 端点（实测返回两个模型），设置页的「拉取模型列表/测试连接」用它。

**联网搜索只在 Anthropic 兼容面**（`https://api.deepseek.com/anthropic/v1/messages`）：用服务端工具
`{"type":"web_search_20250305","name":"web_search","max_uses":N}`，回 `server_tool_use` +
`web_search_tool_result` 内容块。OpenAI 兼容面**拒绝** `web_search` 工具类型（`unknown variant`），
主对话回路仍走 OpenAI 面 + 标准 function calling（`tools`/`tool_calls`），搜索只是被调用的一个函数。
Anthropic 面有已知 bug：会把 `<｜｜DSML｜｜tool_calls>…`（`｜` = U+FF5C 全角竖线）漏进正文，务必
`tool_choice` 强制只调搜索 + 客户端兜底剥离（见 `DeepSeekSearchParser.stripDsmlMarkup`，纯字符串、无 Regex）。
Anthropic 面的 `available()` 不限制 host，兼容自建/转发代理基址。

设计原则：**不为任何厂商特制**。线上只用标准交集（`image_url` data URL、`reasoning_effort`、
`stream_options`）；厂商差异靠数据消化（预设基址表、模型能力表 `ModelCapabilities`、
设置里的「附加请求参数 (JSON)」逃生口）；不预设模型能力，不支持时给可读提示而非静默失败。

## Android skills are installed project-locally

24 official Google skills (from `android/skills`) live in `.claude/skills/`.
OpenCode reads that path, so they're available through the `skill` tool — prefer
them over guessing at Android best practice (`edge-to-edge`, `agp-9-upgrade`,
`r8-analyzer`, `testing-setup`, `navigation-3`, `styles`, `adaptive`, ...).

```pwsh
android skills add --all --agent=claude-code --project=.
```

`--agent=claude-code` deliberately keeps them from being duplicated into other
agents' directories. A skill edited in place is overwritten on update — rename it
if you need to customize one.

## Driving an emulator / device

`opencode.json` configures the project-scoped `scrcpy` MCP server: screenshot, tap,
swipe, `input_text`, `ui_find_element`, logcat, file push/pull. AVDs on this machine:
`Pixel_4`, `Pixel_6a`.

Gotchas that cost real debugging time:

- `opencode.json` hardcodes machine paths (`ADB_PATH`, `SCRCPY_SERVER_PATH`). `adb`
  is not on PATH — use `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.
- **A scrcpy session caches screen size and rotation at startup.** After a rotation
  change, screenshots come back silently rotated 90° and mis-sized, and
  `start_session` keeps reporting the physical panel size as `screenSize`. Run
  `stop_session` then `start_session` to recover; don't trust the reported
  `screenSize` for orientation.
- To verify orientation against the real framebuffer, bypass the MCP:
  `adb exec-out screencap -p > frame.png`, and cross-check with
  `adb shell dumpsys window displays` (`cur=` field).
- The `Pixel_4` AVD emulates a display cutout (171px). `enableEdgeToEdge()` does
  **not** inset for a cutout, so a window that doesn't opt in leaves an
  undrawn black band (top in portrait, left in landscape). `Theme.ChatAI` in
  `app/src/main/res/values/themes.xml` sets
  `android:windowLayoutInDisplayCutoutMode=shortEdges` — removing it brings the
  band back.
- 给模拟器塞测试图片：`adb push <file> /sdcard/Pictures/` 后跑
  `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/<file>`
  再 `adb shell cmd media_scanner scan`，系统相册才会看到。
- 输入框 IME 顶起行为在该 AVD 上无法目视验证（硬件键盘，只弹浮动工具条），需真机。

## 踩过的坑（改代码前先读这几条）

- **不要在 `Regex(...)` 里用 Java 专有语法**。Android 用的是 ICU 正则引擎，`(?U)`、`(?<name>…)`
  这类内联标志/构造会抛 `PatternSyntaxException`，而且是在**类初始化**时抛
  `ExceptionInInitializerError`，表现为「某个功能整体不可用」。JVM 单测发现不了（JVM 引擎认识它们）。
  折叠空白这类需求用 `Char.isWhitespace()` 手写循环更安全（见 `ConversationTitle.collapse`）。
  全仓库目前只有零个 `Regex`，保持这样最好。
- **用户动作不能依赖 UI 生命周期**。发消息、重试、删除这类操作一律跑在 `ChatRepository` 自己的
  scope 上（`send`/`regenerate`/`deleteMessage` 是同步返回、异步执行），否则用户点完发送立刻切后台
  就会「消息存了但回答没了」。只有在 VM 里做纯 UI 编排（选会话、清输入框）才用 `viewModelScope`。
- **LaTeX 有两种写法**：`$…$`/`$$…$$` 与模型更爱用的 `\(…\)`/`\[…\]`。`LatexSplitter` 处理块级、
  `InlineMath` 处理行内，两套都要覆盖（实测 DeepSeek 直接输出 `\[ … \]`）。
- **思考模式默认开**：不回填 `reasoning_content` 的话，用户在「思考中」上会干等几十秒。折叠式
  `ReasoningBlock` 是必需项，不是装饰。
- `adb shell input text` **不支持非 ASCII**：中文要用 `scrcpy` 的 `clipboard_set(paste=true)`。
- 模拟器坐标：用 `uiautomator dump` / MCP 的 `ui_find_element` 拿，不要靠截图目测估——
  底部手势区（约 y > 2268 @1080×2400）会误触 Home，发送按钮虽然贴着它但不在里面。
- **列表类展示一律用懒加载容器，别 `take(N)` + 计数占位**。图片行曾经 `take(3)` 加一个没有
  点击事件的 `+N`，第 4~8 张图在聊天记录里**永远看不到也点不开**；待发图用 `Row` 时 8 张会把
  输入框撑破。现在两处都是 `LazyRow`。
- **一行预览不要假设行结构**。思考摘要原本取「第一段非空行」，遇到逐字换行的推理就只剩一个字；
  改成跨行折叠空白（`ReasoningPreview`），行结构再怪也能出有意义的句子。
- **错误信息要区分原因**。okhttp 的 header 校验失败（例如 API Key 里混进中文）曾经被报成
  「Base URL 无效」。URL 用 `toHttpUrlOrNull()` 单独校验，其余组装失败报「请求参数无效：<原因>」。
- **带 `tool_calls` 的 assistant 回合必须回传 `reasoning_content`**，否则思考模式 400。所以
  `ContextBuilder` 只在 assistant 且 `toolCalls` 非空时才带 `reasoning`（普通回合不回传，避免污染上下文）。
- **每个 assistant `tool_calls` 都必须有配对的 `role=tool` 应答**，否则服务端 400。窗口截断会切出
  孤立 TOOL 行（`ContextBuilder` 用 `dropWhile` 去掉开头的 TOOL），崩溃窗口的缺失应答由
  `ChatRepository.reconcileUnansweredToolCalls` 补占位。空白工具结果也必须以占位文本保留，不能丢行。
- **schema v3→v4 的迁移没有 instrumented 测试**（仓库全是 JVM 测试，未接 `room-testing`）。纯增量列，
  已用导出的 `app/schemas/.../4.json` 人工核对；后续再加列时优先补一个 `MigrationTestHelper` 测试，
  或按下面「查设备上的库」用 `PRAGMA table_info` 手工验。
- **OkHttp 读响应体必须显式循环到 EOF**。`source.read(buffer, n)` 只保证「至少读一点」，一次通常
  只返回一个网络分片；写完就收手会把长 JSON 从中间截断。搜索接口实测这样丢掉 90% 正文，表现为
  `JsonDecodingException ... EOF at path $.content[2].content` 被吞掉 → 每次搜索都「No results found」
  → 模型转而疯狂 `web_fetch` 搜索引擎页。正确写法见 `data/web/OkHttpCall.awaitBody`。
  **症状与原因离得极远，这类「静默解析失败」要优先怀疑截断。**

## 用模拟器联调真接口（宿主机挂了会做 TLS 拦截的代理时）

宿主机开着 FlClash/Clash 这类 **TUN + fake-IP** 代理时，模拟器把 `api.deepseek.com` 解析成
`198.18.x.x`，TLS 会因「不信任中间 CA」失败（`Trust anchor for certification path not found`）——
这是环境问题，不是 App 的问题（宿主机 `curl` 正常、MockWebServer 单测正常）。模拟器又不可 `adb root`，
装不进系统信任库。可行的绕法：

1. `app/src/debug/AndroidManifest.xml` 里只对 debug 构建开 `android:usesCleartextTraffic="true"`；
2. 宿主机跑一段转发脚本（逐块 `pipe`，SSE 不会被缓冲）到真实上游；
3. `adb reverse tcp:8443 tcp:8443`，把应用内 Base URL 指向 `http://127.0.0.1:8443/v1`。

这样 App 侧只多一个明文开关（release 不含），请求/流式解析全走真实服务端。真机不受此影响。

只想验**界面**时更省事：同上 `adb reverse`，但宿主机换成一个本地 mock（伪装 `/v1/models` 与
`/v1/chat/completions` 的 SSE，返回长思考链 + 多段正文），不用真 key、不花余额、回答内容可控。

查设备上的库（db + `-wal` 一起拉，否则看不到最新写入）：

```pwsh
cmd /c "`"$adb`" -s emulator-5554 exec-out run-as com.zcw.chatai cat databases/chatai.db > q.db"
python -c "import sqlite3;print([r for r in sqlite3.connect('q.db').execute('PRAGMA user_version')])"
```

顺带：pwsh 里比较**中文字符串字面量**会被控制台编码吃掉（永远 `False`），要验内容就落到文件再读。

## Tracked-file trap

`.kotlin/` matches no `.gitignore` entry, so `git add .` will commit JetBrains
session caches（已在 `.gitignore` 里补上 `.kotlin/`）。`app/build/`, `build/`,
`.codegraph/`, `.idea/workspace.xml`, `.idea/markdown.xml` 已忽略。`.claude/skills/`
是**故意**入库的。API key 只从 DataStore 读，绝不入库、绝不打日志（`local.properties`
也不放 key）。
