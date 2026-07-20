package io.zer0.muse.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * v0.46: 统计页 — 数据概览 + 活跃热力图 + 每周趋势。
 *
 * 参考 rikkahub StatsPage: 2 列统计卡片网格 + 53 周 × 7 天热力图 + 每周趋势柱状图。
 * 结构:
 *  - TopAppBar(标题"统计" + 返回按钮)
 *  - LazyColumn:
 *    1. 数据概览(9 个统计卡片,2 列网格)
 *    2. 活跃热力图卡片(53 周 × 7 天网格,横向滚动,初始滚到最右)
 *    3. 每周趋势卡片(最近 7 天每日消息数柱状图)
 *    4. v0.47: 模型使用占比卡片(环形图 + 图例)
 *    5. v0.47: 助手使用占比卡片(横向条形图)
 *    6. v0.47: 小时活跃分布卡片(24 根柱状图)
 *    7. v0.47: Top 10 活跃会话卡片(列表)
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    viewModel: StatsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            // iOS 风格 Large Title 顶部栏
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.stats_back),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // L-ST2 修复: 各 item 加 key,避免重组时 LazyColumn 误判项目移动而重建组件树
                item(key = "time_range_filter") {
                    TimeRangeFilterRow(
                        currentRange = state.timeRange,
                        onRangeChange = { viewModel.setTimeRange(it) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "stats_grid") {
                    StatsGrid(
                        state = state,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "heatmap") {
                    HeatmapCard(
                        messagesPerDay = state.messagesPerDay,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "weekly_trend") {
                    WeeklyTrendCard(
                        weeklyTrend = state.weeklyTrend,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "model_usage") {
                    ModelUsageCard(
                        modelCounts = state.modelCounts,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "assistant_usage") {
                    AssistantUsageCard(
                        assistantCounts = state.assistantCounts,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "hourly_dist") {
                    HourlyDistributionCard(
                        hourlyDistribution = state.hourlyDistribution,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "top_sessions") {
                    TopSessionsCard(
                        topSessions = state.topSessions,
                        onOpenSession = onOpenSession,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * v1.104 U8: 时间范围筛选行 — 横向滚动的 FilterChip(全部 / 本月 / 本周 / 今天)。
 * 仅概览卡片响应筛选,图表卡片保持全量以保证趋势完整性。
 */
@Composable
private fun TimeRangeFilterRow(
    currentRange: StatsTimeRange,
    onRangeChange: (StatsTimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranges = listOf(
        StatsTimeRange.ALL_TIME to R.string.stats_range_all,
        StatsTimeRange.THIS_MONTH to R.string.stats_range_month,
        StatsTimeRange.THIS_WEEK to R.string.stats_range_week,
        StatsTimeRange.TODAY to R.string.stats_range_today,
    )
    val allCd = stringResource(R.string.stats_range_cd)
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ranges.size) { index ->
            val (range, labelRes) = ranges[index]
            FilterChip(
                selected = currentRange == range,
                onClick = { onRangeChange(range) },
                label = { Text(stringResource(labelRes)) },
                modifier = Modifier.semantics {
                    contentDescription = "$allCd: ${if (currentRange == range) "已选 " else ""}${ranges[index].first.name}"
                },
            )
        }
    }
}

/**
 * 数据概览:2 列统计卡片网格(9 张卡片,每行 2 张,奇数行末位补占位保持左对齐)。
 */
@Composable
private fun StatsGrid(
    state: StatsViewModel.StatsUiState,
    modifier: Modifier = Modifier,
) {
    val cards = listOf(
        StatCardData(Icons.Filled.Forum, stringResource(R.string.stats_total_sessions), formatCount(state.totalSessions)),
        StatCardData(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.stats_total_messages), formatCount(state.totalMessages)),
        StatCardData(Icons.Filled.SmartToy, stringResource(R.string.stats_ai_replies), formatCount(state.totalAiMessages)),
        StatCardData(Icons.Filled.Person, stringResource(R.string.stats_user_messages), formatCount(state.totalUserMessages)),
        StatCardData(Icons.Filled.LocalFireDepartment, stringResource(R.string.stats_streak), stringResource(R.string.stats_streak_days, state.streakDays)),
        StatCardData(
            icon = Icons.Filled.Event,
            label = stringResource(R.string.stats_most_active_day),
            value = state.mostActiveDay?.let { "${it.first.monthValue}/${it.first.dayOfMonth}" } ?: "—",
        ),
        StatCardData(Icons.AutoMirrored.Filled.ShowChart, stringResource(R.string.stats_avg_daily), formatAvg(state.avgMessagesPerDay)),
        StatCardData(Icons.Filled.ViewWeek, stringResource(R.string.stats_this_week), formatCount(state.messagesThisWeek)),
        StatCardData(Icons.Filled.CalendarMonth, stringResource(R.string.stats_this_month), formatCount(state.messagesThisMonth)),
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { card ->
                    StatCard(
                        icon = card.icon,
                        label = card.label,
                        value = card.value,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 奇数行末位补等宽占位,保持最后一行左对齐
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 单张统计卡片:图标 + 数值 + 标签。 */
@Composable
private fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 统计卡片数据(图标 / 标签 / 数值)。 */
private data class StatCardData(
    val icon: ImageVector,
    val label: String,
    val value: String,
)

/**
 * 每周趋势卡片:最近 7 天每日消息数柱状图。
 *
 * - 柱条高度按当日消息数 / 7 日最大值 等比缩放
 * - 今日柱条用 primary 高亮,其余用 primary 45% 透明
 * - 柱顶显示当日消息数(0 不显示),底部显示周几(中文窄格式:一二三四五六日)
 */
@Composable
private fun WeeklyTrendCard(
    weeklyTrend: List<Pair<LocalDate, Int>>,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val counts = weeklyTrend.map { it.second }
    val maxCount = counts.maxOrNull()?.takeIf { it > 0 } ?: 1

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_weekly_trend),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                weeklyTrend.forEach { (date, count) ->
                    val ratio = if (maxCount > 0) count.toFloat() / maxCount else 0f
                    val isToday = date == today
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 柱顶数值(0 不显示,但保留行高对齐)
                        Text(
                            text = if (count > 0) formatCount(count) else "",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                        // 柱状图区域:固定高度内底部对齐,柱条按 ratio 填充高度
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            if (count > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(ratio)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(
                                            if (isToday) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        ),
                                )
                            }
                        }
                        // 周几标签(中文窄格式)
                        Text(
                            text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.CHINESE),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 热力图卡片: 标题 + 53 周 × 7 天网格 + 图例(少 ↔ 多)。
 */
@Composable
private fun HeatmapCard(
    messagesPerDay: Map<LocalDate, Int>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(), shape = MuseShapes.extraLarge) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_heatmap),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            ChatHeatmap(messagesPerDay = messagesPerDay)

            // 图例: 少 ↔ 多
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(2.dp))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                    HeatmapCell(alpha = alpha, sizeDp = 10)
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.stats_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 聊天热力图: 53 周 × 7 天网格。
 *
 * - 起始日: today.with(previousOrSame(SUNDAY)).minusWeeks(52)
 * - 4 级 alpha: 0 / 0.25 / 0.5 / 0.75 / 1.0(基于四分位数 q1/q2/q3)
 * - 横向滚动,初始滚到最右(用 rememberScrollState(initial = Int.MAX_VALUE),无闪烁)
 * - 左侧周几标签(Mon/Wed/Fri)+ 顶部月份标签行
 *
 * M-ST1 说明: 当前用嵌套 Row/Column + Box 渲染 53×7=371 个单元格,每个单元格是轻量 Box,
 * 仅做 clip + background,无子组件测量开销。371 个 Box 在现代设备上性能可接受(单次测量 pass)。
 * 若后续需进一步优化(如缩减滚动惯性帧),可改用 Canvas + drawRect 绘制整张热力图,
 * 将 371 个 Box 降为 1 个 Canvas 节点,但需自行处理触摸命中检测。
 */
@Composable
private fun ChatHeatmap(messagesPerDay: Map<LocalDate, Int>) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    // 四分位数(只在有消息的日子中算,避免 0 拉低阈值)
    val activeCounts = messagesPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }

    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp

    // 周几标签: 只显示 Mon/Wed/Fri 节省空间(Sun=0 索引)
    val dowLabels = listOf("", "Mon", "", "Wed", "", "Fri", "")

    // 初始滚到最右(最新一周),用 Int.MAX_VALUE 避免 LaunchedEffect 闪烁
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // 左侧固定列: 顶部留白(对齐月份标签行高度)+ 7 个周几标签
        Column(
            modifier = Modifier.width(16.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        // 右侧滚动区: 月份标签行 + 热力图网格(共享同一 scrollState)
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 月份标签行: 遍历 53 周,每周取该周内第一个 dayOfMonth==1 的日期作为月份标签
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75,
                                color = MaterialTheme.colorScheme.outline,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // 热力图主体: 53 列(周)× 7 行(天)
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (messagesPerDay[date] ?: 0)
                            val alpha = when {
                                isFuture -> -1f
                                count == 0 -> 0f
                                count <= q1 -> 0.25f
                                count <= q2 -> 0.5f
                                count <= q3 -> 0.75f
                                else -> 1f
                            }
                            HeatmapCell(alpha = alpha, sizeDp = cellSize.value.toInt())
                        }
                    }
                }
            }
        }
    }
}

/**
 * 热力图单元格: Box + clip(extraSmall) + background。
 *
 * - alpha < 0: 未来日期,surfaceVariant 30% 透明
 * - alpha == 0: 当天无消息,surfaceVariant
 * - alpha > 0: 有消息,primary 按 alpha 叠加(越深越活跃)
 */
@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int) {
    val color = when {
        alpha < 0f -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        alpha == 0f -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
    )
}

/**
 * 大数格式化:>= 1M 用 "M" 后缀,>= 1K 用 "K" 后缀(保留 1 位小数,整数则省略小数)。
 * - 1234 → "1.2K"
 * - 1000000 → "1M"
 * - 999 → "999"
 */
private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> formatWithSuffix(count / 1_000_000.0, "M")
    count >= 1_000 -> formatWithSuffix(count / 1_000.0, "K")
    else -> count.toString()
}

/** 平均值格式化:大数走 K/M 后缀,小数保留 1 位。 */
private fun formatAvg(avg: Double): String = when {
    avg >= 1_000_000 -> formatWithSuffix(avg / 1_000_000.0, "M")
    avg >= 1_000 -> formatWithSuffix(avg / 1_000.0, "K")
    else -> String.format(Locale.US, "%.1f", avg)
}

/** 带后缀格式化:四舍五入到 1 位小数,整数部分省略小数点(避免 "1.0K")。 */
private fun formatWithSuffix(value: Double, suffix: String): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()}$suffix"
    } else {
        String.format(Locale.US, "%.1f", rounded) + suffix
    }
}

// ── v0.47: 4 个新统计卡片 ──────────────────────────────────────────────

/**
 * 模型使用占比卡片:环形图 + 图例。
 *
 * - 环形图:Canvas + drawArc(Stroke 风格),按 percentage 分配 sweepAngle
 * - 图例:每行显示 色块 + 模型名 + 数量 + 百分比
 * - 空数据显示"暂无数据"
 */
@Composable
private fun ModelUsageCard(
    modelCounts: List<StatsViewModel.ModelUsage>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_model_usage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (modelCounts.isEmpty()) {
                EmptyStatsHint()
            } else {
                // L-ST3 修复: palette 原先每次重组都新建 8 元素 List。现先在 @Composable 上下文
                // 读取主题色,再用 remember 缓存 List,仅在主题色变化时重建
                val primary = MaterialTheme.colorScheme.primary
                val secondary = MaterialTheme.colorScheme.secondary
                val tertiary = MaterialTheme.colorScheme.tertiary
                val errorColor = MaterialTheme.colorScheme.error
                val palette = remember(primary, secondary, tertiary, errorColor) {
                    listOf(
                        primary,
                        secondary,
                        tertiary,
                        errorColor,
                        primary.copy(alpha = 0.6f),
                        secondary.copy(alpha = 0.6f),
                        tertiary.copy(alpha = 0.6f),
                        errorColor.copy(alpha = 0.6f),
                    )
                }
                val totalCount = modelCounts.sumOf { it.count }
                // 预提取无障碍描述(semantics lambda 内非 Composable,无法直接调用 stringResource)
                val modelUsageCd = stringResource(R.string.stats_model_usage_cd, modelCounts.joinToString(", ") { "${it.modelName} ${(it.percentage * 100).toInt()}%" })

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 环形图(中心显示总数)
                    Box(
                        modifier = Modifier.size(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics {
                                    contentDescription = modelUsageCd
                                },
                        ) {
                            val stroke = 24.dp.toPx()
                            val inset = stroke / 2f
                            val dim = size.minDimension - stroke
                            val arcSize = Size(dim, dim)
                            val arcTopLeft = Offset(inset, inset)
                            var startAngle = -90f
                            modelCounts.forEachIndexed { idx, usage ->
                                val sweep = usage.percentage * 360f
                                if (sweep > 0f) {
                                    drawArc(
                                        color = palette[idx % palette.size],
                                        startAngle = startAngle,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        topLeft = arcTopLeft,
                                        size = arcSize,
                                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                                    )
                                }
                                startAngle += sweep
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formatCount(totalCount),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.stats_total_replies),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    // 图例列表
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        modelCounts.forEachIndexed { idx, usage ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(palette[idx % palette.size]),
                                )
                                Text(
                                    text = usage.modelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${formatCount(usage.count)} · ${(usage.percentage * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 助手使用占比卡片:横向条形图。
 *
 * - 每行一个助手:名称(左)+ 条形轨道(中,按比例填充)+ 数量(右)
 * - 条形长度按 count / maxCount 等比缩放
 * - 空数据显示"暂无数据"
 */
@Composable
private fun AssistantUsageCard(
    assistantCounts: List<StatsViewModel.AssistantUsage>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_assistant_usage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (assistantCounts.isEmpty()) {
                EmptyStatsHint()
            } else {
                val maxCount = (assistantCounts.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    assistantCounts.forEach { usage ->
                        val ratio = usage.count.toFloat() / maxCount
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = usage.assistantName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = formatCount(usage.count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            // 条形轨道 + 填充
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio)
                                        .fillMaxHeight()
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 小时活跃分布卡片:24 根柱状图。
 *
 * - 横轴 0-23 时,每 3 小时显示标签(0/3/6/9/12/15/18/21)
 * - 柱高按 count / maxCount 等比缩放
 * - 最高柱用 primary 高亮,其余 primary 45% 透明
 * - 全 0 数据显示"暂无数据"
 */
@Composable
private fun HourlyDistributionCard(
    hourlyDistribution: List<Int>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_hourly_dist),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            val hasData = hourlyDistribution.isNotEmpty() && hourlyDistribution.any { it > 0 }
            if (!hasData) {
                EmptyStatsHint()
            } else {
                val counts = hourlyDistribution.ifEmpty { List(24) { 0 } }
                val maxCount = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
                val maxIdx = counts.indices.maxByOrNull { counts[it] } ?: -1

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 柱状图区域
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        counts.forEachIndexed { idx, count ->
                            val ratio = count.toFloat() / maxCount
                            val isMax = idx == maxIdx && count > 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                if (count > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(ratio)
                                            .clip(MaterialTheme.shapes.extraSmall)
                                            .background(
                                                if (isMax) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                            ),
                                    )
                                }
                            }
                        }
                    }
                    // 横轴标签(每 3 小时显示)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        counts.indices.forEach { idx ->
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (idx % 3 == 0) {
                                    Text(
                                        text = idx.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top 10 活跃会话卡片:列表。
 *
 * - 每行显示序号 + 会话标题 + 消息数
 * - 行可点击,v1.66 起跳转到对应会话
 * - 空数据显示"暂无数据"
 */
@Composable
private fun TopSessionsCard(
    topSessions: List<StatsViewModel.TopSessionInfo>,
    onOpenSession: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_top_sessions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))

            if (topSessions.isEmpty()) {
                EmptyStatsHint()
            } else {
                topSessions.forEachIndexed { idx, session ->
                    if (idx > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(role = Role.Button) { onOpenSession(session.sessionId) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = (idx + 1).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(20.dp),
                        )
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.stats_message_count, formatCount(session.count)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/** 空数据提示文本。 */
@Composable
private fun EmptyStatsHint() {
    Text(
        text = stringResource(R.string.stats_no_data),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
    )
}
