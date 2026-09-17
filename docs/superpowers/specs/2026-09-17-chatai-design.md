# ChatAI 设计规格（Claude 风格 AI 聊天应用）

- 日期：2026-09-17
- 状态：已确认，进入实施
- 参考设计：仓库根目录 `DESIGN.md`（Anthropic/Claude 官网设计系统分析）

## 1. 目标与范围

在单模块 Compose 脚手架（AGP 9.4.0 / Kotlin 2.2.10 / BOM 2026.02.01）上，构建一个
OpenAI 兼容、多会话、流式输出的 AI 聊天应用，视觉完全落地 DESIGN.md 的
「暖奶油画布 + 珊瑚主色 + 深色产品面 + 衬线标题」体系，并支持深浅色。

**范围内**：主题系统、聊天界面、Markdown（含代码高亮、表格、LaTeX 行内+块级）、
流式对话（取消/重试）、多会话持久化（Room）、设置页（baseUrl/key/model/系统提示词/主题）。

**范围外（YAGNI）**：登录/云同步、图片与多模态、语音、Function Calling、
Hilt/导航库/多模块、消息编辑重发、LaTeX 完整 TeX 引擎、Mock 数据模式（用户自测真 key）。

## 2. 关键技术决策

| 决策 | 选择 | 依据 |
|---|---|---|
| 模型服务 | OpenAI 兼容，baseUrl/model/key 可配，内置预设（OpenAI/DeepSeek/DashScope/Moonshot/Ollama） | 一套代码通吃 |
| Key 存储 | 应用内设置页 → DataStore；从云备份排除；不写日志 | |
| 网络层 | 手搓：OkHttp 5.5.0 + `okhttp-sse` + kotlinx.serialization 1.11.0 | 无 Ktor 依赖链，完全可控 |
| Markdown | `com.mikepenz:multiplatform-markdown-renderer-m3/-code:0.45.0` | 原生 Compose、GFM 表格、流式、代码高亮 |
| LaTeX | 移植 Kai（SimonSchubert/Kai，Apache-2.0）`ui/markdown/math/` 4 文件到 `ui/md/latex/`；行内走 mikepenz 的 `MarkdownAnnotator` + `markdownInlineContent` 钩子，块级走自研分段器 | 唯一「主题自适应 + 流式容错 + 非 GPL + 无 WebView」方案 |
| 屏幕方向 | 竖屏锁定 | 用户选择 |
| AI 内容宽度 | 严格 90% 视窗宽（`fillMaxWidth(0.9f)` 居中 ⇒ 左右各 5%） | 用户规格 |
| 深色/浅色 | 双套色板 + 设置内三态切换（跟随系统/浅/深） | |
| 字体 | 正文/UI：Inter（打包）；标题衬线：系统 `FontFamily.Serif`（中文走 Noto Serif CJK 回退）；代码：JetBrains Mono（打包） | Copernicus/StyreneB 无公开授权文件；`serif_display.ttf` 槽位保留，日后可覆盖 |

### 2.1 字体槽位（`app/src/main/res/font/`）

| 文件 | 实际内容 | 替换对象 |
|---|---|---|
| `sans_ui.ttf` | Inter variable（OFL） | StyreneB |
| `sans_ui_italic.ttf` | Inter Italic variable | |
| `mono_code.ttf` | JetBrains Mono variable | 同 DESIGN.md |
| `mono_code_italic.ttf` | JetBrains Mono Italic | |
| （无文件） | 系统 serif 家族 | Copernicus / Tiempos Headline；日后放 `serif_display.ttf` 即覆盖 |

变量字体权重通过 `FontVariation.Settings(FontVariation.weight(n))` 取用（minSdk 29 支持）。

## 3. 设计系统映射（DESIGN.md → Compose）

### 3.1 浅色

`background/surface`=canvas `#faf9f5`；surfaceContainer 阶梯 `#f5f0e8`→`#efe9de`→`#e8e0d2`；
`onSurface`=ink `#141413`；`primary`=coral `#cc785c`；`onPrimary`=`#ffffff`；
`outline`=hairline `#e6dfd8`；`onSurfaceVariant`=muted `#6c6a64`；
secondary=teal `#5db8a6`；tertiary=amber `#e8a55a`；error `#c64545`。

### 3.2 深色（由深色产品面语义推导）

`background`=`#181715`；`surface`=`#1f1e1b`；surfaceContainer 阶梯 → `#252320` 系；
`onSurface`=`#faf9f5`；`onSurfaceVariant`=`#a09d96`；`outline`=`#3a3833`；
primary 保持 coral（`#cc785c`）。

### 3.3 扩展 token（`LocalChatColors`，M3 ColorScheme 之外）

hairline / hairlineSoft / canvas / surfaceSoft / surfaceCard / surfaceCreamStrong /
surfaceDark / surfaceDarkElevated / surfaceDarkSoft / onDarkSoft / accentTeal / accentAmber /
success / warning / bubbleUser / bubbleUserText / codeBackground / codeOnBackground。

代码块（明暗一致）：`surface-dark` 底 + `on-dark` 字 + `surface-dark-elevated` 按钮底。

### 3.4 字阶（移动端缩放）

display 40/32/28sp（serif 400，负字距 -0.5~-0.2sp）· title 22/18/16sp（sans 500）·
body 16/14sp（sans 400，lh 1.55）· caption 13sp/500 · labelSmall 12sp/500/1.5sp ·
code 13sp mono/1.6。

`dynamicColor` 必须禁用（否则壁纸色覆盖品牌色）。

### 3.5 品牌符号

Anthropic 四芒星（spike mark）用 Canvas/Path 绘制，用于抽屉字标、空态、流式指示。

## 4. 聊天界面规格

- **顶栏** 64dp：canvas 底 + 1px hairline；左 = 36dp 圆形按钮开抽屉；中 = 标题 + 模型名；
  右 = 新对话。
- **消息列表**：LazyColumn，横向零内边距（保证 AI 内容精确 90%）。
- **用户消息**：coral 实心气泡、白字、居右、`≤85%` 宽、圆角 12dp（右下 4dp）。
- **AI 消息**：无框无底色无头像、居左、`fillMaxWidth(0.9f)` 居中 ⇒ 严格 90% 视窗宽；
  Markdown 渲染（h1-h3 衬线、行内代码 mono + 底色、引用 coral 竖线、链接 coral 下划线、
  表格 hairline 描边、代码块 = 深色 code-window-card + 语言标签 + 复制钮 + 横向滚动）。
- **公式**：行内 `$…$` 走 `MarkdownAnnotator.appendInlineContent` → Kai `MathRenderer`；
  块级 `$$…$$` 由分段器切出单独渲染（可横向滚动）。降级策略：行内尺寸估算失败时按等宽文本。
- **输入栏**：surface-card 输入卡 + 16dp 圆角 + hairline；聚焦 = coral 边框 + 3px 15% 外环；
  多行最多 6 行；右侧圆钮三态（发送/停止/禁用）。
- **长按消息** → 深色 ModalBottomSheet：复制 / 重新生成(AI) / 删除。
- **流式**：spike mark 脉冲；用户上滑后停止自动滚动并显示「回到底部」圆钮。
- **错误**：error 文案 + 「重试」coral 链接；停止后显示「已停止生成」+ 重新生成。
- **空态**：spike mark + 衬线大标题 + muted 副文案 + 3 个建议胶囊（点击填入输入框）。
- **抽屉**：surface-soft 底；字标；coral 全宽「新对话」；会话列表（标题/摘要/时间，选中
  surface-card）；长按项 → 重命名/删除；底部设置入口 + 当前模型。

## 5. 架构与文件结构

单模块，无 DI 框架，`Application` 内手写 ServiceLocator。

```
com.zcw.chatai/
  ChatAiApp.kt            Application + ServiceLocator
  MainActivity.kt         edge-to-edge + 主题 + setContent
  App.kt                  顶层路由 Chat/Settings + 抽屉 + 主题模式
  data/
    AppDatabase.kt  ConversationDao.kt  MessageDao.kt
    ConversationEntity.kt  MessageEntity.kt
    SettingsRepository.kt   DataStore
    ChatRepository.kt       唯一业务入口
    ChatApi.kt  OpenAiCompatibleChatApi.kt
    dto/ChatDtos.kt
    model/                  Conversation Message ChatConfig ChatStreamEvent
  ui/
    chat/  ChatScreen ChatViewModel ChatTopBar MessageList UserMessageItem
           AiMessageItem ChatInputBar EmptyChatState MessageActionsSheet
           ScrollToBottomButton StreamingIndicator
    md/latex/  MathAtom MathParser MathRenderer MathSymbols（vendored, Apache-2.0）
    md/  LatexSplitter（块级分段器）MessageMarkdown（mikepenz 封装+annotator 钩子）
    drawer/ConversationDrawer
    settings/SettingsScreen SettingsViewModel
    theme/  Color ChatColors Type Theme SpikeMark
```

## 6. 数据库（Room 2.8.5，schema v1）

```sql
conversations(
  id TEXT PK, title TEXT, model TEXT, system_prompt TEXT,
  created_at INTEGER, updated_at INTEGER,
  last_message_preview TEXT, message_count INTEGER DEFAULT 0,
  is_pinned INTEGER DEFAULT 0)
INDEX (updated_at DESC)

messages(
  id TEXT PK, conversation_id TEXT FK→conversations ON DELETE CASCADE,
  role TEXT, content TEXT, status TEXT,
  error_message TEXT, reasoning_content TEXT, seq INTEGER,
  model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER,
  created_at INTEGER, updated_at INTEGER)
INDEX (conversation_id, seq)
```

- UUID 主键（导入导出无需重映射）；`seq` 保证同毫秒稳定排序；
  `last_message_preview`/`message_count` 反范式避免列表 N+1；级联删除。
- 不开启破坏性迁移；后续 schema 变更补 Migration。

## 7. 流式对话

- `POST {baseUrl}/chat/completions`，body：`model/messages/stream:true/temperature?`；
  baseUrl 归一化（末尾无 `/v1` 则补）。
- `EventSources` → `callbackFlow`；解析 `delta.content`、`delta.reasoning_content`、
  `finish_reason`、`usage`、`[DONE]`；非 2xx 读 body 解析 OpenAI 错误体 → 可读中文错误。
- 取消：`awaitClose { call.cancel() }`；保留已产出内容，状态 `cancelled`。
- 上下文：system prompt + 会话全部有效消息，超 40 条从最早截断。
- UI 更新节流 ~60ms；DB checkpoint ~800ms + 结束写终值。
- 切换会话取消当前流；不自动重试。

## 8. 依赖（版本已核实 2026-09）

KSP `2.2.10-2.0.2` · kotlin-serialization 插件 `2.2.10` · Room `2.8.5` ·
DataStore `1.2.1` · Lifecycle `2.11.0` · OkHttp/okhttp-sse/mockwebserver `5.5.0` ·
kotlinx-serialization-json `1.11.0` · markdown-renderer `0.45.0`。
全部经 `gradle/libs.versions.toml`，无硬编码版本。

## 9. 里程碑与验收

| # | 内容 | 验收 |
|---|---|---|
| M1 | 主题地基 + 组件画廊页 | assembleDebug + 浅/深截图 |
| M2 | 数据层 | JVM 单测 + 构建 |
| M3 | 网络层 | MockWebServer 单测（分片 SSE/UTF-8 断包/[DONE]/错误体/取消） |
| M4 | 聊天 UI 静态版（含 LaTeX） | 截图（空态/多消息/代码/表格/公式 × 浅深） |
| M5 | 接线（真流式 + 抽屉） | 真 key 端到端 + 录屏 |
| M6 | 设置页 | 截图 + 断网/错 key 路径 |
| M7 | 打磨验收 | 设备走查 + lint + test 全绿 |

每步：`.\gradlew.bat assembleDebug`、`.\gradlew.bat test`；M4 起用 scrcpy MCP 截图验收。

## 10. 风险

1. markdown-renderer 0.45.0 若要求更高 Compose → 升级 BOM 至 2026.09.00。
2. KSP2 + AGP 9.4 首次构建验证；失败降级 KAPT。
3. 白字压 coral 对比度约 2.6:1（品牌优先）；若不满意可切 ink 字色。
4. 行内公式 Placeholder 尺寸估算可能需 1-2 轮调优；降级为等宽文本。
5. Kai 代码为 CMP 风格，vendoring 时需做包名与 API 清理（保留 Apache-2.0 版权头 +
   第三方声明）。
6. 严格 90% 在竖屏无碍；上平板后行长偏长需再调。

---

## 11. 修订（2026-09-17 晚）：多模态 + 思考模式 + 里程碑重排

### 11.1 接口现状（真接口实测，详见 `AGENTS.md`）

`deepseek-flash`（支持图片、思考默认开 effort=high、1M 上下文）/ `deepseek-v4-pro`（无视觉）。
图片走标准 `image_url` + **内联 base64 data URL**（外部 URL 实测被防盗链拒绝），
只能出现在 `user` 消息；`detail:"low"` 实测省 5 倍图片 token（1029→199）。
`reasoning_effort` 是标准字段（`none` 即关思考），无需厂商专有 `thinking`。

### 11.2 本轮范围（已确认）

**做**：图片输入（相册多选/拍照/粘贴，≤8 张，压缩副本落私有目录）；思考过程展示；
`reasoning_effort` 强度设置；`GET /models` 模型选择；错误/截断精确映射；附加参数 JSON 逃生口。
**不做**：语音、文档/Files API、视频、图片生成、Anthropic 端点、Function Calling、`thinking` 专有字段。

**默认值**：模型 `deepseek-flash`；不发 `reasoning_effort`（尊重服务端默认）；不发 `max_tokens`；
图片精度标准档；历史图片只重发最近 2 条（出站请求里更早的替换为 `[图片已省略]`，DB 不动）；
只存压缩副本（长边 1568px JPEG q85 + 320px 缩略图）。

### 11.3 数据模型变更（写 Migration，不破坏性迁移）

**v1 → v2**：`messages` 增三列：`attachments`（TEXT，JSON 数组）、`reasoning_tokens`、`cached_tokens`。
图片二进制不入库，落 `filesDir/attachments/{conversationId}/{id}.jpg`（+ `.thumb.jpg`）。

**v2 → v3**：`messages` 增 `reasoning_ms`（INTEGER，可空）= 思考耗时。
设计取舍：① 存**毫秒**不存秒——精度不丢，界面能自由格式化成「9s」或「1分12秒」；
② **NULL 明确表示未测量**（老消息、没有思考的消息），与「0ms（瞬间完成）」区分，界面此时只显示
「已深度思考」而不假装 0s；③ 与 `reasoning_tokens` 成对：一个「花了多少 token」、一个「花了多久」；
④ 用 `SystemClock.elapsedRealtime()`（单调钟）测量，墙钟被 NTP 调整也不会落库一个荒谬时长。
口径：回合开始（发请求）→ **最后一个 reasoning 增量**，回答开始就定住，不跟着正文涨。

### 11.4 兼容性四原则

① 线上只用标准交集；② 厂商差异靠数据消化（预设基址表、`ModelCapabilities`、附加参数 JSON）；
③ 不预设能力，不支持时给可读提示（v4-pro 收到图片是**静默丢弃**，必须客户端提示）；
④ 请求体不因厂商分叉。

### 11.5 里程碑（重排）

| # | 内容 | 验收 |
|---|---|---|
| M5 | 接线 + 思考过程：ChatRepository / ChatViewModel / ConversationDrawer、DB v2、错误映射、取消/重试/切会话、ReasoningBlock、附加参数 | 真 key 端到端 + 截图；`ApiErrorMapperTest` |
| M6 | 图片输入：picker/拍照 → 压缩 → 存储 → 组装 → 展示 → 预览；能力提示；历史图片降级 | 真 key 发图问答 + 截图；`ImagePayloadTest` |
| M7 | 设置页：baseUrl/key/模型（拉 `/models`）/系统提示词/思考强度/回复上限/图片精度/附加参数/主题/历史图片策略/测试连接 | 截图 + 错误路径 |
| M8 | 打磨：行内公式防拆行、宽表格横向滚动、IME、用量成本展示、lint/test 全绿 | 设备走查 |
