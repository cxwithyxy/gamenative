package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsGroupNetwork() {
    var dnsEnabled by rememberSaveable { mutableStateOf(PrefManager.dnsEnabled) }
    var dnsServersStr by rememberSaveable { mutableStateOf(PrefManager.dnsServers) }
    var showDnsDialog by rememberSaveable { mutableStateOf(false) }

    // DNS servers edit dialog
    if (showDnsDialog) {
        var editingText by rememberSaveable { mutableStateOf(dnsServersStr) }
        AlertDialog(
            onDismissRequest = {
                showDnsDialog = false
            },
            title = {
                Text(
                    text = stringResource(R.string.settings_network_dns_servers_dialog_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.settings_network_dns_servers_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                        lineHeight = 20.sp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.settings_network_dns_servers_placeholder),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dnsServersStr = editingText
                    PrefManager.dnsServers = editingText
                    showDnsDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDnsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = PluviaTheme.colors.surfaceElevated,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsGroup {
        SettingsSwitch(
            colors = settingsTileColors(),
            state = dnsEnabled,
            title = { Text(text = stringResource(R.string.settings_network_dns_enable_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_network_dns_enable_subtitle)) },
            onCheckedChange = { checked ->
                dnsEnabled = checked
                PrefManager.dnsEnabled = checked
            },
        )

        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            enabled = dnsEnabled,
            title = { Text(text = stringResource(R.string.settings_network_dns_servers_title)) },
            subtitle = {
                val summary = if (dnsServersStr.isBlank()) {
                    stringResource(R.string.settings_network_dns_servers_empty)
                } else {
                    dnsServersStr.split(",", "\n", ";", "\r\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                }
                Text(text = summary)
            },
            onClick = { showDnsDialog = true },
        )
    }
}
