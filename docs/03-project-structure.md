# 项目结构

根目录 `d:\1test\1muse\`,`ai/` 为外部模块(不在本次范围)。app 主代码位于 `app/src/main/java/io/zer0/muse/`。

### 根包
- `MuseApp.kt` — `Application` 入口,实现 `ImageLoaderFactory`。最先安装 `MuseCrashHandler`(早于 `startKoin`,避免 Koin 初始化崩溃漏捕获),`Logger.initFileLog` 初始化文件日志,`startKoin` 装载 `allKoinModules`,`notificationManager.ensureChannels()` 创建通知渠道,启动 `MemoryTicker` / `ScheduledTaskRunner` / `ProactiveMessageRunner`(均 `runCatching` 容错),确保默认 Assistant 存在,seed 内置 Skills 与开发文档(`assets/devdocs/`)到知识库,按需启动 WebServer。订阅 `keepAwake`(申请/释放 `PARTIAL_WAKE_LOCK`)、`autoLaunch`(启停 `BootReceiver`)、`mediaConfig`(TTS 语速/音高/音频输出方式)、`defaultSearchEngine`(映射到 `CompositeWebSearchService`)等 Flow 实时应用设置。
- `MainActivity.kt` — 单 Activity,承载 Compose NavHost,edge-to-edge + imePadding。
- `AppKoinModule.kt` — Koin `appModule` 装配,详见第七节。
- `UpdateChecker.kt` — 版本更新检查。

### asr/
语音识别。`AsrConfig` 持久化 provider/apiKey/model。`ChatViewModel` 中 `isRecording` / `isRecognizing` / `recordingAmplitude` / `recordingAmplitudeHistory` 驱动录音 UI 与波形显示。支持 ASR Provider 模式与本地录音两种。

### backup/
备份系统,本地 + 云双通道。`BackupService` 统一导出/导入(含 `memory.db` + `facts.db`),依赖 `SettingsRepository` / `SessionRepository` / `AssistantRepository` / `MuseDb` 等。`CloudBackupService` 派发到 S3 / WebDAV,使用 `named("chat")` 的 OkHttpClient。`BalanceService` 查询云服务余额(JsonPath 提取响应字段)。`CloudBackupConfig` 持久化配置。

### balance/
余额查询,`BalanceService` 使用 `named("chat")` 的 OkHttpClient 请求各 Provider 的余额接口。

### boot/
开机自启。`BootReceiver` 接收 `BOOT_COMPLETED`,manifest 默认 `enabled=false`,由 `MuseApp` 根据 `autoLaunchFlow` 用 `PackageManager.setComponentEnabledSetting` 动态启停(`DONT_KILL_APP` 标志)。

### crash/
`MuseCrashHandler` 全局崩溃捕获,捕获后写日志便于真机验证后回捞(`cacheDir` 卸载自动清理)。

### data/
数据层核心。`SettingsRepository` 基于 DataStore(详见第六节)。`MuseDb` 为 Room 数据库,聚合各 DAO:`sessionDao` / `messageDao` / `assistantDao` / `lorebookDao` / `quickMessageDao` / `promptInjectionDao` / `skillDao` / `folderDao` / `scheduledTaskDao` / `knowledgeDocDao` / `scheduledTaskExecutionDao`。各子包(`assistant` / `lorebook` / `promptinjection` / `quickmsg` / `skill` / `session` / `knowledge`)成对提供 `Entity` + `Dao` + `Repository`。`preset/PresetProviders.kt` 定义预置供应商清单。`ProxyConfig` / `MediaConfig` / `ChatPreferences` / `ShareTemplateConfig` / `ExperimentsConfig` / `MultiAgentConfig` / `ProactiveMessageConfig` 等为配置数据类。`MemoryLlmClientImpl` 实现 `MemoryLlmClient` 接口桥接 memory 模块。

### doc/
文档解析 + OCR。`DocumentParser` 解析附件(PDF/Office/Txt 等),`OcrManager` 基于 ML Kit 做中英文离线识别。`ChatViewModel` 收到图片附件时先 OCR 提取文本再送入上下文。

### importer/
配置导入。`ConfigImporter` 支持 CherryStudio / Chatbox 配置文件导入,解析后写入 `SettingsRepository` 的 Provider 列表。

### intent/
分享 Intent 处理,接收外部分享文本/文件并转入聊天会话。

### mcp/
MCP 协议客户端。`McpRegistry` 管理多个 `McpClient`,桥接到 `ToolRegistry`,实现动态工具扩展。`McpClient` 与外部 MCP server 通信,OAuth token 按 serverId 存储在 `SettingsRepository`。

### notification/
`MuseNotificationManager` 管理 3 个渠道:`chat_completed`(聊天完成)/ `live_update`(流式进度)/ `web_server`(Web 服务器事件)。`ensureChannels()` 幂等创建(Android 8.0+ 必需)。

### schedule/
定时任务与主动消息。`ScheduledTaskRunner` 每 60s 轮询到期任务并通知,通知点击跳转,执行历史记录到 `scheduledTaskExecutionDao`。`ProactiveMessageRunner` 每 60s 检查主动消息触发间隔,到期调用 LLM 生成消息写入会话并弹通知(详见 4.5/4.6)。两者均在 `MuseApp.onCreate` 启动。

### tools/
Skill 系统。`SkillExecutor` 按 `implementationKotlin` 路由到 Kotlin 函数执行(不用 QuickJS)。`ToolRegistry` 简化版 MCP 框架,注册内置工具并桥接 MCP 动态工具,注入 context 用于 Clipboard/UsageStats/Calendar 系统服务。`SkillImporter` 校验 `.skill.json` 格式(详见 4.3)。

### transformer/
上下文管道。6 步 `Transformer` 顺序处理用户消息与上下文(详见 4.1)。包含 `TransformerPipeline` / `MemoryInjectionTransformer` / `TimeReminderTransformer` / `LorebookTransformer` / `PromptInjectionTransformer` / `TemplateTransformer` / `ThinkTagTransformer` / `ContextCompressTransformer`。`SystemPromptAssembler` 组装最终 system prompt(9 个 section 集中拼装),透传 `getExperiments` / `getMultiAgentConfig` 闭包零阻塞读取实验性配置。v1.97:`TemplateTransformer` 增加 camelCase 别名(`userName`/`assistantName`)与 `user_nickname` 空值兜底("你"),修复默认 prompt 的 `{{userName}}` 占位符无法替换的 bug;`SystemPromptAssembler` 的用户画像 section 双路径注入称呼(显式文本 + 模板变量)。v1.97 阶段二:`SystemPromptAssembler` 的 MOOD 内心独白格式从"每字段 1 句"增强为四池框架 — Vibe(1 句直觉感受)/ Sparks(2-3 句意识流闪念)/ Reflections(2-3 句自我观察)/ Will(1 句行动意图),并增加"自然影响回答、不要机械列出"的引导规则,参考 openhanako 意识流内心独白。`AssistantRepository` 新增 `migrateDefaultPromptIfNeeded()`,在 `ensureDefaultExists()` 末尾调用,精确匹配 `LEGACY_SYSTEM_PROMPT_V1_95` 旧版 prompt 才自动升级到三层人设新版(用户自定义过的不动)。

### ui/
UI 层,全 Compose。`MuseRoutes.kt` 定义路由常量(详见第五节)。`ChatViewModel.kt` 为核心业务中枢(注入 19 个依赖)。`MemoryViewModel` 注入 memory 模块 3 个核心服务。`StatsViewModel` 注入 `MessageDao` + `SessionDao`(热力图 + 使用统计)。各子包按页面划分:
- `chat/` — 聊天页组件:`InputBar`(输入栏,含联网搜索/深思考/绘图/引用/附件/TTS 开关)、`MessageBubble`(消息气泡,含工具调用展示)、`TaskCard`/`taskcard/`(工具调用计划与步骤展示)、底部模型切换面板。v1.97 阶段二:新增 `SlashCommandRegistry`(斜杠命令枚举与解析),支持 `/new` `/compact` `/reset` `/pin` `/archive` 5 个客户端命令(不经 LLM,直接复用 `ChatViewModel` 的 `createNewSession`/`manualCompress`/`togglePinned`/`setSessionArchived` 等方法);`ChatScreen` 的 `onSend` 在发送前先经 `SlashCommand.isSlashCommand` 拦截,命中则调 `executeSlashCommand` 并消费输入(不进入聊天管道)。
- `markdown/` — v1.97:轻量 Markdown 渲染组件(自实现)。`MarkdownText` 支持代码块/标题/列表/引用/表格/公式/链接。v1.97 增强:纯文本 URL 自动识别(类似 openhanako linkify)+ URL 高亮色改为 `primary`(主题色)+ 点击 URL 弹 `MuseDialog` 二次确认(打开/复制链接)。v1.97 阶段二:URL 点击行为升级为双手势 — 单击仍弹二次确认(打开/复制链接/"本会话不再确认" Checkbox),长按直接打开浏览器并启用 `skipConfirm`(本会话后续 URL 直接打开不再确认),参考 openhanako 双手势交互。纯文本 URL 自动识别正则 `URL_AUTOLINK_REGEX` 排除尾部中文标点(。、,;:!?」』)。
- `translate/` — v1.97:独立翻译页(`TranslateScreen` + `TranslateViewModel`),复用 `ChatService` 的 `completeText`→`streamChat` 双路径翻译,从设置→工具→AI 翻译进入。v1.97 阶段二:`TranslateViewModel.State` 新增 `history: List<TranslateHistoryItem>` 字段(内存保留最近 20 条,不持久化,退出页面即清空),翻译成功后追加到历史头部;新增 `loadHistoryItem(item)`(加载历史项到输入框)和 `clearHistory()` 方法。`TranslateScreen` 在结果卡片下方新增 `TranslateHistorySection` UI — 顶部标题行(历史图标 + 标题 + 右侧"清空历史"按钮,空时不显示)、空状态 placeholder、历史卡片(目标语言 chip + 相对时间 + 原文/译文单行省略号),点击卡片调 `loadHistoryItem`,清空走 `MuseDialog` 二次确认(destructive)。
- `settings/` — 按 Section 拆分(模型/聊天/记忆/媒体/外观/数据/代理/安全/实验性/多 Agent/Agent/关于),每个二级页一个 Composable。v1.97 阶段二:`ThemeSection` 的 `CustomThemeEditDialog` 在名称输入框后、颜色选择器前新增"精选配色"横向滚动行,内置 8 套色盲友好精选配色(月桂绿/深海蓝/暮色紫/赤霞红/琥珀橙/青瓷青/墨纸灰/樱花粉),每套含 primary/container/onContainer 三色,一键填充自定义主题三色,降低配色门槛。
- `assistant/` — 助手聚合页 + 5 个子页(BASIC/PROMPT/EXTENSIONS/MEMORY/ADVANCED)。
- `stats/` — 统计页(热力图 + 使用统计)。
- `speech/` — `TtsManager`(系统 TextToSpeech 封装)+ v1.97 `CloudTtsService`(OpenAI/MiniMax/Edge 云端 TTS)。
- `onboarding/` — 首次引导页(2 步:功能介绍 + 个性化称呼设置[助手名+用户称呼],写入 `UserProfile` 注入 system prompt)。v1.97 阶段二:个性化称呼步骤的"添加模型供应商"按钮从 `TextButton` 提升为 `OutlinedButton`(显眼次要操作),并在按钮上方加 `onboarding_provider_hint` 提示文案("需要先配置 AI 供应商才能开始对话,推荐先完成此步"),避免新用户跳过 Provider 配置直接进入聊天后无所适从。

### util/
工具。`TokenEstimator` 估算上下文 token 占用(流式过程每 50 字符更新一次)。`GlobalCoroutineExceptionHandler` 企业级协程容错。通用 `Logger` / `AppJson` / `AppDispatchers` 等来自 `io.zer0.common`。

### web/
WebServer + WebSearch。`WebServer` 基于 Ktor CIO + JWT + mDNS,提供局域网 API,`MuseApp` 启动时若 `webServerConfigFlow.enabled` 则自动启动。`WebSearchService` / `CompositeWebSearchService` 联网搜索,使用独立 `named("webSearch")` OkHttpClient 避免与 SSE 长连接互相影响,config 懒加载避免主线程 ANR。`WebSearchConfig` / `WebServerConfig` 持久化配置。`MdnsService` NSD 局域网服务注册。

### widget/
桌面小组件,提供桌面快捷入口。