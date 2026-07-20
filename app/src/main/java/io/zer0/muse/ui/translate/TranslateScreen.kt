package io.zer0.muse.ui.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.huge
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.97 gap8: 独立翻译页 — iOS 风格全屏页面。
 *
 * 布局:
 *  - TopAppBar:标题 + 返回按钮 + 目标语言下拉选择
 *  - 内容区(垂直滚动):
 *    - 输入卡片:OutlinedTextField(多行) + 粘贴/清空按钮
 *    - 进度指示(翻译中 LinearProgressIndicator / 空闲 HorizontalDivider)
 *    - 结果卡片:SelectionContainer 包裹 Text(可选中复制) + 复制按钮
 *  - FloatingActionButton:翻译/取消(根据 translating 状态切换)
 *
 * 参考:rikkahub TranslatorPage(Apache 2.0),但用 muse 自己的 MuseShapes / TopAppBar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(
    onBack: () -> Unit,
    viewModel: TranslateViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // v1.97: 清空历史二次确认弹窗状态
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    // 错误消息 → Toast 提示(单次消费,避免重复弹)
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            MuseToast.show(context.getString(R.string.translate_page_error_failed, msg))
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TranslateTopBar(
                state = state,
                onBack = onBack,
                onTargetLanguageChange = { viewModel.updateTargetLanguage(it) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (state.translating) {
                        viewModel.cancelTranslation()
                    } else {
                        if (state.inputText.isBlank()) {
                            MuseToast.show(context.getString(R.string.translate_page_error_empty_input))
                        } else {
                            viewModel.translate()
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MuseShapes.huge,
            ) {
                if (state.translating) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Translate,
                        contentDescription = stringResource(R.string.translate_page_translate),
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 输入区 ──
            TranslateInputCard(
                inputText = state.inputText,
                translating = state.translating,
                onInputChange = { viewModel.updateInput(it) },
                onPaste = {
                    val clipText = readClipboardText(context)
                    if (viewModel.paste(clipText)) {
                        MuseToast.show(context.getString(R.string.translate_page_pasted))
                    } else {
                        MuseToast.show(context.getString(R.string.translate_page_clipboard_empty))
                    }
                },
                onClear = { viewModel.clear() },
            )

            // ── 进度指示(翻译中显示波浪进度条,空闲时显示分隔线) ──
            Crossfade(targetState = state.translating, label = "translate_progress") { translating ->
                if (translating) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }

            // ── 结果区 ──
            TranslateResultCard(
                translatedText = state.translatedText,
                onCopy = {
                    if (state.translatedText.isNotBlank()) {
                        copyToClipboard(context, state.translatedText)
                        MuseToast.show(context.getString(R.string.translate_page_copied))
                    }
                },
            )

            // ── v1.97: 翻译历史区(内存保留最近 20 条,不持久化) ──
            TranslateHistorySection(
                history = state.history,
                onItemClick = { item ->
                    viewModel.loadHistoryItem(item)
                    MuseToast.show(context.getString(R.string.translate_page_history_loaded))
                },
                onClearClick = { showClearHistoryDialog = true },
            )
            // 底部留白(避免 FAB 遮挡)
            Spacer(Modifier.height(80.dp))
        }
    }

    // 清空历史二次确认弹窗
    if (showClearHistoryDialog) {
        MuseDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = stringResource(R.string.translate_page_history_clear_confirm),
            content = {
                Text(
                    text = stringResource(R.string.translate_page_history_clear_confirm_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.translate_page_history_clear),
            onConfirm = {
                viewModel.clearHistory()
                showClearHistoryDialog = false
                MuseToast.show(context.getString(R.string.translate_page_history_cleared))
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showClearHistoryDialog = false },
            destructive = true,
        )
    }
}

/**
 * 翻译页顶栏 — 标题 + 返回 + 目标语言下拉选择。
 *
 * 用 ExposedDropdownMenuBox 实现只读下拉框(与 rikkahub LanguageSelector 一致),
 * 点击展开菜单列出 [TranslateViewModel.TARGET_LANGUAGES] 全部语言。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslateTopBar(
    state: TranslateViewModel.State,
    onBack: () -> Unit,
    onTargetLanguageChange: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    IosTopBar(
        title = stringResource(R.string.translate_page_title),
        onBack = onBack,
        actions = {
            // 目标语言下拉选择
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            ) {
                Surface(
                    shape = MuseShapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .menuAnchor()
                        .padding(end = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.targetLanguage,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    }
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    TranslateViewModel.TARGET_LANGUAGES.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang) },
                            onClick = {
                                onTargetLanguageChange(lang)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * 输入卡片 — OutlinedTextField + 粘贴/清空按钮。
 *
 * TextField 最多 10 行(与 rikkahub 一致),placeholder 引导用户输入。
 * 翻译中禁用粘贴/清空(避免干扰进行中的任务)。
 */
@Composable
private fun TranslateInputCard(
    inputText: String,
    translating: Boolean,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.translate_page_input_placeholder)) },
                minLines = 3,
                maxLines = 10,
                enabled = !translating,
                shape = MuseShapes.medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPaste, enabled = !translating) {
                    Icon(
                        imageVector = Icons.Filled.ContentPaste,
                        contentDescription = stringResource(R.string.translate_page_paste),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClear, enabled = !translating && inputText.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.translate_page_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 结果卡片 — SelectionContainer 包裹译文(可选中复制) + 复制按钮。
 *
 * 空结果时显示 placeholder 引导文字(与 rikkahub 一致)。
 * 译文非空时才显示复制按钮。
 */
@Composable
private fun TranslateResultCard(
    translatedText: String,
    onCopy: () -> Unit,
) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.translate_page_target_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (translatedText.isNotEmpty()) {
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.translate_page_copy),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            SelectionContainer {
                Text(
                    text = translatedText.ifEmpty { stringResource(R.string.translate_page_result_placeholder) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (translatedText.isEmpty()) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

// ── 剪贴板辅助函数 ──

/** 从系统剪贴板读取纯文本(可能为空)。 */
private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/** 将文本写入系统剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
}

// ── v1.97: 翻译历史区 ──

/**
 * 翻译历史区 — 展示最近 20 条翻译记录,点击加载到输入框重新翻译或查看。
 *
 * 设计:
 *  - 顶部标题行:历史图标 + "历史记录" + 右侧"清空历史"按钮(空时不显示)
 *  - 空状态:Surface 内灰色 placeholder 文案
 *  - 历史项:Surface 卡片,展示 目标语言 chip + 相对时间 + 原文/译文(单行省略号)
 *
 * 历史仅在内存中保留(TranslateViewModel.State.history),不持久化,退出页面即清空。
 */
@Composable
private fun TranslateHistorySection(
    history: List<TranslateViewModel.TranslateHistoryItem>,
    onItemClick: (TranslateViewModel.TranslateHistoryItem) -> Unit,
    onClearClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.translate_page_history_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (history.isNotEmpty()) {
            TextButton(onClick = onClearClick) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.translate_page_history_clear),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    if (history.isEmpty()) {
        Surface(
            shape = MuseShapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.translate_page_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        history.forEach { item ->
            TranslateHistoryItemCard(item = item, onClick = { onItemClick(item) })
        }
    }
}

/**
 * 单条翻译历史卡片 — 双行(原文/译文)布局,顶部展示目标语言 chip 和相对时间。
 */
@Composable
private fun TranslateHistoryItemCard(
    item: TranslateViewModel.TranslateHistoryItem,
    onClick: () -> Unit,
) {
    val timeText = remember(item.timestamp) { formatHistoryTime(item.timestamp) }
    val sourceLabel = stringResource(R.string.translate_page_history_source_label)
    val translatedLabel = stringResource(R.string.translate_page_history_translated_label)

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 目标语言 chip
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = item.targetLanguage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                // 相对时间
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // 原文(单行省略号)
            HistoryTextLine(label = sourceLabel, text = item.sourceText)
            // 译文(单行省略号)
            HistoryTextLine(label = translatedLabel, text = item.translatedText)
        }
    }
}

/** 历史卡片内的"标签 + 内容"单行 — 标签灰色小号,内容主体色,超出省略号。 */
@Composable
private fun HistoryTextLine(label: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 格式化历史时间戳为简短相对时间文案(刚刚 / N 分钟前 / N 小时前 / MM-dd HH:mm)。
 * 与主聊天列表的时间格式风格保持一致。
 */
private fun formatHistoryTime(timestamp: Long): String {
    val delta = System.currentTimeMillis() - timestamp
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L}分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L}小时前"
        else -> SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()).format(Date(timestamp))
    }
}
