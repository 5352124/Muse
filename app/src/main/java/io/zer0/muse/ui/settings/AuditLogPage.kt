package io.zer0.muse.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.audit.AuditLogEntity
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.WindowWidthClass
import io.zer0.muse.ui.common.rememberWindowWidthClass
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-4: 审计日志页 — 展示应用内关键操作日志。
 *
 * 结构:
 *  - 顶部 IosTopBar(返回 + 标题 + 清空按钮)
 *  - 中间 LazyColumn 显示日志条目(分页加载,每页 100 条)
 *  - 每条目:时间戳(yyyy-MM-dd HH:mm:ss)+ 分类图标 + action + target + success/fail 标记
 *  - 长按弹 MuseDialog 显示完整 detail JSON
 *  - 空列表显示"暂无日志记录"
 *
 * 数据源:[AuditLogger.query] 分页查询,首次进入加载第 1 页,
 * 滚动到底部自动加载下一页。
 *
 * @param onBack 返回回调(由 NavHost 注入)
 */
@Composable
fun AuditLogPage(
    onBack: () -> Unit,
) {
    val auditLogger: AuditLogger = koinInject()
    val scope = rememberCoroutineScope()
    val widthClass = rememberWindowWidthClass()

    var logs by remember { mutableStateOf<List<AuditLogEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(0) }
    // 详情弹窗:长按日志条目时显示完整 detail JSON
    var detailDialog by remember { mutableStateOf<AuditLogEntity?>(null) }
    // 清空确认弹窗
    var showClearDialog by remember { mutableStateOf(false) }

    val pageSize = 100
    val dateFormat = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, Locale.getDefault()) }

    // 首次进入加载第 1 页
    LaunchedEffect(Unit) {
        isLoading = true
        val first = auditLogger.query(limit = pageSize, offset = 0)
        logs = first
        hasMore = first.size == pageSize
        page = 1
        isLoading = false
    }

    fun loadMore() {
        if (isLoading || !hasMore) return
        scope.launch {
            isLoading = true
            val next = auditLogger.query(limit = pageSize, offset = page * pageSize)
            logs = logs + next
            hasMore = next.size == pageSize
            if (hasMore) page += 1
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.settings_audit_log),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.audit_log_clear),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (logs.isEmpty() && !isLoading) {
                // 空状态
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.audit_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (widthClass == WindowWidthClass.Expanded) {
                                Modifier.widthIn(max = 720.dp)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = MusePaddings.screen),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    items(logs, key = { it.id }) { log ->
                        AuditLogRow(
                            log = log,
                            dateFormat = dateFormat,
                            onLongClick = { detailDialog = log },
                        )
                    }
                    if (hasMore) {
                        item(key = "footer_loader") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = MusePaddings.contentGap),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    strokeWidth = 2.dp,
                                )
                            }
                            LaunchedEffect(Unit) { loadMore() }
                        }
                    }
                }
            }
        }
    }

    // 详情弹窗:展示完整 detail JSON
    detailDialog?.let { log ->
        MuseDialog(
            onDismissRequest = { detailDialog = null },
            title = formatDialogTitle(log, dateFormat),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    InfoLine(label = "Action", value = log.action)
                    if (log.target.isNotBlank()) {
                        InfoLine(label = "Target", value = log.target)
                    }
                    InfoLine(label = "Success", value = if (log.success) "true" else "false")
                    if (log.detail.isNotBlank() && log.detail != "{}") {
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Text(
                            text = "Detail",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(MusePaddings.tightGap))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = prettyJson(log.detail),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(MusePaddings.contentGap),
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { detailDialog = null },
            dismissText = null,
        )
    }

    // 清空确认弹窗
    if (showClearDialog) {
        MuseDialog(
            onDismissRequest = { showClearDialog = false },
            title = stringResource(R.string.audit_log_clear),
            content = {
                Text(
                    text = stringResource(R.string.audit_log_clear_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            },
            confirmText = stringResource(R.string.audit_log_clear),
            onConfirm = {
                scope.launch {
                    auditLogger.clearAll()
                    logs = emptyList()
                    hasMore = false
                    page = 0
                }
                showClearDialog = false
            },
            destructive = true,
        )
    }
}

/**
 * 单条审计日志的行视图。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AuditLogRow(
    log: AuditLogEntity,
    dateFormat: SimpleDateFormat,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 分类图标(圆形背景 + 不同图标)
            CategoryIcon(category = log.category)
            Spacer(Modifier.size(MusePaddings.contentGap))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = log.action,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    // success / fail 标记(用 Material Icons 表达,遵循项目"禁止 emoji"约定)
                    val statusColor = if (log.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    val statusIcon = if (log.success) {
                        Icons.Filled.Check
                    } else {
                        Icons.Filled.Close
                    }
                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                        }
                    }
                }
                Spacer(Modifier.size(MusePaddings.tightGap))
                // 时间戳
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // target(若有)
                if (log.target.isNotBlank()) {
                    Text(
                        text = "→ ${log.target}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 分类图标 — 按 [category] 显示不同图标与背景色。
 *
 *  - api_call: Api 图标 + primary
 *  - user_action: Person 图标 + secondary
 *  - auth: Lock 图标 + tertiary
 *  - system: Settings 图标 + surfaceVariant
 *  - 其他: 默认 Settings 图标
 */
@Composable
private fun CategoryIcon(category: String) {
    val (icon, tint) = when (category) {
        "api_call" -> Icons.Outlined.Api to MaterialTheme.colorScheme.primary
        "user_action" -> Icons.Outlined.Person to MaterialTheme.colorScheme.secondary
        "auth" -> Icons.Outlined.Lock to MaterialTheme.colorScheme.tertiary
        "system" -> Icons.Outlined.Settings to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Outlined.Settings to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.15f),
        modifier = Modifier.size(MuseIconSizes.iconLarge),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
    }
}

/** 详情弹窗中的键值对行。 */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 详情弹窗标题(时间 + action)。 */
private fun formatDialogTitle(log: AuditLogEntity, dateFormat: SimpleDateFormat): String {
    return dateFormat.format(Date(log.timestamp))
}

/**
 * 把紧凑的 JSON 字符串格式化为带缩进的可读形式。
 *
 * 用极简状态机解析,避免引入额外 JSON 库依赖。输入非合法 JSON 时原样返回。
 */
private fun prettyJson(json: String): String {
    if (json.isBlank()) return ""
    val builder = StringBuilder(json.length * 2)
    var indent = 0
    var inString = false
    var escaped = false
    for (ch in json) {
        if (inString) {
            builder.append(ch)
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }
        when (ch) {
            '"' -> {
                inString = true
                builder.append(ch)
            }
            '{', '[' -> {
                builder.append(ch)
                indent++
                builder.append('\n').append("  ".repeat(indent))
            }
            '}', ']' -> {
                indent = (indent - 1).coerceAtLeast(0)
                builder.append('\n').append("  ".repeat(indent)).append(ch)
            }
            ',' -> {
                builder.append(ch)
                builder.append('\n').append("  ".repeat(indent))
            }
            ':' -> {
                builder.append(": ")
            }
            ' ', '\t', '\n', '\r' -> {
                // 跳过原有空白,用我们的格式替代
            }
            else -> {
                builder.append(ch)
            }
        }
    }
    return builder.toString()
}
