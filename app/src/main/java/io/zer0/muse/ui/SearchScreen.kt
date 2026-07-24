package io.zer0.muse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import io.zer0.muse.R
import io.zer0.muse.ui.common.LoadingState
import io.zer0.muse.ui.common.SegmentedControl
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.semiLarge

/**
 * v0.45: 独立全局搜索页。
 *
 * 从首页右上角搜索按钮进入,右滑入场。功能:
 *  - 顶部搜索框(自动聚焦)
 *  - 无输入:大面积空白空状态 + 热门搜索建议 chip
 *  - 有输入(v2.x: 顶部 Tab 切换"会话/消息内容"):
 *      - Tab=会话:分"会话/消息"section(原 searchResults,FTS4 + buildSnippet)
 *        + "设置"section(本地 hardcode 匹配)
 *      - Tab=消息内容:展示 [ChatUiState.messageResults](FTS4 snippet + 高亮片段),
 *        点击跳转对应会话并传 messageId 用于滚动定位 + 短暂高亮
 *
 * 数据源:
 *  - Tab=会话 消息搜索复用 [ChatViewModel.search](走 SessionRepository.searchMessages / FTS4)
 *  - Tab=消息内容 走 [ChatViewModel.searchMessageContent](走 SessionRepository.searchMessageContentFlow,
 *    FTS4 snippet + LIKE 兜底)
 *  - 会话标题匹配:对 state.sessions 做内存 contains 过滤
 *  - 设置项:本地 hardcode 列表(约 15 项,匹配关键词后展示,点击跳转对应设置页)
 *
 * v2.x: 新增 [onOpenMessage] 回调,Tab=消息内容 点击消息项时触发,
 * MainActivity 据此 switchSession + setTargetMessage 后回到 HOME,
 * ChatScreen 监听 targetMessageId 滚动定位 + 短暂高亮。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
    /**
     * v2.x: Tab=消息内容 点击消息项跳转回调。
     * @param sessionId 目标会话 id
     * @param messageId 目标消息 id(用于 ChatScreen 滚动定位)
     * @param query 搜索关键词(用于 MessageBubble 内文本高亮)
     */
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit = { _, _, _ -> },
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query = state.searchQuery
    // v2.x: 当前搜索 Tab(0=会话, 1=消息内容)
    val searchTab = state.searchTab
    val focusRequester = remember { FocusRequester() }

    // 自动聚焦搜索框
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 输入变化时(去抖 300ms)更新查询并触发搜索
    // v2.x: 根据 searchTab 切换走原 search() 或 searchMessageContent()
    LaunchedEffect(query, searchTab) {
        if (query.isNotBlank()) {
            delay(300)
            viewModel.updateSearchQuery(query)
            if (searchTab == 1) {
                viewModel.searchMessageContent()
            } else {
                viewModel.search()
            }
        } else {
            viewModel.clearSearch()
        }
    }

    Scaffold(
        topBar = {
            // iOS 风格搜索栏(替代 Material TopAppBar)
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 搜索图标
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    // 搜索输入框(胶囊形)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        shape = MuseShapes.semiLarge,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                    )
                    // Cancel 文字按钮
                    TextButton(onClick = onBack) {
                        Text(
                            "Cancel",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // v2.x: 有输入时显示 Tab 切换(会话 / 消息内容)
            if (query.isNotBlank()) {
                SegmentedControl(
                    options = listOf("会话", "消息内容"),
                    selectedIndex = searchTab,
                    onSelectedChange = { viewModel.switchSearchTab(it) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (query.isBlank()) {
                // 无输入:大面积空白空状态
                EmptySearchState(
                    onSuggestionClick = { suggestion ->
                        viewModel.updateSearchQuery(suggestion)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (searchTab == 0) {
                // Tab=会话:沿用原有搜索结果(会话标题/预览匹配 + 消息 + 设置)
                SearchResults(
                    query = query,
                    sessions = state.sessions,
                    messageResults = state.searchResults,
                    isSearching = state.isSearching,
                    settingsItems = rememberSettingsIndex(),
                    onOpenSession = onOpenSession,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Tab=消息内容:展示 FTS4 snippet 结果,点击跳转传 messageId
                MessageSearchResults(
                    query = query,
                    messageResults = state.messageResults,
                    isSearching = state.isSearchingMessages,
                    onOpenMessage = onOpenMessage,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 无输入空状态:居中灰色搜索图标 + 提示 + 建议词 chip。 */
@Composable
private fun EmptySearchState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.search_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(24.dp))
            // 热门搜索建议 chip
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(stringResource(R.string.search_index_proactive_message), onSuggestionClick)
                SuggestionChip(stringResource(R.string.search_index_theme), onSuggestionClick)
                SuggestionChip(stringResource(R.string.search_suggestion_backup), onSuggestionClick)
                SuggestionChip(stringResource(R.string.search_index_pin_lock), onSuggestionClick)
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    label: String,
    onClick: (String) -> Unit,
) {
    // L-SS1: 触摸目标至少 48dp(原 vertical padding 仅 6dp,触摸区不足),用 heightIn(min = touchTarget) 保证
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .heightIn(min = MuseIconSizes.touchTarget)
            .clickable { onClick(label) },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = MusePaddings.itemGap),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 搜索结果:会话 + 消息 + 设置三段。 */
@Composable
private fun SearchResults(
    query: String,
    sessions: List<io.zer0.muse.data.session.SessionEntity>,
    messageResults: List<io.zer0.muse.data.session.SearchResult>,
    isSearching: Boolean,
    settingsItems: List<SettingItem>,
    onOpenSession: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // M-SS1: 用 remember 缓存过滤结果,避免每次 recomposition 重复计算(原实现每次都重新 filter)
    val matchedSessions = remember(sessions, query) {
        sessions.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.lastMessagePreview.contains(query, ignoreCase = true)
        }.take(10)
    }

    val matchedSettings = remember(settingsItems, query) {
        settingsItems.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.subtitle.contains(query, ignoreCase = true)
        }
    }

    val hasAny = matchedSessions.isNotEmpty() ||
        messageResults.isNotEmpty() ||
        matchedSettings.isNotEmpty()

    // v1.0.4 (P2): 搜索中且无结果时,居中显示大号 loading + "正在搜索…"文案
    // (原仅列表顶部 20dp 小圈,体验偏弱,用户分不清是搜索中还是无结果)
    if (!hasAny && isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoadingState()
                Text(
                    text = stringResource(R.string.search_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    if (!hasAny && !isSearching) {
        // 无匹配结果
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.search_no_result),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.search_try_other),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
    ) {
        if (isSearching) {
            item(key = "loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    // v1.0.4 (P2): 加文案,避免单纯小圈太弱
                    Text(
                        text = stringResource(R.string.search_searching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 会话 section
        if (matchedSessions.isNotEmpty()) {
            item(key = "section_sessions") { SectionTitle(stringResource(R.string.search_section_sessions)) }
            items(matchedSessions, key = { "session_${it.id}" }) { session ->
                ResultRow(
                    title = session.title.ifBlank { stringResource(R.string.search_new_session) },
                    subtitle = session.lastMessagePreview,
                    icon = Icons.Outlined.ChatBubbleOutline,
                    onClick = { onOpenSession(session.id) },
                )
            }
        }
        // 消息 section(FTS5 结果)
        if (messageResults.isNotEmpty()) {
            item(key = "section_messages") { SectionTitle(stringResource(R.string.search_section_messages)) }
            items(messageResults, key = { "msg_${it.messageId}" }) { result ->
                ResultRow(
                    title = result.sessionTitle.ifBlank { stringResource(R.string.search_new_session) },
                    subtitle = result.contentSnippet,
                    icon = Icons.Outlined.ChatBubbleOutline,
                    onClick = { onOpenSession(result.sessionId) },
                )
            }
        }
        // 设置 section
        if (matchedSettings.isNotEmpty()) {
            item(key = "section_settings") { SectionTitle(stringResource(R.string.search_section_settings)) }
            items(matchedSettings, key = { "setting_${it.name}" }) { item ->
                ResultRow(
                    title = item.name,
                    subtitle = item.subtitle,
                    icon = Icons.Outlined.Settings,
                    onClick = { onOpenSettings(item.route) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 设置索引项(name 匹配关键词,route 跳转目标)。 */
private data class SettingItem(
    val name: String,
    val subtitle: String,
    val route: String,
)

/** 本地 hardcode 设置索引(约 15 项,匹配关键词后展示)。 */
@Composable
private fun rememberSettingsIndex(): List<SettingItem> = listOf(
    SettingItem(stringResource(R.string.search_index_proactive_message), stringResource(R.string.search_index_chat_behavior), MuseRoutes.SETTINGS_CHAT),
    SettingItem(stringResource(R.string.search_index_deep_thinking), stringResource(R.string.search_index_chat_behavior), MuseRoutes.SETTINGS_CHAT),
    SettingItem(stringResource(R.string.search_index_web_server), stringResource(R.string.search_index_data_backup), MuseRoutes.SETTINGS_DATA),
    SettingItem(stringResource(R.string.search_index_cloud_backup), stringResource(R.string.search_index_data_backup), MuseRoutes.SETTINGS_DATA),
    SettingItem(stringResource(R.string.search_index_theme), stringResource(R.string.search_index_appearance), MuseRoutes.SETTINGS_APPEARANCE),
    SettingItem(stringResource(R.string.search_index_font_size), stringResource(R.string.search_index_appearance), MuseRoutes.SETTINGS_APPEARANCE),
    SettingItem(stringResource(R.string.search_index_pin_lock), stringResource(R.string.search_index_security), MuseRoutes.SETTINGS_SECURITY),
    SettingItem(stringResource(R.string.search_index_assistant_manage), stringResource(R.string.search_index_assistants_page), MuseRoutes.ASSISTANTS),
    SettingItem(stringResource(R.string.search_index_memory), stringResource(R.string.search_index_memory_settings), MuseRoutes.SETTINGS_MEMORY),
    SettingItem(stringResource(R.string.search_index_skill), stringResource(R.string.search_index_skill_manage), MuseRoutes.SKILLS),
    SettingItem(stringResource(R.string.search_index_knowledge), stringResource(R.string.search_index_knowledge_page), MuseRoutes.KNOWLEDGE),
    SettingItem(stringResource(R.string.search_index_scheduled_task), stringResource(R.string.search_index_scheduled_task_page), MuseRoutes.SCHEDULED_TASKS),
    SettingItem(stringResource(R.string.search_index_mcp), stringResource(R.string.search_index_model_service), MuseRoutes.SETTINGS_MCP),
    SettingItem(stringResource(R.string.search_index_provider), stringResource(R.string.search_index_model_service), MuseRoutes.SETTINGS_MODEL),
    // v1.133: 5 个新拆分二级页(从 SettingsModelPage 拆出)
    SettingItem(stringResource(R.string.settings_screen_web_search), stringResource(R.string.settings_screen_model_service), MuseRoutes.SETTINGS_WEB_SEARCH),
    SettingItem(stringResource(R.string.settings_screen_asr), stringResource(R.string.settings_screen_model_service), MuseRoutes.SETTINGS_ASR),
    SettingItem(stringResource(R.string.settings_screen_image_gen), stringResource(R.string.settings_screen_model_service), MuseRoutes.SETTINGS_IMAGE_GEN),
    SettingItem(stringResource(R.string.settings_screen_assistant_resources), stringResource(R.string.settings_screen_assistant_agent), MuseRoutes.SETTINGS_ASSISTANT_RESOURCES),
    SettingItem(stringResource(R.string.search_index_user_profile), stringResource(R.string.search_index_user_profile_edit), MuseRoutes.USER_PROFILE_EDIT),
)

// ── v2.x: Tab=消息内容 搜索结果展示 ──────────────────────────────────

/**
 * v2.x: Tab=消息内容 搜索结果列表。
 *
 * 展示 [SearchResult] 列表(会话标题 + 内容片段(高亮)+ 时间),
 * 点击调用 [onOpenMessage] 跳转对应会话并传 messageId 用于滚动定位 + 短暂高亮。
 *
 * 片段高亮:[SearchResult.contentSnippet] 由 FTS4 snippet() 生成,匹配 token 以 [ ] 包裹,
 * [buildHighlightedSnippet] 解析后给匹配部分加半透明黄色背景。
 *
 * 空结果 + 搜索中:居中 loading;空结果 + 非搜索中:无匹配提示。
 */
@Composable
private fun MessageSearchResults(
    query: String,
    messageResults: List<io.zer0.muse.data.session.SearchResult>,
    isSearching: Boolean,
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 无结果 + 搜索中:居中大号 loading + 文案
    if (messageResults.isEmpty() && isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoadingState()
                Text(
                    text = stringResource(R.string.search_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    // 无结果 + 非搜索中:无匹配提示
    if (messageResults.isEmpty() && !isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.search_no_result),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.search_try_other),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
    ) {
        // 搜索中时顶部显示小号 loading + 文案(结果已有但仍在追加)
        if (isSearching) {
            item(key = "loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.search_searching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "section_messages_content") { SectionTitle("消息内容") }
        items(messageResults, key = { "msg_content_${it.messageId}" }) { result ->
            MessageResultRow(
                sessionTitle = result.sessionTitle,
                contentSnippet = result.contentSnippet,
                timestamp = result.createdAt,
                onClick = { onOpenMessage(result.sessionId, result.messageId, query) },
            )
        }
    }
}

/**
 * v2.x: 消息内容搜索结果行(会话标题 + 高亮内容片段 + 时间)。
 *
 * 高亮片段由 [buildHighlightedSnippet] 解析 [ ] 标记生成 AnnotatedString。
 */
@Composable
private fun MessageResultRow(
    sessionTitle: String,
    contentSnippet: String,
    timestamp: Long,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sessionTitle.ifBlank { stringResource(R.string.search_new_session) },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // v2.x: 内容片段高亮([xxx] 标记 → 半透明黄色背景)
                if (contentSnippet.isNotBlank()) {
                    Text(
                        text = remember(contentSnippet) { buildHighlightedSnippet(contentSnippet) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // v2.x: 时间显示(MM-dd HH:mm)
                Text(
                    text = remember(timestamp) { formatSearchTimestamp(timestamp) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * v2.x: 把 FTS4 snippet 的 [xxx] 标记转换为 AnnotatedString,
 * 匹配部分加半透明黄色背景(Color(0x66FFEB3B))。
 *
 * snippet() 生成片段格式:"前缀[匹配]后缀",可能含多个 [xxx] 段。
 * 解析时把 [ ] 标记剥离,在原匹配文本上应用 SpanStyle。
 */
private fun buildHighlightedSnippet(snippet: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < snippet.length) {
        val start = snippet.indexOf('[', i)
        if (start < 0) {
            // 剩余全部为普通文本
            append(snippet.substring(i))
            break
        }
        if (start > i) {
            append(snippet.substring(i, start))
        }
        val end = snippet.indexOf(']', start)
        if (end < 0) {
            // 未闭合的 '[' 原样输出,避免吞字符
            append(snippet.substring(start))
            break
        }
        val matched = snippet.substring(start + 1, end)
        withStyle(SpanStyle(background = Color(0x66FFEB3B))) {
            append(matched)
        }
        i = end + 1
    }
}

/** v2.x: 格式化时间戳为 "MM-dd HH:mm"(本地时区,列表项时间显示用)。 */
private fun formatSearchTimestamp(timestamp: Long): String {
    return java.text.SimpleDateFormat(
        MuseDateFormats.DATE_TIME_SHORT,
        java.util.Locale.getDefault(),
    ).format(java.util.Date(timestamp))
}
