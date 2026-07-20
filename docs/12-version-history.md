# 版本历史

### v1.19 (versionCode 119) — 2026-07-09

**设置体系完善 — 云备份 UI / WebServer 控制 / 字体大小预览(Phase 24)**:

1. `app/ui/settings/BackupSection.kt`:
   - 本地导出/导入(SAF)入口:导出备份(NDJSON 流式)+ 导入备份(自动识别单 JSON/NDJSON)
   - 云备份状态实时显示(未配置/检查中/云端已有备份/云端无备份)
   - 云存储类型选择(S3 兼容 / WebDAV / 未配置)
   - 自动同步开关、立即上传到云端、从云端恢复、上次同步时间只读展示
   - `CloudBackupConfigDialog`:S3/WebDAV 字段编辑,密钥可显隐切换
2. `app/ui/settings/WebServerSection.kt`:
   - 启用开关(控制 App 启动时是否自动启动 Ktor WebServer)
   - 端口配置(1024-65535,默认 8765)
   - 登录密码与 Web 访问 PIN 明文展示 + 一键复制
   - 重新生成密码 / 重新生成 PIN(重启 App 后生效)
   - 动态局域网 IP 获取,访问地址一键复制
   - 顶部安全提示:同一 Wi-Fi 下他人可通过 IP + PIN 访问
3. `app/ui/settings/ThemeSection.kt`:
   - 字号选择胶囊(小/中/大/特大)
   - `FontSizePreview`:实时预览当前字号下的标题/正文/小字效果
   - 主题模式胶囊(跟随系统/浅色/深色)+ 主题实时预览卡片
4. `app/build.gradle.kts`: versionName 1.18 → 1.19, versionCode 118 → 119
5. APK: `D:\1test\Muse_v1.19_debug.apk`,编译无错误

### v1.24 (versionCode 124) — 2026-07-10

**人机功效优化 + 设置重分类 + Vosk 离线语音识别 + 图片生成 Provider 化(Phase 32)**:

1. 消息正文折叠策略调整(`ui/MessageBubble.kt`):
   - 移除助手消息正文整体折叠逻辑,长文本直接完整展示
   - 保留 MOOD 块、思考过程(reasoning)、工具调用卡片、Markdown 代码块的折叠能力
   - 修复 `ShimmerPlaceholder` 缺少 `Brush` 导入导致的编译错误

2. 首页 Tab 栏与 Agent 菜单优化(`ui/HomeScreen.kt`、`ui/ChatScreen.kt`、`ui/InputBar.kt`、`ui/ChatViewModel.kt`):
   - 自定义紧凑顶部栏,任务/Agent 胶囊 Tab 紧贴下方内容区,移除额外间距
   - Agent Tab 右上角新增三点菜单,集中「切换模型」「更新并压缩」「重启上下文」入口
   - 普通会话(CHAT_DETAIL)保留原有 TopAppBar,不显示「重启上下文」
   - `ChatViewModel` 新增 `restartContext()`:保留当前助手,新建空会话,Toast 提示「上下文已重启」
   - `InputBar` 加号工具菜单仅在 Agent 模式显示「重启上下文」行

3. 任务列表空状态居中(`ui/ChatListScreen.kt`):
   - 「全部/置顶/归档」三个 Filter 空状态的图标、标题、副标题统一水平居中
   - 修复 `Modifier.padding(top = 4.dp, horizontal = 24.dp)` 参数组合不合法导致的编译错误

4. 设置页重分类与去英文(`ui/SettingsScreen.kt`、`ui/settings/AssistantSection.kt`):
   - 一级设置页拆分为 5 个分组:外观与个性化、记忆与数据、模型与服务、高级、关于
   - 用户高频开关(外观/聊天/媒体)置顶,技术性设置(代理/实验性/统计)置底
   - 「模型与服务」下新增独立入口:「供应商」跳转 Provider 管理,「助手」跳转助手管理
   - `AssistantSection` 标签由「Assistant 人格」改为「助手」,副标题改为「助手管理世界书」

5. Vosk 离线中文语音识别(`app/build.gradle.kts`、`ui/speech/VoskSpeechRecognizer.kt`、`ui/speech/VoskModelManager.kt`、`ui/ChatScreen.kt`、`ui/InputBar.kt`):
   - 新增依赖 `com.alphacephei:vosk-android:0.3.47`
   - 首次点击麦克风时从官方下载约 40MB 的 `vosk-model-small-cn-0.22` 到应用私有目录
   - 使用 `AudioRecord` 采集 16kHz 16bit 单声道 PCM,通过 Vosk `Recognizer` 离线解码
   - 录音时实时计算振幅,供 InputBar 波形显示;页面离开(onDispose)时释放资源
   - 保留原 `SpeechInput.kt` 作为系统 Intent fallback,不再主动调用

6. 图片生成 Provider 化(`data/SettingsRepository.kt`、`ui/settings/ImageGenSection.kt`、`ui/ChatViewModel.kt`、`ai/image/ImageModelCatalog.kt`、`ai/image/ImageService.kt`、`ui/InputBar.kt`):
   - `ImageGenConfig` 新增 `providerId` 字段,留空则回退到当前活跃 Provider
   - `ImageGenSection` 模型选择改为从已配置的 OPENAI / GEMINI 供应商中挑选
   - Gemini 模型使用 `supportsImageOutput()` 过滤,OpenAI 模型默认支持 Image API
   - `ChatViewModel.generateImage()` 优先使用 `ImageGenConfig.providerId` 指定的供应商与模型
   - `ImageModelCatalog` 新增 `resolveById()` 作为能力元数据兼容层,支持自定义模型

7. `app/build.gradle.kts`: versionName 1.23 → 1.24, versionCode 123 → 124
8. 编译验证: `BUILD SUCCESSFUL`
9. APK: `D:\1test\Muse_v1.24_debug.apk`(122.4 MB)

### v1.25 (versionCode 125) — 2026-07-10

**多 Agent 协作可视化与主动委托入口(Phase 33)**:

1. 任务卡 `delegate_agent` 可视化优化(`ui/taskcard/TaskCard.kt`):
   - `fromToolCalls()` 识别工具名 `delegate_agent`,解析 `assistantId` 与 `task`
   - 步骤详情默认显示任务摘要(前 60 字符)
   - `ChatViewModel` 在步骤启动前通过 `assistantRepository.getById()` 查询助手名,将步骤标题更新为「委托给 [助手名]」并显示进度文本

2. `SkillExecutor.execDelegateAgent()` 返回结果优化(`tools/SkillExecutor.kt`):
   - 子助手不存在时返回明确中文错误:「未找到 id 为 xxx 的子助手,请在助手管理中创建」
   - 成功时返回结果前缀改为「[助手名] 完成委托任务:」+ 子助手输出,便于主助手与用户识别
   - 支持 `response_format=json` 返回结构化 JSON(assistantId/assistantName/result/success)

3. 协作团队管理 UI(`ui/settings/MultiAgentSettingsPage.kt`、`ui/SettingsScreen.kt`、`ui/MuseRoutes.kt`、`MainActivity.kt`):
   - 新增「多 Agent 协作」二级设置页,包含总开关「启用多 Agent 协作」
   - 团队列表展示团队名、描述、成员头像(最多 3 个,+N 徽标)
   - 支持新建/编辑/删除团队;编辑页用 MuseDialog,含名称、描述、助手多选 chips
   - 删除团队时同步清理 `defaultTeamId`
   - 一级设置页「模型与服务」分组新增「多 Agent 协作」入口

4. 输入栏主动委托入口(`ui/InputBar.kt`):
   - 新增参数 `assistants` 与 `onDelegateToAssistant`
   - 加号工具菜单中当存在可用助手时显示「委托给助手」行(图标 `Icons.Outlined.GroupWork`、副标题「选择子助手协作处理当前消息」)

5. 聊天页委托交互(`ui/ChatScreen.kt`):
   - 新增 `DelegateSheetMode` 密封类,区分「当前输入委托」与「消息委托」两种模式
   - 底部 Sheet 列出所有可用助手与 `multiAgentConfig.teams` 中的协作团队
   - 选择助手:自动前置提示「请委托给 [助手名] 处理:」+ 原输入并触发发送
   - 选择团队:自动前置提示「请按 [团队名] 团队协作处理:」+ 原输入并触发发送
   - 消息模式下引用原消息摘要(200 字符)并追加委托提示,发送新消息
   - 使用 `io.mozi.muse.ui.chat.buildQuotedContent` 构造引用格式

6. 消息气泡操作菜单扩展(`ui/MessageBubble.kt`):
   - 新增 `onDelegate` 回调
   - 长按/单击菜单中新增「委托给助手」选项,触发与 InputBar 一致的底部选择 Sheet

7. 多 Agent 配置接入状态流(`ui/ChatViewModel.kt`、`data/SettingsRepository.kt`):
   - `ChatUiState` 暴露 `multiAgentConfig: MultiAgentConfig`
   - `ChatViewModel` 订阅 `settings.multiAgentConfigFlow`,实时更新团队列表
   - `SettingsRepository` 维护 `multiAgentConfigCache` 内存缓存,供 `SystemPromptAssembler` 零阻塞读取并注入 delegate_agent 工具提示

8. 风格与约束:
   - 所有新增 UI 文本为中文,未使用 emoji
   - 顺手清理 `TaskCard.kt` 步骤耗时文本中的 emoji(改为「耗时: ...」)

9. `app/build.gradle.kts`: versionName 1.24 → 1.25, versionCode 124 → 125
10. 编译验证: `BUILD SUCCESSFUL(:app:assembleDebug)`
11. APK: `D:\1test\Muse_v1.25_debug.apk`(~116.8 MB)

### v1.23 (versionCode 123) — 2026-07-10

**深度思考 `<think>` 提取修复**:

1. 流式响应 think 提取增强(`ui/ChatViewModel.kt`):
   - 新增 `extractThinkContent()` 辅助函数,从 assistant 的流式 content 中剥离 `<think>...</think>` 思考链
   - 支持完整标签提取,同时兼容流式过程中标签尚未闭合的片段,避免 raw tag 直接渲染在正文气泡
   - 忽略大小写,兼容 `<Think>` 等变体;提取内容合并到 `UIMessage.reasoning`
2. 历史消息 think 标签处理(`transformer/ThinkTagTransformer.kt`):
   - think 正则改为忽略大小写(`RegexOption.IGNORE_CASE`)
   - 快速路径判断改为 `contains("<think>", ignoreCase = true)`,覆盖更多模型输出
3. UI 显示开关确认(`data/SettingsRepository.kt`、`ui/MessageBubble.kt`):
   - `ChatPreferences.showReasoning` 默认 `true`,`showMoodBlock` 默认 `true`
   - `MessageBubble` 中 reasoning 块与 mood 块分别渲染,互不影响
4. `app/build.gradle.kts`: versionName 1.22 → 1.23, versionCode 122 → 123
5. 编译验证: `BUILD SUCCESSFUL`
6. APK: `D:\1test\Muse_v1.23_debug.apk`

### v1.22 (versionCode 122) — 2026-07-10

**模型列表服务端动态拉取(本地零维护)**:

1. 清空预设 Provider 本地模型列表(`data/preset/PresetProviders.kt`):
   - 23 个预设 Provider(海外官方 9 / 国产官方 8 / 中转站 6)的 `models` 全部改为 `emptyList()`
   - KDoc 更新为"models 默认空列表,首次使用时从上游 /models 接口动态拉取"
2. Provider 编辑页自动拉取(`ui/settings/ProviderSection.kt`):
   - 打开编辑页且 `config.models.isEmpty() && apiKey.isNotBlank()` 时自动调用 `fetchModels()`
   - `ModelsTab` 空状态时自动拉取,并提供"刷新上游模型"手动按钮
   - 拉取成功后自动同步到 `modelsText`,保存时写回 ProviderConfig
3. 聊天页/模型选择器自动拉取(`ui/ChatViewModel.kt`、`ui/ModelSwitchSheet.kt`、`ui/ChatScreen.kt`):
   - `isConfigured` 判断改为 `config != null`(不再要求本地有模型)
   - 激活 Provider 模型为空时自动触发 `refreshModels(providerId)`
   - 切换 Provider 时若模型为空自动拉取
   - `ModelSwitchSheet` 新增刷新按钮、加载态、错误提示
4. 上下文窗口兜底(`ai/core/ModelContextWindowRegistry.kt`):
   - OpenAI/Anthropic/Gemini 三个 Provider 的 `listModels()` 已使用 `ModelContextWindowRegistry.lookup()` 回填 `contextWindow`
   - 注册表支持剥前缀、精确、前缀、token 数推断、品牌默认五级匹配
   - 上游 /models 返回 contextWindow 时优先使用(当前 OpenAI 兼容接口普遍不返回,用注册表兜底)
5. OpenCode 前缀修复继续生效(`ai/openai/OpenAIProvider.kt`):
   - `effectiveModelId()` 在请求前按 `stripModelPrefix` 剥离 `opencode-go/` 前缀
6. `app/build.gradle.kts`: versionName 1.21 → 1.22, versionCode 121 → 122
7. 编译验证: `BUILD SUCCESSFUL`
8. APK: `D:\1test\Muse_v1.22_debug.apk`(84.8 MB)

### v1.21 (versionCode 121) — 2026-07-10

**OpenCode 模型 ID 前缀修复 + 任务首页图标居中(热修复)**:

1. OpenCode Go 模型请求修复(`ai/openai/OpenAIProvider.kt`):
   - 新增 `effectiveModelId(modelId)` 方法,按 `ProviderSpecificConfig.OpenAI.stripModelPrefix` 剥离模型 ID 前缀
   - 兼容旧配置兜底:模型 ID 若以 `opencode-go/` 开头,自动剥离后再发送 API 请求
   - 解决 "HTTP 401: Model opencode-go/glm-5.2 is not supported" 错误
2. Provider 配置扩展(`ai/core/ProviderSpecificConfig.kt`):
   - `ProviderSpecificConfig.OpenAI` 新增 `stripModelPrefix` 字段
3. OpenCode 预设更新(`data/preset/PresetProviders.kt`):
   - `opencode()` 的 `specific` 从 `Custom()` 改为 `OpenAI(stripModelPrefix = "opencode-go/")`
4. Provider 编辑页(`ui/settings/ProviderSection.kt`):
   - OpenAI 特定高级字段新增"模型 ID 前缀剥离"输入框,允许用户自定义/查看
5. 任务首页大卡片图标居中(`ui/ChatListScreen.kt`):
   - `BigEntryCard` 由左侧图标 + 右侧文字的 `Row` 改为图标置顶、标题/副标题全部居中的 `Column`
6. `app/build.gradle.kts`: versionName 1.20 → 1.21, versionCode 120 → 121
7. 编译验证: `BUILD SUCCESSFUL`
8. APK: `D:\1test\Muse_v1.21_debug.apk`(84.8 MB)

### v1.20 (versionCode 120) — 2026-07-09

**Provider/模型体验完善(Phase 25) + 会话与聊天交互完善(Phase 26) + Agent/助手/Skill 完善(Phase 27) + 系统性无障碍标注(Phase 28)**:

1. Provider 高级字段 UI(`app/ui/settings/ProviderSection.kt`):
   - 为 OpenAI specific 添加 `chatCompletionsPath`、`useResponseApi`、`includeHistoryReasoning`、`embeddingsPath`、`imagesPath` 编辑字段
   - 为 Anthropic specific 添加 `promptCaching`、`promptCacheTtl`、`messagesPath`、`modelsPath` 编辑字段
   - 为 Custom specific 添加 `chatCompletionsPath`、`customHeaders`(Key: Value 多行文本)、`customBody`(JSON 多行文本)编辑字段
   - 在 `buildTempConfig()` 与 `save` 中按 `ProviderType` 正确构造并写回 `ProviderSpecificConfig`
   - 新增 `formatCustomHeaders` / `parseCustomHeaders` / `formatCustomBody` / `parseCustomBody` 辅助函数

2. 会话与聊天交互完善(`app/ui/ChatListScreen.kt`、`app/ui/MessageBubble.kt`、`app/ui/ChatScreen.kt`):
   - `ChatListItem` 长按菜单统一为 iOS 风格 ActionSheet 行项(图标 + 文字):置顶/取消置顶、移动文件夹、重命名、归档/取消归档、删除
   - `MessageBubble` 操作菜单所有图标补充中文 `contentDescription`:编辑、引用、重新生成、翻译、朗读、收藏、复制、分享对话
   - `EmptyChatGuide` 头像容器添加语义描述("助手头像"/"Muse 图标")

3. 助手扩展页多选 chips(`app/ui/AssistantDetailPages.kt`):
   - `AssistantExtensionsPage` 从仅显示数量改为 6 个可点击行项:快捷消息、Lorebook、Prompt 注入、Skill、MCP Server、Tool
   - 点击弹出 `MultiSelectChipsDialog`,使用 `FlowRow` 自动换行,`FilterChip` 展示选项,选中即时保存到 `AssistantEntity` 对应 JSON 字段
   - 数据源:QuickMessageRepository、LorebookRepository、PromptInjectionRepository、SkillRepository、SettingsRepository.mcpServersFlow、ToolRegistry

4. 系统性无障碍标注(11 个文件):
   - `app/ui/ChatScreen.kt` — 顶部栏会话标题/模型选择器/上下文圆环/错误提示/TTS 控制器
   - `app/ui/speech/TtsControllerWidget.kt` — 播放/暂停/停止/速度/快进/展开收起按钮
   - `app/ui/SettingsScreen.kt` — 设置一级页分组标题与入口卡片
   - `app/ui/settings/SettingsSubPages.kt` — 二级页返回按钮与通用容器
   - `app/ui/settings/ProviderSection.kt` — Provider 列表/编辑/开关状态
   - `app/ui/settings/BackupSection.kt` — 备份错误提示图标
   - `app/ui/settings/WebServerSection.kt` — WebServer 安全提示图标
   - `app/ui/settings/ThemeSection.kt` — 主题模式选项选中状态
   - `app/ui/HomeScreen.kt` — 顶部栏按钮与 Tab 语义
   - `app/ui/AssistantScreen.kt` — 克隆/删除图标
   - `app/ui/SkillScreen.kt` — Skill 行
   - 补充 `Icon`/`IconButton` 的 `contentDescription`、`Switch` 的 `stateDescription`、状态类 `semantics`
   - 修复 `SettingsScreen.kt` semantics lambda 中调用 `stringResource` 的问题
   - 修复 `TtsControllerWidget.kt` 参数名与 semantics 属性同名冲突

5. `app/build.gradle.kts`: versionName 1.19 → 1.20, versionCode 119 → 120
6. 编译验证: `BUILD SUCCESSFUL`
7. APK: `D:\1test\Muse_v1.20_debug.apk`(84.8 MB)

### v1.18 (versionCode 118) — 2026-07-09

**系统 SplashScreen + 首次引导 Onboarding + 登录注册入口完善(Phase 23)**:

1. `app/build.gradle.kts`:
   - 新增依赖 `androidx.core:core-splashscreen:1.0.1`
   - versionName 1.17 → 1.18, versionCode 117 → 118
2. `AndroidManifest.xml`: MainActivity theme 改为 `@style/Theme.Muse.Starting`
3. 新增 `app/src/main/res/values/themes.xml` 与 `values-v31/themes.xml`:
   - `Theme.Muse.Starting` 继承 `Theme.SplashScreen`
   - 背景色 `warm-paper #FAFAF8`,中心图标为 `ic_muse_logo`
4. `app/src/main/res/values/colors.xml`: 新增 `splash_background`
5. `MainActivity.kt`:
   - `onCreate` 中调用 `installSplashScreen()` 并设置 `setKeepOnScreenCondition`
   - NavHost 初始化 1.2s 后触发 `onSplashReady()` 退出启动屏
   - 移除原有自定义 Compose SplashScreen 覆盖层
6. 新增 `app/ui/onboarding/OnboardingScreen.kt`:
   - 全屏三页 HorizontalPager 引导:智能对话 / 个性化助手 / 隐私优先
   - 底部主操作"添加第一个 Provider"(跳转 SETTINGS_MODEL)
   - 次要操作"离线体验"(进入游客模式并标记引导已看)
7. `MuseRoutes.kt`: 新增 `ONBOARDING` 路由
8. `MainActivity.kt` 导航逻辑:
   - startDestination:未登录 → AUTH;已登录且无 Provider 且未看过引导 → ONBOARDING;否则 → HOME
   - 游客/离线体验后自动标记 `onboardingShown = true`,避免重复弹引导
9. `SettingsRepository.kt`:
   - 复用已有的 `onboardingShownFlow` / `markOnboardingShown()`
   - 顺手修正历史遗留的 `init() {` 语法为 `init {`
10. `SettingsSubPages.kt`(关于页):
    - 新增"重新显示引导"入口,可从关于页再次打开 Onboarding
11. APK: `D:\1test\Muse_v1.18_debug.apk`(84.7 MB),编译无错误

### v1.17 (versionCode 117) — 2026-07-09

**上下文窗口修正 + 加号菜单相册 + 聊天顶部栏优化(Phase 22)**:

1. `ai/core/ModelContextWindowRegistry.kt`:
   - 修正 DeepSeek V4 系列上下文窗口:`deepseek-v4-pro` / `deepseek-v4-flash` 128K → 1M
   - 修正 MiniMax M3 上下文窗口:`minimax-m3` 128K → 1M
2. `app/ui/InputBar.kt`:
   - 加号工具菜单中摄像头与照片位置互换(摄像头在前,照片在后)
   - 右侧新增最近相册图片横向滚动列表,点击直接加入待发送
   - 未授权时显示"授权访问相册"入口,点击请求 READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE
   - 新增 `onPickGalleryImage(Uri)` 回调与 `queryRecentGalleryImages()` 工具函数
3. `app/ui/ChatScreen.kt`:
   - 顶部栏移除设置按钮,释放空间避免模型选择器与返回按钮堆叠
   - 模型名截断显示:带 provider 前缀时仅显示最后一段(如 `opencode-go/deepseek-v4-flash` → `deepseek-v4-flash`)
   - 模型选择器最大宽度限制为 180dp,防止超长模型名挤压布局
4. `app/ui/HomeScreen.kt` / `MainActivity.kt`:同步移除 ChatScreen 的 `onOpenSettings` 参数
5. `app/build.gradle.kts`: versionName 1.16 → 1.17, versionCode 116 → 117
6. APK: `D:\1test\Muse_v1.17_debug.apk`(84.7 MB),编译无错误

### v1.16 (versionCode 116) — 2026-07-09

**输入栏加号工具菜单 iOS/Manus 风格化(Phase 21)**:

1. `app/ui/InputBar.kt`:
   - 重写加号工具菜单为 iOS/Manus 风格底部 Sheet
   - 移除原生 `Checkbox` 和 `OutlinedTextField` 样式
   - 新增顶部 iOS 把手(36x4dp 圆角横条)
   - 标题区采用 Manus 风格:左侧大标题"工具" + 右侧状态说明"选择要发送的内容"
   - 媒体入口改为横向滚动的圆角卡片(`ToolMediaCard`):图片、摄像头
   - 功能列表改为行点击交互(`ToolListRow`):附件 / 引用知识库 / 绘图模式
   - 行组件统一为左图标 + 标题/副标题 + 右箭头,激活状态显示主色
   - 修复图标 deprecation 警告:`Icons.Default.MenuBook` → `Icons.AutoMirrored.Filled.MenuBook`
   - 重新补充 `Language` 与 `Close` 图标导入,修复编译错误
2. `app/build.gradle.kts`: versionName 1.15 → 1.16, versionCode 115 → 116
3. APK: `D:\1test\Muse_v1.16_debug.apk`(84.7 MB),编译无错误

### v1.15 (versionCode 115) — 2026-07-09

**模型选择器 iOS 化与上下文自动推断(Phase 20)**:

1. `ai/core/ModelContextWindowRegistry.kt`:
   - 新增常见 provider 前缀剥离(opencode-go/、openrouter/、anthropic/、google/、
     meta-llama/、mistralai/、accounts/fireworks/models/ 等),中转平台模型也能命中注册表
   - 补充 OpenCode Go 模型(GLM-5、Kimi K2.7/K2.6、DeepSeek V4 Pro/Flash、MiMo、MiniMax)
     及 qwen3.7/qwen3.6 的上下文窗口数据
2. `app/ui/settings/ProviderSection.kt`:
   - 重写 `FetchedModelsPickerSheet`:去掉 Android 原生 `Checkbox` 与 `OutlinedTextField`
   - 上下文(K)不再手动输入,统一用 `ModelContextWindowRegistry` 自动识别,未命中时显示"未知"
   - iOS 风格底部 Sheet:顶部把手、圆角搜索栏、行点击对勾圆圈、右侧 context 标签、
     底部黑白胶囊按钮
3. `app/build.gradle.kts`: versionName 1.14 → 1.15, versionCode 114 → 115
4. APK: `D:\1test\Muse_v1.15_debug.apk`(84.7 MB),编译无错误

### v1.14 (versionCode 114) — 2026-07-09

**供应商 URL 校准与 OpenCode Go 修正(Phase 19)**:

1. 全面核查 17 个官方厂商 + 5 个中转站预设 baseUrl:
   - 海外 9 家:OpenAI/Anthropic/Gemini baseUrl 留空走默认,其余 Groq/Together/Mistral/OpenRouter/DeepInfra/Fireworks URL 确认正确
   - 国产 8 家:DeepSeek/Qwen/Zhipu/Moonshot/Doubao/Baichuan/Lingyi/StepFun URL 经搜索验证,均正确
   - 中转 5 家:OpenCode 修正,API2D/AiHubMix URL 确认正确,DeepBricks/OneAPI/NewAPI 维持原配置(OneAPI/NewAPI 为自建模板,baseUrl 留空)
2. `app/data/preset/PresetProviders.kt`:
   - OpenCode 名称改为 "OpenCode Go"
   - baseUrl 从错误 `https://api.opencode.com/v1` 修正为 `https://opencode.ai/zen/go/v1`
   - 模型列表替换为官方 OpenAI 兼容模型(GLM-5.2/5.1、Kimi K2.7 Code/K2.6、DeepSeek V4 Pro/Flash、MiMo-V2.5/Pro),ID 带 `opencode-go/` 前缀
   - 注释说明 Anthropic 兼容的 MiniMax/Qwen 系列暂未纳入预设
3. `app/build.gradle.kts`: versionName 1.13 → 1.14, versionCode 113 → 114
4. APK: `D:\1test\Muse_v1.14_debug.apk`(84.6 MB),编译无错误

### v1.13 (versionCode 113) — 2026-07-09

**前端性能优化(Phase 18)**:

1. `app/ui/ChatListScreen.kt`:
   - 用 `remember(filter, sessions, archivedSessions)` 缓存过滤+排序结果,避免每次重组重新计算
   - 对 `ChatListItem` 的 6 个回调用 `remember(session.id)` 缓存,避免父重组导致列表项失效
2. `app/ui/ChatScreen.kt`:
   - 把 `modelName` 查找提到 ChatScreen 作用域,用 `remember` 缓存,避免每个消息项重复遍历 Provider/Model 列表
   - 对 `MessageBubble` 的 7 个消息级回调用 `remember(msg.id)` 缓存,减少流式输出时的整泡重组
3. `app/ui/common/AssistantAvatar.kt`:
   - 用 `remember(assistant.avatarImageUrl)` 缓存 Coil `ImageRequest`,避免头像重组时重建请求对象
4. `app/MuseApp.kt`:
   - Coil `ImageLoader` 新增 `MemoryCache`(上限可用内存 25%) + `DiskCache`(256 MB),减少重复下载与大图OOM风险
5. `app/build.gradle.kts`: versionName 1.12 → 1.13, versionCode 112 → 113
6. APK: `D:\1test\Muse_v1.13_debug.apk`(84.6 MB),编译无错误

### v1.12 (versionCode 112) — 2026-07-09

**图生图与绘图模型 Catalog(Phase 17)**:

1. `ai/image/ImageService.kt` — 支持 `referenceImageUri`,自动切换 `/images/generations`(JSON) 与 `/images/edits`(multipart);新增 `validateParams()` 按模型能力校验参数
2. `ai/image/ImageModelCatalog.kt`(新增) — 预设 dall-e-2 / dall-e-3 / gpt-image-1 / 通义万相 / MiniMax 绘图模型元数据
3. `app/ui/InputBar.kt` — ImageGenParamsPanel 支持选择本地参考图(转 data URI),按模型能力动态过滤尺寸/质量/风格/数量/参考图
4. `app/ui/settings/ImageGenSection.kt` — 新增模型选择卡片与两列网格对话框,选择模型后自动重置默认值并过滤参数;清理 `!!` 非空断言
5. `app/ui/ChatViewModel.kt` — 绘图发送成功/退出绘图模式时清空参考图
6. APK: `D:\1test\Muse_v1.12_debug.apk`(86.2 MB),编译无错误无警告

### v1.11 (versionCode 111) — 2026-07-09

**图片生成参数化(Phase 16)**:

1. `app/data/SettingsRepository.kt` — 新增 `ImageGenConfig` 与 `imageGenConfigFlow`,持久化默认绘图参数
2. `ai/image/ImageService.kt` — `generate()` 改为接收 `ImageGenParams`(model/size/quality/style/response_format/n),支持 `b64_json`
3. `app/ui/settings/ImageGenSection.kt`(新增) — 设置页"图片生成"分组
4. `app/ui/InputBar.kt` — 绘图模式下显示参数面板,可临时覆盖默认值
5. `app/ui/ChatViewModel.kt` — init 中订阅 `imageGenConfigFlow` 并同步到 UI State
6. APK: `D:\1test\Muse_v1.11_debug.apk`

### v1.10 (versionCode 110) — 2026-07-09

**安全性升级 + Web 聊天 + 架构优化**:

1. `compileSdk/targetSdk 35 → 36`: 全模块统一升级(Android 16),配合已有的 execSQL 兼容防御(compileStatement 替代 DML)
2. `WebServer POST /api/send 端点`: 注入 ChatService/SystemPromptAssembler/AssistantRepository,支持 SSE 流式 + JSON 非流式双模式,局域网 Web 端可发起新消息
3. `云备份 AES-256-GCM 加密`: BackupCrypto 完整实现(PBKDF2 密钥派生 + MUSE magic 头检测),exportToCloud/importFromCloud 自动加密解密
4. `CloudBackupConfig + BackupSection`: 云配置对话框加加密密码字段,本地导出/导入自动检测加密格式
5. `SystemPromptAssembler 提示词外部化`: 硬编码英文提示词 → PromptTemplates.kt 独立管理(决策树/MOOD/多 Agent/自我反思)
6. `ChatViewModel 拆分`: launchStream 650 行流式编排逻辑 → ChatOrchestrator(事件流驱动),ChatViewModel 只保留 UI 状态映射。新增 `app/chat/ChatOrchestrator.kt`

### v1.9 (versionCode 109) — 2026-07-09

**全应用去原生安卓风格 — 全面 iOS 风格化**:

1. 新建 `MuseToast.kt` + `MuseToastHost.kt` 替代原生 Toast
2. AlertDialog → MuseDialog(16 处)
3. Toast → MuseToast(62 处)
4. MessageBubble 长按菜单改 iOS ActionSheet(ModalBottomSheet)
5. 颜色和 import 清理

### v1.8 (versionCode 108) — 2026-07-09

**上下文 token 圈修复 + 供应商功能完善**:

1. ModelContextWindowRegistry 四级智能匹配(精确/前缀/token 推断/品牌默认)
2. 供应商 UI 增强:FetchedModelsPickerSheet 显示 context window,可手动编辑
3. 搜索栏/确认按钮改为胶囊形

### v1.7 (versionCode 107) — 2026-07-09

**工具发现机制 + 能力扩展 + 记忆增强**:

1. SystemPromptAssembler 工具清单分类升级 + DECISION_TREE_SECTION 扩充
2. 新增 10 个手机端/系统工具(open_system_setting/toggle_wifi/toggle_bluetooth/send_email/get_battery_info/get_recent_notifications/file_download/read_public_file/save_to_downloads/list_public_files)
3. 全局 temperature、语气风格配置
4. MuseNotificationListenerService 通知监听
5. 记忆 UI 增强:Summary/Compile 删除、Fact 编辑

### v1.6 (versionCode 106) — 2026-07-09

**人机交互全面完善**:

1. 导航与路由修复(Deep Link 返回栈、子页动画、Home BackHandler、PIN rememberSaveable)
2. 手势交互(滑动删除、MessageBubble 仅长按菜单、内容尺寸动画)
3. 触觉反馈全面接入
4. 动画与过渡(animateItem、Crossfade、AnimatedVisibility)
5. 统一 EmptyState / ErrorStateBox
6. 输入体验(AuthPage 实时验证、自动聚焦、Provider API Key 显隐)
7. a11y 修复(48dp 触摸目标、contentDescription)
8. 视觉一致性令牌补全

### v1.5 (versionCode 105) — 2026-07-07

**新增功能**:

1. 供应商自动获取上游模型列表:Provider 接口加 `listModels`,OpenAI/Claude/Gemini 三家实现
2. 上下文 token 圈修复:ModelContextWindowRegistry
3. 供应商选择界面 iOS 风格重写
4. TTS 系统化改造:TextChunker + TtsManager + TtsControllerWidget
5. 登录注册界面:AuthPage(iOS ChatGPT 风格)

### v1.4 (versionCode 104) — 2026-07-07

**新增功能**:

1. 消息快捷复制按钮
2. SkillExecutor 10 个工具参数补全
3. SkillExecutor 6 个新工具(list_dir/delete_file/file_exists/list_skills/uninstall_skill/disable_skill)
4. ToolRegistry 8 个工具参数补全
5. ToolRegistry 3 个新工具(get_contacts_list/send_sms/add_contact)

### v1.3 (versionCode 103) — 2026-07-07

**修复 Bug**:

1. PresetProviderPickerDialog 崩溃修复
2. InputBar 发送按钮位置调整
3. 语音识别崩溃修复

### v1.2 (versionCode 102) — 2026-07-07

**新增功能**:

1. 供应商选择器 UI 精美化

### v1.1 (versionCode 101) — 2026-07-07

**新增功能**:

1. ProviderConfig 加 category 字段
2. 新增 6 个中转站预设
3. 供应商列表 UI 增强

### v1.0 (versionCode 100) — 2026-07-07

**正式版准备**:

1. NOTICE 清理
2. NetworkRetry / GlobalCoroutineExceptionHandler / WebView onRelease
3. 修复 9 个严重 Bug(execSQL/folderId/默认值/WebView 等)

### v0.22-v0.52 (versionCode 22-92) — 2026-07-07

**主要功能**:

- Manus 风格顶部 Tab 导航
- 卡片分组列表 + 长按操作菜单
- MaterialExpressiveTheme + Haze 毛玻璃
- Splash 屏幕 + PIN 锁
- 桌面小部件(Glance Compose)
- 定时任务 + 知识库 + Skill 系统
- 17 个预设供应商
- 多主题 + 字体大小
- 备份/恢复
- WebServer + MCP
- 主动消息 + 深度思考 + @mention 高亮

---