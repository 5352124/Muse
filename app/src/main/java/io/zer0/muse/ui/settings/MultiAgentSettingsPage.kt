@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package io.zer0.muse.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.AssistantAvatar
import io.zer0.muse.ui.common.ConfirmDeleteDialog
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.UUID

/**
 * v1.25: 多 Agent 协作团队管理页。
 *
 * 功能:
 *  - 总开关:启用/禁用多 Agent 协作
 *  - 团队列表:展示名称、描述、成员头像(最多 3 个,+N)
 *  - 点击团队编辑,长按/点击删除图标删除(需确认)
 *  - 底部「新建团队」胶囊按钮
 */
@Composable
fun MultiAgentSettingsPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val config by settings.multiAgentConfigFlow.collectAsStateWithLifecycle(initialValue = MultiAgentConfig())
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    var editingTeam by remember { mutableStateOf<AgentTeam?>(null) }
    var teamToDelete by remember { mutableStateOf<AgentTeam?>(null) }
    val unnamedTeam = stringResource(R.string.settings_multi_agent_unnamed_team)

    fun updateConfig(block: (MultiAgentConfig) -> MultiAgentConfig) {
        // M3: 用原子更新方法,避免读-改-写竞态
        scope.launch { settings.updateMultiAgentConfig(block) }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_multi_agent_title), onBack = onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.GroupWork,
                    title = stringResource(R.string.settings_multi_agent_enable),
                    subtitle = stringResource(R.string.settings_multi_agent_enable_subtitle),
                    checked = config.enabled,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(enabled = v) }
                    },
                )
            }
        }

        item { SectionLabel(stringResource(R.string.settings_multi_agent_team_list)) }

        if (config.teams.isEmpty()) {
            // v1.48: h14 团队列表空态改用 EmptyState 组件
            item {
                EmptyState(
                    icon = Icons.Outlined.Groups,
                    title = stringResource(R.string.settings_multi_agent_no_team),
                    subtitle = stringResource(R.string.settings_multi_agent_no_team_hint),
                    actionText = stringResource(R.string.settings_multi_agent_new_team),
                    onAction = { editingTeam = AgentTeam(id = "") },
                )
            }
        } else {
            items(
                items = config.teams,
                key = { it.id },
            ) { team ->
                TeamCard(
                    team = team,
                    assistants = assistants ?: emptyList(),
                    onClick = { editingTeam = team },
                    onDelete = { teamToDelete = team },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MuseShapes.pill)
                    .clickable { editingTeam = AgentTeam(id = "") },
                color = MaterialTheme.colorScheme.primary,
                shape = MuseShapes.pill,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_multi_agent_new_team),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }

    editingTeam?.let { team ->
        TeamEditDialog(
            team = team,
            assistants = assistants ?: emptyList(),
            onDismiss = { editingTeam = null },
            onSave = { saved ->
                val now = System.currentTimeMillis()
                val isNew = saved.id.isBlank()
                val finalTeam = if (isNew) {
                    saved.copy(id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
                } else {
                    saved.copy(updatedAt = now)
                }
                updateConfig {
                    if (isNew) {
                        it.copy(teams = it.teams + finalTeam)
                    } else {
                        it.copy(teams = it.teams.map { t -> if (t.id == finalTeam.id) finalTeam else t })
                    }
                }
                editingTeam = null
            },
        )
    }

    teamToDelete?.let { team ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.settings_multi_agent_delete_team),
            itemName = team.name.ifBlank { unnamedTeam },
            onConfirm = {
                updateConfig {
                    it.copy(
                        teams = it.teams.filter { t -> t.id != team.id },
                        defaultTeamId = if (it.defaultTeamId == team.id) null else it.defaultTeamId,
                    )
                }
                teamToDelete = null
            },
            onDismiss = { teamToDelete = null },
        )
    }
}

/**
 * 团队卡片:名称 + 描述 + 成员头像(最多 3 个,+N) + 删除按钮。
 */
@Composable
private fun TeamCard(
    team: AgentTeam,
    assistants: List<AssistantEntity>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val unnamedTeam = stringResource(R.string.settings_multi_agent_unnamed_team)
    val members = remember(team.memberIds, assistants) {
        team.memberIds.mapNotNull { id -> assistants.find { it.id == id } }
    }

    Card(
        shape = MuseShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete()
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = team.name.ifBlank { unnamedTeam },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (team.description.isNotBlank()) {
                    Text(
                        text = team.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (members.isNotEmpty()) {
                    MemberAvatarRow(members = members)
                } else {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_no_members),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.settings_multi_agent_delete),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 成员头像行:最多显示前 3 个头像,其余用 +N 圆形徽标。
 */
@Composable
private fun MemberAvatarRow(
    members: List<AssistantEntity>,
) {
    val shown = members.take(3)
    val overflow = members.size - shown.size

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((-8).dp),
    ) {
        shown.forEach { assistant ->
            AssistantAvatar(
                assistant = assistant,
                avatarSize = 28.dp,
                modifier = Modifier.border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
            )
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 团队创建/编辑弹窗:名称、描述、成员多选。
 */
@Composable
private fun TeamEditDialog(
    team: AgentTeam,
    assistants: List<AssistantEntity>,
    onDismiss: () -> Unit,
    onSave: (AgentTeam) -> Unit,
) {
    var name by remember(team.id) { mutableStateOf(team.name) }
    var description by remember(team.id) { mutableStateOf(team.description) }
    var selectedIds by remember(team.id) { mutableStateOf(team.memberIds.toSet()) }
    var nameError by remember { mutableStateOf(false) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = if (team.id.isBlank()) stringResource(R.string.settings_multi_agent_new_team) else stringResource(R.string.settings_multi_agent_edit_team),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.settings_multi_agent_team_name)) },
                    placeholder = { Text(stringResource(R.string.settings_multi_agent_team_name_hint)) },
                    singleLine = true,
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text(stringResource(R.string.settings_multi_agent_team_name_empty)) }
                    } else null,
                    shape = MuseShapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.settings_multi_agent_description)) },
                    placeholder = { Text(stringResource(R.string.settings_multi_agent_description_hint)) },
                    minLines = 2,
                    maxLines = 3,
                    shape = MuseShapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_multi_agent_select_members),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (assistants.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_no_assistants),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        assistants.forEach { assistant ->
                            val selected = assistant.id in selectedIds
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedIds = if (selected) {
                                        selectedIds - assistant.id
                                    } else {
                                        selectedIds + assistant.id
                                    }
                                },
                                label = { Text(assistant.name) },
                                shape = MuseShapes.large,
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.settings_multi_agent_save),
        onConfirm = {
            if (name.isBlank()) {
                nameError = true
                return@MuseDialog
            }
            onSave(
                team.copy(
                    name = name.trim(),
                    description = description.trim(),
                    memberIds = assistants.filter { it.id in selectedIds }.map { it.id },
                )
            )
        },
        dismissText = stringResource(R.string.settings_multi_agent_cancel),
        onDismiss = onDismiss,
    )
}
