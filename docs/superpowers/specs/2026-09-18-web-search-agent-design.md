# 联网搜索 + Web Fetch Agent（设计）

日期：2026-09-18
状态：待评审

## 1. 目标

在 Android 客户端内实现一个小 Agent：**联网搜索 → 思考 → 抓取网页 → 思考，循环直到输出最终回答**。不引入任何自建后端或代理，手机直连模型服务商。

验收标准：

- 输入框有一个 🌐 开关，点击后本会话启用联网搜索（按会话记住）。
- 开启后，模型可自主调用 `web_search(query)` 与 `web_fetch(url)`，循环多步，最终给出带来源链接的回答。
- 搜索/抓取过程以**内联可折叠块**出现在聊天时间线里（搜索词、来源 URL、抓取页、状态）。
- 关闭开关时，行为与现状完全一致（不注入任何 tools，不改变请求体）。
- 所有纯逻辑有 JVM 单测；最终真机联调验证。

## 2. 调研结论：可用的 DeepSeek 接口面

逐条在真接口文档上核对过（2026-09）：

| 接口面 | `web_search` | `web_fetch` | 结论 |
|---|---|---|---|
| OpenAI `/v1/chat/completions` | ❌ 只支持 `function`；`web_search` 工具类型被拒（`unknown variant 'web_search'`） | ❌ | 主回路用它做 **function calling**，搜索靠我们自己的工具实现 |
| **Anthropic `/anthropic/v1/messages`** | ✅ 服务端工具 `{"type":"web_search_20250305","name":"web_search"}`，返回 `server_tool_use` + `web_search_tool_result` 内容块 | ❌（搜索时服务端顺带读页并综合） | **默认搜索后端**；官方 Anthropic 兼容表把这两个内容块标为 Supported，Claude Code 接入页背书 |
| Responses `/responses` | ⚠️ 现文档写「忽略」，2026-07-31 存档写「服务端执行」，自相矛盾 | ❌ | 不使用，不可靠 |

已落地的同路实现（都是本地客户端直连，无自建后端）：Claude Code、DeepSeek Harness 的 `web-search-deepseek`、pi、Hermes、OpenCode。

**可行性把握**：

- `web_fetch`：确定（`okhttp GET` + HTML→文本，项目已有网络栈与权限）。
- `web_search` 客户端侧：确定（一次标准 HTTPS POST + SSE 解析）；结果可用性约 95%（取决于 DeepSeek 服务端，短期由其自家 Claude Code 文档背书）。
- 兜底：`WebSearchProvider` 接口下可换第三方搜索 API，主逻辑零改动。

## 3. 架构

```
主对话回路：OpenAI 兼容 /chat/completions（标准 function calling）
        │  开启 🌐 时注入 tools=[web_search, web_fetch]
        │
        ├─ 模型 finish_reason=tool_calls
        │      ↓  ChatRepository.AgentLoop 串行执行
        │      ├─ web_search → WebSearchProvider（默认 DeepSeekNativeSearchProvider）
        │      │                 └─ POST api.deepseek.com/anthropic/v1/messages
        │      └─ web_fetch  → HttpWebFetcher（OkHttp + Jsoup）
        │      ↓
        │   工具结果作为 role:"tool" 消息回灌 → 再次流式
        │      ↓
        └─ finish_reason=stop，或触到 step 上限 → 定稿
```

设计原则：**主回路不绑厂商**，DeepSeek 差异全部锁在 `WebSearchProvider` 的一个实现里。

## 4. 组件与职责

### 4.1 网络层（扩展，不破坏现有调用）

- `data/net/dto/ChatDtos.kt`
  - `ChatCompletionRequest` 增加 `tools: List<ChatTool>? = null`、`tool_choice: JsonElement? = null`。
  - `RequestMessage` 增加 `toolCallId: String?`（`@SerialName("tool_call_id")`）与 `toolCalls: List<RequestToolCall>?`。
  - `ChunkDelta` 增加 `toolCalls: List<ToolCallDelta>?`。
  - 新增 `@Serializable` DTO：`ChatTool(type, function)`、`FunctionSpec(name, description, parameters: JsonObject, strict=null)`、`ToolCallDelta(index, id?, type?, function: FunctionDelta?)`、`FunctionDelta(name?, arguments?)`、`RequestToolCall(id, type="function", function: FunctionCall(name, arguments))`。
- `data/net/ChatApi.kt`
  - `ChatStreamEvent` 新增 `ToolCallDelta(index, id, name, arguments)`。
- `data/net/OpenAiCompatibleChatApi.kt`
  - `buildRequest`：当 `config.webSearchEnabled` 时注入 `WebTools.specs()` 与 `tool_choice="auto"`。
  - `StreamListener.onEvent`：把 `delta.toolCalls` 逐条转成 `ChatStreamEvent.ToolCallDelta`；`role:"tool"` 请求消息的编码在 `buildRequest` 里处理（`RequestMessage` 直接映射字段）。
- `data/net/EndpointUrl.kt`
  - 新增 `anthropicMessages(baseUrl): String?`：把 base 归一到 `{origin}/anthropic/v1/messages`（去掉已存在的 `/chat/completions`、`/v1`、`/anthropic` 尾巴）。
- `data/net/dto/ChatRequestBody.kt`
  - `ProtectedKeys` 保持；不需要为 tools 开后门（走正规字段）。

### 4.2 工具协议与累加

- `data/ai/ToolCallAccumulator.kt`（纯函数 + JVM 单测）
  - 按 `index` 累积碎片的 `id`/`name`/`arguments`（arguments 是逐字符流，必须字符串拼接）。
  - 输出有序 `List<AssembledToolCall(index, id, name, arguments)>`。
  - 与 `StreamAccumulator` 同风格、同节流无关（工具调用只在回合末使用）。

### 4.3 Web 工具（新增包 `data/web/`）

- `WebSource.kt`：`data class WebSource(url, title?, snippet?, publishedAt?)`。
- `WebSearchProvider.kt`：
  ```kotlin
  interface WebSearchProvider {
      val id: String
      fun available(): Boolean
      suspend fun search(query: String, maxResults: Int): WebSearchResult
  }
  data class WebSearchResult(val answer: String?, val sources: List<WebSource>)
  ```
- `DeepSeekNativeSearchProvider.kt`（默认实现）
  - 端点：`EndpointUrl.anthropicMessages(config.baseUrl)`。
  - 请求体（Anthropic Messages 形状）：
    ```json
    {
      "model": "<会话模型>",
      "max_tokens": 2048,
      "messages": [{"role":"user","content":[{"type":"text","text":"Perform a web search for the query: <q>"}]}],
      "tools": [{"type":"web_search_20250305","name":"web_search","max_uses":3}],
      "tool_choice": {"type":"tool","name":"web_search"}
    }
    ```
  - 头：`x-api-key: <key>`、`anthropic-version: 2023-06-01`、`content-type: application/json`。
  - 解析：`content[]` 中——
    - `text` 块 → `answer`（多块拼接）；
    - `web_search_tool_result.content[]` 里 `web_search_result` → `sources`（url/title；`page_age`→`publishedAt`；`snippet` 取同回的 citation `cited_text`，没有则为 null）；
    - `server_tool_use` → 记录实际搜索 query（用于展示）。
  - **DSML 防御**：强制 `tool_choice`（已缓解已知漏出）；另加 `stripDsmlMarkup(text)` 兜底剥离 `<｜｜DSML｜｜…>`（全角竖线 U+FF5C），剥离后为空则回退 `"No results found."`。
  - `available()`：key 非空、baseUrl 可解析、host 为 DeepSeek（`api.deepseek.com` 或用户配置的 DeepSeek 兼容基址）。
- `WebFetcher.kt` / `HttpWebFetcher.kt`
  - OkHttp GET，浏览器 UA + `Accept-Language`。
  - 安全：只允许 `http`/`https`；解析 IP 后拒绝 loopback/私网/link-local；同源重定向跟随上限（跨源不自动跟）；超时 15s；`Content-Length` 与流式读取双重上限（≤200 KB）；content-type 白名单（text/html、text/plain、application/json、application/xhtml+xml）。
  - 转文本：Jsoup 去掉 `script/style/noscript/svg/nav/footer` 后，按块级元素保留换行输出纯文本（纯函数 `HtmlToText`，JVM 单测）。
  - 输出 `WebFetchResult(url, statusCode, text, truncated)`；非 2xx 也是结果，不抛异常。
- `WebTools.kt`
  - `web_search` / `web_fetch` 的两个 OpenAI function 规范（name/description/JSON schema），供请求注入与 Agent 执行共用。
  - 工具结果转文本：搜索 → `Search results for "<q>":` + 每行 `- Title — URL` + snippet + 「引用相关 URL 为 markdown 链接」；抓取 → 截断后的正文。

### 4.4 Agent 回路

- `data/ChatRepository.kt`
  - `runStream`（单趟）升级为 `runAgentTurn`（有界循环，`maxSteps` 默认 5、上限 8）。
  - 每一「模型步」= 一条 `role=ASSISTANT` 消息行；每一步的 `tool_calls` 存该行。工具结果 = `role=TOOL` 消息行。
  - 循环：
    1. 建 assistant 占位行（STREAMING），发起一次带 tools 的流式；累加 content/reasoning/toolCalls。
    2. 定稿该行（content、reasoning、tool_calls、status）。
    3. 若 `finish_reason == "tool_calls"` 且未超步数：串行执行每个工具 → 每条结果落 `role=TOOL` 行 → 追加 assistant(tool_calls, reasoning) + tool 结果到下一轮入参 → 步数+1，回到 1。
    4. 否则定稿整回合。
  - 步数耗尽仍在要工具时：追加一条合成的 tool 结果「已达到联网搜索步数上限，请基于现有信息作答」，再以 `tool_choice="none"` 做最后一次调用产出正文。
  - 取消：复用现有 `streamJob`，`stop()` 立刻生效；工具执行也检查协程取消。
  - 失败：工具失败变成 `content` 为错误串的 `role=TOOL` 结果，模型可继续或收尾；UI 记录该块失败状态。
- **思考模式硬约束**：带 `tool_calls` 的 assistant 行，重发时必须携带其 `reasoning_content`，否则 400。`Message.reasoningContent` 正好复用。

### 4.5 数据模型与迁移（schema v3 → v4）

- `data/db/MessageEntity.kt`：新增 `tool_calls TEXT`（JSON）、`tool_call_id TEXT`（均可空）。
- `data/db/ConversationEntity.kt`：新增 `web_search_enabled INTEGER NOT NULL DEFAULT 0`。
- `data/model/Message.kt`：`Role` 增加 `TOOL`；`Message` 增加 `toolCalls: List<ToolCall>`、`toolCallId: String?`。
- `data/model/Conversation.kt`：增加 `webSearchEnabled: Boolean`。
- `data/db/Mappers.kt`：`tool_calls` 用 JSON 编码（新增 `ToolCallCodec`，仿 `AttachmentCodec`）；`roleFromString` 支持 `"tool"`。
- `data/db/Migrations.kt`：`MIGRATION_3_4`：
  ```sql
  ALTER TABLE messages ADD COLUMN tool_calls TEXT;
  ALTER TABLE messages ADD COLUMN tool_call_id TEXT;
  ALTER TABLE conversations ADD COLUMN web_search_enabled INTEGER NOT NULL DEFAULT 0;
  ```
- `data/db/AppDatabase.kt`：`version = 4`，注册 `MIGRATION_3_4`。
- `data/db/MessageDao.kt`：终值写回需包含 tool_calls/tool_call_id（`finalize`/新增 `updateToolCalls`）。

### 4.6 上下文组装

- `data/ai/ContextBuilder.kt`
  - 放行 `Role.TOOL` 行（当前 `filter` 只留 USER/ASSISTANT，会丢工具结果），并携带 `tool_call_id`；assistant 行携带 `tool_calls` 与 `reasoning_content`。
  - `MAX_MESSAGES=40` 对多步 Agent 偏紧：为工具消息单独计数上限，超限时保留最近若干步。
  - 图片降级逻辑不变（tool 行不含图片）。

### 4.7 UI

- `ui/chat/Composer.kt`
  - 按钮 `Row` 增加 🌐 开关（可点 `Text("🌐")`；启用态用主色/高亮背景），带 `contentDescription`。
  - 新增参数 `webSearchEnabled: Boolean`、`onToggleWebSearch: () -> Unit`。
- `ui/chat/ChatUiState.kt`
  - `ChatMessageItem` 增加 `toolCalls: List<ToolCallView>`、`toolCallId: String?`、`isToolResult`、`toolSources: List<WebSource>`、`toolStatus`。
  - `ChatUiState` 增加 `webSearchEnabled: Boolean`。
- `ui/chat/ChatViewModel.kt`
  - `toggleWebSearch()`：立即写回当前会话的 `web_search_enabled`（复用 model 覆盖的写库路径）。
  - `Message.toItem()` 映射工具字段。
  - `StreamingMessage` 增加 `activity: String?`（如「正在联网搜索：<q>」「正在抓取：<url>」），Composer 上方/思考区展示。
- `ui/chat/MessageItems.kt`
  - `role=TOOL` 行渲染为可折叠块 `ToolCallBlock`（复用 `ReasoningBlock` 的折叠模式）：标题（联网搜索 / 网页抓取）、query 或 URL、来源列表（可点开）、成功/失败/进行中。
  - assistant 行若带 `tool_calls`，在文字前渲染对应块。

### 4.8 设置与开关

- `data/model/ChatConfig.kt`：增加 `webSearchEnabled: Boolean = false`（请求期决定是否注入 tools；本身不落 DataStore）。
- `data/prefs/SettingsRepository.kt`：`chatConfig()` 保持厂商中立默认；会话级开关从 `ConversationEntity` 读，`resolveConfig` 合并（优先级同 model/systemPrompt 覆盖）。
- 🌐 开关**按会话记住**：写 `conversations.web_search_enabled`。
- 中立性护栏：baseUrl 非 DeepSeek 且未配置第三方 provider 时，🌐 置灰并提示「当前服务商未配置联网搜索后端」，不静默失败。

## 5. 依赖

- 新增 `org.jsoup:jsoup`（HTML 解析/清洗），版本进 `gradle/libs.versions.toml`，`app/build.gradle.kts` 用 `libs.*` 引用。项目要求无硬编码版本。

## 6. 护栏与成本

- `maxSteps` 默认 5（硬上限 8）；每步最多 1 次 `web_search`；`max_uses=3`。
- `web_fetch`：15s 超时、单页 ≤200 KB、回灌文本截断（≤20k 字符/次）。
- 成本：每次搜索 = 一次额外 Anthropic Messages 模型调用（服务端搜索+读页+综合，按 token 计费）；主回路多一次带 tool 结果的请求。
- 错误映射复用 `ApiErrorMapper`；工具错误给可读文案。

## 7. 测试（全部 JVM）

- `ToolCallAccumulator`：跨 chunk 碎片拼装、多工具并发 index、缺 id/name 的容错。
- `ChatDtos`/`ChatRequestBody`：带 tools 的请求体序列化、`tool_choice`、`role=tool` 字段。
- `EndpointUrl.anthropicMessages`：各种 base 形态归一。
- `DeepSeekNativeSearchProvider`：MockWebServer 返回 `server_tool_use`+`web_search_tool_result`+text → sources/answer；含 DSML 标记的正文被剥离；空结果回退。
- `HtmlToText`：去 script/style、块级换行、实体解码。
- `HttpWebFetcher`：非 2xx 是结果、content-type 白名单、大小截断、私网/非 http(s) 拒绝。
- `ContextBuilder`：TOOL 行放行、`tool_call_id`、assistant 的 `reasoning_content` 回传、工具消息预算。
- Agent 循环状态机（fake `ChatApi`）：单步工具、多步、命中步数上限的强制收尾、工具报错继续、取消。
- `Mappers`/`MIGRATION_3_4`：往返与迁移（Room migration test 或 JVM 断言 SQL）。

## 8. 范围外（YAGNI）

- 第三方搜索 provider 只留接口，不在本期实现。
- 不做并行工具调用（串行执行）。
- 不使用 Responses API 的 `web_search`。
- 工具结果中的图片输入不支持。
- 不做 `web_fetch` 的 JS 渲染（动态站点正文可能稀疏，属已知覆盖度限制）。

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Anthropic 端点行为/文档漂移 | 只依赖被官方 Claude Code 页与兼容表钉死的内容块；provider 可替换 |
| DSML 标记漏进正文 | 强制 `tool_choice` + `stripDsmlMarkup` 兜底 |
| 搜索上下文膨胀、token 成本 | 步数/搜索次数/文本长度三重上限 |
| 思考模式 400 | 带 tool_calls 的消息强制回传 `reasoning_content` |
| 抓取被反爬/JS 站点 | 浏览器 UA、超时与可读错误、定位为尽力而为 |
| 需求蔓延 | 第 8 节明确范围外 |

## 10. 开放问题

无（决策已定：路线 C、内联可折叠块、按会话开关、真机联调）。
