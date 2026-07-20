package io.zer0.muse.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * 统一空状态组件,所有列表页空态复用。
 *
 * 用于会话列表 / 收藏 / 知识库 / 计划任务等页面在"无数据"时的占位提示,
 * 统一图标 + 标题 + 副标题 + 可选操作按钮的视觉。
 *
 * @param icon 顶部图标(默认收件箱,空数据语义)
 * @param title 标题文案
 * @param subtitle 副标题文案(可选,灰色说明)
 * @param actionText 操作按钮文案(可选,提供后与 [onAction] 一同显示)
 * @param onAction 操作按钮回调(可选)
 * @param modifier Modifier
 */
@Composable
fun EmptyState(
    icon: ImageVector = Icons.Outlined.Inbox,
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // L-ES3: 空状态用 2x screen 边距(32dp),比常规 16dp 更大留白,
            // 强化"无内容"的呼吸感,对标 iOS 空态视觉。
            .padding(MusePaddings.screen * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            // L-ES2: 64.dp → MuseIconSizes.iconEmpty 令牌(空状态大图标)。
            modifier = Modifier.size(MuseIconSizes.iconEmpty),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            FilledTonalButton(
                onClick = onAction,
                shape = MuseShapes.pill,
            ) {
                Text(actionText)
            }
        }
    }
}
