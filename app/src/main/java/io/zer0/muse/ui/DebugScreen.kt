package io.zer0.muse.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.zer0.muse.debug.DebugLogEntry
import io.zer0.muse.debug.DebugLogStore
import io.zer0.muse.ui.common.IosDropdown
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志页 — 参考 rikkahub LogPage / kelivo log_viewer_page 设计。
 *
 * 功能:
 *  - 顶部 IosTopBar 标题"调试日志" + 返回按钮 + 操作区(暂停/继续、导出、清空)
 *  - 过滤器行:等级下拉(ALL/ERROR/WARN/INFO/DEBUG) + Tag 搜索 + 关键字搜索
 *  - 主体 LazyColumn 展示日志,每条可展开查看完整消息 + stack trace
 *  - 自动滚动到底部(最新日志);新日志到达时若用户在底部则继续跟随,
 *    否则停在原位置;暂停按钮可冻结滚动
 *
 * 数据源:[DebugLogStore.getAll] 每 500ms 轮询一次。日志由 [Logger.sink] 推送。
 *
 * @param onBack 返回回调(由 NavHost 注入)
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // ── 过滤器状态 ───────────────────────────────────────────────────────
    // 等级过滤:ALL 表示不过滤;其余值与 DebugLogStore.LEVELS 对齐
    var levelFilter by remember { mutableStateOf("ALL") }
    var tagQuery by remember { mutableStateOf("") }
    var keywordQuery by remember { mutableStateOf("") }

    // ── 滚动状态 ───────────────────────────────────────────────────────
    // paused:用户主动暂停跟随;atBottom:用户当前是否在底部(用于判断是否自动跟随)
    var paused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            info.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    // ── 日志数据(轮询 DebugLogStore) ──────────────────────────────────
    var allLogs by remember { mutableStateOf<List<DebugLogEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        // 首次立即拉一次,避免空白闪烁
        allLogs = DebugLogStore.getAll()
        // 之后每 500ms 轮询;Logger 是高频路径,500ms 在体验和性能间取平衡
        while (true) {
            delay(500)
            allLogs = DebugLogStore.getAll()
        }
    }

    // 应用过滤器(在 Composable 侧做,避免 DebugLogStore 维护过滤状态)
    val filteredLogs by remember(levelFilter, tagQuery, keywordQuery, allLogs) {
        derivedStateOf {
            val tagTrim = tagQuery.trim()
            val kwTrim = keywordQuery.trim()
            allLogs.asSequence()
                .filter { levelFilter == "ALL" || it.level == levelFilter }
                .filter { tagTrim.isEmpty() || it.tag.contains(tagTrim, ignoreCase = true) }
                .filter {
                    kwTrim.isEmpty() ||
                        it.message.contains(kwTrim, ignoreCase = true) ||
                        (it.throwable?.contains(kwTrim, ignoreCase = true) == true)
                }
                .toList()
        }
    }

    // ── 自动滚动逻辑 ───────────────────────────────────────────────────
    // filteredLogs 变化时:若未暂停且用户在底部,滚动到最后一条
    LaunchedEffect(filteredLogs.size) {
        if (!paused && atBottom && filteredLogs.isNotEmpty()) {
            // 用 scrollToItem 而非 animateScrollToItem,新日志高频到达时动画会卡顿
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }
    // 首次进入:滚动到底部展示最新日志
    LaunchedEffect(Unit) {
        if (allLogs.isNotEmpty()) {
            listState.scrollToItem(allLogs.lastIndex)
        }
    }
    // 监听 atBottom 变化:用户手动滚到底部时自动恢复跟随(等同取消暂停)
    LaunchedEffect(Unit) {
        snapshotFlow { atBottom }.collect { bottom ->
            if (bottom && paused) paused = false
        }
    }

    // ── 清空确认弹窗 ───────────────────────────────────────────────────
    var showClearDialog by remember { mutableStateOf(false) }
    if (showClearDialog) {
        MuseDialog(
            onDismissRequest = { showClearDialog = false },
            title = "清空调试日志",
            content = { Text("确认清空全部 ${DebugLogStore.size()} 条日志?此操作不可撤销。") },
            confirmText = "清空",
            onConfirm = {
                DebugLogStore.clear()
                allLogs = emptyList()
                showClearDialog = false
                MuseToast.show("已清空")
            },
            onDismiss = { showClearDialog = false },
        )
    }

    // ── 展开状态:记录当前展开的 entry(用 timestamp+tag+message 作 key,简单近似唯一) ──
    var expandedKey by remember { mutableStateOf<String?>(null) }

    ScaffoldLayout(
        levelFilter = levelFilter,
        onLevelChange = { levelFilter = it },
        tagQuery = tagQuery,
        onTagChange = { tagQuery = it },
        keywordQuery = keywordQuery,
        onKeywordChange = { keywordQuery = it },
        paused = paused,
        onTogglePause = { paused = !paused },
        onClear = { showClearDialog = true },
        onExport = {
            val file = DebugLogStore.exportToFile()
            if (file == null) {
                MuseToast.show("导出失败:暂无日志或目录未初始化")
            } else {
                val uri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                }.getOrNull()
                if (uri == null) {
                    MuseToast.show("导出失败:无法获取文件 URI")
                } else {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享调试日志"))
                }
            }
        },
        logs = filteredLogs,
        listState = listState,
        expandedKey = expandedKey,
        onToggleExpand = { key -> expandedKey = if (expandedKey == key) null else key },
        onBack = onBack,
    )
}

/**
 * Scaffold 包装 — 把状态从主 Composable 抽离,使布局可预览/可测试。
 *
 * 单独 private 是因为外部无需直接调用。
 */
@Composable
private fun ScaffoldLayout(
    levelFilter: String,
    onLevelChange: (String) -> Unit,
    tagQuery: String,
    onTagChange: (String) -> Unit,
    keywordQuery: String,
    onKeywordChange: (String) -> Unit,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    logs: List<DebugLogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    expandedKey: String?,
    onToggleExpand: (String) -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            IosTopBar(
                title = "调试日志",
                onBack = onBack,
                actions = {
                    // 暂停/继续跟随按钮
                    IconButton(onClick = onTogglePause) {
                        Icon(
                            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (paused) "继续跟随" else "暂停跟随",
                            tint = if (paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // 导出按钮
                    IconButton(onClick = onExport) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "导出",
                        )
                    }
                    // 清空按钮
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "清空",
                        )
                    }
                },
            )

            // ── 过滤器行 ───────────────────────────────────────────────
            FilterRow(
                levelFilter = levelFilter,
                onLevelChange = onLevelChange,
                tagQuery = tagQuery,
                onTagChange = onTagChange,
                keywordQuery = keywordQuery,
                onKeywordChange = onKeywordChange,
            )

            // ── 日志计数 ───────────────────────────────────────────────
            Text(
                text = "共 ${logs.size} 条" + if (paused) " · 已暂停跟随" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
            )

            // ── 日志列表 ───────────────────────────────────────────────
            if (logs.isEmpty()) {
                EmptyLogs()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = MusePaddings.screen,
                        vertical = MusePaddings.contentGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    items(
                        items = logs,
                        // 用 index+timestamp+tag 组合 key,避免相同内容条目被复用导致展开状态错乱
                        key = { entry -> "${entry.timestamp}_${entry.tag}_${entry.message.hashCode()}" },
                    ) { entry ->
                        val entryKey = "${entry.timestamp}_${entry.tag}_${entry.message.hashCode()}"
                        LogEntryItem(
                            entry = entry,
                            expanded = expandedKey == entryKey,
                            onClick = { onToggleExpand(entryKey) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 过滤器行:等级下拉 + Tag 搜索 + 关键字搜索(横向滚动避免窄屏挤压)。
 */
@Composable
private fun FilterRow(
    levelFilter: String,
    onLevelChange: (String) -> Unit,
    tagQuery: String,
    onTagChange: (String) -> Unit,
    keywordQuery: String,
    onKeywordChange: (String) -> Unit,
) {
    // ALL + 全部等级;IosDropdown 期望 List<Pair<value, displayText>>
    val levelOptions = buildList {
        add("ALL" to "ALL")
        DebugLogStore.LEVELS.forEach { add(it to it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 等级下拉(固定宽度,避免横向滚动时占位过多)
        IosDropdown(
            value = levelFilter,
            onValueChange = onLevelChange,
            label = "等级",
            options = levelOptions,
            modifier = Modifier.width(120.dp),
        )
        OutlinedTextField(
            value = tagQuery,
            onValueChange = onTagChange,
            label = { Text("Tag") },
            singleLine = true,
            shape = MuseShapes.medium,
            modifier = Modifier.width(140.dp),
        )
        OutlinedTextField(
            value = keywordQuery,
            onValueChange = onKeywordChange,
            label = { Text("关键字") },
            singleLine = true,
            shape = MuseShapes.medium,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.width(180.dp),
        )
    }
}

/**
 * 空状态:无日志或无匹配项时展示。
 */
@Composable
private fun EmptyLogs() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            Text(
                text = "暂无日志",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(MusePaddings.tightGap))
            Text(
                text = "Logger 调用的日志会在此实时显示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 单条日志条目。点击切换展开/折叠。
 *
 * 展开后显示完整消息(无 maxLines)和 throwable stack trace(若有)。
 * 折叠时消息最多 2 行,末尾省略号。
 */
@Composable
private fun LogEntryItem(
    entry: DebugLogEntry,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
    }
    val levelColor = remember(entry.level) { levelColor(entry.level) }

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // 第一行:时间 + 等级标签 + Tag
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MuseMonoFontFamily,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(8.dp))
                LevelChip(level = entry.level, color = levelColor)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            // 第二行:消息(折叠时 2 行省略,展开时全量)
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = if (expanded) TextOverflow.Visible else TextOverflow.Ellipsis,
            )
            // 展开后:throwable stack trace(若有)
            AnimatedVisibility(
                visible = expanded && !entry.throwable.isNullOrEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = MuseShapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = entry.throwable.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(8.dp)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 等级标签胶囊(彩色背景 + 等级首字母)。
 *
 * 颜色约定:ERROR 红 / WARN 橙 / INFO 蓝 / DEBUG 灰。
 */
@Composable
private fun LevelChip(level: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.18f),
    ) {
        Text(
            text = level.take(1),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 把等级字符串映射为展示颜色。onSurface 兜底未知等级。
 */
private fun levelColor(level: String): Color {
    // 不能在非 Composable 函数访问 MaterialTheme.colorScheme,这里返回 ARGB 常量
    return when (level) {
        DebugLogStore.LEVEL_ERROR -> Color(0xFFD32F2F)
        DebugLogStore.LEVEL_WARN -> Color(0xFFEF6C00)
        DebugLogStore.LEVEL_INFO -> Color(0xFF1976D2)
        DebugLogStore.LEVEL_DEBUG -> Color(0xFF616161)
        else -> Color(0xFF616161)
    }
}
