package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.IosSlider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.ChevronRight
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.ui.speech.TtsManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.32: 媒体设置页。
 *
 * 控制语音录制和 TTS 语音播报的参数:
 *  - 录制采样率/比特率
 *  - TTS 开关/语速/音高/语言
 *  - 音频输出方式(扬声器/听筒/蓝牙)
 */
@Composable
fun MediaSettingsPage(
    onBack: () -> Unit,
    /** P2-9: 打开语音克隆页(从「语音播报(TTS)」分组入口进入)。 */
    onOpenVoiceCloning: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val ttsManager: TtsManager = koinInject()
    val config by settings.mediaConfigFlow.collectAsStateWithLifecycle(initialValue = MediaConfig())
    val scope = rememberCoroutineScope()

    SettingsSubPageScaffold(title = stringResource(R.string.settings_media_page_title), onBack = onBack) {
        // ── 1. 语音录制 ──
        item { SectionLabel(stringResource(R.string.settings_media_recording_section)) }
        item {
            SettingsGroup {
                SliderRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.settings_media_sample_rate),
                    subtitle = stringResource(R.string.settings_media_sample_rate_subtitle),
                    value = config.recordingSampleRate.toFloat(),
                    range = 8000f..48000f,
                    steps = 4,
                    valueText = "${config.recordingSampleRate / 1000} kHz",
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(recordingSampleRate = v.toInt())) }
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.settings_media_bit_rate),
                    subtitle = stringResource(R.string.settings_media_bit_rate_subtitle),
                    value = config.recordingBitRate.toFloat(),
                    range = 64000f..320000f,
                    steps = 7,
                    valueText = "${config.recordingBitRate / 1000} kbps",
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(recordingBitRate = v.toInt())) }
                    },
                )
            }
        }

        // ── 2. 语音播报(TTS) ──
        item { SectionLabel(stringResource(R.string.settings_media_tts_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.settings_media_tts_enable),
                    subtitle = stringResource(R.string.settings_media_tts_enable_subtitle),
                    checked = config.ttsEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsEnabled = v)) }
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.settings_media_speech_rate),
                    subtitle = stringResource(R.string.settings_media_speech_rate_subtitle),
                    value = config.ttsSpeechRate,
                    range = 0.5f..2.0f,
                    steps = 14,
                    valueText = "%.1fx".format(config.ttsSpeechRate),
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsSpeechRate = v)) }
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.settings_media_pitch),
                    subtitle = stringResource(R.string.settings_media_pitch_subtitle),
                    value = config.ttsPitch,
                    range = 0.5f..2.0f,
                    steps = 14,
                    valueText = "%.1fx".format(config.ttsPitch),
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsPitch = v)) }
                    },
                )
                // TTS 声音选择
                SettingsGroupDivider()
                TtsVoiceSelector(
                    ttsManager = ttsManager,
                    currentVoice = config.ttsVoiceName,
                    onVoiceSelected = { voiceName ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsVoiceName = voiceName)) }
                    },
                )
            }
        }

        // ── 3. 云端 TTS 引擎 ──
        item { SectionLabel(stringResource(R.string.settings_media_cloud_tts_section)) }
        item {
            CloudTtsConfigSection(
                config = config,
                settings = settings,
            )
        }

        // ── P2-9: 语音克隆入口(独立 SettingsGroup,从云端 TTS 引擎下方进入)──
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenVoiceCloning() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.voice_cloning_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.voice_cloning_new_voice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    ChevronRight()
                }
            }
        }

        // ── 4. 音频输出 ──
        item { SectionLabel(stringResource(R.string.settings_media_output_section)) }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_media_output_method),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_media_output_method_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val speakerLabel = stringResource(R.string.settings_media_output_speaker)
                            val earpieceLabel = stringResource(R.string.settings_media_output_earpiece)
                            val bluetoothLabel = stringResource(R.string.settings_media_output_bluetooth)
                            listOf("speaker" to speakerLabel, "earpiece" to earpieceLabel, "bluetooth" to bluetoothLabel).forEach { (value, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = config.audioOutput == value,
                                    onClick = {
                                        scope.launch { settings.saveMediaConfig(config.copy(audioOutput = value)) }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            IosSlider(
                value = value,
                onValueChange = { v -> onValueChange(v) },
                valueRange = range,
                steps = steps,
                modifier = Modifier.padding(top = 4.dp),
                showValueLabel = false,
            )
        }
    }
}

/**
 * TTS 声音选择器 — 下拉菜单列出系统可用的 TTS 声音。
 */
@Composable
private fun TtsVoiceSelector(
    ttsManager: TtsManager,
    currentVoice: String,
    onVoiceSelected: (String) -> Unit,
) {
    val voices = remember { ttsManager.getAvailableVoices() }
    if (voices.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val displayName = if (currentVoice.isNotBlank()) currentVoice else "默认"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.RecordVoiceOver,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "TTS 声音",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        ChevronRight()
        Box {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("默认") },
                    onClick = {
                        onVoiceSelected("")
                        expanded = false
                    },
                )
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice.name) },
                        onClick = {
                            onVoiceSelected(voice.name)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * v1.97: 云端 TTS 引擎配置区。
 *
 * 引擎选择(system + 11 家云端 Provider)→ API Key / Voice / Model / Endpoint 表单。
 * system 模式仅显示引擎选择,隐藏表单。
 * 表单交互参考 [AsrSection]:OutlinedTextField + 保存按钮 + Toast 反馈。
 */
@Composable
private fun CloudTtsConfigSection(
    config: MediaConfig,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val systemLabel = stringResource(R.string.settings_media_tts_engine_system)
    val savedToast = stringResource(R.string.settings_media_tts_saved)

    SettingsGroup {
        // 引擎选择(system + 11 家云端)
        var engineExpanded by remember { mutableStateOf(false) }
        // stringResource 必须在 @Composable 作用域直接调用,不能在 let/lambda 内嵌套
        val matchedResId = TtsManager.CLOUD_TTS_ENGINES
            .firstOrNull { it.first == config.ttsEngine }?.second
        val currentLabel = if (config.ttsEngine == "system" || matchedResId == null) systemLabel
        else stringResource(matchedResId)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { engineExpanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudQueue,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_media_tts_engine),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            ChevronRight()
            Box {
                DropdownMenu(
                    expanded = engineExpanded,
                    onDismissRequest = { engineExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(systemLabel) },
                        onClick = {
                            scope.launch { settings.saveMediaConfig(config.copy(ttsEngine = "system")) }
                            engineExpanded = false
                        },
                    )
                    TtsManager.CLOUD_TTS_ENGINES.forEach { (engineId, labelRes) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            onClick = {
                                scope.launch { settings.saveMediaConfig(config.copy(ttsEngine = engineId)) }
                                engineExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // system 模式不显示表单
        if (config.ttsEngine == "system") return@SettingsGroup

        // API Key
        SettingsGroupDivider()
        var apiKey by remember(config.ttsEngine) { mutableStateOf(config.ttsApiKey) }
        var apiKeyVisible by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.settings_media_tts_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Text(stringResource(if (apiKeyVisible) R.string.settings_media_tts_show
                        else R.string.settings_media_tts_hide))
                    }
                },
            )
            TextButton(
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(config.copy(ttsApiKey = apiKey.trim()))
                        MuseToast.show(savedToast)
                    }
                },
            ) { Text(stringResource(R.string.settings_media_tts_save_api_key)) }
        }

        // 音色 / Voice ID
        SettingsGroupDivider()
        var voice by remember(config.ttsEngine) { mutableStateOf(config.ttsVoice) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = voice,
                onValueChange = { voice = it },
                label = { Text(stringResource(R.string.settings_media_tts_voice)) },
                placeholder = { Text(stringResource(R.string.settings_media_tts_voice_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(config.copy(ttsVoice = voice.trim()))
                        MuseToast.show(savedToast)
                    }
                },
            ) { Text(stringResource(R.string.settings_media_tts_save_voice)) }
        }

        // 模型
        SettingsGroupDivider()
        var model by remember(config.ttsEngine) { mutableStateOf(config.ttsModel) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.settings_media_tts_model)) },
                placeholder = { Text(stringResource(R.string.settings_media_tts_model_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(config.copy(ttsModel = model.trim()))
                        MuseToast.show(savedToast)
                    }
                },
            ) { Text(stringResource(R.string.settings_media_tts_save_model)) }
        }

        // 自定义 Endpoint
        SettingsGroupDivider()
        var endpoint by remember(config.ttsEngine) { mutableStateOf(config.ttsEndpoint) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text(stringResource(R.string.settings_media_tts_endpoint)) },
                placeholder = { Text(stringResource(R.string.settings_media_tts_endpoint_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(config.copy(ttsEndpoint = endpoint.trim()))
                        MuseToast.show(savedToast)
                    }
                },
            ) { Text(stringResource(R.string.settings_media_tts_save_endpoint)) }
        }
    }
}
