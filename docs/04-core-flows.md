# 核心业务流程

### 4.1 聊天消息流

用户在 `InputBar` 输入文本(可附图片/附件/引用回复)→ `ChatViewModel.sendMessage` → `transformerPipeline` 6 步处理 → `SystemPromptAssembler` 组装 system prompt(9 个 section)→ `ChatService.stream` 发起 SSE 流式请求 → 流式 token 回调 → 实时写库 + 更新 `ChatUiState` → UI 重组渲染。

6 步 Transformer 顺序(见 `ChatViewModel.transformerPipeline` 的 Builder 链):
1. `MemoryInjectionTransformer` — 注入 `MemoryTicker` 产出的长期记忆摘要。
2. `TimeReminderTransformer` — 注入当前时间提醒。
3. `LorebookTransformer` — 按关键词触发 Lorebook 条目注入。
4. `PromptInjectionTransformer` — 按当前模式开关(`currentMode`)注入 PromptInjection 条目。
5. `TemplateTransformer` — 替换 `Assistant.messageTemplate` 中的 `{{var}}` 变量。
6. `ThinkTagTransformer` — 处理 think 标签与 MoodTag。

`ContextCompressTransformer` 独立持有为字段(`contextCompressTransformer`),供手动压缩(`manualCompress`)调用,不在常规管道链中。`Assistant` 配置通过 `TransformContext.extras` 注入,各 Transformer 自行读取。

工具调用:LLM 返回 `ToolCall` 时,`ChatViewModel` 调用 `SkillExecutor` 或 `ToolRegistry` 执行,设 2 分钟超时(`TOOL_TIMEOUT_MS = 120_000L`),超时终止避免阻塞流式。深思考开关(`deepThinkingEnabled`)开启时切换 `ReasoningLevel.HIGH`(8000 tokens 预算),LLM 可自主调用 `web_search` / `web_fetch`。绘图模式(`isDrawMode`)开启时 `send` 调用 `ImageService` 而非 `ChatService`。

状态管理:单一 `state: StateFlow<ChatUiState>`,UI 通过 `collectAsStateWithLifecycle` 订阅。`ChatUiState` 包含消息列表、输入、流式状态、错误列表(`errors`,支持多错误并存)、会话列表、助手、上下文 token 占用估算(`contextTokenCount` / `contextMaxTokens`)、TTS 状态、任务卡映射(`taskCards`)等。错误分 6 类:`NETWORK` / `API_KEY` / `RATE_LIMIT` / `MODEL_ERROR` / `TOOL_ERROR` / `UNKNOWN`,支持 dismiss。

通知:流式启动/进度/完成/错误/停止各环节调用 `MuseNotificationManager`。`debugMode` 开启时填充本次回复摘要(模型/耗时/字符/工具调用数)。

`SystemPromptAssembler` 组装 system prompt 的 9 个 section(集中拼装,各 section 独立):Assistant 人设 / 用户画像 / 记忆摘要 / 时间 / 工具与 Skill 声明 / Lorebook / 多 Agent 配置 / 实验性开关(`forceMoodBlock` / `selfReflection`)/ 聊天行为偏好。通过 `getExperiments` / `getMultiAgentConfig` 闭包零阻塞读取 `@Volatile` 缓存,保证设置页改动立即影响 system prompt。

多模态输入:用户可附图片(`pendingImages`,base64 无 data 前缀)与附件(经 `DocumentParser` 解析 + `OcrManager` 提取文本)。引用回复(`replyingTo`)将目标消息内容拼入上下文。流式完成后可触发 `TtsManager` 朗读(`isSpeaking` / `speakingMessageId`),TTS 语速/音高/语言由 `mediaConfig` 控制,音频输出方式(扬声器/听筒/蓝牙)由 `applyAudioOutput` 切换 AudioManager 路由。

语音输入:`AsrConfig` 配置 ASR provider/apiKey/model,`InputBar` 麦克风按钮长按录音(`isRecording`),实时采集振幅(`recordingAmplitude` / `recordingAmplitudeHistory`)驱动波形 UI;松开后转识别(`isRecognizing`),识别结果填入输入框。外部分享 Intent(`intent/` 模块)接收文本/文件后转入聊天会话。

### 4.2 Provider 与模型管理

`SettingsRepository` 通过 `providersFlow` / `activeProviderIdFlow` / `providerConfigFlow` 持久化 Provider 列表、活跃 Provider、模型与 API Key。`PresetProviders` 提供预置清单,分三类:
- 海外官方(9 家):`openai` / `anthropic` / `gemini` / `groq` / `together` / `mistral` / `openrouter` / `deepInfra` / `fireworks`。
- 国产官方(8 家):`deepseek` / `qwen` / `zhipu` / `moonshot` / `doubao` / `baichuan` / `lingyi` / `stepfun`。
- 中转站(6 家,含 2 个自建模板):`opencode` / `api2d` / `aihubmix` / `deepbricks` / `oneapiTemplate` / `newapiTemplate`。

每个预置 `ProviderConfig.builtIn = true` 不可删除,`apiKey` 留空由用户填入,`baseUrl` 走各家官方 OpenAI 兼容端点,`models` 默认空列表,首次使用时从上游 `/models` 接口动态拉取并保存(模型 ID 不在本地维护,避免过期)。`category` 字段用于 UI 分组展示。`byId(id)` 支持按 id 查找。

演进(v1.35–1.38):Provider 选择与编辑由 Dialog 改为全屏 Page。根因是 `ModalBottomSheet` 在嵌套滚动 + IME 场景下会卡死,全屏 Page 规避该问题且更适合长表单编辑。新增 Provider 走全屏选择页,编辑走 `ProviderEditPage`,模型列表拉取后保存回 `SettingsRepository`。底部模型切换面板(`isFetchingModels` / `fetchModelsError`)支持运行时拉取上游模型。

### 4.3 Skill 系统

`SkillExecutor` 按 `SkillEntity.implementationKotlin` 字符串路由到预定义 Kotlin 函数执行,不允许任意代码执行。内置实现包括:
- 文件/HTTP 类(4 个):`read_file`(读应用沙盒文件)、`write_file`(写沙盒文件)、`http_get`、`http_post`(30s 超时,响应体最大 1MB)。
- 搜索/信息类(4 个):`web_search`、`web_fetch`、`knowledge_search`(查本地知识库)、`arxiv_search`。
- 自我扩展(1 个):`install_skill`(LLM 生成 skill 定义入库)。
- 扩展能力:`delegate_agent`(多 Agent 协作,委托子助手跑一轮 LLM)、`list_dir` / `delete_file` / `file_exists`(文件管理)、`file_download` / `read_public_file` / `save_to_downloads`(公共目录与文件传输)。

文件操作严格限定在应用沙盒(`filesDir` / `cacheDir`),避免越权读写;所有 IO 在 `Dispatchers.IO` 执行。耗时工具(`web_fetch` / `web_search` / `delegate_agent` / `install_skill`)执行前通过 `onProgress` 回调通知 UI 显示进度文本。

`.skill.json` 格式:由 `SkillImporter` 校验 schema(名称、描述、参数定义、`implementationKotlin` 路由 key 等),通过后 upsert 入 `skillDao`。运行时 `ToolRegistry` 注册 skill 作为 LLM 可调用工具,LLM 决策调用时经 `SkillExecutor` 路由执行。`MuseApp.onCreate` 启动时通过 `SkillExecutor.BUILT_IN_SKILLS.forEach { skillRepository.upsert(it) }` 幂等初始化内置 Skills(REPLACE 策略),`runCatching` 容错。

### 4.4 记忆系统

`MemoryTicker` 由 `memoryModule` 提供但在 `appModule` 中注册(读 `SettingsRepository` 的记忆开关)。每小时做一次 daily check(主触发仍是 `ChatViewModel.notifyTurn`),产出长期记忆摘要与编译结果。

`MemoryInjectionTransformer`(管道第 1 步)把 `MemoryTicker` 的摘要注入上下文。`MemoryLlmClientImpl`(app 层)实现 `MemoryLlmClient` 接口,桥接 memory 模块与 app 的 `ChatService`,使 memory 模块能复用 app 配置的 Provider。

配置联动:`MemoryConfig`(`tokenBudget` / `decay` / `threshold` 等)由用户在设置页实时调整,写入 DataStore 后落到 `@Volatile` 的 `memoryConfigCache` 字段,`MemoryTicker` 通过 `getConfig = { settings.memoryConfigCache }` 闭包零阻塞读取,保证改完设置立即生效。当 `ExperimentsConfig.longMemoryCompression = true` 时,`compileThreshold` 降到 3.0(更激进编译),两个缓存通过 combine 流联动更新。

`memoryModule` 为外部模块,提供 Room(`memory.db` + `facts.db`)、`SummaryManager`、`MemoryCompiler`、`DeepProcessor`、`MemoryTicker`、`FactStore` 等核心服务。`MemoryViewModel` 注入 `factStore` / `summaryManager` / `memoryCompiler` / `memoryTicker`,展示记忆健康度与编译结果。

### 4.5 主动消息

`ProactiveMessageRunner` 让陪伴助手像真人一样定时主动发消息并弹通知。`MuseApp.onCreate` 调用 `start()` 进入轮询,每 60s 检查是否到达触发间隔;到期则调用 LLM 生成一条主动消息,写入当前会话并弹通知。

设计要点:
- 用协程轮询而非 `AlarmManager`,避免 Android 后台限制;单 `Job` 控制生命周期,`stop()` 取消即可。
- `lastTriggeredAt` 持久化在 DataStore,App 重启后不会立即重发。
- 任一环节失败(LLM 调用 / 写库 / 通知)都不更新 `lastTriggeredAt`,下个 tick 重试。
- LLM 决策 JSON 用 `Json { ignoreUnknownKeys = true }` 解析,兼容模型多返回字段。
- 协程加 `GlobalCoroutineExceptionHandler` 容错。

配置项(均在 `SettingsRepository`):主动消息开关、触发间隔、上次触发时间。依赖注入:`SettingsRepository` / `ChatService` / `SessionRepository` / `AssistantRepository` / `MuseNotificationManager` / `Context` / `AppScope`。

### 4.6 定时任务

`ScheduledTaskRunner` 每 60s 轮询到期任务,到期则弹通知,通知点击跳转对应会话/任务。执行历史记录到 `scheduledTaskExecutionDao`(`P1-7` 增强,便于排查)。`MuseApp.onCreate` 启动轮询(`runCatching` 容错)。用户在设置 → 定时任务页(`SCHEDULED_TASKS` 路由)管理任务。设计参考 `ProactiveMessageRunner` 的轮询结构。

### 4.7 备份与导入流程

`BackupService` 统一导出/导入本地数据:导出时打包 `MuseDb`(会话+消息+助手+Lorebook+Skill+知识库等)与 memory 模块的 `memory.db` + `facts.db` 为单个备份文件;导入时反向还原。`CloudBackupService` 在本地备份基础上派发到 S3 / WebDAV 远程存储,使用 `named("chat")` 的 OkHttpClient 上传。`BalanceService` 查询 Provider 余额,用 JsonPath 从各家不同的响应结构中提取余额字段。`ConfigImporter` 解析 CherryStudio / Chatbox 导出的 JSON 配置,转换为 `ProviderConfig` 列表写入 `SettingsRepository`,免去用户逐个手填。

### 4.8 Web 服务器与搜索流程

`WebServer` 基于 Ktor CIO,启用 JWT 鉴权,通过 `MdnsService` 注册 NSD 局域网服务,供同网络设备访问。`MuseApp.onCreate` 启动时若 `webServerConfigFlow.enabled` 为 true 则自动启动(fire-and-forget,失败仅记日志不阻塞)。`WebSearchService` 由 `CompositeWebSearchService` 实现,聚合多个搜索 provider(`SearXNG` / `Tavily` / `Bing` / 自定义 API),config 懒加载避免主线程 ANR。`MuseApp` 订阅 `defaultSearchEngineFlow`,把 "bing" / "searxng" 等值映射到 `WebSearchConfig.providerName` 并调用 `updateConfig` 立即生效。深思考或联网搜索开关(`webSearchEnabled`)开启时,LLM 自主决定调用 `web_search` / `web_fetch` 工具。

### 4.9 多 Agent 协作

`MultiAgentConfig`(`multiAgentConfigCache`)配置团队列表与总开关,由 `SystemPromptAssembler` 通过 `getMultiAgentConfig` 闭包零阻塞读取,注入到 system prompt 让 LLM 知晓可用子助手。LLM 调用 `delegate_agent` skill 时,`SkillExecutor` 根据 `assistantId` 从 `AssistantRepository` 取子助手配置,调用 `ChatService` 跑一轮 LLM 完成子任务并返回结果。设置入口:`SETTINGS_MULTI_AGENT`(团队列表)与 `SETTINGS_AGENT`(助手选择/协作/主动消息)。首页 Agent Tab(`isAgentMode`)用独立 `agentSessionId` 隔离 Agent 会话与任务会话。