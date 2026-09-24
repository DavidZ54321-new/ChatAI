# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 先读 AGENTS.md

本仓库的深度工程文档是 [`AGENTS.md`](./AGENTS.md)：分层清单、AGP 9 DSL 与常规示例的差异、
DeepSeek 兼容端点的**实测**事实、模拟器联调手法、以及一节「踩过的坑」。下面用 `@` 导入把它带进上下文。

@AGENTS.md

> 导入的正文不一定在提示里显示出来，但它在会话启动时已经完整载入——不要因为「看不到」就再 Read 一遍。
> 改动构建脚本、网络层、视觉尺寸之前，**不要凭记忆或通用 Android 经验下手**——那些结论是在真接口和
> 真设备上一条条验出来的，AGENTS.md 里写明了原因。

## 常用命令

```pwsh
.\gradlew.bat assembleDebug        # 只构建，不需要设备
.\gradlew.bat installDebug         # 构建 + 装到已连接设备
.\gradlew.bat test                 # JVM 单测，全部在 app/src/test
.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.data.net.ChatStreamTest"
.\gradlew.bat lint                 # 未配置 formatter / typecheck 任务
```

单测 680 个，**全是 JVM 测试**（无 Robolectric）：网络层走 MockWebServer，其余是纯函数
（错误映射、图片压缩尺寸、模型能力表、供应商目录/工具后端、思考字段线型、LaTeX 分段、
行内公式、Markdown、思考摘要/耗时格式化、视觉度量、Room 映射往返、音频/视频编解码）。
完整验证 = `assembleDebug` + `test`。

`app/src/androidTest/` 只有一个脚手架示例，别指望端到端测试。

## 架构：读多个文件才拼得出来的部分

单模块、单 Activity。**手写 ServiceLocator + 单向数据流**，没有 Hilt、没有导航库（两屏靠
`MainActivity` 里的状态路由，见 `ui/App.kt`）。

```
UI (Compose)  →  ChatViewModel  →  ChatRepository  →  Room / ChatApi
     ↑                                                      │
     └──────────────────────  Flow ─────────────────────────┘
```

四条跨文件的约定，改动时最容易踩：

1. **`ChatRepository` 是唯一业务入口，且跑在自己的 CoroutineScope 上。**
   `send` / `regenerate` / `deleteMessage` 都是**同步返回、异步执行**，不挂在 `viewModelScope` 上。
   用户点完发送立刻切后台也不能丢回答——把这类操作挪回 VM scope 会直接复现这个 bug。
   只有纯 UI 编排（选会话、清输入框）才用 `viewModelScope`。
2. **`data/ai/` 是刻意抽出的纯逻辑层**（`ContextBuilder` / `StreamAccumulator` / `ReasoningPreview`
   / `ReasoningDuration`），不依赖 Android，就是为了能进 JVM 单测。往这里加东西时保持无 Android 依赖。
3. **`data/net/` 是接口 + 实现分离**：`ChatApi` 是接口，`OpenAiCompatibleChatApi` 用 okhttp-sse +
   `callbackFlow` 实现。设计原则是**不为任何厂商特制**——线上只用标准 OpenAI 交集，厂商差异靠数据
   消化（预设基址表、模型能力表、设置里的「附加请求参数 JSON」逃生口）。
4. **Room schema v4**，迁移在 `data/db/Migrations.kt`。`messages.reasoning_ms` 的 **NULL 语义 =
   「未测量」**，与「0ms 瞬间完成」是两回事，别把它当 0 处理。改 schema 要同时动
   entity / DAO / `Mappers.kt`（有往返单测）和迁移。

视觉尺寸的单一来源是 `ui/chat/ChatMetrics.kt`（相对**视窗**而非父容器），调观感只动那里的常量，
各配纯函数单测。

## Claude Code 环境（AGENTS.md 未覆盖）

- **MCP**：`codegraph`（本仓库有 `.codegraph/` 索引，定位/理解代码优先用它而非 grep）、`blender`
  两者是 user scope；`scrcpy` 是本项目 **local scope**，存在 `~/.claude.json` 的
  `projects["C:/Users/ZZW/AndroidStudioProjects/ChatAI"]` 下，**不进 git**。仓库里的 `opencode.json`
  是同一份配置的 opencode 版本，两边并存、别互相删。
  → 因此 AGENTS.md 里「`opencode.json` hardcodes machine paths」那条要注意：改路径时**两个地方都要改**。
- **Skills**：`.claude/skills/` 下 24 个 Google 官方 Android skill，在 Claude Code 里通过 Skill 工具调用
  （AGENTS.md 里写的是「OpenCode reads that path」，那是另一个工具的说法）。涉及 Android 最佳实践时
  **优先调用对应 skill**，别凭印象回答。该目录是**故意入库**的。
- 模拟器/设备操作走 `scrcpy` MCP 工具（screenshot / tap / swipe / `input_text` / `ui_find_element` /
  logcat）。若 `mcp__scrcpy__*` 工具在当前会话取不到，说明会话是在配置之前启动的——重启 Claude Code。
  相关坑（旋转后截图错位、中文输入、坐标获取）见 AGENTS.md「Driving an emulator / device」。
- **推 GitHub 别裸 `git push`**：会连环弹 Git Credential Manager 登录窗（用户点到手酸）。
  用 AGENTS.md「Pushing to GitHub」的零弹窗配方：`git credential fill` 取已存 PAT →
  一次性 `GIT_ASKPASS` 喂凭据 → `git -c credential.helper=` 推送。环境变量 `GITHUB_TOKEN`
  是别的账号（OrganCanvasGlass），不能拿来推本仓库；PAT 只在管道里用，不回显、不落文件。
