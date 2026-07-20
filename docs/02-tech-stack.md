# 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin | 主语言,全项目 Kotlin 实现 |
| Jetpack Compose | 全量声明式 UI,单 Activity 架构 |
| Room | 本地数据库(会话、消息、助手、Lorebook、Skill、知识库等),含 FTS5 全文搜索 |
| DataStore (Preferences) | 全局配置持久化(`SettingsRepository`) |
| Koin | 依赖注入,无编译期开销,模块化装配 |
| OkHttp | 网络层,SSE 流式与图片生成共用 `named("chat")` client,Web 搜索用独立 `named("webSearch")` client |
| Coil | 图片加载,注册 `SvgDecoder` + `GifDecoder`,内存缓存限可用内存 25%,磁盘 256MB |
| kotlinx.serialization | JSON 序列化(配置、Skill、实体等) |
| ML Kit | 离线 OCR(中英文识别),0 服务器依赖 |
| Ktor | 嵌入式 Web 服务器(CIO + JWT + mDNS),局域网 API |
| Android 系统 TextToSpeech | TTS 播报,0 APK 体积 |
| NSD (mDNS) | 局域网服务发现与注册 |
| AlarmManager / 协程轮询 | 定时任务与主动消息(用协程轮询规避 Android 后台限制) |

外部模块(不在本次维护范围,但被 app 依赖):
- `io.zer0.ai`(`aiModule`):提供 `ChatService` / `ImageService` / `ProviderConfigStore` 接口 / `ProviderConfig` / `Model` / `ModelContextWindowRegistry` / `ProviderRegistry` / `ReasoningLevel` 等核心 AI 抽象。
- `io.zer0.common`:`Logger` / `AppJson` / `AppDispatchers` 等基础工具。
- `io.zer0.memory`(`memoryModule`):记忆编译、摘要、`MemoryTicker`、`MemoryLlmClient` 接口、`MemoryConfig` 等长期记忆能力。