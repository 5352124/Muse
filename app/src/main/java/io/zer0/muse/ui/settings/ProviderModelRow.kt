package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.muse.R
import io.zer0.ai.core.ProviderType
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Provider 详情页模型行组件。
 *
 * 左侧 Provider 小图标,中间模型名 + 能力标签,右侧设置/添加按钮。
 */
@Composable
internal fun ProviderModelRow(
    model: Model,
    providerType: ProviderType,
    providerName: String,
    isAdded: Boolean = true,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 左侧 Provider 品牌图标(圆形背景)
        val brandColor = providerBrandColor(providerType, providerName)
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(brandColor.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = providerBrandIcon(providerType, providerName),
                contentDescription = null,
                tint = brandColor,
                modifier = Modifier.size(22.dp),
            )
        }

        // 中间:模型名 + 能力标签
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name.takeIf { it.isNotBlank() } ?: model.id,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(4.dp))
            ModelAbilityChips(model = model)
        }

        // 右侧操作按钮
        IconButton(onClick = onAction) {
            Icon(
                imageVector = if (isAdded) Icons.Default.Settings else Icons.Default.Add,
                contentDescription = if (isAdded) stringResource(R.string.settings_model_action_settings) else stringResource(R.string.settings_model_action_add),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 模型能力标签行。
 *
 * 按顺序显示:工具 / 推理 / 流式 / 画图 / 多模态 / 视频,没有任何能力时默认显示"聊天"。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelAbilityChips(model: Model) {
    val labelTool = stringResource(R.string.settings_model_ability_tool)
    val labelReasoning = stringResource(R.string.settings_model_ability_reasoning)
    val labelStreaming = stringResource(R.string.settings_model_ability_streaming)
    val labelImage = stringResource(R.string.settings_model_ability_image)
    val labelMultimodal = stringResource(R.string.settings_model_ability_multimodal)
    val labelVideo = stringResource(R.string.settings_model_ability_video)
    val labelChat = stringResource(R.string.settings_model_ability_chat)

    val labels = buildList {
        if (model.supportsToolCalling()) add(labelTool)
        if (model.supportsReasoning()) add(labelReasoning)
        if (model.supportsStreaming) add(labelStreaming)
        if (model.supportsImageOutput()) add(labelImage)
        if (model.supportsVisionInput()) add(labelMultimodal)
        if (model.supportsVideoOutput()) add(labelVideo)
    }.ifEmpty { listOf(labelChat) }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { label ->
            val (containerColor, contentColor) = when (label) {
                labelTool -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                labelReasoning -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                labelStreaming -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                labelImage -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                labelMultimodal -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                labelVideo -> MaterialTheme.colorScheme.inverseSurface to MaterialTheme.colorScheme.inverseOnSurface
                labelChat -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            }
            Surface(
                color = containerColor,
                shape = MuseShapes.small,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}
