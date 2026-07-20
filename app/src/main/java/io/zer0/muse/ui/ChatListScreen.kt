package io.zer0.muse.ui

import io.zer0.muse.ui.common.EmotionalEmptyState
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.MuseToast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.FolderEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.mega
import io.zer0.muse.ui.theme.pill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Filter { ALL, PINNED, ARCHIVED }

@Composable
fun ChatListScreen(
    sessions: List<SessionEntity>,
    folders: List<FolderEntity>,
    currentSessionId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (SessionEntity) -> Unit,
    /** v1.48: 重命名(带新名字),修复旧实现传 session.title 导致重命名失效的 bug。 */
    onRenameTo: (SessionEntity, String) -> Unit = { s, _ -> onRename(s) },
    onTogglePinned: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onToggleFolderExpanded: (String, Boolean) -> Unit,
    assistants: List<AssistantEntity> = emptyList(),
    currentAssistant: AssistantEntity? = null,
    /** v0.45: 已归档会话列表(归档 FilterCard 用)。 */
    archivedSessions: List<SessionEntity> = emptyList(),
    /** v0.45: 归档会话(主列表项长按菜单用)。 */
    onArchive: (String) -> Unit = {},
    /** v0.45: 取消归档(归档列表项长按菜单用)。 */
    onUnarchive: (String) -> Unit = {},
    onOpenScheduledTasks: () -> Unit = {},
                onOpenQuickNotes: () -> Unit = {},
                /** v2.0: 打开最近删除页。 */
    onOpenRecentlyDeleted: () -> Unit = {},
    /** Phase 1 WS5: 打开助手/角色管理页面(情感空状态 CTA 用)。 */
    onOpenAssistants: () -> Unit = {},
    /** v1.72: 会话列表首次加载标志(避免闪空状态) */
    isSessionsLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(Filter.ALL) }
    // v1.69: 文件夹分组 UI — 新建文件夹对话框状态
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = MusePaddings.screen),
        ) {
            // 两个大方块入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                BigEntryCard(
                    title = stringResource(R.string.chat_list_scheduled_tasks),
                    subtitle = stringResource(R.string.chat_list_scheduled_subtitle),
                    icon = Icons.Outlined.Schedule,
                    onClick = onOpenScheduledTasks,
                    modifier = Modifier.weight(1f),
                )
                BigEntryCard(
                    title = stringResource(R.string.chat_list_quick_notes),
                    subtitle = stringResource(R.string.chat_list_quick_notes_subtitle),
                    icon = Icons.Default.Edit,
                    onClick = onOpenQuickNotes,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(MusePaddings.sectionGap))

            // 分类卡片行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                FilterCard(
                    label = stringResource(R.string.chat_list_filter_all),
                    icon = Icons.Default.AccessTime,
                    selected = filter == Filter.ALL,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = Filter.ALL },
                )
                FilterCard(
                    label = stringResource(R.string.chat_list_filter_pinned),
                    icon = Icons.Default.PushPin,
                    selected = filter == Filter.PINNED,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = Filter.PINNED },
                )
                FilterCard(
                    label = stringResource(R.string.chat_list_filter_archived),
                    icon = Icons.Outlined.Archive,
                    selected = filter == Filter.ARCHIVED,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = Filter.ARCHIVED },
                )
            }

            // v2.0: 最近删除入口
            TextButton(
                onClick = onOpenRecentlyDeleted,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.recently_deleted_entry),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.recently_deleted_entry_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 会话列表或空状态
            // v0.36 性能优化:缓存过滤+排序结果,避免每次重组都重新计算。
            val filteredSessions = remember(filter, sessions, archivedSessions) {
                when (filter) {
                    Filter.ALL -> sessions.sortedByDescending { it.updatedAt }
                    Filter.PINNED -> sessions.filter { it.pinned }.sortedByDescending { it.updatedAt }
                    // v1.67: 归档列表也按 pinned 优先排序,与主列表一致
                    Filter.ARCHIVED -> archivedSessions.sortedWith(
                        compareByDescending<SessionEntity> { it.pinned }.thenByDescending { it.updatedAt }
                    )
                }
            }

            // v1.72: 首次加载时显示 loading,避免 DB emit 前闪"还没有任务"空状态
            if (isSessionsLoading) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredSessions.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (filter == Filter.ALL) {
                        // Phase 1 WS5: emotional empty state for main chat list
                        EmotionalEmptyState(
                            onChatWithMuse = onCreate,
                            onMeetCharacters = onOpenAssistants,
                        )
                    } else {
                        EmptyState(
                            icon = if (filter == Filter.ARCHIVED) Icons.Outlined.Archive
                            else Icons.Outlined.ChatBubbleOutline,
                            title = when (filter) {
                                Filter.PINNED -> stringResource(R.string.chat_list_empty_pinned)
                                Filter.ARCHIVED -> stringResource(R.string.chat_list_empty_archived)
                                else -> ""
                            },
                            subtitle = when (filter) {
                                Filter.PINNED -> stringResource(R.string.chat_list_empty_pinned_sub)
                                Filter.ARCHIVED -> stringResource(R.string.chat_list_empty_archived_sub)
                                else -> ""
                            },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    when (filter) {
                        Filter.ALL -> {
                            // v1.69: 文件夹分组 UI 接入
                            // 布局: 置顶段(跨文件夹) → 各文件夹段(FolderHeaderRow + 会话) → 未分组段
                            val pinned = filteredSessions.filter { it.pinned }
                            val nonPinned = filteredSessions.filterNot { it.pinned }
                            val byFolder = nonPinned.groupBy { it.folderId }
                            val ungrouped = byFolder[null] ?: emptyList()
                            val folderSessions = folders.associateWith { f -> byFolder[f.id] ?: emptyList() }

                            // 置顶段
                            if (pinned.isNotEmpty()) {
                                item(key = "section_pinned") {
                                    SectionTitle(stringResource(R.string.chat_list_filter_pinned))
                                }
                                items(pinned, key = { "pinned_${it.id}" }) { session ->
                                    SessionRowItem(
                                        session = session,
                                        currentSessionId = currentSessionId,
                                        folders = folders,
                                        onSelect = onSelect,
                                        onDelete = onDelete,
                                        onRename = onRename,
                                        onRenameTo = onRenameTo,
                                        onTogglePinned = onTogglePinned,
                                        onArchive = onArchive,
                                        onMoveSessionToFolder = onMoveSessionToFolder,
                                    )
                                }
                            }

                            // 各文件夹段
                            folders.forEach { folder ->
                                val sessionsInFolder = folderSessions[folder] ?: emptyList()
                                item(key = "folder_header_${folder.id}") {
                                    FolderHeaderRow(
                                        folder = folder,
                                        sessionCount = sessionsInFolder.size,
                                        onToggleExpanded = { onToggleFolderExpanded(folder.id, !folder.expanded) },
                                        onRename = { newName -> onRenameFolder(folder.id, newName) },
                                        onDelete = { onDeleteFolder(folder.id) },
                                    )
                                }
                                if (folder.expanded && sessionsInFolder.isNotEmpty()) {
                                    items(sessionsInFolder, key = { "folder_${folder.id}_${it.id}" }) { session ->
                                        SessionRowItem(
                                            session = session,
                                            currentSessionId = currentSessionId,
                                            folders = folders,
                                            onSelect = onSelect,
                                            onDelete = onDelete,
                                            onRename = onRename,
                                            onRenameTo = onRenameTo,
                                            onTogglePinned = onTogglePinned,
                                            onArchive = onArchive,
                                            onMoveSessionToFolder = onMoveSessionToFolder,
                                        )
                                    }
                                }
                            }

                            // 未分组段 + 新建文件夹入口
                            if (pinned.isNotEmpty() || folders.isNotEmpty()) {
                                item(key = "section_ungrouped") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            SectionTitle(stringResource(R.string.chat_list_section_ungrouped))
                                        }
                                        TextButton(
                                            onClick = {
                                                newFolderName = ""
                                                showCreateFolderDialog = true
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.CreateNewFolder,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.size(4.dp))
                                            Text(stringResource(R.string.chat_list_new_folder), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            } else if (ungrouped.isNotEmpty()) {
                                item(key = "section_ungrouped") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            SectionTitle(stringResource(R.string.chat_list_section_recent))
                                        }
                                        TextButton(
                                            onClick = {
                                                newFolderName = ""
                                                showCreateFolderDialog = true
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.CreateNewFolder,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.size(4.dp))
                                            Text(stringResource(R.string.chat_list_new_folder), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                            items(ungrouped, key = { "ungrouped_${it.id}" }) { session ->
                                SessionRowItem(
                                    session = session,
                                    currentSessionId = currentSessionId,
                                    folders = folders,
                                    onSelect = onSelect,
                                    onDelete = onDelete,
                                    onRename = onRename,
                                    onRenameTo = onRenameTo,
                                    onTogglePinned = onTogglePinned,
                                    onArchive = onArchive,
                                    onMoveSessionToFolder = onMoveSessionToFolder,
                                )
                            }
                            // M-CL1: 已删除 empty_create_folder 死代码项 — 该分支仅在 filteredSessions.isNotEmpty() 时进入,
                            //        内层判断 filteredSessions.isEmpty() 永远为 false,不可达。"新建文件夹"入口已移到上方 EmptyState 中
                        }
                        Filter.PINNED -> {
                            items(filteredSessions, key = { "pinned_list_${it.id}" }) { session ->
                                val onSelectSession = remember(session.id) { { onSelect(session.id) } }
                                val onDeleteSession = remember(session.id) { { onDelete(session.id) } }
                                val onRenameSession = remember(session.id) { { onRename(session) } }
                                val onRenameToSession = remember(session.id) { { newName: String -> onRenameTo(session, newName) } }
                                val onTogglePinnedSession = remember(session.id) { { onTogglePinned(session.id) } }
                                val onArchiveSession = remember(session.id) { { onArchive(session.id) } }
                                val onMoveToFolderSession = remember(session.id) { { folderId: String? -> onMoveSessionToFolder(session.id, folderId) } }
                                ChatListItem(
                                    modifier = Modifier.animateItem(),
                                    session = session,
                                    isActive = session.id == currentSessionId,
                                    folders = folders,
                                    onSelect = onSelectSession,
                                    onDelete = onDeleteSession,
                                    onRename = onRenameSession,
                                    onRenameTo = onRenameToSession,
                                    onTogglePinned = onTogglePinnedSession,
                                    onMoveToFolder = onMoveToFolderSession,
                                    onArchive = onArchiveSession,
                                )
                            }
                        }
                        Filter.ARCHIVED -> {
                            items(filteredSessions, key = { "archived_${it.id}" }) { session ->
                                val onSelectSession = remember(session.id) { { onSelect(session.id) } }
                                val onDeleteSession = remember(session.id) { { onDelete(session.id) } }
                                val onRenameSession = remember(session.id) { { onRename(session) } }
                                val onRenameToSession = remember(session.id) { { newName: String -> onRenameTo(session, newName) } }
                                val onTogglePinnedSession = remember(session.id) { { onTogglePinned(session.id) } }
                                val onUnarchiveSession = remember(session.id) { { onUnarchive(session.id) } }
                                val onMoveToFolderSession = remember(session.id) { { folderId: String? -> onMoveSessionToFolder(session.id, folderId) } }
                                ChatListItem(
                                    modifier = Modifier.animateItem(),
                                    session = session,
                                    isActive = session.id == currentSessionId,
                                    folders = folders,
                                    onSelect = onSelectSession,
                                    onDelete = onDeleteSession,
                                    onRename = onRenameSession,
                                    onRenameTo = onRenameToSession,
                                    onTogglePinned = onTogglePinnedSession,
                                    onMoveToFolder = onMoveToFolderSession,
                                    onUnarchive = onUnarchiveSession,
                                )
                            }
                        }
                    }
                }
            }

            // 底部按钮
            // v1.131: 用 Box 包裹 + fillMaxWidth(0.55f) + height(48dp),水平居中,宽度约为之前一半多一点
            // v1.132: Text 加 fillMaxWidth() + contentPadding=0 让文字真正水平居中(原 Button 默认 Row Start 导致偏左)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Button(
                    onClick = onCreate,
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(48.dp),
                    shape = MuseShapes.mega,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.chat_list_new_task),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // v1.69: 新建文件夹对话框
    if (showCreateFolderDialog) {
        MuseDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = stringResource(R.string.chat_list_new_folder),
            content = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text(stringResource(R.string.chat_list_folder_name_placeholder)) },
                    singleLine = true,
                    shape = MuseShapes.medium,
                )
            },
            confirmText = stringResource(R.string.chat_list_create),
            onConfirm = {
                if (newFolderName.isNotBlank()) {
                    onCreateFolder(newFolderName.trim())
                }
                showCreateFolderDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showCreateFolderDialog = false },
        )
    }
}

/**
 * v1.69: 会话行渲染辅助函数,避免文件夹分组场景下重复 lambda 缓存代码。
 * 在 LazyItemScope 上下文中调用,以支持 animateItem()。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.SessionRowItem(
    session: SessionEntity,
    currentSessionId: String?,
    folders: List<FolderEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (SessionEntity) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onArchive: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
) {
    val onSelectSession = remember(session.id) { { onSelect(session.id) } }
    val onDeleteSession = remember(session.id) { { onDelete(session.id) } }
    val onRenameSession = remember(session.id) { { onRename(session) } }
    val onRenameToSession = remember(session.id) { { newName: String -> onRenameTo(session, newName) } }
    val onTogglePinnedSession = remember(session.id) { { onTogglePinned(session.id) } }
    val onArchiveSession = remember(session.id) { { onArchive(session.id) } }
    val onMoveToFolderSession = remember(session.id) { { folderId: String? -> onMoveSessionToFolder(session.id, folderId) } }
    ChatListItem(
        modifier = Modifier.animateItem(),
        session = session,
        isActive = session.id == currentSessionId,
        folders = folders,
        onSelect = onSelectSession,
        onDelete = onDeleteSession,
        onRename = onRenameSession,
        onRenameTo = onRenameToSession,
        onTogglePinned = onTogglePinnedSession,
        onMoveToFolder = onMoveToFolderSession,
        onArchive = onArchiveSession,
    )
}

@Composable
private fun FilterCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "filter_card_bg",
    )
    val contentColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "filter_card_content",
    )
    Surface(
        onClick = onClick,
        shape = MuseShapes.medium,
        color = bgColor,
        modifier = modifier.aspectRatio(1f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = contentColor,
            )
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

/**
 * 大方块入口卡片。
 *
 * 设计:
 *  - 较大的圆角卡片(高度自适应,padding 充裕)
 *  - 图标居中置顶 + 标题 + 副标题(全部居中对齐)
 *  - 点击涟漪效果
 *  - 背景色: surfaceVariant 半透明(暖纸风格)
 */
@Composable
private fun BigEntryCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 线条风格图标
            io.zer0.muse.ui.common.IosSettingsIcon(
                icon = icon,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListItem(
    session: SessionEntity,
    isActive: Boolean,
    folders: List<FolderEntity>,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    /** v1.48: 重命名回调改为带新名字,修复重命名失效 bug(旧实现传原 session.title)。 */
    onRenameTo: (String) -> Unit = { onRename() },
    onTogglePinned: () -> Unit,
    onMoveToFolder: (String?) -> Unit,
    /** v0.45: 归档当前会话(主列表项菜单,非空才显示)。 */
    onArchive: (() -> Unit)? = null,
    /** v0.45: 取消归档(归档列表项菜单,非空才显示)。 */
    onUnarchive: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(session.title) }
    // v1.48: 删除前二次确认(滑动删除与菜单删除共用),避免误滑/误点导致会话不可恢复丢失
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 功能3: 归档前二次确认
    var showArchiveConfirm by remember { mutableStateOf(false) }

    // 滑动删除:v1.48 改为滑出阈值后弹确认框,不再立即删除
    // 功能3: 左滑删除/右滑归档(或取消归档)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                showDeleteConfirm = true
                false  // 不立即消失,等用户确认
            } else if (value == SwipeToDismissBoxValue.StartToEnd) {
                if (onArchive != null) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showArchiveConfirm = true
                } else if (onUnarchive != null) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onUnarchive()
                }
                false
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MuseShapes.medium),
            ) {
                // 右侧:删除(滑出 EndToStart 时显示)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                // 左侧:归档/取消归档(滑出 StartToEnd 时显示,覆盖在删除背景上方)
                if (onArchive != null || onUnarchive != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Icon(
                            imageVector = if (onArchive != null) Icons.Outlined.Archive else Icons.Default.Unarchive,
                            contentDescription = if (onArchive != null) stringResource(R.string.chat_list_filter_archived) else stringResource(R.string.chat_list_unarchive),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        },
        enableDismissFromStartToEnd = onArchive != null || onUnarchive != null,
    ) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        shape = MuseShapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showMenu = true
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.pinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        text = session.title.ifBlank { stringResource(R.string.chat_new_session) },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // v5: 分叉子会话计数标签
                    if (session.childCount > 0) {
                        Spacer(Modifier.size(4.dp))
                        Surface(
                            shape = MuseShapes.pill,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = stringResource(R.string.chat_list_child_count, session.childCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                // v5: 分叉来源提示
                if (session.parentSessionId != null) {
                    Text(
                        text = stringResource(R.string.chat_list_forked_from, session.parentSessionId.take(8)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (session.lastMessagePreview.isNotBlank()) {
                    Text(
                        text = session.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // Phase 1 WS4: relationship duration
                val days = remember(session.createdAt) {
                    (System.currentTimeMillis() - session.createdAt) / (24 * 60 * 60 * 1000)
                }
                Text(
                    text = if (days <= 0L) stringResource(R.string.chat_list_just_met_today)
                    else stringResource(R.string.chat_list_days_together, days.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = formatTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    }

    if (showMenu) {
        MuseDialog(
            onDismissRequest = { showMenu = false },
            title = session.title.ifBlank { stringResource(R.string.chat_new_session) },
            content = {
                Column {
                    ActionSheetItem(
                        icon = Icons.Default.PushPin,
                        text = if (session.pinned) stringResource(R.string.chat_list_unpin) else stringResource(R.string.chat_list_filter_pinned),
                        contentDescription = if (session.pinned) stringResource(R.string.chat_list_unpin) else stringResource(R.string.chat_list_filter_pinned),
                        onClick = {
                            showMenu = false
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTogglePinned()
                        },
                    )
                    if (folders.isNotEmpty()) {
                        ActionSheetItem(
                            icon = Icons.AutoMirrored.Filled.DriveFileMove,
                            text = stringResource(R.string.chat_list_move_ungrouped),
                            contentDescription = stringResource(R.string.chat_list_move_ungrouped),
                            onClick = {
                                showMenu = false
                                onMoveToFolder(null)
                            },
                        )
                        folders.forEach { folder ->
                            ActionSheetItem(
                                icon = Icons.AutoMirrored.Filled.DriveFileMove,
                                text = stringResource(R.string.chat_list_move_to, folder.name),
                                contentDescription = stringResource(R.string.chat_list_move_to_cd, folder.name),
                                onClick = {
                                    showMenu = false
                                    onMoveToFolder(folder.id)
                                },
                            )
                        }
                    }
                    ActionSheetItem(
                        icon = Icons.Default.Edit,
                        text = stringResource(R.string.chat_list_rename),
                        contentDescription = stringResource(R.string.chat_list_rename),
                        onClick = {
                            showMenu = false
                            renameText = session.title
                            showRenameDialog = true
                        },
                    )
                    // v0.45: 归档 / 取消归档
                    if (onArchive != null) {
                        ActionSheetItem(
                            icon = Icons.Outlined.Archive,
                            text = stringResource(R.string.chat_list_filter_archived),
                            contentDescription = stringResource(R.string.chat_list_filter_archived),
                            onClick = {
                                showMenu = false
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onArchive()
                            },
                        )
                    }
                    if (onUnarchive != null) {
                        ActionSheetItem(
                            icon = Icons.Default.Unarchive,
                            text = stringResource(R.string.chat_list_unarchive),
                            contentDescription = stringResource(R.string.chat_list_unarchive),
                            onClick = {
                                showMenu = false
                                onUnarchive()
                            },
                        )
                    }
                    ActionSheetItem(
                        icon = Icons.Default.Delete,
                        text = stringResource(R.string.action_delete),
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            showMenu = false
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showDeleteConfirm = true
                        },
                    )
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showMenu = false },
        )
    }

    if (showRenameDialog) {
        MuseDialog(
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.chat_list_rename_session_title),
            content = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = MuseShapes.medium,
                )
            },
            confirmText = stringResource(R.string.chat_list_confirm),
            onConfirm = {
                if (renameText.isNotBlank()) {
                    onRenameTo(renameText.trim())
                }
                showRenameDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showRenameDialog = false },
        )
    }

    // v1.48: 删除会话二次确认
    if (showDeleteConfirm) {
        MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.chat_list_delete_session_title),
            content = { Text(stringResource(R.string.chat_list_delete_session_confirm, session.title)) },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
                MuseToast.show(context.getString(R.string.chat_list_deleted_toast))
            },
            destructive = true,
        )
    }

    // 功能3: 归档确认对话框
    if (showArchiveConfirm) {
        MuseDialog(
            onDismissRequest = { showArchiveConfirm = false },
            title = stringResource(R.string.chat_list_archive_title),
            content = { Text(stringResource(R.string.chat_list_archive_confirm, session.title)) },
            confirmText = stringResource(R.string.chat_list_archive_confirm_button),
            onConfirm = {
                showArchiveConfirm = false
                onArchive?.invoke()
            },
        )
    }
}

/**
 * iOS 风格 ActionSheet 行项 — 图标 + 文字,用于 ChatListItem 长按菜单。
 */
@Composable
private fun ActionSheetItem(
    icon: ImageVector,
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeaderRow(
    folder: FolderEntity,
    sessionCount: Int,
    onToggleExpanded: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(folder.name) }
    // v1.48: 删除文件夹二次确认,避免误删导致该文件夹下所有会话丢失
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = MuseShapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onToggleExpanded,
                onLongClick = { showMenu = true },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$sessionCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    if (showMenu) {
        MuseDialog(
            onDismissRequest = { showMenu = false },
            title = folder.name,
            content = {
                Column {
                    TextButton(onClick = {
                        showMenu = false
                        renameText = folder.name
                        showRenameDialog = true
                    }) {
                        Text(stringResource(R.string.chat_list_rename))
                    }
                    TextButton(onClick = {
                        showMenu = false
                        showDeleteConfirm = true
                    }) {
                        Text(stringResource(R.string.chat_list_delete_folder_title), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showMenu = false },
        )
    }

    if (showRenameDialog) {
        MuseDialog(
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.chat_list_rename_folder_title),
            content = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = MuseShapes.medium,
                )
            },
            confirmText = stringResource(R.string.chat_list_confirm),
            onConfirm = {
                if (renameText.isNotBlank()) onRename(renameText.trim())
                showRenameDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showRenameDialog = false },
        )
    }

    // v1.48: 删除文件夹二次确认
    if (showDeleteConfirm) {
        MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.chat_list_delete_folder_title),
            content = { Text(stringResource(R.string.chat_list_delete_folder_confirm, folder.name)) },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            destructive = true,
        )
    }
}

// H-CL1: SimpleDateFormat 提为文件级 lazy val 复用,避免每次调用都新建(与 MessageBubble.kt 的 sdf24Hour 模式一致)
private val chatListSdf by lazy {
    SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault())
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return chatListSdf.format(Date(timestamp))
}
