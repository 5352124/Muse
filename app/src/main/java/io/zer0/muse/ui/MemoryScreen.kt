package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 阶段 6: 记忆页 — iOS 风格设置页 + "深夜台灯"调性。
 *
 * 阶段 8 重构: 弃用 surfaceVariant 独立方块卡片,改用 iOS 风格 [SettingsGroup]
 * 圆角分组容器,同一层内的多条记忆用 0.5dp 细 divider 分隔,视觉更扁平克制。
 *
 * 数据来源:[MemoryViewModel] 主动 pull memory 模块的 suspend Repository。
 */
@Composable
fun MemoryScreen(
    onBack: () -> Unit = {},
    viewModel: MemoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Phase 2 2D: Export dialog state (declared before Scaffold for topBar access)
    var showExportDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.memory_screen_title),
                onBack = onBack,
                actions = {
                    // Phase 2 2D: Export button
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Export",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // v1.78 (#1): 搜索防抖 — 本地 state + 300ms delay,避免每次按键都查库
            var searchQuery by rememberSaveable { mutableStateOf("") }
            LaunchedEffect(searchQuery) {
                delay(300)
                viewModel.search(searchQuery)
            }

            // 顶部搜索框(Fact 层 LIKE 搜索)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                enabled = !state.isLoading, // v1.78 (#8): 加载中禁用搜索,避免空库搜索
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                placeholder = { Text(stringResource(R.string.memory_screen_search_fact_hint)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.memory_screen_search_cd),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                },
                shape = MuseShapes.large,
                singleLine = true,
            )

            // Phase 2 2A: View mode state (list vs timeline)
            var showTimelineView by rememberSaveable { mutableStateOf(false) }

            // Phase 2 2A: View mode toggle (below search bar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = !showTimelineView,
                    onClick = { showTimelineView = false },
                    label = { Text("列表") },
                )
                FilterChip(
                    selected = showTimelineView,
                    onClick = { showTimelineView = true },
                    label = { Text("时间轴") },
                )
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                    )
                }
                return@Column
            }

            // 错误展示框(可上下滚动 + 复制按钮)— 用户可滚动查看完整堆栈并复制给开发者
            state.errorTrace?.let { trace ->
                ErrorTraceBox(
                    trace = trace,
                    onRetry = { viewModel.loadAll() },
                )
                return@Column
            }

            // 搜索结果模式 vs 4 层浏览模式
            if (state.query.isNotBlank()) {
                SearchResultsList(
                    results = state.searchResults,
                    isSearching = state.isSearching,
                    onDelete = viewModel::deleteFact,
                )
            } else if (showTimelineView) {
                // Phase 2 2A: 时间轴视图
                val timelineItems = remember(state.factItems, state.summaryItems) {
                    (state.factItems + state.summaryItems).map { item ->
                        io.zer0.muse.ui.memory.TimelineItem(
                            id = item.id,
                            content = item.content,
                            source = item.source,
                            importance = item.importance,
                            createdAt = item.createdAt,
                            tags = item.tags,
                        )
                    }.sortedByDescending { it.createdAt }
                }
                io.zer0.muse.ui.memory.MemoryTimelineView(
                    items = timelineItems,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // v0.51: 4 层折叠状态提升到此(原 FourLayerMemoryList 内部 rememberSaveable 移到外层,
                // 因为 4 层 item 现已平铺到本 LazyColumn,不再有独立 Composable 作用域)。
                var factExpanded by rememberSaveable { mutableStateOf(true) }
                var summaryExpanded by rememberSaveable { mutableStateOf(false) }
                var compileExpanded by rememberSaveable { mutableStateOf(false) }
                var deepExpanded by rememberSaveable { mutableStateOf(false) }

                // P2: 删除确认 + 编辑弹窗状态(跨层共享)
                var pendingDeleteItem by remember { mutableStateOf<MemoryItem?>(null) }
                var editItem by remember { mutableStateOf<MemoryItem?>(null) }
                // v4: 重要程度选择弹窗状态(仅 Fact 层)
                var importanceItem by remember { mutableStateOf<MemoryItem?>(null) }

                // 手动新增元事实弹窗状态
                var showAddFactDialog by remember { mutableStateOf(false) }

                // v1.98: 经验库弹窗状态(新增 / 编辑 / 删除确认)
                var showAddExperienceDialog by remember { mutableStateOf(false) }
                var editExperienceItem by remember { mutableStateOf<MemoryItem?>(null) }
                var pendingDeleteExperience by remember { mutableStateOf<MemoryItem?>(null) }

                // v2.0: 筛选条件芯片行
                if (state.query.isBlank()) {
                    FilterChipRow(
                        importanceFilter = state.importanceFilter,
                        timeFilter = state.timeFilter,
                        typeFilter = state.typeFilter,
                        onImportanceChange = viewModel::setImportanceFilter,
                        onTimeChange = viewModel::setTimeFilter,
                        onTypeChange = viewModel::setTypeFilter,
                    )
                }
                // 手动新增元事实 + 立即编译入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showAddFactDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.memory_screen_add_fact_cd), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.memory_add_fact))
                    }
                    // v1.78 (#7): 立即编译入口,不等待 ticker 自动调度
                    OutlinedButton(
                        onClick = { viewModel.compileNow() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.memory_screen_compile_now_cd), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.memory_screen_compile_now))
                    }
                }

                // v2.0: 根据筛选条件过滤记忆条目
                val filteredFactItems = remember(state.factItems, state.importanceFilter, state.timeFilter, state.typeFilter) {
                    filterMemoryItems(state.factItems, state.importanceFilter, state.timeFilter, state.typeFilter)
                }
                val filteredSummaryItems = remember(state.summaryItems, state.importanceFilter, state.timeFilter, state.typeFilter) {
                    filterMemoryItems(state.summaryItems, state.importanceFilter, state.timeFilter, state.typeFilter)
                }
                val filteredCompileItems = remember(state.compileItems, state.importanceFilter, state.timeFilter, state.typeFilter) {
                    filterMemoryItems(state.compileItems, state.importanceFilter, state.timeFilter, state.typeFilter)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // v0.51: 顶部 dashboard 概览卡片 + AI 理解摘要卡片
                    item { MemoryDashboardCard(state = state) }
                    item { MemorySummaryCard(markdown = state.compiledMarkdown) }
                    // v1.98: 经验库卡片(记忆概览下方,仅开关开启时显示)
                    if (state.experienceEnabled) {
                        item {
                            ExperienceLibraryCard(
                                items = state.experienceItems,
                                onAdd = { showAddExperienceDialog = true },
                                onEdit = { editExperienceItem = it },
                                onDelete = { pendingDeleteExperience = it },
                            )
                        }
                    }
                    // v2.0: 使用过滤后的条目
                    val fs = filteredFactItems
                    val ss = filteredSummaryItems
                    val cs = filteredCompileItems
                    // 4 层折叠列表(平铺到本 LazyColumn,避免嵌套 LazyColumn 触发无限高度告警)
                    fourLayerMemoryListItems(
                        state = state.copy(factItems = fs, summaryItems = ss, compileItems = cs),
                        onDeleteFact = viewModel::deleteFact,
                        onEditFact = { item -> editItem = item },
                        onEditSummary = { item -> editItem = item },
                        onEditCompile = { item -> editItem = item },
                        onPendingDeleteSummary = { item -> pendingDeleteItem = item },
                        onPendingDeleteCompile = { item -> pendingDeleteItem = item },
                        onSetImportance = { item -> importanceItem = item },
                        factExpanded = factExpanded,
                        summaryExpanded = summaryExpanded,
                        compileExpanded = compileExpanded,
                        deepExpanded = deepExpanded,
                        onToggleFact = { factExpanded = !factExpanded },
                        onToggleSummary = { summaryExpanded = !summaryExpanded },
                        onToggleCompile = { compileExpanded = !compileExpanded },
                        onToggleDeep = { deepExpanded = !deepExpanded },
                    )
                }

                // P2: Summary / Compile 层删除确认弹窗
                pendingDeleteItem?.let { item ->
                    val layerName = when (item.source) {
                        "Summary" -> stringResource(R.string.memory_screen_layer_summary)
                        "Compile" -> stringResource(R.string.memory_screen_layer_compile)
                        else -> stringResource(R.string.memory_screen_layer_memory)
                    }
                    MuseDialog(
                        onDismissRequest = { pendingDeleteItem = null },
                        title = stringResource(R.string.memory_screen_delete_layer, layerName),
                        content = {
                            Text(stringResource(R.string.memory_screen_confirm_delete_layer, layerName))
                        },
                        confirmText = stringResource(R.string.memory_screen_delete),
                        onConfirm = {
                            when (item.source) {
                                "Summary" -> viewModel.deleteSummary(item.id)
                                "Compile" -> viewModel.deleteCompile(item.id)
                            }
                            pendingDeleteItem = null
                        },
                        dismissText = stringResource(R.string.memory_screen_cancel),
                        onDismiss = { pendingDeleteItem = null },
                        destructive = true,
                    )
                }

                // P2: 跨层编辑弹窗(Fact / Summary / Compile)
                editItem?.let { item ->
                    val title = when (item.source) {
                        "Fact" -> stringResource(R.string.memory_screen_edit_fact)
                        "Summary" -> stringResource(R.string.memory_screen_edit_summary)
                        "Compile" -> stringResource(R.string.memory_screen_edit_compile)
                        else -> stringResource(R.string.memory_screen_edit_memory)
                    }
                    FactEditDialog(
                        title = title,
                        initialContent = item.content,
                        onDismiss = { editItem = null },
                        onConfirm = { newContent ->
                            when (item.source) {
                                "Fact" -> viewModel.editFact(item.id, newContent)
                                "Summary" -> viewModel.editSummary(item.id, newContent)
                                "Compile" -> viewModel.editCompile(item.id, newContent)
                            }
                            editItem = null
                        },
                    )
                }

                // 新增元事实弹窗
                if (showAddFactDialog) {
                    AddFactDialog(
                        onDismiss = { showAddFactDialog = false },
                        onConfirm = { content ->
                            viewModel.addFact(content)
                            showAddFactDialog = false
                        },
                    )
                }

                // v4: 重要程度选择弹窗(仅 Fact 层)
                importanceItem?.let { item ->
                    ImportanceSelectDialog(
                        currentImportance = item.importance,
                        onDismiss = { importanceItem = null },
                        onSelect = { importance ->
                            viewModel.setFactImportance(item.id, importance)
                            importanceItem = null
                        },
                    )
                }

                // v1.98: 经验库 — 新增弹窗
                if (showAddExperienceDialog) {
                    ExperienceEditDialog(
                        title = stringResource(R.string.memory_screen_experience_add_dialog_title),
                        initialTitle = "",
                        initialContent = "",
                        initialCategory = "通用",
                        initialTags = "",
                        onDismiss = { showAddExperienceDialog = false },
                        onConfirm = { t, c, cat, tags ->
                            viewModel.addExperience(t, c, cat, tags)
                            showAddExperienceDialog = false
                        },
                    )
                }

                // v1.98: 经验库 — 编辑弹窗
                editExperienceItem?.let { item ->
                    val tagText = item.tags.joinToString(", ")
                    ExperienceEditDialog(
                        title = stringResource(R.string.memory_screen_experience_edit_dialog_title),
                        initialTitle = item.title,
                        initialContent = item.content,
                        initialCategory = item.category ?: "通用",
                        initialTags = tagText,
                        onDismiss = { editExperienceItem = null },
                        onConfirm = { t, c, cat, tags ->
                            viewModel.editExperience(item.id, t, c, cat, tags)
                            editExperienceItem = null
                        },
                    )
                }

                // v1.98: 经验库 — 删除确认弹窗
                pendingDeleteExperience?.let { item ->
                    MuseDialog(
                        onDismissRequest = { pendingDeleteExperience = null },
                        title = stringResource(R.string.memory_screen_experience_title),
                        content = {
                            Text(stringResource(R.string.memory_screen_experience_delete_confirm))
                        },
                        confirmText = stringResource(R.string.memory_screen_delete),
                        onConfirm = {
                            viewModel.deleteExperience(item.id)
                            pendingDeleteExperience = null
                        },
                        dismissText = stringResource(R.string.memory_screen_cancel),
                        onDismiss = { pendingDeleteExperience = null },
                        destructive = true,
                    )
                }

                // Phase 2 2D: Export dialog
                if (showExportDialog) {
                    val context = LocalContext.current
                    MuseDialog(
                        onDismissRequest = { showExportDialog = false },
                        title = "导出记忆",
                        content = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("选择导出格式：")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val md = buildMemoryMarkdown(state.factItems, state.summaryItems)
                                            shareText(context, md, "memory_export.md")
                                            showExportDialog = false
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Markdown") }
                                    OutlinedButton(
                                        onClick = {
                                            val json = buildMemoryJson(state.factItems, state.summaryItems)
                                            shareText(context, json, "memory_export.json")
                                            showExportDialog = false
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("JSON") }
                                }
                            }
                        },
                        confirmText = "",
                        onConfirm = null,
                        dismissText = stringResource(R.string.memory_screen_cancel),
                        onDismiss = { showExportDialog = false },
                    )
                }
            }
        }
    }
}

/**
 * v2.0: 根据筛选条件过滤记忆条目。
 */
private fun filterMemoryItems(
    items: List<MemoryItem>,
    importanceFilter: Int?,
    timeFilter: String?,
    typeFilter: String?,
): List<MemoryItem> {
    return items.filter { item ->
        val matchesImportance = importanceFilter == null || item.importance == importanceFilter
        val matchesType = typeFilter == null || item.source.equals(typeFilter, ignoreCase = true)
        val matchesTime = when (timeFilter) {
            null -> true
            "today" -> isToday(item.createdAt)
            "week" -> isThisWeek(item.createdAt)
            "month" -> isThisMonth(item.createdAt)
            else -> true
        }
        matchesImportance && matchesType && matchesTime
    }
}

private fun isToday(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        itemDate == today
    } catch (e: Exception) { false }
}

private fun isThisWeek(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        !itemDate.isBefore(weekStart) && !itemDate.isAfter(today)
    } catch (e: Exception) { false }
}

private fun isThisMonth(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        itemDate.year == today.year && itemDate.month == today.month
    } catch (e: Exception) { false }
}

/**
 * v2.0: 筛选条件芯片行 — 重要性 / 时间 / 类型。
 *
 * 修复: 时间筛选与类型筛选拆分为独立行,避免同一行过宽导致类型芯片被截断
 * 或出现右侧空白区域。
 */
@Composable
private fun FilterChipRow(
    importanceFilter: Int?,
    timeFilter: String?,
    typeFilter: String?,
    onImportanceChange: (Int?) -> Unit,
    onTimeChange: (String?) -> Unit,
    onTypeChange: (String?) -> Unit,
) {
    // 重要性筛选
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val importanceOptions = listOf<Pair<Int?, String>>(
            null to stringResource(R.string.memory_filter_all),
            0 to stringResource(R.string.memory_filter_normal),
            1 to stringResource(R.string.memory_filter_important),
            2 to stringResource(R.string.memory_filter_critical),
        )
        importanceOptions.forEach { (value, label) ->
            FilterChip(
                selected = importanceFilter == value,
                onClick = { onImportanceChange(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    // 时间筛选
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val timeOptions = listOf<Pair<String?, String>>(
            null to stringResource(R.string.memory_filter_all),
            "today" to stringResource(R.string.memory_filter_today),
            "week" to stringResource(R.string.memory_filter_week),
            "month" to stringResource(R.string.memory_filter_month),
        )
        timeOptions.forEach { (value, label) ->
            FilterChip(
                selected = timeFilter == value,
                onClick = { onTimeChange(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    // 类型筛选
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val typeOptions = listOf<Pair<String?, String>>(
            null to stringResource(R.string.memory_filter_all),
            "Fact" to stringResource(R.string.memory_filter_fact),
            "Summary" to stringResource(R.string.memory_filter_summary),
            "Compile" to stringResource(R.string.memory_filter_compile),
        )
        typeOptions.forEach { (value, label) ->
            FilterChip(
                selected = typeFilter == value,
                onClick = { onTypeChange(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/**
 * 搜索结果列表 — iOS 风格 SettingsGroup 容器。
 */
@Composable
private fun SearchResultsList(
    results: List<MemoryItem>,
    isSearching: Boolean,
    onDelete: (String) -> Unit,
) {
    if (isSearching) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
        return
    }
    if (results.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.memory_screen_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.memory_screen_search_results, results.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        item {
            // iOS 风格:一个 SettingsGroup 容器包裹所有搜索结果,行间 divider
            io.zer0.muse.ui.common.SettingsGroup {
                results.forEachIndexed { index, item ->
                    if (index > 0) io.zer0.muse.ui.common.SettingsGroupDivider()
                    key(item.id) {
                        MemoryRow(item = item, onDelete = onDelete)
                    }
                }
            }
        }
    }
}

/**
 * 4 层记忆折叠列表 — Fact / Summary / Compile / Deep。
 *
 * 每层用一个独立 SettingsGroup 容器,层内多条记忆用 divider 分隔。
 *
 * v0.51: 由原 Composable 改为 [LazyListScope] 扩展,4 层 item 直接平铺到外层 LazyColumn,
 * 避免"外层 LazyColumn 包内层 LazyColumn"的无限高度告警;展开状态由调用方持有并传入。
 */
private fun LazyListScope.fourLayerMemoryListItems(
    state: MemoryUiState,
    onDeleteFact: (String) -> Unit,
    onEditFact: (MemoryItem) -> Unit,
    onEditSummary: (MemoryItem) -> Unit,
    onEditCompile: (MemoryItem) -> Unit,
    onPendingDeleteSummary: (MemoryItem) -> Unit,
    onPendingDeleteCompile: (MemoryItem) -> Unit,
    onSetImportance: (MemoryItem) -> Unit,
    factExpanded: Boolean,
    summaryExpanded: Boolean,
    compileExpanded: Boolean,
    deepExpanded: Boolean,
    onToggleFact: () -> Unit,
    onToggleSummary: () -> Unit,
    onToggleCompile: () -> Unit,
    onToggleDeep: () -> Unit,
) {
    // ① Fact 层
    item {
        SectionHeader(
            title = stringResource(R.string.memory_screen_fact_section),
            count = state.factCount,
            expanded = factExpanded,
            onClick = onToggleFact,
        )
    }
    if (factExpanded) {
        item {
            if (state.factItems.isEmpty()) {
                EmptyHint(text = stringResource(R.string.memory_screen_no_fact))
            } else {
                io.zer0.muse.ui.common.SettingsGroup {
                    state.factItems.take(50).forEachIndexed { index, item ->
                        if (index > 0) io.zer0.muse.ui.common.SettingsGroupDivider()
                        key(item.id) {
                            MemoryRow(
                                item = item,
                                onDelete = onDeleteFact,
                                onEdit = onEditFact,
                                onSetImportance = onSetImportance,
                            )
                        }
                    }
                }
                // v1.78 (#3): 超过 50 条时提示用户
                if (state.factCount > 50) {
                    Text(
                        text = stringResource(R.string.memory_screen_fact_limit, state.factCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp, start = 16.dp),
                    )
                }
            }
        }
    }

    // ② Summary 层
    item {
        SectionHeader(
            title = stringResource(R.string.memory_screen_summary_section),
            count = state.summaryCount,
            expanded = summaryExpanded,
            onClick = onToggleSummary,
        )
    }
    if (summaryExpanded) {
        item {
            if (state.summaryItems.isEmpty()) {
                EmptyHint(text = stringResource(R.string.memory_screen_no_summary))
            } else {
                io.zer0.muse.ui.common.SettingsGroup {
                    state.summaryItems.forEachIndexed { index, item ->
                        if (index > 0) io.zer0.muse.ui.common.SettingsGroupDivider()
                        key(item.id) {
                            MemoryRow(
                                item = item,
                                onDelete = { onPendingDeleteSummary(item) },
                                onEdit = { onEditSummary(item) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ③ Compile 层
    item {
        SectionHeader(
            title = stringResource(R.string.memory_screen_compile_section),
            count = state.compileItems.size,
            expanded = compileExpanded,
            onClick = onToggleCompile,
        )
    }
    if (compileExpanded) {
        item {
            if (state.compileItems.isEmpty()) {
                EmptyHint(text = stringResource(R.string.memory_screen_no_compile))
            } else {
                io.zer0.muse.ui.common.SettingsGroup {
                    state.compileItems.forEachIndexed { index, item ->
                        if (index > 0) io.zer0.muse.ui.common.SettingsGroupDivider()
                        key(item.id) {
                            MemoryRow(
                                item = item,
                                onDelete = { onPendingDeleteCompile(item) },
                                onEdit = { onEditCompile(item) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ④ Deep 层(无独立存储,展示统计;无具体条目可删除)
    item {
        SectionHeader(
            title = stringResource(R.string.memory_screen_deep_section),
            count = null,
            expanded = deepExpanded,
            onClick = onToggleDeep,
        )
    }
    if (deepExpanded) {
        item {
            io.zer0.muse.ui.common.SettingsGroup {
                Column(modifier = Modifier.padding(16.dp)) {
                    val noneText = stringResource(R.string.memory_screen_none)
                    Text(
                        text = stringResource(R.string.memory_screen_deep_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.memory_screen_fact_total, state.factCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = stringResource(R.string.memory_screen_summary_total, state.summaryCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = stringResource(R.string.memory_screen_last_updated, state.lastUpdatedAt ?: noneText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * 分区 header — 标题 + 数量 + 展开/折叠图标(iOS 风格扁平行)。
 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int?,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 单条记忆行 — iOS 风格(无独立方块背景,靠 SettingsGroup 容器 + divider 分隔)。
 *
 * @param onDelete 删除回调,非 null 时显示删除图标(Fact 层立即执行,Summary/Compile 层弹窗确认)
 * @param onEdit 编辑回调,非 null 时显示编辑图标
 * @param onSetImportance v4: 重要程度设置回调,非 null 时显示星标按钮(仅 Fact 层)
 */
@Composable
private fun MemoryRow(
    item: MemoryItem,
    onDelete: ((String) -> Unit)?,
    onEdit: ((MemoryItem) -> Unit)? = null,
    onSetImportance: ((MemoryItem) -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // v4: 重要程度徽章(importance > 0 时显示在标题前)
            if (item.importance > 0) {
                ImportanceBadge(importance = item.importance)
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // v4: 重要程度切换按钮(仅 Fact 层)
            if (onSetImportance != null) {
                IconButton(
                    onClick = { onSetImportance(item) },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.memory_sync_set_importance_cd),
                        tint = when (item.importance) {
                            2 -> MaterialTheme.colorScheme.error
                            1 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (onEdit != null) {
                IconButton(
                    onClick = { onEdit(item) },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.memory_screen_edit_cd),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (onDelete != null) {
                IconButton(
                    onClick = { onDelete(item.id) },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.memory_screen_delete_cd),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (item.content.isNotBlank()) {
            Spacer(Modifier.size(4.dp))
            // Compile 层内容为 markdown,用 MarkdownText 渲染
            MarkdownText(
                text = item.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 标签 + 时间行
        if (item.tags.isNotEmpty() || item.time != null) {
            Spacer(Modifier.size(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.tags.forEach { tag ->
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (item.time != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = item.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        // v4: 来源会话 + 入库时间行(仅 Fact 层有值时显示)
        val createdAtText = formatCreatedAtText(item.createdAt)
        if ((item.source == "Fact" && item.sessionId != null) || createdAtText != null) {
            Spacer(Modifier.size(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.source == "Fact" && item.sessionId != null) {
                    Text(
                        text = stringResource(R.string.memory_source_session, item.sessionId.take(8)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (createdAtText != null) {
                    Text(
                        text = createdAtText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * v4: 重要程度徽章 — importance=1 显示橙色"重要",importance=2 显示红色"关键"。
 */
@Composable
private fun ImportanceBadge(importance: Int) {
    if (importance <= 0) return
    val icon = if (importance >= 2) Icons.Filled.Warning else Icons.Filled.PriorityHigh
    val text = if (importance >= 2) {
        stringResource(R.string.memory_importance_critical)
    } else {
        stringResource(R.string.memory_importance_important)
    }
    val color = if (importance >= 2) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * v4: 根据 createdAt(ISO 8601)计算相对时间文案("今天入库"/"昨天入库"/"N 天前入库")。
 * 解析失败返回 null(不显示,不崩溃)。
 */
@Composable
private fun formatCreatedAtText(createdAt: String?): String? {
    if (createdAt == null) return null
    // 解析放在 try-catch 内,stringResource 调用移到 try-catch 外(Compose 不允许
    // @Composable 调用在 try-catch 块内)
    val days = try {
        Duration.between(Instant.parse(createdAt), Instant.now()).toDays()
    } catch (e: Exception) {
        return null
    }
    return when {
        days <= 0L -> stringResource(R.string.memory_created_today)
        days == 1L -> stringResource(R.string.memory_created_yesterday)
        else -> stringResource(R.string.memory_created_days_ago, days.toInt())
    }
}

/**
 * 错误堆栈展示框 — 用户可上下滚动查看完整错误信息,并提供"复制"和"重试"按钮。
 * 用于定位记忆系统崩溃等需要完整堆栈的问题。
 */
@Composable
private fun ErrorTraceBox(
    trace: String,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // 标题 + 复制按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.memory_screen_load_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            IconButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Muse Error Trace", trace)
                )
                MuseToast.show(context.getString(R.string.memory_screen_copied_trace))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.memory_screen_copy_trace_cd))
            }
        }

        // 可滚动的错误堆栈框(占满剩余空间,上下滑动查看)
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            shape = MuseShapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Text(
                text = trace,
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MuseMonoFontFamily,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // 重试按钮
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            shape = MuseShapes.large,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.memory_screen_retry))
        }
    }
}

/**
 * 空状态提示。
 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * P2: 通用内容编辑对话框。
 *
 * 预填当前 content,用户可修改后保存或取消。
 * 保存时若内容为空则视为取消。
 */
@Composable
private fun FactEditDialog(
    title: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // v1.78 (M8): remember 加 key(initialContent),确保编辑不同条目时状态正确重置,
    // 不复用上一条目的草稿内容
    var text by remember(initialContent) { mutableStateOf(initialContent) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp),
                placeholder = { Text(stringResource(R.string.memory_screen_input_content)) },
                shape = MuseShapes.large,
            )
        },
        confirmText = stringResource(R.string.memory_screen_save),
        onConfirm = {
            if (text.isNotBlank()) {
                onConfirm(text)
            }
        },
        dismissText = stringResource(R.string.memory_screen_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * 新增元事实对话框。
 */
@Composable
private fun AddFactDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    FactEditDialog(
        title = stringResource(R.string.memory_add_fact_dialog_title),
        initialContent = "",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/**
 * v4: 重要程度选择对话框 — 三个选项(普通/重要/关键),当前选中项右侧显示 check 图标。
 * 选中任一选项后立即回调并关闭弹窗(无需额外确认按钮)。
 */
@Composable
private fun ImportanceSelectDialog(
    currentImportance: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.memory_importance_set_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ImportanceOptionRow(
                    title = stringResource(R.string.memory_importance_normal),
                    desc = stringResource(R.string.memory_importance_normal_desc),
                    selected = currentImportance == 0,
                    onClick = {
                        onSelect(0)
                    },
                )
                ImportanceOptionRow(
                    title = stringResource(R.string.memory_importance_important),
                    desc = stringResource(R.string.memory_importance_important_desc),
                    selected = currentImportance == 1,
                    onClick = {
                        onSelect(1)
                    },
                )
                ImportanceOptionRow(
                    title = stringResource(R.string.memory_importance_critical),
                    desc = stringResource(R.string.memory_importance_critical_desc),
                    selected = currentImportance == 2,
                    onClick = {
                        onSelect(2)
                    },
                )
            }
        },
        onConfirm = null,
        dismissText = stringResource(R.string.memory_screen_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * v4: 重要程度选项行 — 标题 + 描述 + 选中状态(check 图标)。
 */
@Composable
private fun ImportanceOptionRow(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * v0.51: 记忆 dashboard 概览卡片。
 *
 * 展示 3 项核心指标 + 统计图表(重要度分布饼图 + 增长趋势折线图) + 可折叠的健康详情。
 *
 * v5: 新增统计卡片:总记忆数/本周新增/本月新增/重要度分布/最活跃会话/增长趋势。
 */
@Composable
private fun MemoryDashboardCard(state: MemoryUiState) {
    // 健康状态汇总:任意步骤有 failCount>0 即视为异常
    val hasAnyError = state.healthMap.values.any { it.failCount > 0 }
    val healthText = if (state.healthMap.isEmpty()) {
        stringResource(R.string.memory_screen_not_running)
    } else if (hasAnyError) {
        stringResource(R.string.memory_screen_has_error)
    } else {
        stringResource(R.string.memory_screen_normal)
    }
    val healthColor = if (hasAnyError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    var healthExpanded by rememberSaveable { mutableStateOf(false) }
    var statsExpanded by rememberSaveable { mutableStateOf(true) }

    io.zer0.muse.ui.common.SettingsGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.memory_screen_overview),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(12.dp))
            // 3 行核心指标
            DashboardMetricRow(label = stringResource(R.string.memory_screen_fact_total_label), value = state.factCount.toString())
            DashboardMetricRow(
                label = stringResource(R.string.memory_screen_last_compile),
                value = state.lastCompileTime ?: stringResource(R.string.memory_screen_no_compile_time),
            )
            DashboardMetricRow(label = stringResource(R.string.memory_screen_health), value = healthText, valueColor = healthColor)

            // v4: 同步状态提示(最近编译时间下方)
            if (state.syncStatus.isNotBlank()) {
                val isStale = state.syncStatus.contains("可能还未进入记忆")
                Spacer(Modifier.size(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = if (isStale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = state.syncStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isStale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // v5: 统计卡片(可折叠)
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { statsExpanded = !statsExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.TrendingUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "记忆统计",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (statsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (statsExpanded) {
                Spacer(Modifier.size(4.dp))
                // 统计指标行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatChip(label = "总计", value = state.totalFactCount.toString())
                    StatChip(label = "本周", value = state.weekNewCount.toString())
                    StatChip(label = "本月", value = state.monthNewCount.toString())
                }
                Spacer(Modifier.size(12.dp))
                // 重要度分布饼图
                if (state.importanceDistribution.isNotEmpty()) {
                    Text(
                        text = "重要度分布",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.size(4.dp))
                    ImportancePieChart(distribution = state.importanceDistribution)
                }
                // 最活跃会话
                if (state.topSessions.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "最活跃会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.size(4.dp))
                    state.topSessions.take(3).forEach { (sid, count) ->
                        DashboardMetricRow(label = sid.take(12), value = "$count 条")
                    }
                }
                // 增长趋势折线图
                if (state.dailyTrend.size >= 2) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "近 30 天趋势",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.size(4.dp))
                    TrendLineChart(dailyData = state.dailyTrend)
                }
            }

            // 可折叠的健康详情
            if (state.healthMap.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { healthExpanded = !healthExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (healthExpanded) stringResource(R.string.memory_screen_collapse_steps) else stringResource(R.string.memory_screen_view_steps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (healthExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (healthExpanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
                AnimatedVisibility(
                    visible = healthExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        state.healthMap.forEach { (step, health) ->
                            HealthStepRow(stepKey = step, health = health)
                        }
                    }
                }
            }
        }
    }
}

/**
 * v5: 统计小标签(圆形背景 + 数值)。
 */
@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = MuseShapes.pill,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.size(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * v5: 重要度分布饼图 — 用 Canvas 绘制三段色环。
 */
@Composable
private fun ImportancePieChart(distribution: Map<Int, Int>) {
    val total = distribution.values.sum().coerceAtLeast(1)
    val segments = listOf(
        0 to Color(0xFF9E9E9E),  // 普通-灰色
        1 to Color(0xFFFF9800),  // 重要-橙色
        2 to Color(0xFFF44336),  // 关键-红色
    ).map { (key, color) ->
        val count = distribution[key] ?: 0
        Triple(key, color, count.toFloat() / total)
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Canvas(modifier = Modifier.size(80.dp)) {
            var startAngle = -90f
            segments.forEach { (key, color, ratio) ->
                if (ratio > 0f) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = 360f * ratio,
                        useCenter = true,
                    )
                    startAngle += 360f * ratio
                }
            }
            // 中心小圆(镂空效果)
            drawCircle(color = surfaceColor, radius = size.minDimension * 0.3f)
        }
        Column {
            segments.forEach { (key, color, ratio) ->
                val label = when (key) {
                    2 -> "关键"
                    1 -> "重要"
                    else -> "普通"
                }
                val percent = (ratio * 100).toInt()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier.size(8.dp).drawBehind { drawCircle(color = color) })
                    Text(
                        text = "$label $percent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * v5: 记忆增长趋势折线图 — Canvas 绘制。
 */
@Composable
private fun TrendLineChart(dailyData: List<Pair<String, Int>>) {
    val maxVal = dailyData.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stepX = size.width / (dailyData.size - 1).coerceAtLeast(1)
            val points = dailyData.mapIndexed { index, (_, count) ->
                Offset(
                    x = index * stepX,
                    y = size.height - (count.toFloat() / maxVal) * size.height * 0.85f - size.height * 0.05f,
                )
            }
            // 画折线
            if (points.size >= 2) {
                for (i in 0 until points.size - 1) {
                    drawLine(
                        color = Color(0xFF4CAF50),
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                // 画圆点
                points.forEach { point ->
                    drawCircle(color = Color(0xFF4CAF50), radius = 2.dp.toPx(), center = point)
                }
            }
            // 基线
            drawLine(
                color = Color.LightGray.copy(alpha = 0.5f),
                start = Offset(0f, size.height - 1.dp.toPx()),
                end = Offset(size.width, size.height - 1.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

/**
 * dashboard 单行指标(label + value)。
 */
@Composable
private fun DashboardMetricRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 单个记忆步骤的健康行(展示 lastSuccessAt / failCount / lastErrorMsg)。
 */
@Composable
private fun HealthStepRow(
    stepKey: String,
    health: io.zer0.memory.ticker.MemoryTicker.StepHealth,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stepKey,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (health.failCount > 0) stringResource(R.string.memory_screen_failed_times, health.failCount) else stringResource(R.string.memory_screen_normal),
                style = MaterialTheme.typography.labelSmall,
                color = if (health.failCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            )
        }
        val lastSuccessAt = health.lastSuccessAt
        if (lastSuccessAt != null) {
            Text(
                text = stringResource(R.string.memory_screen_last_success, lastSuccessAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        val lastErrorMsg = health.lastErrorMsg
        if (lastErrorMsg != null) {
            Text(
                text = stringResource(R.string.memory_screen_error, lastErrorMsg),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * v0.51: "AI 对你的理解" 摘要卡片。
 *
 * 展示最近编译的 memory.md 摘要(与 ChatService 注入 system prompt 同源,
 * 反映 AI 真正"看到"的长期记忆)。
 *
 *  - 用 MarkdownText 渲染 markdown
 *  - 默认折叠,点击展开看完整摘要
 *  - 没有摘要时显示"暂无摘要,继续对话后会自动生成"
 */
@Composable
private fun MemorySummaryCard(markdown: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasSummary = markdown.isNotBlank()

    io.zer0.muse.ui.common.SettingsGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_screen_ai_understanding),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(4.dp))
            if (!hasSummary) {
                Text(
                    text = stringResource(R.string.memory_screen_no_summary_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Text(
                    text = if (expanded) stringResource(R.string.memory_screen_click_collapse) else stringResource(R.string.memory_screen_click_expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        MarkdownText(
                            text = markdown,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * v1.98: 经验库卡片 — 展示在记忆概览下方。
 *
 * 与 4 层记忆并列的独立经验性知识库,用户可手动 CRUD。
 * 条目按 updatedAt 降序排列,默认折叠,展开后列出所有条目(每条可编辑/删除)。
 */
@Composable
private fun ExperienceLibraryCard(
    items: List<MemoryItem>,
    onAdd: () -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    io.zer0.muse.ui.common.SettingsGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.memory_screen_experience_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = stringResource(R.string.memory_screen_experience_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                // 条目数徽标
                Surface(
                    shape = MuseShapes.pill,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = stringResource(R.string.memory_screen_experience_count, items.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (items.isEmpty()) {
                        Text(
                            text = stringResource(R.string.memory_screen_experience_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        items.forEachIndexed { index, item ->
                            if (index > 0) io.zer0.muse.ui.common.SettingsGroupDivider()
                            key(item.id) {
                                MemoryRow(
                                    item = item,
                                    onDelete = { onDelete(item) },
                                    onEdit = { onEdit(item) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    // 新增按钮(右对齐文字按钮,iOS 风格)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onAdd) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.memory_screen_experience_add_cd), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.memory_screen_experience_add_dialog_title))
                        }
                    }
                }
            }
        }
    }
}

/**
 * v1.98: 经验库新增/编辑对话框 — 标题 + 详细内容 + 分类 + 标签(逗号分隔)。
 *
 * 与 FactEditDialog 的差异:经验库条目有 title(独立于 content)和分类/标签,
 * 因此需要多字段表单。标签输入为逗号分隔字符串,提交时拆分为 List<String>。
 */
@Composable
private fun ExperienceEditDialog(
    title: String,
    initialTitle: String,
    initialContent: String,
    initialCategory: String,
    initialTags: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String, category: String, tags: List<String>) -> Unit,
) {
    // v1.78 (M8): remember 加 key,确保编辑不同条目时状态正确重置
    var titleText by remember(initialTitle) { mutableStateOf(initialTitle) }
    var contentText by remember(initialContent) { mutableStateOf(initialContent) }
    var categoryText by remember(initialCategory) { mutableStateOf(initialCategory) }
    var tagsText by remember(initialTags) { mutableStateOf(initialTags) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_screen_experience_title_label)) },
                    placeholder = { Text(stringResource(R.string.memory_screen_experience_title_hint)) },
                    singleLine = true,
                    shape = MuseShapes.large,
                )
                OutlinedTextField(
                    value = contentText,
                    onValueChange = { contentText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    label = { Text(stringResource(R.string.memory_screen_experience_content_label)) },
                    placeholder = { Text(stringResource(R.string.memory_screen_experience_content_hint)) },
                    shape = MuseShapes.large,
                )
                OutlinedTextField(
                    value = categoryText,
                    onValueChange = { categoryText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_screen_experience_category_label)) },
                    singleLine = true,
                    shape = MuseShapes.large,
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_screen_experience_tags_label)) },
                    singleLine = true,
                    shape = MuseShapes.large,
                )
            }
        },
        confirmText = stringResource(R.string.memory_screen_save),
        onConfirm = {
            if (titleText.isNotBlank() && contentText.isNotBlank()) {
                val tags = tagsText.split(",", "，", ";", "；")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                onConfirm(titleText, contentText, categoryText, tags)
            }
        },
        dismissText = stringResource(R.string.memory_screen_cancel),
        onDismiss = onDismiss,
    )
}

// ── Phase 2 2D: Export helpers ──────────────────────────────────────────

private fun buildMemoryMarkdown(
    facts: List<MemoryItem>,
    summaries: List<MemoryItem>,
): String {
    val sb = StringBuilder()
    sb.appendLine("# Muse Memory Export")
    sb.appendLine()
    sb.appendLine("Exported at: ${java.time.Instant.now()}")
    sb.appendLine()
    sb.appendLine("## Facts (${facts.size})")
    sb.appendLine()
    facts.forEach { f ->
        val stars = "⭐".repeat(f.importance)
        sb.appendLine("- $stars ${f.content}")
        if (f.tags.isNotEmpty()) sb.appendLine("  Tags: ${f.tags.joinToString(", ")}")
        f.createdAt?.let { sb.appendLine("  Created: $it") }
        sb.appendLine()
    }
    sb.appendLine("## Summaries (${summaries.size})")
    sb.appendLine()
    summaries.forEach { s ->
        sb.appendLine("### ${s.title}")
        sb.appendLine(s.content)
        sb.appendLine()
    }
    return sb.toString()
}

private fun buildMemoryJson(
    facts: List<MemoryItem>,
    summaries: List<MemoryItem>,
): String {
    val sb = StringBuilder()
    sb.appendLine("{")
    sb.appendLine("  \"exportedAt\": \"${java.time.Instant.now()}\",")
    sb.appendLine("  \"facts\": [")
    facts.forEachIndexed { i, f ->
        val comma = if (i < facts.size - 1) "," else ""
        val escaped = f.content.replace("\"", "\\\"").replace("\n", "\\n")
        sb.appendLine("    {\"id\": \"${f.id}\", \"content\": \"$escaped\", \"importance\": ${f.importance}, \"tags\": [${f.tags.joinToString(",") { "\"$it\"" }}], \"createdAt\": \"${f.createdAt ?: ""}\"}$comma")
    }
    sb.appendLine("  ],")
    sb.appendLine("  \"summaries\": [")
    summaries.forEachIndexed { i, s ->
        val comma = if (i < summaries.size - 1) "," else ""
        val escaped = s.content.replace("\"", "\\\"").replace("\n", "\\n")
        sb.appendLine("    {\"id\": \"${s.id}\", \"title\": \"${s.title}\", \"content\": \"$escaped\"}$comma")
    }
    sb.appendLine("  ]")
    sb.appendLine("}")
    return sb.toString()
}

private fun shareText(context: android.content.Context, text: String, fileName: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = if (fileName.endsWith(".json")) "application/json" else "text/markdown"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share Memory Export"))
}
