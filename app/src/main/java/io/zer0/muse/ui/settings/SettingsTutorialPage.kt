package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.61-B: 使用教程页 — 面向新手的图文引导。
 *
 * 把用户当成完全不懂技术的小白,用通俗语言讲解 Muse 的各项功能。
 * 分为七个章节,每章用圆角卡片(MuseShapes.large)包裹,风格对标 iOS 设置。
 * 禁止 emoji,禁止 Android 原生方块风格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTutorialPage(
    onBack: () -> Unit,
) {
    SettingsSubPageScaffold(title = stringResource(R.string.settings_tutorial_title), onBack = onBack) {
        // 第一章:开始使用(新手必读)
        item {
            TutorialChapter(
                icon = Icons.Outlined.RocketLaunch,
                title = stringResource(R.string.settings_tutorial_ch1_title),
                sections = listOf(
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch1_s1_title),
                        content = stringResource(R.string.settings_tutorial_ch1_s1_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch1_s2_title),
                        content = stringResource(R.string.settings_tutorial_ch1_s2_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch1_s3_title),
                        content = stringResource(R.string.settings_tutorial_ch1_s3_content),
                    ),
                ),
            )
        }

        // 第二章:配置 AI 模型(重要)
        item {
            TutorialChapter(
                icon = Icons.Outlined.Key,
                title = stringResource(R.string.settings_tutorial_ch2_title),
                sections = listOf(
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch2_s1_title),
                        content = stringResource(R.string.settings_tutorial_ch2_s1_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch2_s2_title),
                        content = stringResource(R.string.settings_tutorial_ch2_s2_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch2_s3_title),
                        content = stringResource(R.string.settings_tutorial_ch2_s3_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch2_s4_title),
                        content = stringResource(R.string.settings_tutorial_ch2_s4_content),
                    ),
                ),
            )
        }

        // 第三章:日常聊天
        item {
            TutorialChapter(
                icon = Icons.AutoMirrored.Outlined.Chat,
                title = stringResource(R.string.settings_tutorial_ch3_title),
                sections = listOf(
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch3_s1_title),
                        content = stringResource(R.string.settings_tutorial_ch3_s1_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch3_s2_title),
                        content = stringResource(R.string.settings_tutorial_ch3_s2_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch3_s3_title),
                        content = stringResource(R.string.settings_tutorial_ch3_s3_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch3_s4_title),
                        content = stringResource(R.string.settings_tutorial_ch3_s4_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch3_s5_title),
                        content = stringResource(R.string.settings_tutorial_ch3_s5_content),
                    ),
                ),
            )
        }

        // 第四章:高级功能
        item {
            TutorialChapter(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(R.string.settings_tutorial_ch4_title),
                sections = listOf(
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch4_s1_title),
                        content = stringResource(R.string.settings_tutorial_ch4_s1_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch4_s2_title),
                        content = stringResource(R.string.settings_tutorial_ch4_s2_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch4_s3_title),
                        content = stringResource(R.string.settings_tutorial_ch4_s3_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch4_s4_title),
                        content = stringResource(R.string.settings_tutorial_ch4_s4_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch4_s5_title),
                        content = stringResource(R.string.settings_tutorial_ch4_s5_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch4_s6_title),
                        content = stringResource(R.string.settings_tutorial_ch4_s6_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch4_s7_title),
                        content = stringResource(R.string.settings_tutorial_ch4_s7_content),
                    ),
                ),
            )
        }

        // 第五章:个性化设置
        item {
            TutorialChapter(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_tutorial_ch5_title),
                sections = listOf(
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch5_s1_title),
                        content = stringResource(R.string.settings_tutorial_ch5_s1_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch5_s2_title),
                        content = stringResource(R.string.settings_tutorial_ch5_s2_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch5_s3_title),
                        content = stringResource(R.string.settings_tutorial_ch5_s3_content),
                    ),
                ),
            )
        }

        // 第六章:数据管理
        item {
            TutorialChapter(
                icon = Icons.Outlined.Storage,
                title = stringResource(R.string.settings_tutorial_ch6_title),
                sections = listOf(
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch6_s1_title),
                        content = stringResource(R.string.settings_tutorial_ch6_s1_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch6_s2_title),
                        content = stringResource(R.string.settings_tutorial_ch6_s2_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch6_s3_title),
                        content = stringResource(R.string.settings_tutorial_ch6_s3_content),
                    ),
                ),
            )
        }

        // 第七章:常见问题
        item {
            TutorialChapter(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                title = stringResource(R.string.settings_tutorial_ch7_title),
                sections = listOf(
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch7_s1_title),
                        content = stringResource(R.string.settings_tutorial_ch7_s1_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch7_s2_title),
                        content = stringResource(R.string.settings_tutorial_ch7_s2_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch7_s3_title),
                        content = stringResource(R.string.settings_tutorial_ch7_s3_content),
                    ),
                    TutorialSection(
                        title = stringResource(R.string.settings_tutorial_ch7_s4_title),
                        content = stringResource(R.string.settings_tutorial_ch7_s4_content),
                    ),
                ),
            )
        }
    }
}

/**
 * 教程章节卡片 — 图标 + 标题 + 多个小节。
 * 使用 MuseShapes.large 圆角(18dp),对标 iOS 设置分组卡片风格。
 */
@Composable
private fun TutorialChapter(
    icon: ImageVector,
    title: String,
    sections: List<TutorialSection>,
) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(12.dp))
            sections.forEach { section ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = section.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 教程小节 — 标题 + 正文内容。 */
private data class TutorialSection(
    val title: String,
    val content: String,
)
