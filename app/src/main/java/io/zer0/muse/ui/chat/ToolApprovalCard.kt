package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.zer0.muse.R

/**
 * Tool approval card (RikkaHub GenerationHandler.kt port).
 *
 * Shown when a tool call requires user approval before execution.
 * Displays tool name, argument preview, and approve/deny buttons.
 */
@Composable
fun ToolApprovalCard(
    toolName: String,
    argumentsPreview: String,
    onApprove: () -> Unit,
    onDeny: (reason: String) -> Unit,
    alwaysAllow: Boolean,
    onAlwaysAllowChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDenyReason by remember { mutableStateOf(false) }
    var denyReason by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.tool_approval_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            // Arguments preview (truncated)
            val preview = if (argumentsPreview.length > 200) {
                argumentsPreview.take(200) + "..."
            } else {
                argumentsPreview
            }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
            )

            // Always allow checkbox
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = alwaysAllow,
                    onCheckedChange = onAlwaysAllowChanged,
                )
                Text(
                    text = stringResource(R.string.tool_approval_always_allow, toolName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onApprove,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.tool_approval_approve), style = MaterialTheme.typography.labelMedium)
                }

                TextButton(
                    onClick = {
                        if (showDenyReason) {
                            onDeny(denyReason)
                        } else {
                            showDenyReason = true
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(if (showDenyReason) stringResource(R.string.tool_approval_confirm_deny) else stringResource(R.string.tool_approval_deny), style = MaterialTheme.typography.labelMedium)
                }
            }

            // Deny reason input
            if (showDenyReason) {
                androidx.compose.material3.OutlinedTextField(
                    value = denyReason,
                    onValueChange = { denyReason = it },
                    label = { Text(stringResource(R.string.tool_approval_deny_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
