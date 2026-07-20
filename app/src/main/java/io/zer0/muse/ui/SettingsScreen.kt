package io.zer0.muse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.ui.common.IosSettingsIcon
import io.zer0.muse.ui.common.IosSwitch
import io.zer0.muse.ui.common.MuseToast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.components.CardGroup
import org.koin.compose.koinInject
import io.zer0.muse.update.UpdateNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Refresh

/**
 * v0.34: 设置页 — 卡片分组 + DSL 架构。
 *
 * v1.132 重新整理分组:
 *  - 账户卡片(置顶)
 *  - 通用 — 外观 / 聊天 / 媒体 / 翻译 / 用户画像
 *  - 助手与 Agent — 助手 / Agent
 *  - 模型与服务 — 供应商 / 视觉 / 插件管理 / 视频生成
 *  - 记忆与知识 — 记忆通知 / RAG / 数据管理
 *  - 数据与备份 — 云备份 / 数据导入 / 工作区
 *  - 隐私与安全 — PII Guard / 安全 / 代理 / 审计日志
 *  - 关于 — 教程 / 关于 / 检查更新 / 调试日志 / 实验性 / 统计
 *
 * v1.132 新增:搜索功能(右上角搜索图标)。
 *  - 点击搜索图标 → 顶部栏切换为搜索输入框
 *  - 输入关键词后实时过滤所有设置项(标题 + 描述匹配)
 *  - 点击搜索结果项直接跳转到对应二级页
 *
 * 每个 CardGroup 内的 item 共享圆角卡片(首项顶 20dp / 末项底 20dp / 中间 4dp),
 * 按下时圆角动画过渡(iOS 风格分组卡片的按压反馈)。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAssistants: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenModelSettings: () -> Unit = {},
    onOpenMultiAgentSettings: () -> Unit = {},
    onOpenAgentSettings: () -> Unit = {},
    onOpenDataSettings: () -> Unit = {},
    onOpenAppearanceSettings: () -> Unit = {},
    onOpenChatSettings: () -> Unit = {},
    onOpenMemorySettings: () -> Unit = {},
    onOpenMediaSettings: () -> Unit = {},
    onOpenExperimentsSettings: () -> Unit = {},
    onOpenSecuritySettings: () -> Unit = {},
    onOpenProxySettings: () -> Unit = {},
    onOpenAboutSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenRagSettings: () -> Unit = {},
    onOpenDataImport: () -> Unit = {},
    /** v1.61-B: 打开使用教程页(新手引导)。 */
    onOpenTutorial: () -> Unit = {},
    /** v1.76: 打开用户画像编辑页(称呼 / 年龄 / 城市等)。 */
    onOpenUserProfile: () -> Unit = {},
    /** v1.97 gap8: 打开独立翻译页。 */
    onOpenTranslate: () -> Unit = {},
    /** v2.0: 打开数据管理页。 */
    onOpenVisionSettings: () -> Unit = {},
    onOpenDataManagement: () -> Unit = {},
    /** 打开调试日志页(从关于分组进入,展示最近 Logger 调用)。 */
    onOpenDebugLog: () -> Unit = {},
    /** P2-4: 打开审计日志页(从「数据与隐私」分组进入)。 */
    onOpenAuditLog: () -> Unit = {},
    /** P2-7: 打开工作区页(从「数据与隐私」分组进入,文件管理器)。 */
    onOpenWorkspace: () -> Unit = {},
    /** P2-8: 打开视频生成页(从「工具」分组进入)。 */
    onOpenVideoGeneration: () -> Unit = {},
    /** P2-10: 打开 Provider 插件管理页(从「模型与服务」分组进入)。 */
    onOpenProviderPlugins: () -> Unit = {},
    /** v1.133: 打开联网搜索页(从「模型与服务」分组进入,原 SettingsModelPage 内嵌)。 */
    onOpenWebSearch: () -> Unit = {},
    /** v1.133: 打开语音识别 ASR 页(从「模型与服务」分组进入,原 SettingsModelPage 内嵌)。 */
    onOpenAsr: () -> Unit = {},
    /** v1.133: 打开图像生成页(从「模型与服务」分组进入,原 SettingsModelPage 内嵌)。 */
    onOpenImageGen: () -> Unit = {},
    /** v1.133: 打开 MCP 服务器页(从「模型与服务」分组进入,原 SettingsModelPage 内嵌)。 */
    onOpenMcp: () -> Unit = {},
    /** v1.133: 打开助手资源页(从「助手与 Agent」分组进入,容纳收藏夹/世界书/快捷消息/模式注入/Skills/记忆开关)。 */
    onOpenAssistantResources: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val updateNotifier: UpdateNotifier = koinInject()
    val proxyConfig by settings.proxyConfigFlow.collectAsStateWithLifecycle(initialValue = ProxyConfig())
    // PII Guard 开关(默认开启)
    val piiGuardEnabled by settings.piiGuardEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val proxyDisabled = stringResource(R.string.proxy_disabled)
    val proxyTitle = stringResource(R.string.proxy_title)
    val proxySubtitle = when {
        !proxyConfig.enabled -> proxyDisabled
        proxyConfig.host.isBlank() || proxyConfig.port <= 0 -> proxyDisabled
        else -> "${proxyConfig.type} ${proxyConfig.host}:${proxyConfig.port}"
    }
    // v1.133: 手动检查更新状态(checking=true 时禁用按钮并显示进度)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var checkingUpdate by remember { mutableStateOf(false) }
    // v1.132: 搜索状态(isSearching=true 时顶部栏切换为搜索框,LazyColumn 显示搜索结果)
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    // 进入搜索模式时自动聚焦输入框并弹出键盘
    androidx.compose.runtime.LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // v1.132: 预提取所有设置项的标题/描述字符串(供搜索过滤使用)
    val userProfileTitle = stringResource(R.string.settings_screen_user_profile)
    val userProfileDesc = stringResource(R.string.settings_screen_user_profile_desc)
    val appearanceTitle = stringResource(R.string.settings_screen_appearance_label)
    val appearanceDesc = stringResource(R.string.settings_screen_appearance_desc)
    val chatTitle = stringResource(R.string.settings_screen_chat)
    val chatDesc = stringResource(R.string.settings_screen_chat_desc)
    val mediaTitle = stringResource(R.string.settings_screen_media)
    val mediaDesc = stringResource(R.string.settings_screen_media_desc)
    val translateTitle = stringResource(R.string.settings_screen_translate)
    val translateDesc = stringResource(R.string.settings_screen_translate_desc)
    val assistantTitle = stringResource(R.string.settings_screen_assistant)
    val assistantDesc = stringResource(R.string.settings_screen_assistant_desc)
    val agentTitle = "Agent"
    val agentDesc = stringResource(R.string.settings_screen_agent_desc)
    val providerTitle = stringResource(R.string.settings_screen_provider)
    val providerDesc = stringResource(R.string.settings_screen_provider_desc)
    val visionTitle = stringResource(R.string.settings_screen_vision)
    val visionDesc = stringResource(R.string.settings_screen_vision_desc)
    val providerPluginsTitle = stringResource(R.string.provider_plugins_title)
    val videoGenTitle = stringResource(R.string.settings_screen_video_gen)
    val videoGenDesc = stringResource(R.string.settings_screen_video_gen_desc)
    val memoryTitle = stringResource(R.string.settings_screen_memory_notification)
    val memoryDesc = stringResource(R.string.settings_screen_memory_notification_desc)
    val ragTitle = stringResource(R.string.settings_screen_rag)
    val ragDesc = stringResource(R.string.settings_screen_rag_desc)
    val dataManagementTitle = stringResource(R.string.data_management_entry)
    val dataManagementDesc = stringResource(R.string.data_management_entry_desc)
    val dataBackupTitle = stringResource(R.string.settings_screen_data_backup)
    val dataBackupDesc = stringResource(R.string.settings_screen_data_backup_desc)
    val dataImportTitle = stringResource(R.string.settings_screen_data_import)
    val dataImportDesc = stringResource(R.string.settings_screen_data_import_desc)
    val workspaceTitle = stringResource(R.string.workspace_title)
    val piiGuardTitle = stringResource(R.string.settings_screen_pii_guard)
    val piiGuardDesc = stringResource(R.string.settings_screen_pii_guard_desc)
    val securityTitle = stringResource(R.string.settings_screen_security)
    val securityDesc = stringResource(R.string.settings_screen_security_desc)
    val auditLogTitle = stringResource(R.string.settings_audit_log)
    val experimentsTitle = stringResource(R.string.settings_screen_experiments)
    val experimentsDesc = stringResource(R.string.settings_screen_experiments_desc)
    val statsTitle = stringResource(R.string.settings_screen_stats)
    val statsDesc = stringResource(R.string.settings_screen_stats_desc)
    val tutorialTitle = stringResource(R.string.settings_screen_tutorial)
    val tutorialDesc = stringResource(R.string.settings_screen_tutorial_desc)
    val aboutTitle = stringResource(R.string.settings_screen_about)
    val aboutDesc = stringResource(R.string.settings_screen_about_desc)
    val checkUpdateTitle = stringResource(R.string.settings_screen_check_update)
    val checkUpdateDesc = stringResource(R.string.settings_screen_check_update_desc)
    val debugLogTitle = stringResource(R.string.settings_screen_debug_log)
    val debugLogDesc = stringResource(R.string.settings_screen_debug_log_desc)
    // v1.133: 5 个新拆分入口的标题/描述
    val webSearchEntryTitle = stringResource(R.string.settings_screen_web_search)
    val webSearchEntryDesc = stringResource(R.string.settings_screen_web_search_desc)
    val asrEntryTitle = stringResource(R.string.settings_screen_asr)
    val asrEntryDesc = stringResource(R.string.settings_screen_asr_desc)
    val imageGenEntryTitle = stringResource(R.string.settings_screen_image_gen)
    val imageGenEntryDesc = stringResource(R.string.settings_screen_image_gen_desc)
    val mcpEntryTitle = stringResource(R.string.settings_screen_mcp)
    val mcpEntryDesc = stringResource(R.string.settings_screen_mcp_desc)
    val assistantResourcesTitle = stringResource(R.string.settings_screen_assistant_resources)
    val assistantResourcesDesc = stringResource(R.string.settings_screen_assistant_resources_desc)
    val searchHint = stringResource(R.string.settings_search_hint)
    val noResults = stringResource(R.string.settings_search_no_results)

    // v1.132: 所有可搜索的设置项列表(标题 + 描述 + 图标 + 跳转回调)
    // 搜索时按 (title + desc) 包含 query 过滤
    data class SearchEntry(
        val title: String,
        val desc: String,
        val icon: ImageVector,
        val onClick: () -> Unit,
    )
    val allEntries by remember(piiGuardEnabled, proxySubtitle, checkingUpdate) {
        mutableStateOf(
            listOf(
                SearchEntry(userProfileTitle, userProfileDesc, Icons.Outlined.AccountCircle, onOpenUserProfile),
                SearchEntry(appearanceTitle, appearanceDesc, Icons.Outlined.Palette, onOpenAppearanceSettings),
                SearchEntry(chatTitle, chatDesc, Icons.Outlined.Forum, onOpenChatSettings),
                SearchEntry(mediaTitle, mediaDesc, Icons.Outlined.RecordVoiceOver, onOpenMediaSettings),
                SearchEntry(translateTitle, translateDesc, Icons.Outlined.Translate, onOpenTranslate),
                SearchEntry(assistantTitle, assistantDesc, Icons.Outlined.Psychology, onOpenAssistants),
                SearchEntry(agentTitle, agentDesc, Icons.Outlined.GroupWork, onOpenAgentSettings),
                SearchEntry(providerTitle, providerDesc, Icons.Outlined.SettingsEthernet, onOpenModelSettings),
                SearchEntry(visionTitle, visionDesc, Icons.Outlined.Visibility, onOpenVisionSettings),
                SearchEntry(providerPluginsTitle, providerPluginsTitle, Icons.Outlined.Extension, onOpenProviderPlugins),
                SearchEntry(videoGenTitle, videoGenDesc, Icons.Outlined.Movie, onOpenVideoGeneration),
                // v1.133: 5 个新拆分入口
                SearchEntry(webSearchEntryTitle, webSearchEntryDesc, Icons.Outlined.Language, onOpenWebSearch),
                SearchEntry(asrEntryTitle, asrEntryDesc, Icons.Outlined.Mic, onOpenAsr),
                SearchEntry(imageGenEntryTitle, imageGenEntryDesc, Icons.Outlined.Image, onOpenImageGen),
                SearchEntry(mcpEntryTitle, mcpEntryDesc, Icons.Outlined.Hub, onOpenMcp),
                SearchEntry(assistantResourcesTitle, assistantResourcesDesc, Icons.Outlined.AutoAwesome, onOpenAssistantResources),
                SearchEntry(memoryTitle, memoryDesc, Icons.Outlined.Psychology, onOpenMemorySettings),
                SearchEntry(ragTitle, ragDesc, Icons.AutoMirrored.Outlined.MenuBook, onOpenRagSettings),
                SearchEntry(dataManagementTitle, dataManagementDesc, Icons.Outlined.Storage, onOpenDataManagement),
                SearchEntry(dataBackupTitle, dataBackupDesc, Icons.Outlined.Cloud, onOpenDataSettings),
                SearchEntry(dataImportTitle, dataImportDesc, Icons.Outlined.CloudUpload, onOpenDataImport),
                SearchEntry(workspaceTitle, workspaceTitle, Icons.Outlined.Folder, onOpenWorkspace),
                SearchEntry(piiGuardTitle, piiGuardDesc, Icons.Outlined.PrivacyTip, {}),
                SearchEntry(securityTitle, securityDesc, Icons.Outlined.Lock, onOpenSecuritySettings),
                SearchEntry(auditLogTitle, auditLogTitle, Icons.Outlined.History, onOpenAuditLog),
                SearchEntry(proxyTitle, proxySubtitle, Icons.Outlined.Tune, onOpenProxySettings),
                SearchEntry(experimentsTitle, experimentsDesc, Icons.Outlined.Science, onOpenExperimentsSettings),
                SearchEntry(statsTitle, statsDesc, Icons.Outlined.BarChart, onOpenStats),
                SearchEntry(tutorialTitle, tutorialDesc, Icons.Outlined.School, onOpenTutorial),
                SearchEntry(aboutTitle, aboutDesc, Icons.Outlined.Info, onOpenAboutSettings),
                SearchEntry(checkUpdateTitle, checkUpdateDesc, Icons.Outlined.Refresh, {}),
                SearchEntry(debugLogTitle, debugLogDesc, Icons.Outlined.BugReport, onOpenDebugLog),
            ),
        )
    }
    // 过滤后的搜索结果(query 为空时返回全部,否则按 title/desc 包含查询)
    val filteredEntries by remember(searchQuery, allEntries) {
        mutableStateOf(
            if (searchQuery.isBlank()) allEntries
            else allEntries.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.desc.contains(searchQuery, ignoreCase = true)
            },
        )
    }

    // 预提取 semantics contentDescription 字符串
    val appearanceGroupCd = stringResource(R.string.settings_screen_appearance_group_cd)
    val userProfileCd = stringResource(R.string.settings_screen_user_profile_cd)
    val appearanceCd = stringResource(R.string.settings_screen_appearance_cd)
    val chatCd = stringResource(R.string.settings_screen_chat_cd)
    val mediaCd = stringResource(R.string.settings_screen_media_cd)
    val memoryDataGroupCd = stringResource(R.string.settings_screen_memory_data_group_cd)
    val memoryNotificationCd = stringResource(R.string.settings_screen_memory_notification_cd)
    val dataBackupCd = stringResource(R.string.settings_screen_data_backup_cd)
    val dataImportCd = stringResource(R.string.settings_screen_data_import_cd)
    val ragCd = stringResource(R.string.settings_screen_rag_cd)
    val piiGuardCd = stringResource(R.string.settings_screen_pii_guard_cd)
    val privacyGroupCd = stringResource(R.string.settings_screen_privacy_group_cd)
    val modelServiceGroupCd = stringResource(R.string.settings_screen_model_service_group_cd)
    val providerCd = stringResource(R.string.settings_screen_provider_cd)
    val assistantCd = stringResource(R.string.settings_screen_assistant_cd)
    val agentCd = stringResource(R.string.settings_screen_agent_cd)
    val advancedGroupCd = stringResource(R.string.settings_screen_advanced_group_cd)
    val securityCd = stringResource(R.string.settings_screen_security_cd)
    val proxyCd = stringResource(R.string.settings_screen_proxy_cd, proxyTitle, proxySubtitle)
    val experimentsCd = stringResource(R.string.settings_screen_experiments_cd)
    val statsCd = stringResource(R.string.settings_screen_stats_cd)
    val aboutGroupCd = stringResource(R.string.settings_screen_about_group_cd)
    val tutorialCd = stringResource(R.string.settings_screen_tutorial_cd)
    val aboutCd = stringResource(R.string.settings_screen_about_cd)
    val debugLogCd = stringResource(R.string.settings_screen_debug_log_cd)
    val toolsGroupCd = stringResource(R.string.settings_screen_tools_group_cd)
    val translateCd = stringResource(R.string.settings_screen_translate_cd)
    val videoGenCd = stringResource(R.string.settings_screen_video_gen_cd)
    val checkUpdateCd = stringResource(R.string.settings_screen_check_update_cd)

    Scaffold(
        topBar = {
            // v1.131: 显式状态栏遮罩,解决 enableEdgeToEdge 导致的透明状态栏问题
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                        .background(MaterialTheme.colorScheme.background)
                        .align(Alignment.TopCenter),
                )
                // iOS 风格 Large Title 顶部栏(替代 Material LargeTopAppBar)
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    // 返回按钮 + 搜索按钮行(v1.132: 右上角加搜索图标,与返回键同水平)
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (isSearching) {
                            // 搜索模式:返回按钮 = 退出搜索;中间是搜索输入框
                            IconButton(onClick = {
                                isSearching = false
                                searchQuery = ""
                                keyboard?.hide()
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.settings_screen_back_cd),
                                )
                            }
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                placeholder = { Text(searchHint) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Search,
                                ),
                                // 输入框右侧清空按钮
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                Icons.Outlined.Refresh,
                                                contentDescription = null,
                                            )
                                        }
                                    }
                                },
                            )
                        } else {
                            // 普通模式:返回按钮 + 右上角搜索按钮
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.settings_screen_back_cd),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            // v1.132: 右上角搜索图标,与返回键同水平
                            IconButton(onClick = { isSearching = true }) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.settings_search_cd),
                                )
                            }
                        }
                    }
                    // Large Title — 32dp 粗体(搜索模式隐藏,让出空间给搜索框)
                    if (!isSearching) {
                        Text(
                            text = stringResource(R.string.settings_screen_title),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
                // v1.99: 大R角/曲面屏 — 保留 innerPadding 的横向 safeDrawing,避免卡片贴边裁切
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // v1.132: 搜索模式 — 只显示搜索结果列表
            if (isSearching) {
                item(key = "search_status") {
                    Text(
                        text = if (searchQuery.isBlank()) stringResource(R.string.settings_search_prompt)
                        else "${filteredEntries.size} $noResults",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (filteredEntries.isEmpty()) {
                    item(key = "search_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = noResults,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                } else {
                    items(filteredEntries, key = { it.title + it.desc }) { entry ->
                        CardGroup(
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            item(
                                onClick = {
                                    keyboard?.hide()
                                    isSearching = false
                                    searchQuery = ""
                                    entry.onClick()
                                },
                                leadingContent = {
                                    IosSettingsIcon(entry.icon)
                                },
                                headlineContent = { Text(entry.title) },
                                supportingContent = { Text(entry.desc) },
                                trailingContent = { ChevronRight() },
                            )
                        }
                    }
                }
            } else {
                // ── 账户卡片(置顶,未登录状态占位)──
                item(key = "account") {
                    io.zer0.muse.ui.account.AccountCard(onClick = onOpenAccount)
                }

                // ── 通用(用户高频外观偏好置顶)──
                item(key = "general") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = appearanceGroupCd }) {
                                Text(stringResource(R.string.settings_screen_appearance))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = userProfileCd },
                            onClick = onOpenUserProfile,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.AccountCircle) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_user_profile)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_user_profile_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = appearanceCd },
                            onClick = onOpenAppearanceSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Palette) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_appearance_label)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_appearance_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = chatCd },
                            onClick = onOpenChatSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Forum) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_chat)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_chat_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = mediaCd },
                            onClick = onOpenMediaSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.RecordVoiceOver) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_media)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_media_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = translateCd },
                            onClick = onOpenTranslate,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Translate) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_translate)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_translate_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 助手与 Agent ──
                item(key = "assistant_agent") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = assistantCd }) {
                                Text(stringResource(R.string.settings_screen_assistant_agent))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = assistantCd },
                            onClick = onOpenAssistants,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Psychology) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_assistant)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_assistant_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = agentCd },
                            onClick = onOpenAgentSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.GroupWork) },
                            headlineContent = { Text("Agent") },
                            supportingContent = { Text(stringResource(R.string.settings_screen_agent_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 助手资源(收藏夹/世界书/快捷消息/模式注入/Skills/记忆开关,原 SettingsModelPage 内嵌)
                        item(
                            onClick = onOpenAssistantResources,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.AutoAwesome) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_assistant_resources)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_assistant_resources_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 模型与服务(供应商 / 视觉 / 插件 / 视频生成)──
                item(key = "model_service") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = modelServiceGroupCd }) {
                                Text(stringResource(R.string.settings_screen_model_service))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = providerCd },
                            onClick = onOpenModelSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.SettingsEthernet) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_provider)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_provider_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            onClick = onOpenVisionSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Visibility) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_vision)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_vision_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // P2-10: 插件管理入口(从 JSON 文件导入自定义 Provider)
                        item(
                            onClick = onOpenProviderPlugins,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Extension) },
                            headlineContent = { Text(stringResource(R.string.provider_plugins_title)) },
                            trailingContent = { ChevronRight() },
                        )
                        // P2-8: 视频生成入口
                        item(
                            modifier = Modifier.semantics { contentDescription = videoGenCd },
                            onClick = onOpenVideoGeneration,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Movie) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_video_gen)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_video_gen_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 联网搜索(从 SettingsModelPage 拆出)
                        item(
                            onClick = onOpenWebSearch,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Language) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_web_search)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_web_search_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 语音识别 ASR
                        item(
                            onClick = onOpenAsr,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Mic) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_asr)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_asr_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 图像生成
                        item(
                            onClick = onOpenImageGen,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Image) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_image_gen)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_image_gen_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: MCP 服务器
                        item(
                            onClick = onOpenMcp,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Hub) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_mcp)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_mcp_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 记忆与知识(记忆通知 / RAG / 数据管理)──
                item(key = "memory_knowledge") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = memoryDataGroupCd }) {
                                Text(stringResource(R.string.settings_screen_memory_data))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = memoryNotificationCd },
                            onClick = onOpenMemorySettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Psychology) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_memory_notification)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_memory_notification_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = ragCd },
                            onClick = onOpenRagSettings,
                            leadingContent = { IosSettingsIcon(Icons.AutoMirrored.Outlined.MenuBook) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_rag)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_rag_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            onClick = onOpenDataManagement,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Storage) },
                            headlineContent = { Text(stringResource(R.string.data_management_entry)) },
                            supportingContent = { Text(stringResource(R.string.data_management_entry_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 数据与备份(云备份 / 数据导入 / 工作区)──
                item(key = "data_backup") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = dataBackupCd }) {
                                Text(stringResource(R.string.settings_screen_data_backup_group))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = dataBackupCd },
                            onClick = onOpenDataSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Cloud) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_data_backup)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_data_backup_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = dataImportCd },
                            onClick = onOpenDataImport,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.CloudUpload) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_data_import)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_data_import_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // P2-7: 工作区入口(文件管理器)
                        item(
                            onClick = onOpenWorkspace,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Folder) },
                            headlineContent = { Text(stringResource(R.string.workspace_title)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 隐私与安全(PII Guard / 安全 / 代理 / 审计日志)──
                item(key = "privacy_security") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = privacyGroupCd }) {
                                Text(stringResource(R.string.settings_screen_privacy))
                            }
                        },
                    ) {
                        // PII Guard 开关
                        item(
                            modifier = Modifier.semantics { contentDescription = piiGuardCd },
                            leadingContent = { IosSettingsIcon(Icons.Outlined.PrivacyTip) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_pii_guard)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_pii_guard_desc)) },
                            trailingContent = {
                                IosSwitch(
                                    checked = piiGuardEnabled,
                                    onCheckedChange = { v -> scope.launch { settings.savePiiGuardEnabled(v) } },
                                )
                            },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = securityCd },
                            onClick = onOpenSecuritySettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Lock) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_security)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_security_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = proxyCd },
                            onClick = onOpenProxySettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Tune) },
                            headlineContent = { Text(proxyTitle) },
                            supportingContent = { Text(proxySubtitle) },
                            trailingContent = { ChevronRight() },
                        )
                        // P2-4: 审计日志入口
                        item(
                            onClick = onOpenAuditLog,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.History) },
                            headlineContent = { Text(stringResource(R.string.settings_audit_log)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 关于(教程 / 关于 / 检查更新 / 调试日志 / 实验性 / 统计)──
                item(key = "about") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = aboutGroupCd }) {
                                Text(stringResource(R.string.settings_screen_about))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = tutorialCd },
                            onClick = onOpenTutorial,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.School) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_tutorial)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_tutorial_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = aboutCd },
                            onClick = onOpenAboutSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Info) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_about)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_about_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 手动检查更新 — forceCheck=true,跳过 24h 间隔限制
                        item(
                            modifier = Modifier.semantics { contentDescription = checkUpdateCd },
                            onClick = {
                                if (checkingUpdate) return@item
                                checkingUpdate = true
                                scope.launch {
                                    val beforeJson = runCatching {
                                        settings.latestReleaseInfoFlow.first()
                                    }.getOrNull()
                                    updateNotifier.checkAndNotify(context, forceCheck = true)
                                    checkingUpdate = false
                                    val latest = runCatching {
                                        settings.latestReleaseInfoFlow.first()
                                    }.getOrNull()
                                    if (latest != null && latest != beforeJson) {
                                        MuseToast.show(context.getString(R.string.update_found_new))
                                    } else if (latest == null) {
                                        MuseToast.show(context.getString(R.string.update_already_latest))
                                    }
                                }
                            },
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Refresh) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_check_update)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_check_update_desc)) },
                            trailingContent = {
                                if (checkingUpdate) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    ChevronRight()
                                }
                            },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = debugLogCd },
                            onClick = onOpenDebugLog,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.BugReport) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_debug_log)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_debug_log_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = experimentsCd },
                            onClick = onOpenExperimentsSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Science) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_experiments)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_experiments_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = statsCd },
                            onClick = onOpenStats,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.BarChart) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_stats)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_stats_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * v0.34: 右箭头指示符 — 用于 CardGroup item 的 trailingContent,
 * 提示该行可点击进入下一级。对标 iOS SwiftUI Form 中的 ">" chevron。
 */
@Composable
private fun ChevronRight() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
    )
}
