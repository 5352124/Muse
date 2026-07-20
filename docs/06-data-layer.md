# 数据层

### Room (`MuseDb`)
app 层数据库,聚合以下 DAO 与实体:
- `SessionEntity` / `MessageEntity` — 会话与消息(核心),会话按 `updatedAt` 降序,支持归档(`archivedSessions`)与文件夹分组(`FolderEntity`)。
- `AssistantEntity` — 助手配置(名称/头像/模型/systemPrompt/messageTemplate/采样参数/4 个记忆开关/标签等)。
- `LorebookEntity` — Lorebook 条目(关键词触发注入)。
- `QuickMessageEntity` — 快捷消息(global + 各 Assistant 绑定)。
- `PromptInjectionEntity` — Prompt 注入条目(按模式开关,`default` 表示无注入)。
- `SkillEntity` — Skill 定义(含 `implementationKotlin` 路由 key + 参数 JSON Schema)。
- `FolderEntity` — 文件夹(Drawer 按 folderId 分组渲染会话)。
- `ScheduledTaskEntity` / `ScheduledTaskExecutionEntity` — 定时任务与执行历史。
- `KnowledgeDocEntity` — 知识库文档(`knowledge_search` 检索源,含 `fileType="devdoc"` 的内置开发文档,UI 过滤不展示)。

`MuseDb.get(context)` 单例。各 Repository(`SessionRepository` / `AssistantRepository` / `LorebookRepository` / `QuickMessageRepository` / `PromptInjectionRepository` / `SkillRepository` / `FolderRepository`)封装 DAO 操作。`SessionRepository` 同时注入 `sessionDao` 与 `messageDao`。

FTS5 全文搜索:`MessageEntity` 配套 FTS5 虚拟表,支撑全局搜索页(`SEARCH` 路由)与会话内搜索(`searchQuery` / `searchResults` / `isSearching`)。

实体关系:`SessionEntity` 与 `MessageEntity` 一对多(按 sessionId 关联);`SessionEntity` 可选关联 `FolderEntity`(folderId,Drawer 分组);`AssistantEntity` 与 `QuickMessageEntity` / `LorebookEntity` / `PromptInjectionEntity` 通过 assistantId 绑定(global 标记的为全局共享);`KnowledgeDocEntity` 用稳定 id(`devdoc-<filename>`)保证 seed 幂等。`MessageEntity` 含角色(`MessageRole.User` / `Assistant` / `System` / `Tool`)、工具调用记录、引用内容等。

外部模块边界:`io.zer0.ai` 与 `io.zer0.memory` 为独立 Gradle 模块,app 仅通过接口注入(`ProviderConfigStore` / `MemoryLlmClient`),不直接依赖其内部实现。修改这两个模块的接口需同步检查 app 侧实现(`SettingsRepository` 实现 `ProviderConfigStore`、`MemoryLlmClientImpl` 实现 `MemoryLlmClient`)。

### DataStore (`SettingsRepository`)
基于 Jetpack DataStore (Preferences),store name = `muse_settings`,顶层扩展 `private val Context.settingsDataStore`。职责:
- Provider 列表 / 活跃 Provider / 模型 / API Key 持久化(`providersFlow` / `activeProviderIdFlow` / `providerConfigFlow`)。同时实现 `ProviderConfigStore` 接口供 ai 模块注入。
- Assistant 激活态 / 用户画像存取(具体实体走 Room)。
- 主题模式 / 字号 / `keepAwake` / `autoLaunch` 等外观与行为开关。
- 主动消息配置(开关 + 间隔 + 上次触发时间)。
- `WebServerConfig` / `WebSearchConfig` / `AsrConfig` / `MediaConfig` / `ProxyConfig` / `CloudBackupConfig`。
- 账户状态(登录态 / 游客模式)、首次引导是否已展示、MCP OAuth token 存储(按 serverId)。

缓存策略:hot path 字段用 `@Volatile` 内存缓存零阻塞读取(`memoryConfigCache` / `experimentsCache` / `multiAgentConfigCache`),后台协程(`cacheScope`)订阅对应 Flow 把最新值落入缓存。`memoryEnabledCache` 用 `AtomicBoolean` 缓存。注意 Flows 声明必须在 init 块之前(Kotlin 按声明顺序初始化,否则 init 协程异步访问会 NPE)。