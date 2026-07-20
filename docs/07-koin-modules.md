# Koin 模块装配

`allKoinModules = listOf(appModule, aiModule, memoryModule)`,在 `MuseApp.onCreate` 的 `startKoin` 中装载。

注册顺序约定:
1. `appModule` — `SettingsRepository` / `ProviderConfigStore` / `MemoryLlmClient` / `AppScope`(`CoroutineScope(SupervisorJob() + Dispatchers.IO)`) / `MuseDb` + 各 DAO + Repository / `OkHttpClient`(named `chat` 与 `webSearch`)/ `DocumentParser` / `OcrManager` / `TtsManager` / `ToolRegistry` / `SystemPromptAssembler` / `McpRegistry` / `BackupService` / `CloudBackupService` / `BalanceService` / `ConfigImporter` / `MuseNotificationManager` / `MdnsService` / `WebServer` / `WebSearchService` / `MemoryTicker` / `ScheduledTaskRunner` / `ProactiveMessageRunner` / `SkillExecutor` / 3 个 ViewModel。
2. `aiModule` — `ChatService` + `ImageService`(依赖 `ProviderConfigStore` + `OkHttpClient`)。
3. `memoryModule` — Room(`memory.db` + `facts.db`)+ 核心服务 + `MemoryTicker`(依赖 `MemoryLlmClient` + `AppScope`)。

模块加载顺序有依赖:`aiModule` 依赖 `appModule` 注册的 `ProviderConfigStore` 与 `OkHttpClient`;`memoryModule` 依赖 `appModule` 注册的 `MemoryLlmClient` 与 `AppScope`。`allKoinModules` 的列表顺序需保证 `appModule` 在前。

关键设计:`SettingsRepository` 同时注册为自身和 `ProviderConfigStore` 接口实现:

```kotlin
single { SettingsRepository(androidContext()) }
single<ProviderConfigStore> { get<SettingsRepository>() }
```

这样 `ai` 模块的 `ChatService` / `ImageService` 能通过接口注入,而 app 层仍用具体类型访问完整 API。`ChatViewModel` 用 `viewModel { }` DSL 注册(注入 19 个依赖),UI 用 `koinViewModel()` 获取。`MemoryViewModel` / `StatsViewModel` 同样用 viewModel DSL 注册。

OkHttp client 用 qualifier 区分:`named("chat")` 用于 SSE 流式与图片生成(`connectTimeout` 30s / `readTimeout` 120s / `writeTimeout` 30s),`named("webSearch")` 独立 client 避免与 SSE 长连接互相影响。两者都通过 `applyProxy(config)` 应用 `ProxyConfig`(支持 HTTP / SOCKS / SOCKS5 + 代理认证)。Phase 8.5 修复过 webSearch client 覆盖 chat client 导致图片生成超时的问题。`WebSearchService` 的 config 懒加载,避免 Koin 初始化时主线程 `runBlocking` ANR。