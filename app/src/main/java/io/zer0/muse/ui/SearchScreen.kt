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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.semiLarge

/**
 * v0.45: 独立全局搜索页。
 *
 * 从首页右上角搜索按钮进入,右滑入场。功能:
 *  - 顶部搜索框(自动聚焦)
 *  - 无输入:大面积空白空状态 + 热门搜索建议 chip
 *  - 有输入:分"会话/消息"section(FTS5 结果)+ "设置"section(本地 hardcode 匹配)
 *
 * 数据源:
 *  - 消息搜索复用 [ChatViewModel.search](走 SessionRepository.searchMessages / FTS5)
 *  - 会话标题匹配:对 state.sessions 做内存 contains 过滤
 *  - 设置项:本地 hardcode 列表(约 15 项,匹配关键词后展示,点击跳转对应设置页)
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query = state.searchQuery
    val focusRequester = remember { FocusRequester() }

    // 自动聚焦搜索框
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 输入变化时(去抖 300ms)更新查询并触发 FTS 搜索
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(300)
            viewModel.updateSearchQuery(query)
            viewModel.search()
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
        if (query.isBlank()) {
            // 无输入:大面积空白空状态
            EmptySearchState(
                onSuggestionClick = { suggestion ->
                    viewModel.updateSearchQuery(suggestion)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            // 有输入:展示结果
            SearchResults(
                query = query,
                sessions = state.sessions,
                messageResults = state.searchResults,
                isSearching = state.isSearching,
                settingsItems = rememberSettingsIndex(),
                onOpenSession = onOpenSession,
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
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
            modifier = Modifier.padding(horizontal = 12.dp),
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
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
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
