# 架构总览

### 4.1 分层架构

```
┌─────────────────────────────────────────────────┐
│  UI 层 (app/ui/)                                │
│  Composable + ViewModel + StateFlow             │
├─────────────────────────────────────────────────┤
│  业务层                                          │
│  ┌──────────┬──────────┬──────────┬─────────┐  │
│  │ ChatVM   │ Memory   │ Schedule │ Backup  │  │
│  │ ChatOrchestrator │ Ticker │ Runner │ Service│  │
│  └──────────┴──────────┴──────────┴─────────┘  │
├─────────────────────────────────────────────────┤
│  数据层                                          │
│  ┌──────────┬──────────┬──────────┬─────────┐  │
│  │ MuseDb   │ MemoryDb │ FactDb   │ DataSt. │  │
│  │ (Room)   │ (Room)   │ (Room)   │         │  │
│  └──────────┴──────────┴──────────┴─────────┘  │
├─────────────────────────────────────────────────┤
│  AI 层 (ai/)                                     │
│  Provider 抽象 + OpenAI/Anthropic/Gemini 实现    │
│  ImageService(OpenAI 兼容图片生成)               │
├─────────────────────────────────────────────────┤
│  工具层                                          │
│  SkillExecutor (数据库驱动) + ToolRegistry (内存)│
└─────────────────────────────────────────────────┘
```

### 4.2 关键文件位置

#### UI 层
- `app/ui/ChatScreen.kt` — 聊天主页
- `app/ui/ChatViewModel.kt` — 聊天业务核心(UI 状态映射)
- `app/ui/chat/ChatOrchestrator.kt` — 流式编排逻辑(从 ChatViewModel 拆分)
- `app/ui/InputBar.kt` — 输入栏(左 + 右发送/麦克风)
- `app/ui/MessageBubble.kt` — 消息气泡(含工具卡片、TTS、快捷复制)
- `app/ui/HomeScreen.kt` — 首页(会话/Agent/归档/统计磁贴)
- `app/ui/ChatListScreen.kt` — 会话列表(长按菜单:置顶/归档/移动/重命名/删除)
- `app/ui/SearchScreen.kt` — 全局搜索(会话/消息/设置三段)
- `app/ui/AssistantScreen.kt` — 助手列表
- `app/ui/AssistantDetailPages.kt` — 助手详情聚合 + 5 子页(Basic/Prompt/Extensions/Memory/Advanced)

#### 设置体系
- `app/ui/SettingsScreen.kt` — 设置一级页(账户卡片 + 4 分组入口)
- `app/ui/settings/SettingsSubPages.kt` — 二级设置页容器与 4 个分组入口
- `app/ui/settings/ProviderSection.kt` — 供应商列表 + 编辑(含拉取上游模型)
- `app/ui/settings/PresetProviderPickerDialog.kt` — 预设供应商选择器(warm-paper 风格)
- `app/ui/settings/ChatSettingsPage.kt` — 聊天设置(主动消息/深度思考/发送间隔)
- `app/ui/settings/ImageGenSection.kt` — 图片生成默认参数与模型选择
- `app/ui/settings/BackupSection.kt` — 本地/云备份配置
- `app/ui/settings/McpSection.kt` — MCP 服务器管理

#### AI 层
- `ai/core/Provider.kt` — Provider 接口(streamChat/completeText/listModels)
- `ai/core/ProviderConfig.kt` — 供应商配置(含 category: OFFICIAL/RELAY/CUSTOM)
- `ai/core/Model.kt` — 模型定义(含 contextWindow)
- `ai/openai/OpenAIProvider.kt` — OpenAI 兼容实现
- `ai/anthropic/AnthropicProvider.kt` — Claude 实现
- `ai/gemini/GeminiProvider.kt` — Gemini 实现
- `ai/image/ImageService.kt` — 图片生成服务(文生图 + 图生图)
- `ai/image/ImageModelCatalog.kt` — 预设绘图模型元数据

#### 工具层
- `app/tools/SkillExecutor.kt` — 内置 Skill(read/write/list_dir/web_search/web_fetch/arxiv/knowledge/delegate_agent/install_skill/list_skills/uninstall_skill/disable_skill/http_get/http_post/file_download/read_public_file/save_to_downloads/list_public_files)
- `app/tools/ToolRegistry.kt` — 23 个内置工具(get_current_time/calculator/echo/clipboard/screen_time/calendar/set_alarm/set_timer/open_app/share_text/get_location/get_device_info/get_contacts_count/get_contacts_list/send_sms/add_contact/open_system_setting/toggle_wifi/toggle_bluetooth/send_email/get_battery_info/get_recent_notifications)
- `app/data/skill/SkillEntity.kt` — Skill 数据库实体(含 enabled 字段)

#### 记忆系统
- `memory/ticker/MemoryTicker.kt` — 记忆编译调度器
- `memory/fact/FactStore.kt` — Fact 存储
- `memory/summary/` — 每日摘要
- `app/transformer/SystemPromptAssembler.kt` — 系统提示组装(含决策树)

#### 数据层
- `app/data/session/MuseDb.kt` — 主数据库(含多版迁移)
- `app/data/session/SessionEntity.kt` — 会话实体(含 archived/pinned/folderId 字段)
- `app/data/SettingsRepository.kt` — DataStore 设置仓库(25+ Flow)

### 4.3 路由体系

`app/ui/MuseRoutes.kt` 定义所有路由:
- `AUTH` — 登录/注册页(未登录时显示)
- `HOME` — 首页(会话/Agent Tab)
- `CHAT_DETAIL` — 聊天详情(右滑入)
- `SETTINGS` — 设置
- `ASSISTANTS` — 助手管理
- `SEARCH` — 全局搜索
- `STATS` — 统计热力图
- 二级设置: `SETTINGS_MODEL` / `SETTINGS_DATA` / `SETTINGS_APPEARANCE` / `SETTINGS_ABOUT`

导航在 `MainActivity.kt` 的 `MuseNavGraph` 中注册,用 `key(accountState.isAuthed)` 包裹 NavHost 实现登录态切换。

---