# 联网搜索 + Web Fetch Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android 客户端内实现「联网搜索 → 思考 → 抓取网页 → 思考」的多步 Agent，主回路走 OpenAI 兼容 function calling，搜索后端默认 DeepSeek 原生（Anthropic 端点），抓取走本地 HTTP。

**Architecture:** 主对话回路继续用 `/v1/chat/completions` + 标准 `tools`/`tool_calls`；`ChatRepository` 升级为有界循环，每步落一条 assistant 消息、工具结果落 `role=TOOL` 消息；搜索后端藏在 `WebSearchProvider` 接口后，默认 `DeepSeekNativeSearchProvider` 调 `api.deepseek.com/anthropic/v1/messages` 的服务端 `web_search` 工具；`web_fetch` 是本地 `OkHttp + Jsoup`。UI 用内联可折叠块展示，输入框 🌐 按会话记忆。

**Tech Stack:** Kotlin 2.4.10 / AGP 9.4 / OkHttp 5.5（含 okhttp-sse）/ kotlinx.serialization / Room 2.8 / Jetpack Compose M3 / JUnit4 + MockWebServer（全部 JVM 单测）/ 新增 Jsoup。

**规范文档:** `docs/superpowers/specs/2026-09-18-web-search-agent-design.md`

**构建/测试命令（见 AGENTS.md）:**
- 全量单测：`.\gradlew.bat test`
- 单类：`.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.net.EndpointUrlTest"`
- 编译：`.\gradlew.bat assembleDebug`

---

## 文件结构（先锁定分解）

**新增：**
- `app/src/main/java/com/zcw/chatai/data/model/ToolSource.kt` — `ToolSource` / `ToolResult` / `ToolStatus`
- `app/src/main/java/com/zcw/chatai/data/db/ToolCallCodec.kt` — `tool_calls` / `tool_result` 列的 JSON 编解码
- `app/src/main/java/com/zcw/chatai/data/ai/ToolCallAccumulator.kt` — 跨 chunk 拼装 `tool_calls`
- `app/src/main/java/com/zcw/chatai/data/ai/AgentLoop.kt` — 纯决策（继续 / 强制收尾 / 结束）
- `app/src/main/java/com/zcw/chatai/data/web/WebSearchProvider.kt` — provider 接口 + `WebSearchResult`
- `app/src/main/java/com/zcw/chatai/data/web/DeepSeekNativeSearchProvider.kt` — 默认搜索后端 + 响应解析
- `app/src/main/java/com/zcw/chatai/data/web/WebFetcher.kt` — fetch 接口 + `WebFetchResult`
- `app/src/main/java/com/zcw/chatai/data/web/HttpWebFetcher.kt` — 本地抓取 + SSRF 防护
- `app/src/main/java/com/zcw/chatai/data/web/HtmlToText.kt` — HTML → 纯文本
- `app/src/main/java/com/zcw/chatai/data/web/WebTools.kt` — 工具 schema + 结果格式化 + 参数解析
- `app/src/main/java/com/zcw/chatai/ui/chat/ToolCallBlock.kt` — 内联可折叠工具块
- 测试：`EndpointUrlTest`（追加）、`ToolRequestTest`、`ToolCallAccumulatorTest`、`ToolCallCodecTest`、`ContextBuilderTest`（追加）、`MappersTest`（追加）、`WebToolsTest`、`DeepSeekSearchParserTest`、`DeepSeekNativeSearchProviderTest`、`HtmlToTextTest`、`HttpWebFetcherTest`、`AgentLoopTest`

**修改：**
- `gradle/libs.versions.toml`、`app/build.gradle.kts` — 加 Jsoup
- `data/net/EndpointUrl.kt` — `anthropicMessages`
- `data/net/dto/ChatDtos.kt` — tools / tool_calls / reasoning_content
- `data/net/ChatApi.kt` — `ChatStreamEvent.ToolCallDelta`、`ChatRequestMessage` 扩展
- `data/net/OpenAiCompatibleChatApi.kt` — 注入 tools、映射 tool_calls、role=tool
- `data/model/Message.kt` — `Role.TOOL`、`ToolCall`、`Message` 新字段
- `data/model/Conversation.kt` — `webSearchEnabled`
- `data/model/ChatConfig.kt` — `webSearchEnabled`、`maxAgentSteps`
- `data/db/MessageEntity.kt`、`ConversationEntity.kt`、`Mappers.kt`、`Migrations.kt`、`MessageDao.kt`、`ConversationDao.kt`、`AppDatabase.kt`
- `data/ai/ContextBuilder.kt` — 放行 TOOL、携带 tool_calls/tool_call_id/reasoning
- `data/ChatRepository.kt` — Agent 循环
- `data/prefs/SettingsRepository.kt` — `chatConfig()` 带上 `webSearchEnabled=false` 默认
- `ChatAiApp.kt` — 装配 provider / fetcher
- `ui/chat/ChatUiState.kt`、`ChatViewModel.kt`、`ChatScreen.kt`、`Composer.kt`
- `ui/App.kt` — 透传回调

---

## Phase 1 — 协议与工具（全部 JVM 单测）

### Task 1: Anthropic Messages 端点解析

**Files:**
- Modify: `app/src/main/java/com/zcw/chatai/data/net/EndpointUrl.kt`
- Test: `app/src/test/java/com/zcw/chatai/data/net/EndpointUrlTest.kt`

- [ ] **Step 1: 写失败测试**（追加到 `EndpointUrlTest`）

```kotlin
    @Test
    fun derivesAnthropicMessagesEndpoint() {
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("https://api.deepseek.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("https://api.deepseek.com/anthropic/v1"),
        )
        assertEquals(
            "http://192.168.1.5:11434/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("http://192.168.1.5:11434/v1"),
        )
        assertNull(EndpointUrl.anthropicMessages(""))
        assertNull(EndpointUrl.anthropicMessages("   "))
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.net.EndpointUrlTest"`
Expected: 编译失败 `Unresolved reference: anthropicMessages`

- [ ] **Step 3: 实现**（`EndpointUrl.kt` 加常量与函数）

```kotlin
    private const val ANTHROPIC_PATH = "/anthropic"
    private const val MESSAGES_PATH = "/messages"

    /** DeepSeek 的 Anthropic 兼容面：`{origin}/anthropic/v1/messages`。 */
    fun anthropicMessages(baseUrl: String): String? {
        val base = baseUrl.trim().trimEnd('/').removeSuffix(COMPLETIONS_PATH)
        if (base.isEmpty()) return null
        var root = base
        if (root.endsWith(VERSION_PATH, ignoreCase = true)) root = root.dropLast(VERSION_PATH.length)
        if (root.endsWith(ANTHROPIC_PATH, ignoreCase = true)) root = root.dropLast(ANTHROPIC_PATH.length)
        root = root.trimEnd('/')
        if (root.isEmpty()) return null
        return root + ANTHROPIC_PATH + VERSION_PATH + MESSAGES_PATH
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.net.EndpointUrlTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/net/EndpointUrl.kt app/src/test/java/com/zcw/chatai/data/net/EndpointUrlTest.kt
git commit -m "feat(net): 解析 DeepSeek Anthropic Messages 端点"
```

---

### Task 2: 工具调用的请求/流式 DTO

**Files:**
- Modify: `app/src/main/java/com/zcw/chatai/data/model/Message.kt`（先加 `ToolCall`，Task 5 再加 `Message` 字段）
- Modify: `app/src/main/java/com/zcw/chatai/data/net/dto/ChatDtos.kt`
- Modify: `app/src/main/java/com/zcw/chatai/data/net/ChatApi.kt`
- Modify: `app/src/main/java/com/zcw/chatai/data/net/OpenAiCompatibleChatApi.kt`
- Modify: `app/src/main/java/com/zcw/chatai/data/model/ChatConfig.kt`（仅加 `webSearchEnabled`/`maxAgentSteps`）
- Test: `app/src/test/java/com/zcw/chatai/data/net/ToolRequestTest.kt`（新建）

- [ ] **Step 1: 先在 `Message.kt` 加 `ToolCall` 模型**

```kotlin
/** 模型请求的一次函数调用（`arguments` 是原始 JSON 字符串）。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
```

- [ ] **Step 2: 写失败测试**（新建 `ToolRequestTest.kt`）

```kotlin
package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.net.dto.chatJson
import com.zcw.chatai.data.net.dto.ChatCompletionRequest
import com.zcw.chatai.data.net.dto.ChatRequestBody
import com.zcw.chatai.data.net.dto.ChatTool
import com.zcw.chatai.data.net.dto.FunctionSpec
import com.zcw.chatai.data.net.dto.RequestFunctionCall
import com.zcw.chatai.data.net.dto.RequestMessage
import com.zcw.chatai.data.net.dto.RequestToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRequestTest {

    @Test
    fun encodesToolSpecsAndToolChoice() {
        val payload = ChatCompletionRequest(
            model = "m",
            messages = listOf(RequestMessage("user", ChatRequestBody.content("hi", emptyList()))),
            tools = listOf(
                ChatTool(
                    function = FunctionSpec(
                        name = "web_search",
                        description = "search",
                        parameters = buildJsonObject {
                            put("type", "object")
                        },
                    ),
                ),
            ),
            toolChoice = JsonPrimitive("auto"),
        )
        val body = ChatRequestBody.encode(chatJson, payload, null)
        assertTrue(body, body.contains("\"tools\""))
        assertTrue(body, body.contains("\"web_search\""))
        assertTrue(body, body.contains("\"tool_choice\":\"auto\""))
    }

    @Test
    fun encodesAssistantToolCallsAndToolResultMessage() {
        val payload = ChatCompletionRequest(
            model = "m",
            messages = listOf(
                RequestMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        RequestToolCall(
                            id = "call_1",
                            function = RequestFunctionCall("web_search", "{\"query\":\"x\"}"),
                        ),
                    ),
                ),
                RequestMessage(
                    role = "tool",
                    content = ChatRequestBody.content("结果", emptyList()),
                    toolCallId = "call_1",
                ),
            ),
        )
        val body = ChatRequestBody.encode(chatJson, payload, null)
        assertTrue(body, body.contains("\"tool_calls\""))
        assertTrue(body, body.contains("\"call_1\""))
        assertTrue(body, body.contains("\"tool_call_id\":\"call_1\""))
        assertTrue(body, body.contains("\"role\":\"tool\""))
    }

    @Test
    fun carriesReasoningContentOnlyWhenPresent() {
        val payload = ChatCompletionRequest(
            model = "m",
            messages = listOf(RequestMessage("assistant", null, reasoningContent = "想了一下")),
        )
        val body = ChatRequestBody.encode(chatJson, payload, null)
        assertTrue(body, body.contains("\"reasoning_content\":\"想了一下\""))
    }

    @Test
    fun appendedToolsAreNotBlockedByExtraParamsProtection() {
        val payload = ChatCompletionRequest(
            model = "m",
            messages = emptyList(),
            tools = listOf(ChatTool(function = FunctionSpec("web_fetch", "fetch", buildJsonObject { put("type", "object") }))),
        )
        val body = ChatRequestBody.encode(chatJson, payload, """{"temperature":0.5}""")
        assertTrue(body, body.contains("\"web_fetch\""))
        assertTrue(body, body.contains("\"temperature\":0.5"))
        assertFalse(body, body.contains("\"temperature\":null"))
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.net.ToolRequestTest"`
Expected: 编译失败（`ChatTool` / `FunctionSpec` / `toolCalls` 等不存在）

- [ ] **Step 4: 实现 DTO**（`ChatDtos.kt`）

把 `ChatCompletionRequest` 与 `RequestMessage` 替换为：

```kotlin
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<RequestMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    val tools: List<ChatTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
)

@Serializable
data class ChatTool(val type: String = "function", val function: FunctionSpec)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val strict: Boolean? = null,
)

@Serializable
data class RequestMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<RequestToolCall>? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class RequestToolCall(
    val id: String,
    val type: String = "function",
    val function: RequestFunctionCall,
)

@Serializable
data class RequestFunctionCall(val name: String, val arguments: String)
```

并在 `ChunkDelta` 追加：

```kotlin
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
```

并新增：

```kotlin
@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionDelta? = null,
)

@Serializable
data class FunctionDelta(val name: String? = null, val arguments: String? = null)
```

顶部 `import` 补 `kotlinx.serialization.json.JsonObject`。

- [ ] **Step 5: 扩展 `ChatApi.kt`**

```kotlin
data class ChatRequestMessage(
    val role: String,
    val content: String,
    val images: List<ChatRequestImage> = emptyList(),
    /** assistant 消息携带的工具调用（重发历史时用）。 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** `role=tool` 结果消息对应的调用 id。 */
    val toolCallId: String? = null,
    /** 仅带 tool_calls 的 assistant 回合需要回传（否则思考模式 400）。 */
    val reasoning: String? = null,
)
```

`ChatStreamEvent` 增加：

```kotlin
    /** 模型决定调用工具；`arguments` 是逐字符增量，必须按 index 拼接。 */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val arguments: String? = null,
    ) : ChatStreamEvent
```

顶部 `import com.zcw.chatai.data.model.ToolCall`。

- [ ] **Step 6: `OpenAiCompatibleChatApi.buildRequest` 注入 tools 与 tool 消息**

在 `messages.forEach { ... }` 里替换为：

```kotlin
                messages.forEach { message ->
                    // 图片只能出现在 user/tool 消息里，其它角色一律降级为纯文本。
                    val images = if (message.role == ROLE_USER) message.images else emptyList()
                    add(
                        RequestMessage(
                            role = message.role,
                            content = if (message.toolCalls.isNotEmpty() && message.content.isEmpty()) {
                                null
                            } else {
                                ChatRequestBody.content(message.content, images)
                            },
                            toolCallId = message.toolCallId,
                            toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }?.map {
                                RequestToolCall(
                                    id = it.id,
                                    function = RequestFunctionCall(name = it.name, arguments = it.arguments),
                                )
                            },
                            reasoningContent = message.reasoning,
                        ),
                    )
                }
```

在 `ChatCompletionRequest(...)` 参数末尾加：

```kotlin
            tools = if (config.webSearchEnabled) listOf(searchToolSpec(), fetchToolSpec()) else null,
            toolChoice = if (config.webSearchEnabled) JsonPrimitive("auto") else null,
```

`import kotlinx.serialization.json.JsonPrimitive`、`kotlinx.serialization.json.buildJsonObject`、`kotlinx.serialization.json.put`、`kotlinx.serialization.json.putJsonObject`、`kotlinx.serialization.json.JsonArray`。

并在本文件内加两个临时工具规范（Task 9 会替换为 `WebTools.specs()`）：

```kotlin
private fun searchToolSpec() = ChatTool(
    function = FunctionSpec(
        name = "web_search",
        description = "Search the web for current information.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("query") { put("type", "string") } }
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        },
    ),
)

private fun fetchToolSpec() = ChatTool(
    function = FunctionSpec(
        name = "web_fetch",
        description = "Fetch the full text of one http(s) URL.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("url") { put("type", "string") } }
            put("required", JsonArray(listOf(JsonPrimitive("url"))))
        },
    ),
)
```

`import com.zcw.chatai.data.net.dto.ChatTool`、`FunctionSpec`。

`StreamListener.onEvent` 在 `chunk.choices.forEach` 内追加：

```kotlin
                delta?.toolCalls?.forEach { tc ->
                    scope.trySend(
                        ChatStreamEvent.ToolCallDelta(
                            index = tc.index,
                            id = tc.id,
                            name = tc.function?.name,
                            arguments = tc.function?.arguments,
                        ),
                    )
                }
```

- [ ] **Step 7: `ChatConfig` 加字段（本步只为编译后续）**

```kotlin
    /** 本回合是否注入 web_search/web_fetch 工具。 */
    val webSearchEnabled: Boolean = false,
    /** Agent 最多几步工具调用。 */
    val maxAgentSteps: Int = 5,
```

- [ ] **Step 8: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.net.ToolRequestTest"`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/model/Message.kt app/src/main/java/com/zcw/chatai/data/model/ChatConfig.kt app/src/main/java/com/zcw/chatai/data/net/dto/ChatDtos.kt app/src/main/java/com/zcw/chatai/data/net/ChatApi.kt app/src/main/java/com/zcw/chatai/data/net/OpenAiCompatibleChatApi.kt app/src/test/java/com/zcw/chatai/data/net/ToolRequestTest.kt
git commit -m "feat(net): 支持工具调用请求/流式协议"
```

---

### Task 3: ToolCallAccumulator

**Files:**
- Create: `app/src/main/java/com/zcw/chatai/data/ai/ToolCallAccumulator.kt`
- Test: `app/src/test/java/com/zcw/chatai/data/ai/ToolCallAccumulatorTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.zcw.chatai.data.ai

import com.zcw.chatai.data.net.ChatStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallAccumulatorTest {

    @Test
    fun assemblesFragmentedArgumentsAcrossChunks() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, id = "call_1", name = "web_search", arguments = "{\"qu"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "ery\":\"kot"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "lin\"}"))
        val calls = acc.assemble()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("web_search", calls[0].name)
        assertEquals("{\"query\":\"kotlin\"}", calls[0].arguments)
    }

    @Test
    fun keepsParallelCallsOrderedByIndex() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(1, id = "b", name = "web_fetch", arguments = "{}"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, id = "a", name = "web_search", arguments = "{}"))
        assertEquals(listOf("a", "b"), acc.assemble().map { it.id })
    }

    @Test
    fun synthesizesIdWhenMissing() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, name = "web_search", arguments = "{}"))
        assertEquals("call_0", acc.assemble().single().id)
    }

    @Test
    fun ignoresNamelessPartials() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "{}"))
        assertTrue(acc.assemble().isEmpty())
        assertTrue(acc.isEmpty)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.ai.ToolCallAccumulatorTest"`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

```kotlin
package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.net.ChatStreamEvent

/**
 * 把流式 `tool_calls` 增量按 index 拼成完整调用（纯逻辑，JVM 单测）。
 * `arguments` 是逐字符到达的 JSON 字符串，只能拼接，不能逐块解析。
 */
class ToolCallAccumulator {

    private class Partial {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private val parts = LinkedHashMap<Int, Partial>()

    fun accept(event: ChatStreamEvent.ToolCallDelta) {
        val part = parts.getOrPut(event.index) { Partial() }
        event.id?.takeIf { it.isNotEmpty() }?.let { part.id = it }
        event.name?.takeIf { it.isNotEmpty() }?.let { part.name = it }
        event.arguments?.takeIf { it.isNotEmpty() }?.let { part.arguments.append(it) }
    }

    val isEmpty: Boolean get() = parts.isEmpty()

    fun assemble(): List<ToolCall> = parts.entries
        .filter { it.value.name.isNotBlank() }
        .map { (index, part) ->
            ToolCall(
                id = part.id.ifBlank { "call_$index" },
                name = part.name,
                arguments = part.arguments.toString(),
            )
        }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.ai.ToolCallAccumulatorTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/ai/ToolCallAccumulator.kt app/src/test/java/com/zcw/chatai/data/ai/ToolCallAccumulatorTest.kt
git commit -m "feat(ai): 流式 tool_calls 增量累加器"
```

---

### Task 4: 领域模型与编解码（ToolResult / ToolCallCodec）

**Files:**
- Create: `app/src/main/java/com/zcw/chatai/data/model/ToolSource.kt`
- Create: `app/src/main/java/com/zcw/chatai/data/db/ToolCallCodec.kt`
- Test: `app/src/test/java/com/zcw/chatai/data/db/ToolCallCodecTest.kt`

- [ ] **Step 1: 写领域模型**

```kotlin
package com.zcw.chatai.data.model

/** 一条可引用的来源（URL 必有，其余可缺，不编造）。 */
data class ToolSource(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val publishedAt: String? = null,
)

enum class ToolStatus { RUNNING, OK, FAILED }

/** 一次工具调用的结构化结果：结构化字段给 UI，[text] 给模型。 */
data class ToolResult(
    val status: ToolStatus,
    val detail: String,
    val sources: List<ToolSource> = emptyList(),
    val text: String = "",
)
```

- [ ] **Step 2: 写失败测试**

```kotlin
package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallCodecTest {

    @Test
    fun roundTripsToolCalls() {
        val calls = listOf(ToolCall("call_1", "web_search", "{\"query\":\"x\"}"))
        val encoded = ToolCallCodec.encodeCalls(calls)
        assertEquals(calls, ToolCallCodec.decodeCalls(encoded))
    }

    @Test
    fun emptyCallsEncodeToNull() {
        assertNull(ToolCallCodec.encodeCalls(emptyList()))
        assertTrue(ToolCallCodec.decodeCalls(null).isEmpty())
        assertTrue(ToolCallCodec.decodeCalls("not json").isEmpty())
    }

    @Test
    fun roundTripsToolResultWithSources() {
        val result = ToolResult(
            status = ToolStatus.OK,
            detail = "kotlin",
            sources = listOf(ToolSource("https://a", "A", "snippet", "2026-01-01")),
            text = "Search results",
        )
        assertEquals(result, ToolCallCodec.decodeResult(ToolCallCodec.encodeResult(result)))
    }

    @Test
    fun nullResultStaysNull() {
        assertNull(ToolCallCodec.encodeResult(null))
        assertNull(ToolCallCodec.decodeResult(null))
        assertNull(ToolCallCodec.decodeResult("{"))
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.db.ToolCallCodecTest"`
Expected: 编译失败

- [ ] **Step 4: 实现**

```kotlin
package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.model.ToolStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** `messages.tool_calls` / `messages.tool_result` 两列的 JSON 编解码（纯函数，JVM 可测）。 */
object ToolCallCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val callsSerializer = ListSerializer(ToolCallDto.serializer())

    fun encodeCalls(calls: List<ToolCall>): String? =
        if (calls.isEmpty()) null else json.encodeToString(callsSerializer, calls.map { it.toDto() })

    fun decodeCalls(raw: String?): List<ToolCall> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(callsSerializer, raw).mapNotNull { it.toModel() }
        } catch (t: Exception) {
            emptyList()
        }
    }

    fun encodeResult(result: ToolResult?): String? =
        result?.let { json.encodeToString(ToolResultDto.serializer(), it.toDto()) }

    fun decodeResult(raw: String?): ToolResult? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ToolResultDto.serializer(), raw).toModel()
        } catch (t: Exception) {
            null
        }
    }

    private fun ToolCall.toDto() = ToolCallDto(id = id, name = name, arguments = arguments)

    private fun ToolCallDto.toModel(): ToolCall? =
        if (id.isBlank() || name.isBlank()) null else ToolCall(id = id, name = name, arguments = arguments)

    private fun ToolResult.toDto() = ToolResultDto(
        status = status.name,
        detail = detail,
        sources = sources.map { SourceDto(it.url, it.title, it.snippet, it.publishedAt) },
        text = text,
    )

    private fun ToolResultDto.toModel() = ToolResult(
        status = ToolStatus.entries.firstOrNull { it.name == status } ?: ToolStatus.FAILED,
        detail = detail,
        sources = sources.filter { it.url.isNotBlank() }
            .map { ToolSource(it.url, it.title, it.snippet, it.publishedAt) },
        text = text,
    )
}

@Serializable
private data class ToolCallDto(val id: String, val name: String, val arguments: String = "{}")

@Serializable
private data class ToolResultDto(
    val status: String,
    val detail: String,
    val sources: List<SourceDto> = emptyList(),
    val text: String = "",
)

@Serializable
private data class SourceDto(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val publishedAt: String? = null,
)
```

- [ ] **Step 5: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.db.ToolCallCodecTest"`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/model/ToolSource.kt app/src/main/java/com/zcw/chatai/data/db/ToolCallCodec.kt app/src/test/java/com/zcw/chatai/data/db/ToolCallCodecTest.kt
git commit -m "feat(db): 工具结果领域模型与 JSON 编解码"
```

---

### Task 5: Room schema v4

**Files:**
- Modify: `data/db/MessageEntity.kt`、`ConversationEntity.kt`、`Mappers.kt`、`Migrations.kt`、`MessageDao.kt`、`ConversationDao.kt`、`AppDatabase.kt`
- Modify: `data/model/Message.kt`（`Role.TOOL` + `Message` 字段）、`data/model/Conversation.kt`
- Test: `app/src/test/java/com/zcw/chatai/data/db/MappersTest.kt`（追加）

- [ ] **Step 1: 写失败测试**（追加到 `MappersTest`）

```kotlin
    @Test
    fun roundTripsToolFields() {
        val message = Message(
            id = "m1",
            conversationId = "c1",
            role = Role.TOOL,
            content = "result text",
            status = MessageStatus.COMPLETE,
            errorMessage = null,
            reasoningContent = null,
            seq = 3,
            model = null,
            promptTokens = null,
            completionTokens = null,
            toolCallId = "call_1",
            toolResult = com.zcw.chatai.data.model.ToolResult(
                status = com.zcw.chatai.data.model.ToolStatus.OK,
                detail = "q",
                sources = listOf(com.zcw.chatai.data.model.ToolSource("https://a", "A")),
                text = "result text",
            ),
            createdAt = 1,
            updatedAt = 1,
        )
        assertEquals(message, message.toEntity().toModel())
    }

    @Test
    fun roundTripsWebSearchEnabledConversation() {
        val conversation = Conversation(
            id = "c1", title = "t", model = "m", systemPrompt = null,
            createdAt = 1, updatedAt = 1, lastMessagePreview = "", messageCount = 0,
            isPinned = false, webSearchEnabled = true,
        )
        assertEquals(conversation, conversation.toEntity().toModel())
    }
```

（该文件已有 `Message(...)` 的 import；若 `Role`/`MessageStatus` 等未导入，按现有风格补。）

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.db.MappersTest"`
Expected: 编译失败（`toolCallId` 等不存在）

- [ ] **Step 3: 改 `Role` 与 `Message`**

`data/model/Message.kt`：`enum class Role { USER, ASSISTANT, SYSTEM, TOOL }`；`Message` 末尾加：

```kotlin
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolResult: ToolResult? = null,
```

- [ ] **Step 4: 改 `Conversation`**

`data/model/Conversation.kt` 加 `val webSearchEnabled: Boolean = false,`。

- [ ] **Step 5: 改实体**

`MessageEntity` 追加：

```kotlin
    @ColumnInfo(name = "tool_calls")
    val toolCalls: String? = null,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo(name = "tool_result")
    val toolResult: String? = null,
```

`ConversationEntity` 追加：

```kotlin
    @ColumnInfo(name = "web_search_enabled", defaultValue = "0")
    val webSearchEnabled: Boolean = false,
```

- [ ] **Step 6: 改 Mappers**

`ConversationEntity.toModel()` / `Conversation.toEntity()` 补 `webSearchEnabled = webSearchEnabled`。
`MessageEntity.toModel()` 补：

```kotlin
    toolCalls = ToolCallCodec.decodeCalls(toolCalls),
    toolCallId = toolCallId,
    toolResult = ToolCallCodec.decodeResult(toolResult),
```

`Message.toEntity()` 补：

```kotlin
    toolCalls = ToolCallCodec.encodeCalls(toolCalls),
    toolCallId = toolCallId,
    toolResult = ToolCallCodec.encodeResult(toolResult),
```

- [ ] **Step 7: 改迁移与版本**

`Migrations.kt` 追加：

```kotlin
/**
 * v3 → v4：Agent 工具调用落地。
 *
 * - `messages.tool_calls`：assistant 一步内请求的所有函数调用（JSON 数组）
 * - `messages.tool_call_id`：`role=TOOL` 行对应的调用 id
 * - `messages.tool_result`：结构化工具结果（ToolCallCodec），正文同时写进 `content`
 * - `conversations.web_search_enabled`：🌐 开关按会话记忆
 *
 * 均为可空/带默认值，旧数据行为 NULL/0，不做破坏性迁移。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN tool_calls TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN tool_call_id TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN tool_result TEXT")
        db.execSQL("ALTER TABLE conversations ADD COLUMN web_search_enabled INTEGER NOT NULL DEFAULT 0")
    }
}
```

`AppDatabase.kt`：`version = 4`，`.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`。

- [ ] **Step 8: 改 DAO**

`MessageDao` 追加：

```kotlin
    @Query("UPDATE messages SET tool_calls = :toolCalls, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateToolCalls(id: String, toolCalls: String?, updatedAt: Long)

    @Query("UPDATE messages SET content = :content, tool_result = :toolResult, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateToolResult(id: String, content: String, toolResult: String?, updatedAt: Long)
```

`ConversationDao` 追加：

```kotlin
    @Query("UPDATE conversations SET web_search_enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateWebSearchEnabled(id: String, enabled: Boolean, updatedAt: Long)
```

- [ ] **Step 9: 运行确认通过 + 编译**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.db.MappersTest"`
Expected: PASS
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL（Room 依据 schema 生成校验新列）

- [ ] **Step 10: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/db app/src/main/java/com/zcw/chatai/data/model/Message.kt app/src/main/java/com/zcw/chatai/data/model/Conversation.kt app/src/test/java/com/zcw/chatai/data/db/MappersTest.kt
git commit -m "feat(db): schema v4 落地工具调用与会话联网开关"
```

---

### Task 6: ContextBuilder 支持工具消息

**Files:**
- Modify: `app/src/main/java/com/zcw/chatai/data/ai/ContextBuilder.kt`
- Test: `app/src/test/java/com/zcw/chatai/data/ai/ContextBuilderTest.kt`（追加）

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun keepsToolMessagesAndCarriesToolCallId() {
        val calls = listOf(ToolCall("call_1", "web_search", "{\"query\":\"x\"}"))
        val messages = listOf(
            message(id = "a1", role = Role.ASSISTANT, content = "", toolCalls = calls, reasoning = "想"),
            message(id = "t1", role = Role.TOOL, content = "结果", toolCallId = "call_1"),
        )
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        assertEquals(listOf("assistant", "tool"), built.map { it.role })
        assertEquals(calls, built[0].toolCalls)
        assertEquals("想", built[0].reasoning)
        assertEquals("call_1", built[1].toolCallId)
    }

    @Test
    fun plainAssistantDoesNotEchoReasoning() {
        val messages = listOf(message(id = "a1", role = Role.ASSISTANT, content = "答案", reasoning = "想"))
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        assertNull(built.single().reasoning)
    }
```

（若 `ContextBuilderTest` 无 `message(...)` 工厂，请用该文件现有构造方式；字段：`toolCalls`、`toolCallId`、`reasoningContent`。）

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.ai.ContextBuilderTest"`
Expected: 编译失败 / 断言失败（TOOL 行被过滤）

- [ ] **Step 3: 实现**

把 `build` 的过滤与映射改为：

```kotlin
        val usable = history
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT || it.role == Role.TOOL }
            .filter { it.status != MessageStatus.STREAMING }
            .filter {
                it.content.isNotBlank() || it.attachments.isNotEmpty() ||
                    it.toolCalls.isNotEmpty() || it.toolCallId != null
            }
            .takeLast(MAX_MESSAGES)

        val keepImages = messageIdsKeepingImages(usable, imageLimit)

        return usable.map { message ->
            if (message.role == Role.TOOL) {
                return@map ChatRequestMessage(
                    role = message.role.wire,
                    content = message.toolResult?.text ?: message.content,
                    toolCallId = message.toolCallId,
                )
            }
            if (message.attachments.isEmpty()) {
                ChatRequestMessage(
                    role = message.role.wire,
                    content = message.content,
                    toolCalls = message.toolCalls,
                    // 只有带 tool_calls 的回合必须回传思考内容，否则思考模式 400。
                    reasoning = message.reasoningContent.takeIf { message.toolCalls.isNotEmpty() },
                )
            } else {
                val keep = message.id in keepImages
                val images = if (keep) message.attachments.mapNotNull(imageProvider) else emptyList()
                val note = when {
                    !keep -> IMAGE_OMITTED
                    images.isEmpty() -> IMAGE_MISSING
                    else -> null
                }
                ChatRequestMessage(
                    role = message.role.wire,
                    content = withNote(message.content, note),
                    images = images,
                )
            }
        }
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.ai.ContextBuilderTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/ai/ContextBuilder.kt app/src/test/java/com/zcw/chatai/data/ai/ContextBuilderTest.kt
git commit -m "feat(ai): 上下文组装支持 role=tool 与工具调用回传"
```

---

### Task 7: Web 工具 schema、参数解析与结果格式化

**Files:**
- Create: `app/src/main/java/com/zcw/chatai/data/web/WebTools.kt`
- Test: `app/src/test/java/com/zcw/chatai/data/web/WebToolsTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsTest {

    @Test
    fun specsExposeBothFunctions() {
        assertEquals(listOf("web_search", "web_fetch"), WebTools.specs().map { it.function.name })
    }

    @Test
    fun parsesQueryAndUrlArguments() {
        assertEquals("kotlin 协程", WebTools.queryOf("{\"query\":\"kotlin 协程\"}"))
        assertEquals("https://a.com", WebTools.urlOf("{\"url\":\"https://a.com\"}"))
        assertNull(WebTools.queryOf("{}"))
        assertNull(WebTools.urlOf("not json"))
        assertNull(WebTools.queryOf(""))
    }

    @Test
    fun formatsSearchResultWithHeadingAndSources() {
        val text = WebTools.formatSearchResult(
            "kotlin",
            WebSearchResult(
                answer = "Kotlin 是…",
                sources = listOf(ToolSource("https://a", "A", "快照")),
            ),
        )
        assertTrue(text, text.contains("kotlin"))
        assertTrue(text, text.contains("https://a"))
        assertTrue(text, text.contains("A"))
        assertTrue(text, text.contains("markdown"))
    }

    @Test
    fun formatsEmptySearchResult() {
        val text = WebTools.formatSearchResult("x", WebSearchResult())
        assertTrue(text, text.contains("No results found."))
    }

    @Test
    fun formatsFetchResultAndTruncates() {
        val long = "a".repeat(WebTools.MAX_FETCH_CHARS + 100)
        val text = WebTools.formatFetchResult(WebFetchResult("https://a", 200, long, truncated = false))
        assertTrue(text.length <= WebTools.MAX_FETCH_CHARS + 200)
        assertTrue(text, text.contains("https://a"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.WebToolsTest"`
Expected: 编译失败

- [ ] **Step 3: 实现**（`WebTools.kt`，同时定义 provider/fetcher 的接口与结果类型）

```kotlin
package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.net.dto.ChatTool
import com.zcw.chatai.data.net.dto.FunctionSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 一次搜索的结果：可选的 provider 综合回答 + 可引用来源。 */
data class WebSearchResult(val answer: String? = null, val sources: List<ToolSource> = emptyList())

/** 一次抓取的结果；非 2xx 也是结果，不抛异常。 */
data class WebFetchResult(
    val url: String,
    val statusCode: Int,
    val text: String,
    val truncated: Boolean,
)

/** 模型可见的两个工具：schema、参数解析、结果文本格式化。 */
object WebTools {

    const val SEARCH = "web_search"
    const val FETCH = "web_fetch"
    const val DEFAULT_MAX_RESULTS = 5
    const val MAX_FETCH_CHARS = 20_000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun specs(): List<ChatTool> = listOf(searchSpec(), fetchSpec())

    fun queryOf(arguments: String): String? = stringArg(arguments, "query")

    fun urlOf(arguments: String): String? = stringArg(arguments, "url")

    private fun stringArg(arguments: String, key: String): String? {
        if (arguments.isBlank()) return null
        val obj = try {
            json.parseToJsonElement(arguments) as? JsonObject
        } catch (t: Exception) {
            null
        } ?: return null
        return (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    fun formatSearchResult(query: String, result: WebSearchResult): String {
        val builder = StringBuilder()
        builder.append("Search results for \"").append(query).append("\":\n")
        result.answer?.takeIf { it.isNotBlank() }?.let { builder.append(it).append("\n\n") }
        if (result.sources.isEmpty()) {
            if (result.answer.isNullOrBlank()) builder.append("No results found.\n")
        } else {
            builder.append("Sources:\n")
            result.sources.forEach { source ->
                builder.append("- ").append(source.title ?: source.url).append(" — ").append(source.url)
                source.snippet?.takeIf { it.isNotBlank() }?.let { builder.append("\n  ").append(it) }
                builder.append("\n")
            }
        }
        builder.append("Cite the relevant URLs above as markdown links in your answer.")
        return builder.toString()
    }

    fun formatFetchResult(result: WebFetchResult): String {
        val header = "Fetched ${result.url} (HTTP ${result.statusCode}):\n"
        val body = result.text.take(MAX_FETCH_CHARS)
        val note = if (result.truncated || result.text.length > MAX_FETCH_CHARS) "\n[内容已截断]" else ""
        return header + body + note
    }

    private fun searchSpec() = ChatTool(
        function = FunctionSpec(
            name = SEARCH,
            description = "Search the web for current information. Use it when the answer needs up-to-date or external facts.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "The search query.")
                    }
                }
                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                put("additionalProperties", false)
            },
        ),
    )

    private fun fetchSpec() = ChatTool(
        function = FunctionSpec(
            name = FETCH,
            description = "Fetch the full text of one http(s) URL, e.g. to read a specific search result.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Absolute http(s) URL to read.")
                    }
                }
                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("url"))))
                put("additionalProperties", false)
            },
        ),
    )
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.WebToolsTest"`
Expected: PASS

- [ ] **Step 5: 把 Task 2 的临时 spec 换成 `WebTools.specs()`**

`OpenAiCompatibleChatApi.buildRequest` 改回 `tools = if (config.webSearchEnabled) WebTools.specs() else null,`，删除临时 `searchToolSpec()/fetchToolSpec()`。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/web/WebTools.kt app/src/main/java/com/zcw/chatai/data/net/OpenAiCompatibleChatApi.kt app/src/test/java/com/zcw/chatai/data/web/WebToolsTest.kt
git commit -m "feat(web): web_search/web_fetch schema 与结果格式化"
```

---

### Task 8: DeepSeek 原生搜索 provider

**Files:**
- Create: `app/src/main/java/com/zcw/chatai/data/web/WebSearchProvider.kt`
- Create: `app/src/main/java/com/zcw/chatai/data/web/DeepSeekNativeSearchProvider.kt`
- Test: `app/src/test/java/com/zcw/chatai/data/web/DeepSeekSearchParserTest.kt`、`DeepSeekNativeSearchProviderTest.kt`

- [ ] **Step 1: 写接口**

```kotlin
package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig

/** 搜索后端：换实现不动主回路。 */
interface WebSearchProvider {
    val id: String

    /** 廉价的本地检查（无网络调用）：能否用当前配置发起搜索。 */
    fun available(baseUrl: String, apiKey: String): Boolean

    suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult
}
```

- [ ] **Step 2: 写解析器失败测试**

```kotlin
package com.zcw.chatai.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekSearchParserTest {

    @Test
    fun extractsAnswerAndSources() {
        val body = """
            {"content":[
              {"type":"text","text":"答案是 42。"},
              {"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"x"}},
              {"type":"web_search_tool_result","tool_use_id":"s1","content":[
                 {"type":"web_search_result","url":"https://a","title":"A","page_age":"2026-01-01"},
                 {"type":"web_search_result","url":"https://a","title":"A dup"},
                 {"type":"web_search_result","url":"https://b","title":"B"}
              ]}
            ]}
        """.trimIndent()
        val result = DeepSeekSearchParser.parse(body)
        assertEquals("答案是 42。", result.answer)
        assertEquals(listOf("https://a", "https://b"), result.sources.map { it.url })
        assertEquals("2026-01-01", result.sources[0].publishedAt)
    }

    @Test
    fun stripsDsmlMarkup() {
        val body = """{"content":[{"type":"text","text":"前 <\uFF5C\uFF5CDSML\uFF5C\uFF5Ctool_calls>{\"a\":1}<\uFF5C\uFF5C/DSML\uFF5C\uFF5Ctool_calls> 后"}]}"""
        val result = DeepSeekSearchParser.parse(body)
        assertFalse(result.answer.orEmpty(), result.answer.orEmpty().contains("DSML"))
        assertTrue(result.answer.orEmpty(), result.answer.orEmpty().contains("前"))
        assertTrue(result.answer.orEmpty(), result.answer.orEmpty().contains("后"))
    }

    @Test
    fun emptyContentGivesEmptyResult() {
        val result = DeepSeekSearchParser.parse("""{"content":[]}""")
        assertEquals(null, result.answer)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun malformedBodyDoesNotThrow() {
        val result = DeepSeekSearchParser.parse("not json")
        assertTrue(result.sources.isEmpty())
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.DeepSeekSearchParserTest"`
Expected: 编译失败

- [ ] **Step 4: 实现解析器（含 DSML 剥离）**

```kotlin
package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ToolSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 解析 DeepSeek Anthropic Messages 响应里的 `web_search_tool_result`。纯函数，JVM 可测。 */
object DeepSeekSearchParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val FULLWIDTH_BAR = '\uFF5C'

    fun parse(raw: String): WebSearchResult {
        val response = try {
            json.decodeFromString(AnthropicResponse.serializer(), raw)
        } catch (t: Exception) {
            return WebSearchResult()
        }
        val answerParts = mutableListOf<String>()
        val sources = mutableListOf<ToolSource>()
        val seen = HashSet<String>()
        response.content.forEach { block ->
            when (block.type) {
                "text" -> block.text?.takeIf { it.isNotBlank() }?.let { answerParts += it }
                "web_search_tool_result" -> {
                    val items = block.content as? JsonArray ?: return@forEach
                    items.forEach { item ->
                        val obj = item as? JsonObject ?: return@forEach
                        if (obj["type"]?.jsonPrimitive?.contentOrNull != "web_search_result") return@forEach
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (url.isBlank() || !seen.add(url)) return@forEach
                        val title = obj["title"]?.jsonPrimitive?.contentOrNull
                        val age = obj["page_age"]?.jsonPrimitive?.contentOrNull
                        sources += ToolSource(url = url, title = title, publishedAt = age)
                    }
                }
            }
        }
        val answer = stripDsmlMarkup(answerParts.joinToString("\n")).takeIf { it.isNotBlank() }
        return WebSearchResult(answer = answer, sources = sources)
    }

    /** 参见设计：Anthropic 端点已知会把原生工具标记漏进正文，这里兜底剥离。 */
    fun stripDsmlMarkup(text: String): String {
        var result = text
        val open = "<${FULLWIDTH_BAR}${FULLWIDTH_BAR}DSML${FULLWIDTH_BAR}${FULLWIDTH_BAR}"
        while (true) {
            val start = result.indexOf(open)
            if (start < 0) break
            val endMarker = "${open}/"
            val close = result.indexOf(">", result.indexOf(endMarker, start).takeIf { it >= 0 } ?: break)
            if (close < 0) break
            result = result.removeRange(start, close + 1)
        }
        // 清掉残留的孤立 DSML 标签（没有配对结束标记的情况）。
        var cleaned = result
        while (true) {
            val start = cleaned.indexOf(open)
            if (start < 0) break
            val gt = cleaned.indexOf('>', start)
            if (gt < 0) break
            cleaned = cleaned.removeRange(start, gt + 1)
        }
        return collapseBlankLines(cleaned).trim()
    }

    private fun collapseBlankLines(text: String): String {
        val builder = StringBuilder()
        var newlineCount = 0
        text.forEach { ch ->
            if (ch == '\n') {
                newlineCount++
                if (newlineCount <= 2) builder.append(ch)
            } else {
                newlineCount = 0
                builder.append(ch)
            }
        }
        return builder.toString()
    }
}

@Serializable
private data class AnthropicResponse(val content: List<AnthropicBlock> = emptyList())

@Serializable
private data class AnthropicBlock(
    val type: String = "",
    val text: String? = null,
    val content: kotlinx.serialization.json.JsonElement? = null,
)
```

> 注：`stripDsmlMarkup` 用纯字符串操作，**不引入 `Regex`**（遵循 AGENTS.md 的 ICU 约束）。

- [ ] **Step 5: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.DeepSeekSearchParserTest"`
Expected: PASS

- [ ] **Step 6: 写 provider 测试**

```kotlin
package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DeepSeekNativeSearchProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun postsAnthropicRequestAndParsesSources() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"content":[{"type":"text","text":"OK"},{"type":"web_search_tool_result","tool_use_id":"s","content":[{"type":"web_search_result","url":"https://a","title":"A"}]}]}""",
                ),
        )
        val provider = DeepSeekNativeSearchProvider()
        val result = provider.search("kotlin", 5, config())
        assertEquals("OK", result.answer)
        assertEquals("https://a", result.sources.single().url)

        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/anthropic/v1/messages", recorded.path)
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val payload = recorded.body.readUtf8()
        assertTrue(payload, payload.contains("\"web_search_20250305\""))
        assertTrue(payload, payload.contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"web_search\"}"))
    }

    @Test
    fun mapsHttpErrorToFriendlyMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"bad key"}}"""),
        )
        try {
            DeepSeekNativeSearchProvider().search("x", 5, config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("API Key"))
        }
    }

    @Test
    fun availabilityRequiresKeyAndValidUrl() {
        val provider = DeepSeekNativeSearchProvider()
        assertTrue(provider.available("https://api.deepseek.com/v1", "k"))
        assertTrue(!provider.available("https://api.deepseek.com/v1", ""))
        assertTrue(!provider.available("", "k"))
    }

    private fun config() = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "deepseek-flash",
        systemPrompt = "",
        temperature = null,
    )
}
```

- [ ] **Step 7: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.DeepSeekNativeSearchProviderTest"`
Expected: 编译失败

- [ ] **Step 8: 实现 provider**

```kotlin
package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.net.ApiErrorMapper
import com.zcw.chatai.data.net.EndpointUrl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 默认搜索后端：调用 DeepSeek 的 Anthropic 兼容面，用服务端 `web_search` 工具。
 * OpenAI 兼容面不支持该工具类型，只有这条路；客户端只发一次普通 HTTPS 请求。
 */
class DeepSeekNativeSearchProvider(
    private val client: OkHttpClient = defaultClient(),
) : WebSearchProvider {

    override val id: String = "deepseek-native"

    override fun available(baseUrl: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && EndpointUrl.anthropicMessages(baseUrl) != null

    override suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult =
        withContext(Dispatchers.IO) {
            val url = EndpointUrl.anthropicMessages(config.baseUrl)
                ?: throw ChatApiException("请先在设置中填写 Base URL")
            val payload = buildJsonObject {
                put("model", config.model)
                put("max_tokens", 2048)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "Perform a web search for the query: $query")
                                    },
                                )
                            }
                        },
                    )
                }
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                            put("max_uses", maxResults.coerceIn(1, 5))
                        },
                    )
                }
                // 强制只调搜索：既保证真的联网，也规避已知的 DSML 标记漏出。
                putJsonObject("tool_choice") {
                    put("type", "tool")
                    put("name", "web_search")
                }
            }
            val request = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        throw ChatApiException(ApiErrorMapper.httpError(response.code, errorMessage(body)))
                    }
                    DeepSeekSearchParser.parse(body)
                }
            } catch (e: ChatApiException) {
                throw e
            } catch (t: Exception) {
                throw ChatApiException("联网搜索失败：${t.message ?: "未知错误"}", t)
            }
        }

    private fun errorMessage(body: String): String? = try {
        val error = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("error")
            ?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("message")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        error
    } catch (t: Exception) {
        null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
```

- [ ] **Step 9: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.DeepSeekNativeSearchProviderTest"`
Expected: PASS

- [ ] **Step 10: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/web/WebSearchProvider.kt app/src/main/java/com/zcw/chatai/data/web/DeepSeekNativeSearchProvider.kt app/src/test/java/com/zcw/chatai/data/web/DeepSeekSearchParserTest.kt app/src/test/java/com/zcw/chatai/data/web/DeepSeekNativeSearchProviderTest.kt
git commit -m "feat(web): DeepSeek 原生搜索 provider（Anthropic web_search）"
```

---

### Task 9: 本地 web_fetch（HTML→文本 + SSRF 防护）

**Files:**
- Create: `app/src/main/java/com/zcw/chatai/data/web/WebFetcher.kt`
- Create: `app/src/main/java/com/zcw/chatai/data/web/HtmlToText.kt`
- Create: `app/src/main/java/com/zcw/chatai/data/web/HttpWebFetcher.kt`
- Modify: `gradle/libs.versions.toml`、`app/build.gradle.kts`（Jsoup）
- Test: `HtmlToTextTest.kt`、`HttpWebFetcherTest.kt`

- [ ] **Step 1: 加 Jsoup 依赖**

`gradle/libs.versions.toml` `[versions]` 加 `jsoup = "1.18.3"`；`[libraries]` 加：

```toml
jsoup = { group = "org.jsoup", name = "jsoup", version.ref = "jsoup" }
```

`app/build.gradle.kts` `dependencies` 加 `implementation(libs.jsoup)`。

- [ ] **Step 2: 写 HtmlToText 失败测试**

```kotlin
package com.zcw.chatai.data.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlToTextTest {

    @Test
    fun dropsScriptStyleAndKeepsBlockText() {
        val html = "<html><head><style>p{}</style></head><body><script>evil()</script><h1>标题</h1><p>第一段</p><p>第二段</p></body></html>"
        val text = HtmlToText.toText(html)
        assertTrue(text, text.contains("标题"))
        assertTrue(text, text.contains("第一段"))
        assertTrue(text, text.contains("第二段"))
        assertFalse(text, text.contains("evil"))
        assertFalse(text, text.contains("p{}"))
    }

    @Test
    fun keepsAnchorTextAndListItems() {
        val text = HtmlToText.toText("<ul><li>甲</li><li><a href=\"https://a\">乙</a></li></ul>")
        assertTrue(text, text.contains("甲"))
        assertTrue(text, text.contains("乙"))
    }

    @Test
    fun decodesEntities() {
        val text = HtmlToText.toText("<p>A &amp; B &#20320;</p>")
        assertTrue(text, text.contains("A & B"))
        assertTrue(text, text.contains("你"))
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.HtmlToTextTest"`
Expected: 编译失败

- [ ] **Step 4: 实现 HtmlToText**

```kotlin
package com.zcw.chatai.data.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** HTML → 纯文本（Jsoup，纯函数，JVM 可测）。块级元素之间保留换行。 */
object HtmlToText {

    private val DROP = "script,style,noscript,svg,iframe,form,nav,footer,header"
    private val BLOCK = setOf(
        "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ul", "ol", "br", "tr", "table", "blockquote", "pre",
    )

    fun toText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select(DROP).remove()
        val builder = StringBuilder()
        doc.body()?.let { render(it, builder) }
        return collapseWhitespace(builder.toString())
    }

    private fun render(node: Node, builder: StringBuilder) {
        when (node) {
            is TextNode -> builder.append(node.text())
            is Element -> {
                val block = node.tagName() in BLOCK
                if (block) builder.append('\n')
                node.childNodes().forEach { render(it, builder) }
                if (block) builder.append('\n')
            }
        }
    }

    private fun collapseWhitespace(text: String): String {
        val builder = StringBuilder(text.length)
        var pendingSpace = false
        var newlines = 0
        text.forEach { ch ->
            when {
                ch == '\n' -> {
                    newlines++
                    pendingSpace = false
                    if (newlines <= 2) builder.append('\n')
                }
                ch == ' ' || ch == '\t' || ch == '\r' -> pendingSpace = true
                else -> {
                    if (pendingSpace && builder.isNotEmpty() && builder.last() != '\n') builder.append(' ')
                    pendingSpace = false
                    newlines = 0
                    builder.append(ch)
                }
            }
        }
        return builder.toString().trim()
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.HtmlToTextTest"`
Expected: PASS

- [ ] **Step 6: 写 fetcher 接口 + 失败测试**

`WebFetcher.kt`：

```kotlin
package com.zcw.chatai.data.web

/** 本地抓取后端。 */
interface WebFetcher {
    suspend fun fetch(url: String): WebFetchResult
}
```

`HttpWebFetcherTest.kt`：

```kotlin
package com.zcw.chatai.data.web

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpWebFetcherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher() = HttpWebFetcher(hostPolicy = { true })

    @Test
    fun convertsHtmlToText() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<html><body><p>你好</p></body></html>"),
        )
        val result = fetcher().fetch(server.url("/page").toString())
        assertEquals(200, result.statusCode)
        assertTrue(result.text, result.text.contains("你好"))
    }

    @Test
    fun non2xxIsAResultNotAnError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("nope"))
        val result = fetcher().fetch(server.url("/missing").toString())
        assertEquals(404, result.statusCode)
        assertTrue(result.text, result.text.contains("nope"))
    }

    @Test
    fun rejectsNonHttpScheme() = runBlocking {
        val result = fetcher().fetch("file:///etc/passwd")
        assertEquals(0, result.statusCode)
        assertTrue(result.text, result.text.contains("http"))
    }

    @Test
    fun rejectsPrivateHostByDefault() = runBlocking {
        val result = HttpWebFetcher().fetch(server.url("/page").toString())
        assertEquals(0, result.statusCode)
    }

    @Test
    fun rejectsBlockedContentType() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("bin"))
        val result = fetcher().fetch(server.url("/bin").toString())
        assertEquals(0, result.statusCode)
    }
}
```

- [ ] **Step 7: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.HttpWebFetcherTest"`
Expected: 编译失败

- [ ] **Step 8: 实现 HttpWebFetcher**

```kotlin
package com.zcw.chatai.data.web

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本地 HTTP 抓取。非 2xx 也是结果；只接受 http(s) 与公网地址，关闭自动重定向，
 * 限制大小与类型，HTML 转纯文本。
 *
 * [hostPolicy] 可注入以便单测（MockWebServer 跑在 localhost）。
 */
class HttpWebFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val hostPolicy: (String) -> Boolean = ::isPublicHost,
    private val maxBytes: Int = 200_000,
    private val allowedTypes: Set<String> = DEFAULT_ALLOWED_TYPES,
) : WebFetcher {

    override suspend fun fetch(url: String): WebFetchResult = withContext(Dispatchers.IO) {
        val httpUrl = runCatching { url.trim().toHttpUrlOrNull() }.getOrNull()
            ?: return@withContext failure(url, "只支持 http(s) URL")
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
            return@withContext failure(url, "只支持 http(s) URL")
        }
        if (!hostPolicy(httpUrl.host)) {
            return@withContext failure(url, "拒绝访问非公网地址")
        }
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (contentType.isNotEmpty() && contentType !in allowedTypes) {
                    return@withContext failure(url, "不支持的内容类型：$contentType")
                }
                val bytes = response.body.source().let { source ->
                    val buffer = okio.Buffer()
                    source.read(buffer, maxBytes.toLong())
                    buffer.readByteArray()
                }
                val truncated = response.body.contentLength() > bytes.size
                val raw = bytes.toString(Charsets.UTF_8)
                val text = if (contentType == "text/html" || contentType == "application/xhtml+xml" || contentType.isEmpty()) {
                    HtmlToText.toText(raw)
                } else {
                    raw
                }
                WebFetchResult(url = httpUrl.toString(), statusCode = response.code, text = text, truncated = truncated)
            }
        } catch (t: Exception) {
            failure(url, "抓取失败：${t.message ?: "未知错误"}")
        }
    }

    private fun failure(url: String, message: String) =
        WebFetchResult(url = url, statusCode = 0, text = message, truncated = false)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private val DEFAULT_ALLOWED_TYPES = setOf(
            "text/html", "text/plain", "application/json", "application/xhtml+xml", "text/markdown",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /** 拒绝 loopback / 私网 / link-local / multicast。 */
        fun isPublicHost(host: String): Boolean = try {
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.all { address ->
                !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress &&
                    !address.isAnyLocalAddress &&
                    !address.isMulticastAddress
            }
        } catch (t: Exception) {
            false
        }
    }
}
```

- [ ] **Step 9: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.web.*"`
Expected: 全部 PASS

- [ ] **Step 10: 提交**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/zcw/chatai/data/web/WebFetcher.kt app/src/main/java/com/zcw/chatai/data/web/HtmlToText.kt app/src/main/java/com/zcw/chatai/data/web/HttpWebFetcher.kt app/src/test/java/com/zcw/chatai/data/web/HtmlToTextTest.kt app/src/test/java/com/zcw/chatai/data/web/HttpWebFetcherTest.kt
git commit -m "feat(web): 本地 web_fetch（HTML→文本 + SSRF 防护）"
```

---

### Task 10: AgentLoop 纯决策

**Files:**
- Create: `app/src/main/java/com/zcw/chatai/data/ai/AgentLoop.kt`
- Test: `app/src/test/java/com/zcw/chatai/data/ai/AgentLoopTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentLoopTest {

    private val call = ToolCall("c1", "web_search", "{}")

    @Test
    fun finishesWhenNoToolCalls() {
        assertEquals(AgentLoop.Decision.Finish, AgentLoop.decide("stop", emptyList(), 0, 5))
        assertEquals(AgentLoop.Decision.Finish, AgentLoop.decide(null, emptyList(), 0, 5))
    }

    @Test
    fun continuesWhenToolCallsAndBudgetLeft() {
        assertEquals(
            AgentLoop.Decision.Continue(listOf(call)),
            AgentLoop.decide("tool_calls", listOf(call), steps = 0, maxSteps = 5),
        )
    }

    @Test
    fun forcesFinalWhenBudgetExhausted() {
        assertEquals(AgentLoop.Decision.ForceFinal, AgentLoop.decide("tool_calls", listOf(call), 5, 5))
        assertEquals(AgentLoop.Decision.ForceFinal, AgentLoop.decide("tool_calls", listOf(call), 6, 5))
    }

    @Test
    fun ignoresToolCallsWithoutFinishReason() {
        assertEquals(AgentLoop.Decision.Finish, AgentLoop.decide("stop", listOf(call), 0, 5))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.ai.AgentLoopTest"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.ToolCall

/** Agent 循环的纯决策：继续调工具 / 强制收尾 / 结束。JVM 单测覆盖。 */
object AgentLoop {

    const val DEFAULT_MAX_STEPS = 5
    const val HARD_MAX_STEPS = 8

    sealed interface Decision {
        data class Continue(val toolCalls: List<ToolCall>) : Decision

        /** 预算耗尽但模型还想调工具：先执行完这批（保持历史合法），再强制不带工具收尾。 */
        data object ForceFinal : Decision

        data object Finish : Decision
    }

    fun decide(
        finishReason: String?,
        toolCalls: List<ToolCall>,
        steps: Int,
        maxSteps: Int,
    ): Decision {
        if (finishReason != "tool_calls" || toolCalls.isEmpty()) return Decision.Finish
        return if (steps >= maxSteps) Decision.ForceFinal else Decision.Continue(toolCalls)
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.ai.AgentLoopTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/ai/AgentLoop.kt app/src/test/java/com/zcw/chatai/data/ai/AgentLoopTest.kt
git commit -m "feat(ai): Agent 循环纯决策"
```

---

## Phase 2 — 装配与 UI

### Task 11: 会话级开关写库 + 配置解析

**Files:**
- Modify: `data/ChatRepository.kt`（`resolveConfig`、`setConversationWebSearch`、`startAssistant`）
- Modify: `data/prefs/SettingsRepository.kt`（`chatConfig()` 显式传 `webSearchEnabled = false`）

- [ ] **Step 1: `SettingsRepository.chatConfig()` 显式默认**

在 `ChatConfig(...)` 里加 `webSearchEnabled = false,`（其余不变）。

- [ ] **Step 2: `ChatRepository.resolveConfig` 合并会话开关**

```kotlin
    private suspend fun resolveConfig(conversationId: String): ChatConfig {
        val base = settingsRepository.chatConfig().first()
        val conversation = db.conversationDao().getById(conversationId)
        return base.copy(
            model = conversation?.model?.takeIf { it.isNotBlank() } ?: base.model,
            systemPrompt = conversation?.systemPrompt?.takeIf { it.isNotBlank() } ?: base.systemPrompt,
            webSearchEnabled = conversation?.webSearchEnabled == true,
        )
    }
```

- [ ] **Step 3: 加开关方法**

```kotlin
    suspend fun setConversationWebSearch(conversationId: String, enabled: Boolean) {
        db.conversationDao().updateWebSearchEnabled(conversationId, enabled, nowMs())
    }
```

- [ ] **Step 4: 编译**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/ChatRepository.kt app/src/main/java/com/zcw/chatai/data/prefs/SettingsRepository.kt
git commit -m "feat(data): 会话级联网开关读取与写回"
```

---

### Task 12: ChatRepository Agent 循环

**Files:**
- Modify: `data/ChatRepository.kt`

> 这是最大的一处改动；按整段替换 `startAssistant` / `runStream` / `finalize`，新增 `runAgentTurn` / `streamOnce` / `executeTools` / `runTool` / `activityLabel`。

- [ ] **Step 1: 构造函数加依赖**

```kotlin
class ChatRepository(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val api: ChatApi,
    private val attachmentStore: AttachmentStore,
    private val searchProvider: WebSearchProvider? = null,
    private val webFetcher: WebFetcher = HttpWebFetcher(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
```

类体内加工具活动流：

```kotlin
    /** 当前工具活动（如「正在联网搜索：xxx」）。独立于 [streaming]：assistant 步结束后 streaming 会被清空。 */
    private val _activity = MutableStateFlow<String?>(null)

    val activity: StateFlow<String?> = _activity.asStateFlow()
```

imports：`com.zcw.chatai.data.ai.AgentLoop`、`com.zcw.chatai.data.ai.ToolCallAccumulator`、`com.zcw.chatai.data.db.ToolCallCodec`、`com.zcw.chatai.data.model.ToolCall`、`com.zcw.chatai.data.model.ToolResult`、`com.zcw.chatai.data.model.ToolStatus`、`com.zcw.chatai.data.web.*`。

- [ ] **Step 2: 替换 `startAssistant`**

```kotlin
    private suspend fun startAssistant(conversationId: String) {
        val config = resolveConfig(conversationId)
        val toolsUsable = config.webSearchEnabled && searchProvider?.available(config.baseUrl, config.apiKey) == true
        streamJob = scope.launch {
            runAgentTurn(conversationId, config.copy(webSearchEnabled = toolsUsable))
        }
    }
```

- [ ] **Step 3: 新增 Agent 回合循环与单步流式**

```kotlin
    private data class TurnOutcome(
        val finishReason: String?,
        val toolCalls: List<ToolCall>,
        val failed: Boolean,
    )

    /**
     * 有界 Agent 循环：每一步一条 assistant 消息；模型要工具就串行执行、落 TOOL 行、再流式。
     * 预算耗尽时先执行完最后一批工具（保持历史合法），再不带工具强制收尾。
     */
    private suspend fun runAgentTurn(conversationId: String, config: ChatConfig) {
        var steps = 0
        while (true) {
            val forceFinal = steps >= config.maxAgentSteps
            val messageId = newId()
            val timestamp = nowMs()
            db.messageDao().upsert(
                MessageEntity(
                    id = messageId,
                    conversationId = conversationId,
                    role = Role.ASSISTANT.name,
                    content = "",
                    status = MessageStatus.STREAMING.name,
                    errorMessage = null,
                    reasoningContent = null,
                    seq = db.messageDao().nextSeq(conversationId),
                    model = config.model,
                    promptTokens = null,
                    completionTokens = null,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            refreshSummary(conversationId)
            val history = db.messageDao().getByConversation(conversationId).map { it.toModel() }
            val outcome = streamOnce(
                conversationId = conversationId,
                messageId = messageId,
                config = if (forceFinal) config.copy(webSearchEnabled = false) else config,
                history = history,
            )
            if (outcome.failed) return
            val decision = AgentLoop.decide(outcome.finishReason, outcome.toolCalls, steps, config.maxAgentSteps)
            when (decision) {
                is AgentLoop.Decision.Continue -> {
                    executeTools(conversationId, config, decision.toolCalls)
                    steps++
                }

                AgentLoop.Decision.ForceFinal -> {
                    executeTools(conversationId, config, outcome.toolCalls)
                    steps++
                }

                AgentLoop.Decision.Finish -> return
            }
            // 收尾步无论如何都结束，杜绝模型在没有工具时仍回 tool_calls 造成死循环。
            if (forceFinal) return
        }
    }

    private suspend fun streamOnce(
        conversationId: String,
        messageId: String,
        config: ChatConfig,
        history: List<Message>,
    ): TurnOutcome {
        val messages = withContext(Dispatchers.IO) {
            ContextBuilder.build(history, config.historyImageLimit) { attachment ->
                attachmentStore.toRequestImage(attachment, config.imageDetail)
            }
        }
        val accumulator = StreamAccumulator(startedAt = elapsedMs())
        val toolCalls = ToolCallAccumulator()
        var status = MessageStatus.COMPLETE
        var errorMessage: String? = null

        _streaming.value = StreamingMessage(conversationId, messageId, "", "")
        try {
            api.stream(config, messages).collect { event ->
                if (event is ChatStreamEvent.ToolCallDelta) toolCalls.accept(event)
                val update = accumulator.accept(event, elapsedMs())
                if (update.publish) {
                    _streaming.value = StreamingMessage(
                        conversationId = conversationId,
                        messageId = messageId,
                        content = accumulator.content,
                        reasoning = accumulator.reasoning,
                        reasoningMs = accumulator.reasoningMs,
                    )
                }
                if (update.checkpoint) {
                    db.messageDao().updateContent(
                        id = messageId,
                        content = accumulator.content,
                        reasoning = accumulator.reasoning,
                        reasoningMs = accumulator.reasoningMs,
                        updatedAt = nowMs(),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            status = MessageStatus.CANCELLED
            throw cancelled
        } catch (t: ChatApiException) {
            status = MessageStatus.ERROR
            errorMessage = t.message ?: "请求失败"
        } catch (t: Throwable) {
            status = MessageStatus.ERROR
            errorMessage = t.message ?: "请求失败"
        } finally {
            withContext(NonCancellable) {
                finalize(conversationId, messageId, accumulator, status, errorMessage)
                val assembled = toolCalls.assemble()
                if (assembled.isNotEmpty()) {
                    db.messageDao().updateToolCalls(messageId, ToolCallCodec.encodeCalls(assembled), nowMs())
                }
            }
        }
        return TurnOutcome(
            finishReason = accumulator.finishReason,
            toolCalls = toolCalls.assemble(),
            failed = status == MessageStatus.ERROR,
        )
    }
```

- [ ] **Step 4: 新增工具执行**

```kotlin
    private suspend fun executeTools(conversationId: String, config: ChatConfig, calls: List<ToolCall>) {
        for (call in calls) {
            val toolMessageId = newId()
            val timestamp = nowMs()
            db.messageDao().upsert(
                MessageEntity(
                    id = toolMessageId,
                    conversationId = conversationId,
                    role = Role.TOOL.name,
                    content = "",
                    status = MessageStatus.COMPLETE.name,
                    errorMessage = null,
                    reasoningContent = null,
                    seq = db.messageDao().nextSeq(conversationId),
                    model = config.model,
                    promptTokens = null,
                    completionTokens = null,
                    toolCallId = call.id,
                    toolResult = ToolCallCodec.encodeResult(
                        ToolResult(status = ToolStatus.RUNNING, detail = activityLabel(call)),
                    ),
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            _activity.value = activityLabel(call)
            val result = runCatching { runTool(call, config) }
                .getOrElse { ToolResult(status = ToolStatus.FAILED, detail = call.name, text = it.message ?: "工具执行失败") }
            db.messageDao().updateToolResult(
                id = toolMessageId,
                content = result.text.ifBlank { result.detail },
                toolResult = ToolCallCodec.encodeResult(result),
                updatedAt = nowMs(),
            )
            refreshSummary(conversationId)
        }
        _activity.value = null
    }

    private suspend fun runTool(call: ToolCall, config: ChatConfig): ToolResult = when (call.name) {
        WebTools.SEARCH -> {
            val query = WebTools.queryOf(call.arguments)
                ?: return ToolResult(ToolStatus.FAILED, call.name, text = "缺少搜索词")
            val provider = searchProvider
                ?: return ToolResult(ToolStatus.FAILED, query, text = "未配置联网搜索后端")
            val result = provider.search(query, WebTools.DEFAULT_MAX_RESULTS, config)
            ToolResult(
                status = ToolStatus.OK,
                detail = query,
                sources = result.sources,
                text = WebTools.formatSearchResult(query, result),
            )
        }

        WebTools.FETCH -> {
            val url = WebTools.urlOf(call.arguments)
                ?: return ToolResult(ToolStatus.FAILED, call.name, text = "缺少 URL")
            val result = webFetcher.fetch(url)
            ToolResult(
                status = if (result.statusCode in 200..299) ToolStatus.OK else ToolStatus.FAILED,
                detail = url,
                text = WebTools.formatFetchResult(result),
            )
        }

        else -> ToolResult(ToolStatus.FAILED, call.name, text = "未知工具：${call.name}")
    }

    private fun activityLabel(call: ToolCall): String = when (call.name) {
        WebTools.SEARCH -> "正在联网搜索：${WebTools.queryOf(call.arguments).orEmpty()}"
        WebTools.FETCH -> "正在抓取网页：${WebTools.urlOf(call.arguments).orEmpty()}"
        else -> "正在调用 ${call.name}"
    }
```

- [ ] **Step 5: `refreshSummary` 预览跳过 TOOL 行**

```kotlin
        val preview = messages.lastOrNull { it.role != Role.TOOL.name }?.toModel()?.let { message ->
```

- [ ] **Step 6: 编译 + 全量单测**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL
Run: `.\gradlew.bat test`
Expected: 全绿

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/data/ChatRepository.kt
git commit -m "feat(data): 多步联网搜索 Agent 循环"
```

---

### Task 13: 装配 provider（ChatAiApp）

**Files:**
- Modify: `ChatAiApp.kt`

- [ ] **Step 1: 加懒加载单例并注入**

```kotlin
    val webSearchProvider: WebSearchProvider by lazy { DeepSeekNativeSearchProvider() }

    val webFetcher: WebFetcher by lazy { HttpWebFetcher() }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(
            db = database,
            settingsRepository = settingsRepository,
            api = chatApi,
            attachmentStore = attachmentStore,
            searchProvider = webSearchProvider,
            webFetcher = webFetcher,
        )
    }
```

imports 补 `com.zcw.chatai.data.web.*`。

- [ ] **Step 2: 编译**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/ChatAiApp.kt
git commit -m "feat(app): 装配搜索 provider 与抓取器"
```

---

### Task 14: UI state 与 ViewModel

**Files:**
- Modify: `ui/chat/ChatUiState.kt`、`ui/chat/ChatViewModel.kt`

- [ ] **Step 1: `ChatMessageItem` 与 `ChatUiState` 加字段**

`ChatUiState.kt` 的 `ChatMessageItem` 加（只保留结果，模型意图由紧随其后的 TOOL 行统一呈现，避免重复）：

```kotlin
    val toolResult: ToolResult? = null,
```

`ChatUiState` 加：

```kotlin
    val webSearchEnabled: Boolean = false,
    val webSearchAvailable: Boolean = true,
    val activity: String? = null,
```

imports 补 `com.zcw.chatai.data.model.ToolResult`。

- [ ] **Step 2: ViewModel factory 注入 provider**

```kotlin
class ChatViewModel(
    private val repository: ChatRepository,
    private val attachmentStore: AttachmentStore,
    settingsRepository: SettingsRepository,
    private val searchProvider: WebSearchProvider? = null,
) : ViewModel() {
```

`ComposerSnapshot` 加 `val activity: String?`；`composerFlow` 改为五路 combine：

```kotlin
    private val composerFlow = combine(
        input,
        pending,
        notice,
        settingsRepository.settings,
        repository.activity,
    ) { text, pend, note, settings, activity ->
        ComposerSnapshot(
            input = text,
            pending = pend,
            notice = note,
            defaultModel = settings.model,
            webSearchAvailable = searchProvider?.available(settings.baseUrl, settings.apiKey) == true,
            activity = activity,
        )
    }
```

`factory(...)` 加参数 `searchProvider: WebSearchProvider?`，并传入构造。

- [ ] **Step 3: 加 toggle 方法**

```kotlin
    fun toggleWebSearch() {
        val id = conversationId.value ?: return
        val current = state.value.webSearchEnabled
        viewModelScope.launch {
            if (!current && !state.value.webSearchAvailable) {
                notice.value = "联网搜索不可用：请先在设置里填写 API Key"
                return@launch
            }
            repository.setConversationWebSearch(id, !current)
        }
    }
```

- [ ] **Step 4: buildState 透传 + toItem 映射工具字段**

`buildState` 的 `ChatUiState(...)` 加：

```kotlin
            webSearchEnabled = conversation?.webSearchEnabled == true,
            webSearchAvailable = composer.webSearchAvailable,
            activity = composer.activity,
```

`Message.toItem()` 补：

```kotlin
        toolResult = toolResult,
```

（`toolResult` 直接来自 `Message.toolResult`；无 WebTools 依赖。）

- [ ] **Step 5: 编译**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL（UI 尚未渲染新字段，允许）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/ui/chat/ChatUiState.kt app/src/main/java/com/zcw/chatai/ui/chat/ChatViewModel.kt
git commit -m "feat(ui): 会话联网开关与工具字段进入 UI state"
```

---

### Task 15: ToolCallBlock 与消息渲染

**Files:**
- Create: `ui/chat/ToolCallBlock.kt`
- Modify: `ui/chat/ChatScreen.kt`

- [ ] **Step 1: 写 ToolCallBlock（复用 ReasoningBlock 的折叠模式）**

```kotlin
package com.zcw.chatai.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
import com.zcw.chatai.data.web.WebTools

/** 一次工具调用的折叠块：标题 + 查询/URL + 来源列表。 */
@Composable
fun ToolCallBlock(result: ToolResult, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    val isFetch = result.detail.startsWith("http") || result.detail.startsWith("正在抓取")
    val title = if (isFetch) "网页抓取" else "联网搜索"
    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = if (result.status == ToolStatus.RUNNING) "$title…" else title,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = "· ${result.detail}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (result.status == ToolStatus.FAILED) {
                Text("失败", style = MaterialTheme.typography.labelMedium, color = scheme.error)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            result.sources.forEach { source ->
                Text(
                    text = source.title ?: source.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 2.dp),
                )
            }
            if (result.text.isNotBlank()) {
                Text(
                    text = result.text.take(2000),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 2: `AiMessageItem` 无需改渲染**

工具结果只在紧随其后的 `role=TOOL` 行由 `ChatScreen` 渲染成 `ToolCallBlock`；assistant 行本身不重复展示调用意图。跳过本步（保留编号以对齐后续步骤）。

- [ ] **Step 3: `ChatScreen` 增加 TOOL 分支**

`itemsIndexed` 的 `when (message.role)` 改为：

```kotlin
                        when (message.role) {
                            Role.USER -> UserMessageItem(...)

                            Role.TOOL -> Box(Modifier.padding(horizontal = 16.dp)) {
                                message.toolResult?.let { ToolCallBlock(it) }
                            }

                            else -> AiMessageItem(...)
                        }
```

- [ ] **Step 4: 编译**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/ui/chat/ToolCallBlock.kt app/src/main/java/com/zcw/chatai/ui/chat/MessageItems.kt app/src/main/java/com/zcw/chatai/ui/chat/ChatScreen.kt
git commit -m "feat(ui): 内联可折叠工具块"
```

---

### Task 16: 🌐 开关与流式活动提示

**Files:**
- Modify: `ui/chat/Composer.kt`、`ui/chat/ChatScreen.kt`、`ui/App.kt`

- [ ] **Step 1: `Composer` 加开关**

签名加：

```kotlin
    webSearchEnabled: Boolean,
    webSearchAvailable: Boolean,
    onToggleWebSearch: () -> Unit,
```

在按钮 `Row` 的图片按钮后加：

```kotlin
            WebSearchToggle(
                enabled = webSearchEnabled,
                available = webSearchAvailable,
                onClick = onToggleWebSearch,
            )
```

文件末尾加：

```kotlin
@Composable
private fun WebSearchToggle(enabled: Boolean, available: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = ChatTheme.colors
    val tint = when {
        !available -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
        enabled -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled && available) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = available, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "🌐", style = MaterialTheme.typography.bodyLarge)
    }
}
```

（`Color` 已在 imports；`enabled` 只影响 `tint`，实际用 emoji 颜色，保留变量避免未使用；如需 lint 干净可在 `Text` 上加 `.alpha`。）

- [ ] **Step 2: `ChatScreen` 透传**

`Composer(...)` 调用加：

```kotlin
                webSearchEnabled = state.webSearchEnabled,
                webSearchAvailable = state.webSearchAvailable,
                onToggleWebSearch = onToggleWebSearch,
```

`ChatScreen` 签名加 `onToggleWebSearch: () -> Unit,`。

流式活动提示：在底部 Composer 的 `Box` 上方加：

```kotlin
        state.activity?.let { activity ->
            Text(
                text = activity,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp, start = 16.dp, end = 16.dp),
            )
        }
```

- [ ] **Step 3: `App.kt` 透传回调**

`ChatViewModel.factory(...)` 加 `searchProvider = app.webSearchProvider`；`ChatScreen(...)` 加：

```kotlin
                onToggleWebSearch = viewModel::toggleWebSearch,
```

- [ ] **Step 4: 编译**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zcw/chatai/ui/chat/Composer.kt app/src/main/java/com/zcw/chatai/ui/chat/ChatScreen.kt app/src/main/java/com/zcw/chatai/ui/App.kt
git commit -m "feat(ui): 输入框 🌐 联网开关与流式活动提示"
```

---

### Task 17: 全量校验与真机联调

**Files:** 无（验证任务）

- [ ] **Step 1: 全量单测**

Run: `.\gradlew.bat test`
Expected: 全绿（原 164 + 新增约 25）

- [ ] **Step 2: Lint**

Run: `.\gradlew.bat lint`
Expected: 无新增 error

- [ ] **Step 3: 安装到真机**

Run: `.\gradlew.bat installDebug`
（真机需已开启 USB 调试；按 AGENTS.md，模拟器 IME 行为无法验证、且宿主机代理会做 TLS 拦截，故必须真机。）

- [ ] **Step 4: 真机手工验证清单**

1. 设置页填好 DeepSeek API Key 与 `https://api.deepseek.com/v1`。
2. 新会话点 🌐（高亮），问「2026 年今天有什么 AI 新闻」，确认出现折叠搜索块 + 来源链接，最终回答带 markdown 链接。
3. 追问需要读全文的问题，确认出现 `web_fetch` 块，正文基于网页内容。
4. 关闭 🌐 再问同一问题，确认不再出现工具块（行为与旧版一致）。
5. 退出 App 重进，确认该会话 🌐 仍为开启态、历史工具块仍在。
6. 清空 API Key 后点 🌐，确认提示「联网搜索不可用：请先在设置里填写 API Key」。
7. 生成中点停止，确认可中断且已产生的消息不丢。

- [ ] **Step 5: 更新 AGENTS.md（记录事实与新约定）**

在「DeepSeek / OpenAI 兼容端点的事实」节补一条：联网搜索只在 Anthropic 面（`web_search_20250305`），OpenAI 面拒绝 `web_search`；主回路仍用 OpenAI 面 function calling。并在「踩过的坑」补：带 `tool_calls` 的 assistant 回合必须回传 `reasoning_content`，否则 400。

- [ ] **Step 6: 提交**

```bash
git add AGENTS.md
git commit -m "docs: 记录联网搜索接口面与工具调用注意事项"
```

---

## 完成定义（DoD）

- [ ] `.\gradlew.bat test` 全绿；`.\gradlew.bat assembleDebug` 成功。
- [ ] 真机验证清单 7 条全过。
- [ ] 关闭 🌐 时请求体不含 `tools`，与旧行为逐字节等价。
- [ ] spec 第 8 节「范围外」事项确未实现（无第三方 provider、无并行工具、无 Responses web_search）。
