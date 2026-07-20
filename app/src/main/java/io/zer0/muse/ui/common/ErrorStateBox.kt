package io.zer0.muse.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge

/**
 * 统一错误状态组件,带重试按钮。
 *
 * 用于网络请求失败 / 加载异常等场景,统一错误图标 + 文案 + 重试 / 关闭操作。
 * 与 [EmptyState] 配对,覆盖"空 / 错"两类占位态。
 *
 * @param message 错误文案
 * @param onRetry 重试回调(可选,提供后显示"重试"按钮)
 * @param onDismiss 关闭回调(可选,提供后显示"关闭"按钮)
 * @param modifier Modifier
 */
@Composable
fun ErrorStateBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(MusePaddings.screen),
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                // L-ESB3: 24.dp → MuseIconSizes.icon 令牌。
                modifier = Modifier.size(MuseIconSizes.icon),
            )
            // L-ESB2: Column 内仅一个 Text,旧 spacedBy(4.dp) 无意义,删除。
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            // M-ESB1: 裸 TextButton 替换为 FilledTonalButton + pill 形状,
            // 与 EmptyState 操作按钮视觉一致,避免使用 Material 默认文字按钮样式。
            if (onRetry != null) {
                FilledTonalButton(
                    onClick = onRetry,
                    shape = MuseShapes.pill,
                ) {
                    Text(stringResource(R.string.common_retry))
                }
            }
            if (onDismiss != null) {
                FilledTonalButton(
                    onClick = onDismiss,
                    shape = MuseShapes.pill,
                ) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}
