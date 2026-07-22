package io.zer0.muse.ui.onboarding

import android.app.Activity
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.PresetThemes
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// ── 测试连接状态 ──────────────────────────────────────────────
private sealed class TestStatus {
    data object Idle : TestStatus()
    data object Loading : TestStatus()
    data class Success(val models: List<Model>) : TestStatus()
    data class Error(val message: String) : TestStatus()
}

/**
 * 开机引导页面 — 全屏 HorizontalPager，6 步完成初始配置。
 *
 * 数据流：OnboardingScreen 接收 [onComplete] 回调，内部用 koinInject 获取
 * [SettingsRepository] / [AssistantRepository] / [PresetProviders]，
 * 每步完成后即时保存到 SettingsRepository。
 *
 * 设计令牌：使用 [MuseShapes]（iOS 风格圆角）与 [MusePaddings]（间距令牌），
 * 全程 Material Icons（无 emoji），Material 3 组件。
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val assistantRepo: AssistantRepository = koinInject()
    val presetProviders: PresetProviders = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 6 })
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── 各步骤状态 ──
    var selectedLanguage by rememberSaveable { mutableStateOf("zh") }
    var selectedThemeId by rememberSaveable { mutableStateOf("warm_paper") }
    var userName by rememberSaveable { mutableStateOf("") }
    var agentName by rememberSaveable { mutableStateOf("Muse") }
    var selectedPresetId by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<TestStatus>(TestStatus.Idle) }
    var fetchedModels by remember { mutableStateOf<List<Model>>(emptyList()) }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var modelSearchQuery by rememberSaveable { mutableStateOf("") }
    var providerSkipped by rememberSaveable { mutableStateOf(false) }

    // 翻页时收起键盘
    LaunchedEffect(pagerState.currentPage) {
        keyboardController?.hide()
    }

    // 当前选中的预设供应商
    val selectedPreset: ProviderConfig? = remember(selectedPresetId) {
        presetProviders.all.firstOrNull { it.id == selectedPresetId }
    }

    val pageCount = 6
    val currentPage = pagerState.currentPage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶部进度圆点
        ProgressDots(
            currentPage = currentPage,
            pageCount = pageCount,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MusePaddings.sectionGap),
        )

        // 内容区域
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> StepWelcome()
                1 -> StepLanguageTheme(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { lang ->
                        selectedLanguage = lang
                        scope.launch {
                            settings.saveLanguage(lang)
                            (context as? Activity)?.recreate()
                        }
                    },
                    selectedThemeId = selectedThemeId,
                    onThemeSelected = { id ->
                        selectedThemeId = id
                        scope.launch { settings.saveThemeId(id) }
                    },
                )
                2 -> StepNames(
                    userName = userName,
                    onUserNameChange = { userName = it },
                    agentName = agentName,
                    onAgentNameChange = { agentName = it },
                )
                3 -> StepProviderConfig(
                    presetProviders = presetProviders,
                    selectedPresetId = selectedPresetId,
                    onPresetSelected = { id ->
                        selectedPresetId = id
                        testStatus = TestStatus.Idle
                        fetchedModels = emptyList()
                    },
                    apiKey = apiKey,
                    onApiKeyChange = {
                        apiKey = it
                        testStatus = TestStatus.Idle
                    },
                    apiKeyVisible = apiKeyVisible,
                    onToggleApiKeyVisible = { apiKeyVisible = !apiKeyVisible },
                    testStatus = testStatus,
                    onTestConnection = {
                        val preset = selectedPreset
                        if (preset == null || apiKey.isBlank()) return@StepProviderConfig
                        testStatus = TestStatus.Loading
                        scope.launch {
                            val config = preset.copy(apiKey = apiKey)
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    ProviderRegistry.create(config).listModels(config)
                                }
                            }
                            result.onSuccess { models ->
                                fetchedModels = models
                                testStatus = TestStatus.Success(models)
                                if (models.isNotEmpty()) {
                                    selectedModelId = models.first().id
                                }
                            }.onFailure { e ->
                                Logger.w("Onboarding", "测试连接失败: ${e.message}", e)
                                testStatus = TestStatus.Error(e.message ?: "连接失败")
                            }
                        }
                    },
                    onSkip = {
                        providerSkipped = true
                        scope.launch { pagerState.animateScrollToPage(5) }
                    },
                )
                4 -> StepSelectModel(
                    models = fetchedModels,
                    selectedModelId = selectedModelId,
                    onSelectModel = { id ->
                        selectedModelId = id
                        scope.launch { settings.saveSelectedModel(id) }
                    },
                    searchQuery = modelSearchQuery,
                    onSearchQueryChange = { modelSearchQuery = it },
                    providerSkipped = providerSkipped,
                )
                5 -> StepComplete()
            }
        }

        // 底部按钮区域
        BottomButtons(
            currentPage = currentPage,
            // 步骤 3 需测试成功才能下一步
            canGoNext = when (currentPage) {
                3 -> testStatus is TestStatus.Success
                else -> true
            },
            onPrevious = {
                scope.launch { pagerState.animateScrollToPage(currentPage - 1) }
            },
            onNext = {
                // 翻页前保存当前步骤数据
                scope.launch {
                    saveStepData(
                        currentPage = currentPage,
                        settings = settings,
                        assistantRepo = assistantRepo,
                        userName = userName,
                        agentName = agentName,
                        selectedPreset = selectedPreset,
                        apiKey = apiKey,
                        fetchedModels = fetchedModels,
                        selectedModelId = selectedModelId,
                        providerSkipped = providerSkipped,
                    )
                    val targetPage = when {
                        // 步骤 3 → 若跳过则直接到步骤 5
                        currentPage == 3 && providerSkipped -> 5
                        // 步骤 4 → 若无供应商则直接到步骤 5
                        currentPage == 4 && providerSkipped -> 5
                        else -> currentPage + 1
                    }
                    pagerState.animateScrollToPage(targetPage)
                }
            },
            onComplete = {
                scope.launch {
                    saveStepData(
                        currentPage = currentPage,
                        settings = settings,
                        assistantRepo = assistantRepo,
                        userName = userName,
                        agentName = agentName,
                        selectedPreset = selectedPreset,
                        apiKey = apiKey,
                        fetchedModels = fetchedModels,
                        selectedModelId = selectedModelId,
                        providerSkipped = providerSkipped,
                    )
                    settings.saveOnboardingShown()
                    onComplete()
                }
            },
        )
    }
}

// ── 顶部进度圆点 ──────────────────────────────────────────────
@Composable
private fun ProgressDots(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isCurrent = index == currentPage
            val isCompleted = index < currentPage
            val size by animateDpAsState(
                targetValue = if (isCurrent) 10.dp else 6.dp,
                label = "dot_size",
            )
            val color = when {
                isCurrent || isCompleted -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = MusePaddings.tightGap)
                    .size(size)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

// ── 步骤 0：欢迎页 ──────────────────────────────────────────────
@Composable
private fun StepWelcome() {
    val features = listOf(
        FeatureItem(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.onboarding_feature_chat_title), stringResource(R.string.onboarding_feature_chat_desc)),
        FeatureItem(Icons.Outlined.Psychology, stringResource(R.string.onboarding_feature_memory_title), stringResource(R.string.onboarding_feature_memory_desc)),
        FeatureItem(Icons.Outlined.Build, stringResource(R.string.onboarding_feature_tools_title), stringResource(R.string.onboarding_feature_tools_desc)),
        FeatureItem(Icons.AutoMirrored.Outlined.LibraryBooks, stringResource(R.string.onboarding_feature_knowledge_title), stringResource(R.string.onboarding_feature_knowledge_desc)),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap * 2))
        // 2x2 功能特性网格
        features.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                rowItems.forEach { item ->
                    FeatureCard(
                        item = item,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }
    }
}

private data class FeatureItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val description: String,
)

@Composable
private fun FeatureCard(
    item: FeatureItem,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(MusePaddings.tightGap))
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 步骤 1：语言与外观 ──────────────────────────────────────────
@Composable
private fun StepLanguageTheme(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    selectedThemeId: String,
    onThemeSelected: (String) -> Unit,
) {
    val languages = listOf(
        "zh" to stringResource(R.string.onboarding_lang_zh),
        "zh-TW" to stringResource(R.string.onboarding_lang_zh_tw),
        "en" to stringResource(R.string.onboarding_lang_en),
        "ja" to stringResource(R.string.onboarding_lang_ja),
        "ko" to stringResource(R.string.onboarding_lang_ko),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_language_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        languages.forEach { (code, label) ->
            LanguageCard(
                label = label,
                selected = selectedLanguage == code,
                onClick = { onLanguageSelected(code) },
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }

        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        Text(
            text = stringResource(R.string.onboarding_theme_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        // 12 套主题网格（3 列 x 4 行）
        PresetThemes.chunked(3).forEach { rowThemes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                rowThemes.forEach { theme ->
                    ThemeColorBlock(
                        color = theme.lightScheme.primary,
                        selected = selectedThemeId == theme.id,
                        onClick = { onThemeSelected(theme.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 不足 3 个时补齐占位，保持网格对齐
                repeat(3 - rowThemes.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }
    }
}

@Composable
private fun LanguageCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ThemeColorBlock(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    val borderWidth = if (selected) 3.dp else 0.dp
    Surface(
        modifier = modifier
            .height(56.dp)
            .clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = color,
        border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
    ) {}
}

// ── 步骤 2：你的名字 ──────────────────────────────────────────
@Composable
private fun StepNames(
    userName: String,
    onUserNameChange: (String) -> Unit,
    agentName: String,
    onAgentNameChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_names_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        OutlinedTextField(
            value = userName,
            onValueChange = onUserNameChange,
            label = { Text(stringResource(R.string.onboarding_names_user_label)) },
            singleLine = true,
            shape = MuseShapes.semiLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        OutlinedTextField(
            value = agentName,
            onValueChange = onAgentNameChange,
            label = { Text(stringResource(R.string.onboarding_names_agent_label)) },
            singleLine = true,
            shape = MuseShapes.semiLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_names_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 步骤 3：配置供应商 ──────────────────────────────────────────
@Composable
private fun StepProviderConfig(
    presetProviders: PresetProviders,
    selectedPresetId: String,
    onPresetSelected: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisible: () -> Unit,
    testStatus: TestStatus,
    onTestConnection: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_provider_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_provider_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))

        // 供应商选择列表
        Text(
            text = stringResource(R.string.onboarding_provider_select_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        presetProviders.all.take(15).forEach { preset ->
            LanguageCard(
                label = preset.displayName,
                selected = selectedPresetId == preset.id,
                onClick = { onPresetSelected(preset.id) },
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }

        // API Key 输入
        Text(
            text = stringResource(R.string.onboarding_provider_apikey_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(stringResource(R.string.onboarding_provider_apikey_input)) },
            singleLine = true,
            shape = MuseShapes.semiLarge,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleApiKeyVisible) {
                    Icon(
                        imageVector = if (apiKeyVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = stringResource(
                            if (apiKeyVisible) R.string.onboarding_provider_apikey_hide
                            else R.string.onboarding_provider_apikey_show,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(MusePaddings.itemGap))

        // 测试连接按钮
        val canTest = selectedPresetId.isNotBlank() && apiKey.isNotBlank()
        Button(
            onClick = onTestConnection,
            enabled = canTest && testStatus !is TestStatus.Loading,
            shape = MuseShapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testStatus is TestStatus.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.onboarding_provider_test_button))
            }
        }

        // 测试结果展示
        when (testStatus) {
            is TestStatus.Success -> {
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(MusePaddings.iconPadding))
                    Text(
                        text = stringResource(R.string.onboarding_provider_test_success, testStatus.models.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            is TestStatus.Error -> {
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(MusePaddings.iconPadding))
                    Text(
                        text = testStatus.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            else -> {}
        }

        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        // 跳过配置
        TextButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.onboarding_provider_skip))
        }
    }
}

// ── 步骤 4：选择模型 ──────────────────────────────────────────
@Composable
private fun StepSelectModel(
    models: List<Model>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    providerSkipped: Boolean,
) {
    if (providerSkipped || models.isEmpty()) {
        // 步骤 3 被跳过或无模型时，提示用户
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MusePaddings.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_model_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
            Text(
                text = stringResource(R.string.onboarding_model_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val filteredModels = remember(searchQuery, models) {
        if (searchQuery.isBlank()) {
            models
        } else {
            models.filter { model ->
                model.id.contains(searchQuery, ignoreCase = true) ||
                    model.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_model_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text(stringResource(R.string.onboarding_model_search_label)) },
            singleLine = true,
            shape = MuseShapes.semiLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            items(filteredModels, key = { it.id }) { model ->
                ModelItem(
                    model = model,
                    selected = selectedModelId == model.id,
                    onClick = { onSelectModel(model.id) },
                )
            }
        }
    }
}

@Composable
private fun ModelItem(
    model: Model,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                )
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ── 步骤 5：完成页 ──────────────────────────────────────────────
@Composable
private fun StepComplete() {
    val tutorials = listOf(
        FeatureItem(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.onboarding_tutorial_chat_title), stringResource(R.string.onboarding_tutorial_chat_desc)),
        FeatureItem(Icons.Outlined.Person, stringResource(R.string.onboarding_tutorial_assistant_title), stringResource(R.string.onboarding_tutorial_assistant_desc)),
        FeatureItem(Icons.Outlined.Build, stringResource(R.string.onboarding_tutorial_tools_title), stringResource(R.string.onboarding_tutorial_tools_desc)),
        FeatureItem(Icons.Outlined.CloudUpload, stringResource(R.string.onboarding_tutorial_backup_title), stringResource(R.string.onboarding_tutorial_backup_desc)),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        Text(
            text = stringResource(R.string.onboarding_complete_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_complete_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        tutorials.forEach { item ->
            TutorialCard(item = item)
            Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        }
    }
}

@Composable
private fun TutorialCard(item: FeatureItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(MusePaddings.cardInner.calculateTopPadding()))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 底部按钮区域 ──────────────────────────────────────────────
@Composable
private fun BottomButtons(
    currentPage: Int,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onComplete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (currentPage) {
            // 步骤 0：只有"开始设置"按钮（居中）
            0 -> {
                Button(
                    onClick = onNext,
                    shape = MuseShapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                ) {
                    Text(stringResource(R.string.onboarding_button_start))
                }
            }
            // 步骤 5：只有"开始使用"按钮（居中）
            5 -> {
                Button(
                    onClick = onComplete,
                    shape = MuseShapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                ) {
                    Text(stringResource(R.string.onboarding_button_finish))
                }
            }
            // 步骤 1-4：左边"上一步"，右边"下一步"
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    OutlinedButton(
                        onClick = onPrevious,
                        shape = MuseShapes.medium,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(MusePaddings.tightGap))
                        Text(stringResource(R.string.onboarding_button_previous))
                    }
                    Button(
                        onClick = onNext,
                        enabled = canGoNext,
                        shape = MuseShapes.medium,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(stringResource(R.string.onboarding_button_next))
                        Spacer(modifier = Modifier.width(MusePaddings.tightGap))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── 步骤数据保存 ──────────────────────────────────────────────

/**
 * 翻页前保存当前步骤的数据到持久化存储。
 *
 * 各步骤对应的保存逻辑：
 *  - 步骤 2：保存用户名（mockLogin）+ 更新默认助手名字
 *  - 步骤 3：创建并保存 ProviderConfig + 设置激活供应商
 *  - 步骤 4：保存选中的模型 id
 *
 * 语言（步骤 1）和主题（步骤 1）在用户选择时即时保存，此处不重复。
 */
private suspend fun saveStepData(
    currentPage: Int,
    settings: SettingsRepository,
    assistantRepo: AssistantRepository,
    userName: String,
    agentName: String,
    selectedPreset: ProviderConfig?,
    apiKey: String,
    fetchedModels: List<Model>,
    selectedModelId: String,
    providerSkipped: Boolean,
) {
    when (currentPage) {
        2 -> {
            // 保存用户名
            settings.mockLogin(userName)
            // 更新默认助手名字
            val defaultAssistant = assistantRepo.getById("default")
            if (defaultAssistant != null && agentName.isNotBlank()) {
                assistantRepo.upsert(defaultAssistant.copy(name = agentName))
            }
        }
        3 -> {
            // 创建并保存供应商配置（仅当测试成功且未跳过时）
            if (!providerSkipped && selectedPreset != null && apiKey.isNotBlank()) {
                val config = selectedPreset.copy(
                    apiKey = apiKey,
                    models = fetchedModels,
                )
                settings.addProvider(config)
                settings.setActiveProvider(config.id)
            }
        }
        4 -> {
            // 保存选中的模型
            settings.saveSelectedModel(selectedModelId.ifBlank { null })
        }
    }
}
