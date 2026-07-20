package io.zer0.muse.ui.quicknotes

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.quicknote.QuickNote
import io.zer0.muse.tools.quicknote.QuickNoteStore
import io.zer0.muse.ui.common.ConfirmDeleteDialog
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.mega
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.136: 快速记录页面。
 *
 * 提供列表、搜索、新建、编辑、删除、置顶功能,数据由 [QuickNoteStore] 持久化。
 */
@Composable
fun QuickNotesScreen(
    onBack: () -> Unit,
    store: QuickNoteStore,
) {
    var query by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(store.list()) }
    var editingNote by remember { mutableStateOf<QuickNote?>(null) }
    var noteToDelete by remember { mutableStateOf<QuickNote?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    fun refresh() {
        notes = store.list(keyword = query.takeIf { it.isNotBlank() })
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.quick_notes_title),
                onBack = onBack,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                shape = MuseShapes.mega,
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.quick_notes_new))
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MusePaddings.screen),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    refresh()
                },
                label = { Text(stringResource(R.string.quick_notes_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
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
                        onEdit = { editingNote = note },
                        onDelete = { noteToDelete = note },
                    )
                }
            }
        }
    }

    if (showCreate) {
        QuickNoteDialog(
            existing = null,
            onDismiss = { showCreate = false },
            onSave = { title, content, tags ->
                store.add(title, content, tags)
                showCreate = false
                refresh()
            },
        )
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
}

@Composable
private fun QuickNoteCard(
    note: QuickNote,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Surface(
        shape = MuseShapes.medium,
        color = if (note.pinned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onPin, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = stringResource(R.string.quick_notes_pin),
                        tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Edit, stringResource(R.string.quick_notes_edit), modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Delete, stringResource(R.string.quick_notes_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
            if (note.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = note.tags.joinToString("  #", prefix = "#"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = fmt.format(Date(note.updatedAtMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
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
        title = stringResource(if (existing == null) R.string.quick_notes_new else R.string.quick_notes_edit_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; errorMessage = null },
                    label = { Text(stringResource(R.string.quick_notes_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.quick_notes_content_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.quick_notes_tags_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmText = stringResource(R.string.quick_notes_save),
        onConfirm = {
            if (title.isBlank()) {
                errorMessage = errorRequired
                return@MuseDialog
            }
            onSave(title.trim(), content.trim(), tags.split(",").map { it.trim() }.filter { it.isNotBlank() })
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}
