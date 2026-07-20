package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsSwitchRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 视觉辅助设置页 — 让纯文本模型通过视觉模型"看到"图片。
 *
 * 配置项:
 *  - 启用/禁用视觉辅助
 *  - 选择用于视觉分析的模型(仅显示支持视觉输入的模型)
 */
@Composable
fun VisionSettingsPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val enabled by settings.visionEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val visionModelId by settings.visionModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val visionProviderId by settings.visionProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    // 筛选支持视觉输入的模型
    val visionModels = providers.flatMap { provider ->
        provider.models
            .filter { it.supportsVisionInput() }
            .map { model -> VisionModelItem(model.id, model.name, provider.id, provider.displayName) }
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_vision_title),
        onBack = onBack,
    ) {
        // 功能说明
        item {
            SectionLabel(stringResource(R.string.settings_vision_description))
        }

        // 总开关
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Visibility,
                    title = stringResource(R.string.settings_vision_enabled),
                    subtitle = stringResource(R.string.settings_vision_enabled_subtitle),
                    checked = enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveVisionEnabled(v) }
                    },
                )
            }
        }

        // 视觉模型选择(仅在启用时显示)
        if (enabled) {
            item {
                SectionLabel(stringResource(R.string.settings_vision_model_section))
            }
            item {
                SettingsGroup {
                    if (visionModels.isEmpty()) {
                        // 无可用的视觉模型提示
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_vision_no_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        // 视觉模型列表
                        visionModels.forEachIndexed { index, item ->
                            val isSelected = item.modelId == visionModelId && item.providerId == visionProviderId
                            VisionModelRow(
                                modelName = item.modelName,
                                providerName = item.providerName,
                                isSelected = isSelected,
                                onClick = {
                                    scope.launch {
                                        settings.saveVisionModelId(item.modelId)
                                        settings.saveVisionProviderId(item.providerId)
                                    }
                                },
                            )
                            if (index < visionModels.size - 1) {
                                SettingsGroupDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 视觉模型选择行。
 */
@Composable
private fun VisionModelRow(
    modelName: String,
    providerName: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = modelName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = providerName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 视觉模型选项数据类。 */
private data class VisionModelItem(
    val modelId: String,
    val modelName: String,
    val providerId: String,
    val providerName: String,
)
