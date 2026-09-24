# ChatAI

单模块 Jetpack Compose + Material 3 的 Android AI 聊天应用。多供应商、流式输出、思考过程、
工具调用（联网搜索 / 图搜 / 网页抓取）、附件（图片 / 视频 / 音频 / 文档）、Markdown 与 LaTeX
渲染，双主题族（Claude 暖奶油 / ChatGPT 黑白）× 明暗两轴正交切换。

## 功能

- **多供应商**：DeepSeek、通义千问（DashScope）、OpenCode Go、MiMo、自定义 OpenAI 兼容端点。
  供应商是**数据**（`ProviderCatalog` preset：基址 / 能力 / 思考线型 / 视频策略），加供应商不写
  if-vendor 分支；会话按供应商绑定，连接参数随会话、生成参数全局。
- **流式对话**：SSE 流式正文 + `reasoning_content` 思考过程折叠展示，思考耗时落库
  （单调钟测量，NULL = 未测量）。
- **Agent 工具循环**：有界轮次（`maxAgentSteps = 6`）内「流式 → 工具 → 再流式」，
  预算显式回喂模型。工具含文本搜索（Anthropic / Qwen Responses / MiMo 插件三种协议，有序借道回退）、
  文搜图 / 图搜图（Qwen 模型链与对话模型解耦）、网页抓取（本机 HttpWebFetcher）。
- **附件**：图片（压缩 + 内联 base64，按轮次保留并逐张编号标注）、视频（按供应商分路由：
  内联 / DashScope 上传 / 可读拒绝，发送前预检门禁）、音频（MiMo 内联 base64）、
  文档（PDF / docx / xlsx / pptx，手写 zip + kxml2 解析，不走 Apache POI）。
- **渲染**：Markdown（mikepenz）+ LaTeX（vendored Kai，行内与块级两套分段）+ 独立图片行
  （`LazyRow` 横滑）+ PlantUML / Mermaid 资产。
- **主题**：`ThemeFamily` × `ThemeMode` 双轴注册表，加主题 = 加 preset + 注册一行。
- **本地优先**：Room schema v5 全量落库，会话列表抽屉，冷启动进新对话。
  API Key 只存 DataStore，绝不入库、绝不打日志。

## 架构

单 Activity、手写 ServiceLocator + 单向数据流（无 Hilt、无导航库）：

```
UI (Compose)  →  ChatViewModel  →  ChatRepository  →  Room / ChatApi
     ↑                                            │
     └────────────────────  Flow ─────────────────┘
```

- `ChatRepository` 是唯一业务入口，跑在自己的 scope 上（发送 / 重试 / 删除同步返回、异步执行，
  用户切后台不丢回答）。
- `data/ai/` 是无 Android 依赖的纯逻辑层，专门进 JVM 单测。
- `data/net/` 接口 + 实现分离，不为任何厂商特制；厂商差异由 preset 数据消化。
- 视觉尺寸单一来源 `ui/chat/ChatMetrics.kt`（相对视窗）。

更完整的工程约定见 [`AGENTS.md`](./AGENTS.md)。

## 构建与测试

```pwsh
.\gradlew.bat assembleDebug        # 只构建，不需要设备
.\gradlew.bat installDebug         # 构建 + 装到已连接设备
.\gradlew.bat test                 # JVM 单测，全部在 app/src/test
.\gradlew.bat lint                 # AGP 默认；未配置 formatter / typecheck
```

完整验证 = `assembleDebug` + `test`。单测全是 JVM 测试（无 Robolectric）：网络层走
MockWebServer，其余是纯函数（错误映射、供应商目录、工具回退链、上下文组装、附件保留、
视觉度量、Room 映射往返……）。`app/src/androidTest/` 只有脚手架示例。

要求：AGP 9.4.0 / Gradle 9.6.0 / Kotlin 2.4.10 / compileSdk 37 / minSdk 29。
版本号统一在 `gradle/libs.versions.toml`，构建脚本里不写死版本。

## 仓库文档

| 文件 | 内容 |
| --- | --- |
| [`AGENTS.md`](./AGENTS.md) | 深度工程文档：分层清单、AGP 9 DSL 差异、各供应商实测事实、模拟器联调、踩过的坑 |
| [`CLAUDE.md`](./CLAUDE.md) | Claude Code 会话用的浓缩指引 |
| [`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md) | 第三方 vendored / 打包代码的许可 |
| `design/` | 视觉设计文档（ClaudeDesign.md 为营销页口径，ChatGPT.md 为槽位映射） |

## License

[Apache License 2.0](./LICENSE)。第三方组件许可见 [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md)。
