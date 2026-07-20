# UI 导航结构

`MuseRoutes.kt` 定义全部路由常量,`MainActivity` 的 NavHost 据此组装。`HOME` 为起始页,`CHAT_DETAIL` 等详情页隐藏 BottomBar。主路由:
- `ONBOARDING` — 首次引导页(无 Provider 时全屏显示)。
- `HOME` — 首页,顶部双 Tab:任务 / Agent(`isAgentMode` 决定 `send` 用 `agentSessionId` 还是 `currentSessionId`)。
- `CHAT_DETAIL` — 聊天详情页(从对话列表进入,无 BottomBar)。
- `AUTH` — 登录/注册页(本地桩 + 离线体验)。
- `SEARCH` — 独立全局搜索页(从首页右上角搜索按钮进入)。
- `USER_PROFILE_EDIT` — 用户画像编辑(年龄/城市/MBTI 等,用于 AI 个性化)。

Assistant 聚合页(`ASSISTANT_DETAIL` 头部 + 5 个子页入口):
- `ASSISTANT_BASIC` — 名称/头像/模型/采样参数。
- `ASSISTANT_PROMPT` — systemPrompt / 模板 / 预设消息。
- `ASSISTANT_EXTENSIONS` — 关联资源数量。
- `ASSISTANT_MEMORY` — 4 个记忆开关。
- `ASSISTANT_ADVANCED` — 背景 / 自定义请求 / 标签。

设置二级页(从 `SETTINGS` Tab 进入):
- `SETTINGS_MODEL` — 模型与服务(Provider 管理、Web 搜索配置)。
- `SETTINGS_CHAT` — 聊天行为。
- `SETTINGS_MEMORY` — 记忆与通知。
- `SETTINGS_MEDIA` — 媒体(录音/TTS 采样率/语速/音高/语言/输出方式)。
- `SETTINGS_APPEARANCE` — 外观(主题/字号)。
- `SETTINGS_DATA` — 数据与备份。
- `SETTINGS_PROXY` — 网络代理。
- `SETTINGS_SECURITY` — 安全与分享。
- `SETTINGS_EXPERIMENTS` — 实验性功能(`forceMoodBlock` / `selfReflection` / `longMemoryCompression` 等开关)。
- `SETTINGS_MULTI_AGENT` — 多 Agent 协作。
- `SETTINGS_AGENT` — Agent 配置(助手选择/协作/主动消息)。
- `SETTINGS_ABOUT` — 关于。

设置入口子页:`FAVORITES` / `LOREBOOKS` / `QUICK_MESSAGES` / `PROMPT_INJECTIONS` / `SKILLS` / `LICENSES` / `ACCOUNT` / `SCHEDULED_TASKS` / `KNOWLEDGE` / `STATS`(从设置 → 通用偏好 → 统计进入,展示热力图与使用统计)。

导航约定:路由常量集中在 `MuseRoutes` object 便于检索;新增页面时在此添加常量并在 `MainActivity` 的 NavHost 注册对应 Composable。全屏页(Provider 编辑、Assistant 子页等)用全屏 Page 而非 Dialog/BottomSheet,规避 `ModalBottomSheet` 卡死问题。