package io.zer0.muse.ui.quicknotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.quicknote.QuickNote
import io.zer0.muse.tools.quicknote.QuickNoteStore
import io.zer0.muse.ui.common.ConfirmDeleteDialog
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.137: 快速记录页面 UI/UX 升级。
 *
 * 采用 iOS 风格大标题 + 暖纸主题 + 卡片式列表:
 *  - 顶部 Large Title + 圆角搜索框
 *  - 输入区:多行文本框 + 井号标签自动识别 + 保存按钮
 *  - 历史记录:卡片式,显示摘要/标签/时间,支持置顶、复制、发送、编辑、删除
 *  - 空状态:温馨提示
 *
 * 数据仍由 [QuickNoteStore] 持久化;[onSendToNewChat] 由调用方处理跳转到新聊天会话。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickNotesScreen(
    onBack: () -> Unit,
    store: QuickNoteStore,
    onSendToNewChat: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf(store.list()) }
    var editingNote by remember { mutableStateOf<QuickNote?>(null) }
    var noteToDelete by remember { mutableStateOf<QuickNote?>(null) }
    var noteForMenu by remember { mutableStateOf<QuickNote?>(null) }
    var inputText by remember { mutableStateOf("") }

    fun refresh() {
        notes = store.list(
            keyword = query.takeIf { it.isNotBlank() },
            tag = selectedTag,
        )
    }

    fun saveInput() {
        val text = inputText.trim()
        if (text.isBlank()) return
        val tags = extractHashTags(text)
        val title = deriveTitle(text, tags)
        store.add(title, text, tags)
        inputText = ""
        refresh()
    }

    fun copyNote(note: QuickNote) {
        val text = buildString {
            if (note.title.isNotBlank()) appendLine(note.title)
            if (note.content.isNotBlank()) appendLine(note.content)
            if (note.tags.isNotEmpty()) {
                appendLine(note.tags.joinToString(" ") { "#$it" })
            }
        }.trim()
        clipboard.setText(AnnotatedString(text))
        MuseToast.show(context.getString(R.string.quick_notes_copied))
    }

    fun sendNoteToChat(note: QuickNote) {
        val text = buildString {
            if (note.title.isNotBlank()) appendLine(note.title)
            if (note.content.isNotBlank()) appendLine(note.content)
        }.trim()
        onSendToNewChat(text)
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.quick_notes_title),
                onBack = onBack,
                largeTitle = true,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MusePaddings.screen),
        ) {
            Spacer(Modifier.height(MusePaddings.contentGap))
            QuickNoteSearchField(
                value = query,
                onValueChange = {
                    query = it
                    refresh()
                },
                placeholder = stringResource(R.string.quick_notes_search),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MusePaddings.sectionGap))
            QuickNoteInputCard(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = ::saveInput,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MusePaddings.sectionGap))
            val allTags = remember(notes) {
                store.list()
                    .flatMap { it.tags }
                    .map { it.lowercase() }
                    .distinct()
                    .sorted()
            }
            if (allTags.isNotEmpty()) {
                QuickNoteTagFilterRow(
                    tags = allTags,
                    selectedTag = selectedTag,
                    onTagSelected = { tag ->
                        selectedTag = if (selectedTag == tag) null else tag
                        refresh()
                    },
                )
                Spacer(Modifier.height(MusePaddings.sectionGap))
            }
            if (notes.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Lightbulb,
                    title = if (query.isBlank()) {
                        stringResource(R.string.quick_notes_empty_title)
                    } else {
                        stringResource(R.string.quick_notes_empty_search_title)
                    },
                    subtitle = if (query.isBlank()) {
                        stringResource(R.string.quick_notes_empty_subtitle)
                    } else {
                        stringResource(R.string.quick_notes_empty_search_subtitle)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.quick_notes_history),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.quick_notes_count, notes.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.height(MusePaddings.contentGap))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    items(notes, key = { it.id }) { note ->
                        QuickNoteCard(
                            note = note,
                            onPin = {
                                store.setPinned(note.id, !note.pinned)
                                refresh()
                            },
                            onCopy = { copyNote(note) },
                            onSendToChat = { sendNoteToChat(note) },
                            onEdit = { editingNote = note },
                            onDelete = { noteToDelete = note },
                            onMore = { noteForMenu = note },
                        )
                    }
                }
            }
        }
    }

    editingNote?.let { note ->
        QuickNoteDialog(
            existing = note,
            onDismiss = { editingNote = null },
            onSave = { title, content, tags ->
                store.update(note.id, title, content, tags)
                editingNote = null
                refresh()
            },
        )
    }

    noteToDelete?.let { note ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.quick_notes_delete_title),
            itemName = note.title,
            onConfirm = {
                store.remove(note.id)
                noteToDelete = null
                refresh()
            },
            onDismiss = { noteToDelete = null },
        )
    }

    noteForMenu?.let { note ->
        QuickNoteActionMenu(
            note = note,
            onDismiss = { noteForMenu = null },
            onEdit = {
                noteForMenu = null
                editingNote = note
            },
            onCopy = {
                noteForMenu = null
                copyNote(note)
            },
            onDelete = {
                noteForMenu = null
                noteToDelete = note
            },
        )
    }
}

@Composable
private fun QuickNoteSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.quick_notes_clear_search),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        singleLine = true,
        shape = MuseShapes.pill,
        modifier = modifier,
    )
}

@Composable
private fun QuickNoteInputCard(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.quick_notes_input_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp),
                minLines = 3,
                maxLines = 6,
                shape = MuseShapes.large,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.quick_notes_tag_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                SendButton(
                    enabled = value.trim().isNotBlank(),
                    onClick = onSend,
                )
            }
        }
    }
}

@Composable
private fun SendButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.outline
        },
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = stringResource(R.string.quick_notes_save),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickNoteTagFilterRow(
    tags: List<String>,
    selectedTag: String?,
    onTagSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        tags.forEach { tag ->
            val selected = tag == selectedTag
            Surface(
                onClick = { onTagSelected(tag) },
                shape = MuseShapes.pill,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
            ) {
                Text(
                    text = "#$tag",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(
                        horizontal = MusePaddings.iconPadding,
                        vertical = MusePaddings.labelVerticalGap,
                    ),
                )
            }
        }
    }
}

@Composable
private fun QuickNoteCard(
    note: QuickNote,
    onPin: () -> Unit,
    onCopy: () -> Unit,
    onSendToChat: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Surface(
        shape = MuseShapes.extraLarge,
        color = if (note.pinned) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
        ) {
            Text(
                text = note.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.content.isNotBlank()) {
                Spacer(Modifier.height(MusePaddings.tightGap))
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.tags.isNotEmpty()) {
                Spacer(Modifier.height(MusePaddings.labelVerticalGap))
                Text(
                    text = note.tags.joinToString("  #", prefix = "#"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(MusePaddings.auxGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fmt.format(Date(note.updatedAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onPin,
                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = stringResource(
                            if (note.pinned) R.string.quick_notes_unpin else R.string.quick_notes_pin
                        ),
                        tint = if (note.pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.quick_notes_copy),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                IconButton(
                    onClick = onSendToChat,
                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = stringResource(R.string.quick_notes_send_to_chat),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                Box {
                    IconButton(
                        onClick = onMore,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.quick_notes_more),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickNoteActionMenu(
    note: QuickNote,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = note.title.takeIf { it.isNotBlank() },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                QuickNoteActionRow(
                    icon = Icons.Default.Edit,
                    label = stringResource(R.string.quick_notes_edit),
                    onClick = onEdit,
                )
                QuickNoteActionRow(
                    icon = Icons.Default.ContentCopy,
                    label = stringResource(R.string.quick_notes_copy),
                    onClick = onCopy,
                )
                QuickNoteActionRow(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.quick_notes_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
private fun QuickNoteActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MusePaddings.iconPadding,
                    vertical = MusePaddings.inputPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickNoteDialog(
    existing: QuickNote?,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(",") ?: "") }
    val errorRequired = stringResource(R.string.quick_notes_error_required)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (existing == null) R.string.quick_notes_new else R.string.quick_notes_edit_title
        ),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; errorMessage = null },
                    label = { Text(stringResource(R.string.quick_notes_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.large,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.quick_notes_content_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    shape = MuseShapes.large,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.quick_notes_tags_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.large,
                )
                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.quick_notes_save),
        onConfirm = {
            if (title.isBlank()) {
                errorMessage = errorRequired
                return@MuseDialog
            }
            onSave(
                title.trim(),
                content.trim(),
                tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
            )
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/** 从文本中提取 #标签,返回不含 # 的标签列表。 */
private fun extractHashTags(text: String): List<String> {
    val regex = Regex("#([^#\\s,，.。!！?？\\n]+)")
    return regex.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

/**
 * 从内容推导标题。
 * 优先取第一行非空文本;若第一行过短或只有标签,则取前 40 个字符。
 */
private fun deriveTitle(text: String, tags: List<String>): String {
    val stripped = text.replace(Regex("#[^#\\s,，.。!！?？\\n]+"), "").trim()
    val firstLine = stripped.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    return when {
        firstLine.length >= 3 -> firstLine.take(40)
        stripped.isNotBlank() -> stripped.take(40)
        tags.isNotEmpty() -> tags.first().take(40)
        else -> text.trim().take(40)
    }
}
