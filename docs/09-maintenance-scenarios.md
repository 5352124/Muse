# 维护指南

### 9.1 新增一个预置供应商

编辑 `data/preset/PresetProviders.kt`:

```kotlin
private fun xxx() = ProviderConfig(
    id = "xxx",
    name = "XXX",
    type = ProviderType.OpenAICompatible,
    baseUrl = "https://api.xxx.com/v1",
    builtIn = true,
    apiKey = "",
    models = emptyList(),
    category = ProviderCategory.Official,
)
```

1. 导入 `ProviderConfig` / `ProviderType` / `ProviderCategory` / `Model` / `ModelAbility`(若预置模型)。
2. 按 category 加入对应列表:`overseas`(海外官方)/ `domestic`(国产官方)/ `relay`(中转站)。
3. `all` 由 `overseas + domestic + relay` 自动聚合,无需手动改;`byId(id)` 自动支持查询。
4. `models` 留空列表,首次使用时从 `/models` 接口动态拉取。`apiKey` 留空由用户填入。

### 9.2 新增一个 Skill 实现

编辑 `tools/SkillExecutor.kt`:
1. 在 `execute` 的 `when (skill.implementationKotlin)` 分支新增路由,如 `"my_skill" -> execMySkill(args)`。耗时操作前调用 `onProgress("正在...")`。
2. 实现 `private suspend fun execMySkill(args: JsonObject): String`,所有 IO 在 `withContext(Dispatchers.IO)` 内执行。用 `parseArgs(argumentsJson)` 解析参数。
3. 在 `BUILT_IN_SKILLS` 列表新增对应 `SkillEntity`(含 name / description / parameters JSON Schema / `implementationKotlin` 路由 key),启动时 `MuseApp` 会幂等 upsert(REPLACE 策略)。
4. 若需要外部依赖(如新的 DAO / service),在 `SkillExecutor` 构造函数加参数并在 `AppKoinModule` 的 `single { SkillExecutor(...) }` 处补上注入。

注意:Skill 系统不允许任意代码执行,只路由到预定义 Kotlin 函数。文件操作严格限定在应用沙盒(`filesDir` / `cacheDir`)。

### 9.3 新增一个设置项

1. `SettingsRepository` 加 `Flow`(`store.data.map { prefs -> prefs[KEY_XXX] ?: default }`)+ `suspend fun saveXxx(value)` 方法(`store.edit { it[KEY_XXX] = value }`),并定义 `private val KEY_XXX = stringPreferencesKey("xxx")`(或 `booleanPreferencesKey` / `longPreferencesKey`)。
2. 若是 hot path 配置(被 ticker / assembler 高频读),加 `@Volatile var xxxCache` 字段 + 后台协程订阅 Flow 落缓存,供闭包零阻塞读取(参照 `memoryConfigCache` 写法)。
3. 在对应设置 Section 的 Composable(如 `settings/` 下对应页面)加 UI 控件,调用 `saveXxx`。
4. 业务侧通过 `xxxFlow.collect` 或 `xxxCache` 读取生效。

### 9.4 修改聊天 Transformer 顺序

编辑 `ui/ChatViewModel.kt` 的 `transformerPipeline` Builder 链:

```kotlin
private val transformerPipeline: TransformerPipeline = TransformerPipeline.Builder()
    .add(MemoryInjectionTransformer(memoryTicker))
    .add(TimeReminderTransformer())
    .add(LorebookTransformer(lorebookRepository))
    .add(PromptInjectionTransformer(promptInjectionRepository))
    .add(TemplateTransformer())
    .add(ThinkTagTransformer())
    .build()
```

调整 `.add()` 顺序即可。注意:`ContextCompressTransformer` 独立持有为字段(`contextCompressTransformer`),供 `manualCompress` 调用,不在常规链中。新增 Transformer 需实现 `Transformer` 接口并在 `transformer/` 包新增类。`TransformContext.extras` 用于在 Transformer 间传递 Assistant 配置等数据。`SystemPromptAssembler` 的 9 个 section 拼装独立于 transformerPipeline,修改系统提示需改 `SystemPromptAssembler`。

### 9.5 发布新版本

详见第十节。

### 9.6 调试与日志排查

`Logger` 来自 `io.zer0.common`,`MuseApp.onCreate` 调用 `Logger.initFileLog(this)` 把日志写入 `cacheDir`(卸载自动清理),真机验证后可回捞。崩溃由 `MuseCrashHandler` 捕获并写日志。协程未捕获异常走 `GlobalCoroutineExceptionHandler` 不致崩溃。排查步骤:
1. 查 `Logger` 输出的 "muse 启动" 确认 onCreate 执行;查各 "失败" 警告定位启动失败项。
2. 聊天问题查 `ChatViewModel` 的 `debugInfo`(`debugMode` 开启时流式结束填充,含模型/耗时/字符/工具调用数)。
3. 错误分类见 `ChatErrorType`(`NETWORK` / `API_KEY` / `RATE_LIMIT` / `MODEL_ERROR` / `TOOL_ERROR` / `UNKNOWN`),UI 用 `errors` 列表展示支持 dismiss。
4. 上下文占用查 `contextTokenCount` / `contextMaxTokens`(`TokenEstimator` 估算,流式过程每 50 字符更新)。

### 9.7 新增 / 修改后台轮询任务

主动消息与定时任务均采用协程轮询模式(非 `AlarmManager`),参考 `ProactiveMessageRunner` / `ScheduledTaskRunner` 结构:
1. 构造函数注入所需依赖(`SettingsRepository` / `ChatService` / `Context` / `AppScope` 等),在 `AppKoinModule` 注册 `single { }`。
2. `start()` 内 `appScope.launch(GlobalCoroutineExceptionHandler) { while (isActive) { delay(60_000); ... } }`,单 `Job` 控制生命周期,`stop()` 取消。
3. 触发时间戳持久化到 DataStore,App 重启后不立即重发;任一环节失败不更新时间戳,下个 tick 重试。
4. 在 `MuseApp.onCreate` 调用 `runCatching { xxxRunner.start() }.onFailure { Logger.w(...) }` 启动。

### 9.8 新增一个斜杠命令

v1.97 阶段二起,聊天输入框支持 `/` 开头的客户端命令(不经 LLM)。新增步骤:
1. 在 `ui/chat/SlashCommandRegistry.kt` 的 `SlashCommand` 枚举新增一项,如 `FOO("foo", R.string.slash_command_foo)`。
2. 在 `strings_chat_core.xml`(中英两套)新增 `slash_command_foo`(命令名)与 `slash_command_foo_desc`(命令描述)字符串。
3. 在 `ChatViewModel.executeSlashCommand` 的 `when` 分支新增路由,调对应业务方法(优先复用现有 `createNewSession`/`manualCompress`/`togglePinned`/`setSessionArchived` 等,避免重复实现)。
4. `ChatScreen` 的 `onSend` 拦截逻辑无需改动(`SlashCommand.isSlashCommand` 自动覆盖新命令)。
5. 若命令需要用户确认(如 `/reset` 清空会话),在 `executeSlashCommand` 内弹 `MuseDialog` 二次确认,用户取消则返回 false 不消费输入。

注意:斜杠命令是客户端快捷操作,不进入 LLM 管道。需要 LLM 参与的功能(如"总结当前会话")仍走 `manualCompress` 等已有方法,不要借斜杠命令旁路。

---

## 版本发布流程

1. 改版本号:编辑 `app/build.gradle.kts`(或对应 gradle 文件),同步修改 `versionCode` 与 `versionName`,遵守 `versionCode = versionName 去小数点` 规则(如 `versionName = "1.38"` → `versionCode = 138`)。
2. 编译 debug APK:在项目根目录执行 `gradlew assembleDebug`(Windows 用 `gradlew.bat`)。
3. 生成 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
4. 复制并重命名到输出目录:复制到 `D:\1test\Muse_v{version}_debug.apk`(如 `Muse_v1.38_debug.apk`)。
5. 更新 `PROJECT_DOCS.md`:在变更历史顶部新增版本条目,记录 `versionName` / `versionCode` / 日期 / 变更内容摘要。每次有修改动作都必须更新 `PROJECT_DOCS.md`(该文档供接手开发的 AI 阅读)。
6. 验证:安装 APK 后检查首启动无崩溃(`MuseCrashHandler` 已安装)、通知渠道创建、`MemoryTicker` / `ScheduledTaskRunner` / `ProactiveMessageRunner` 正常启动、内置 Skills 与开发文档 seed 完成(查 `Logger` 输出的 "muse 启动" 与各 "失败" 警告)。

发布注意事项:
- 版本号必须同步修改 `versionCode` 与 `versionName`,且满足 `versionCode = versionName 去小数点` 规则,否则与 `UpdateChecker` 逻辑不一致。
- APK 文件名严格用 `Muse_v{version}_debug.apk` 格式(如 `Muse_v1.38_debug.apk`),保持与历史版本命名一致便于回溯。
- 每次发布必须更新 `PROJECT_DOCS.md` 变更历史,该文档供接手开发的 AI 阅读,遗漏会导致后续 AI 缺失上下文。
- debug APK 用于验证;若需发布 release 版需额外配置签名,当前流程仅覆盖 debug。
- 发布后建议在真机覆盖验证:聊天流式 / 工具调用 / 记忆注入 / 定时任务通知 / 主动消息 / 备份导入 / Web 搜索等核心链路。

---

## 维护规则

1. **每次修改必须更新本文档**:在"六、版本历史"中加新版本条目,记录新增功能和修复 Bug
2. **版本号递增规则**:versionName 小数点后递增(1.12 → 1.13),versionCode = versionName 去小数点(1.13 → 113)
3. **APK 命名**: `Muse_v{versionName}_debug.apk`
4. **编译验证**:每次修改后执行 `.\gradlew.bat :app:compileDebugKotlin --console=plain`
5. **完整构建**: `.\gradlew.bat assembleDebug --console=plain` + Copy-Item 到 D:\1test
6. **验收报告**:每个 Phase 结束后在 `1muse/docs/` 新增 `Phase{N}-Acceptance-Report.txt`

---

EOF