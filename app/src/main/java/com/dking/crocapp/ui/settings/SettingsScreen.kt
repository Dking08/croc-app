package com.crocworks.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crocworks.app.BuildConfig

private const val MAX_VISIBLE_CODES = 6

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    var relayPasswordVisible by remember { mutableStateOf(false) }
    var defaultCodeVisible by remember { mutableStateOf(false) }
    var savedCodeDraft by remember { mutableStateOf("") }
    var showAllCodes by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // App Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.CompareArrows,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "croc-app",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} • croc v10.4.2",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Appearance
            SettingsSection(icon = Icons.Rounded.Palette, title = "Appearance") {
                DropdownSetting(
                    label = "Theme",
                    value = prefs.themeMode,
                    options = listOf("system", "light", "dark"),
                    onValueChange = { viewModel.updateThemeMode(it) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                SwitchSetting(
                    icon = Icons.Rounded.Palette,
                    label = "AMOLED Dark",
                    description = "Pure black background for OLED displays",
                    checked = prefs.amoledDark,
                    onCheckedChange = { viewModel.updateAmoledDark(it) }
                )
            }

            // Relay Settings
            SettingsSection(icon = Icons.Rounded.Cloud, title = "Relay Server") {
                TextFieldSetting(
                    label = "Address",
                    value = prefs.relayAddress,
                    onValueChange = { viewModel.updateRelayAddress(it) },
                    placeholder = "croc.schollz.com:9009"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                PasswordTextFieldSetting(
                    label = "Password",
                    value = prefs.relayPassword,
                    onValueChange = { viewModel.updateRelayPassword(it) },
                    placeholder = "pass123",
                    visible = relayPasswordVisible,
                    onToggleVisibility = { relayPasswordVisible = !relayPasswordVisible }
                )
            }

            // Secret Codes
            SettingsSection(icon = Icons.Rounded.Security, title = "Secret Codes") {
                PasswordTextFieldSetting(
                    label = "Default Secret Code",
                    value = prefs.defaultCodePhrase,
                    onValueChange = { viewModel.updateDefaultCodePhrase(it) },
                    placeholder = "leave blank to randomize",
                    visible = defaultCodeVisible,
                    onToggleVisibility = { defaultCodeVisible = !defaultCodeVisible }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = savedCodeDraft,
                        onValueChange = { savedCodeDraft = it },
                        label = { Text("Add Saved Code") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("e.g. red-sun-42") },
                        shape = MaterialTheme.shapes.large
                    )
                    FilledTonalButton(
                        onClick = {
                            viewModel.saveCodePhrase(savedCodeDraft)
                            savedCodeDraft = ""
                        },
                        enabled = savedCodeDraft.isNotBlank(),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                if (prefs.savedCodePhrases.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))

                    val codesToShow = if (showAllCodes) prefs.savedCodePhrases
                    else prefs.savedCodePhrases.take(MAX_VISIBLE_CODES)

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        codesToShow.forEach { code ->
                            SavedCodeRow(
                                code = code,
                                onDelete = { viewModel.deleteCodePhrase(code) }
                            )
                        }
                    }

                    if (prefs.savedCodePhrases.size > MAX_VISIBLE_CODES) {
                        val remaining = prefs.savedCodePhrases.size - MAX_VISIBLE_CODES
                        FilledTonalButton(
                            onClick = { showAllCodes = !showAllCodes },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text(
                                if (showAllCodes) "Show less"
                                else "Show all ${prefs.savedCodePhrases.size} codes"
                            )
                        }
                    }
                }
            }

            // Quick Transfer
            SettingsSection(icon = Icons.Rounded.Speed, title = "Quick Transfer") {
                TextFieldSetting(
                    label = "Quick Send Code",
                    value = prefs.quickSendCode,
                    onValueChange = { viewModel.updateQuickSendCode(it) },
                    placeholder = if (prefs.defaultCodePhrase.isNotBlank())
                        "using default: ${prefs.defaultCodePhrase}"
                    else "leave blank to use default code"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                TextFieldSetting(
                    label = "Quick Receive Code",
                    value = prefs.quickReceiveCode,
                    onValueChange = { viewModel.updateQuickReceiveCode(it) },
                    placeholder = if (prefs.defaultCodePhrase.isNotBlank())
                        "using default: ${prefs.defaultCodePhrase}"
                    else "leave blank to use default code"
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Quick tab uses these codes. Falls back to Default Secret Code if blank.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Network
            SettingsSection(icon = Icons.Rounded.Wifi, title = "Network") {
                SwitchSetting(
                    icon = Icons.Rounded.Wifi,
                    label = "Local Only",
                    description = "Only use local network connections",
                    checked = prefs.forceLocal,
                    onCheckedChange = { viewModel.updateForceLocal(it) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                SwitchSetting(
                    icon = Icons.Rounded.Cloud,
                    label = "Built-in DNS",
                    description = "Use internal DNS resolver",
                    checked = prefs.useInternalDns,
                    onCheckedChange = { viewModel.updateUseInternalDns(it) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                TextFieldSetting(
                    label = "Multicast Address",
                    value = prefs.multicastAddress,
                    onValueChange = { viewModel.updateMulticastAddress(it) },
                    placeholder = "239.255.255.250"
                )
            }

            // Encryption
            SettingsSection(icon = Icons.Rounded.Security, title = "Encryption") {
                DropdownSetting(
                    label = "PAKE Curve",
                    value = prefs.pakeCurve,
                    options = listOf("p256", "p384", "p521", "siec", "ed25519"),
                    onValueChange = { viewModel.updatePakeCurve(it) }
                )
            }

            // Transfer
            SettingsSection(icon = Icons.Rounded.Speed, title = "Transfer Options") {
                SwitchSetting(
                    icon = Icons.Rounded.Speed,
                    label = "Disable Compression",
                    description = "Transfer files without compression",
                    checked = prefs.disableCompression,
                    onCheckedChange = { viewModel.updateDisableCompression(it) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                TextFieldSetting(
                    label = "Upload Speed Limit",
                    value = prefs.uploadThrottle,
                    onValueChange = { viewModel.updateUploadThrottle(it) },
                    placeholder = "e.g. 500k or 1m"
                )
            }

            // About
            SettingsSection(icon = Icons.Rounded.Info, title = "About") {
                InfoRow(label = "App Version", value = BuildConfig.VERSION_NAME)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                InfoRow(label = "croc Version", value = "v10.4.2")
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Built with ❤️ by Dastageer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "github.com/schollz/croc",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SavedCodeRow(
    code: String,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Code,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Delete code",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TextFieldSetting(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = ""
) {
    // Buffer locally to prevent cursor jumps from DataStore recomposition
    var localValue by remember(value) { mutableStateOf(value) }

    OutlinedTextField(
        value = localValue,
        onValueChange = { newValue ->
            localValue = newValue
            onValueChange(newValue)
        },
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        singleLine = true,
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder) }
        } else null,
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun PasswordTextFieldSetting(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    visible: Boolean,
    onToggleVisibility: () -> Unit
) {
    // Buffer locally to prevent cursor jumps from DataStore recomposition
    var localValue by remember(value) { mutableStateOf(value) }

    OutlinedTextField(
        value = localValue,
        onValueChange = { newValue ->
            localValue = newValue
            onValueChange(newValue)
        },
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        singleLine = true,
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder) }
        } else null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Hide value" else "Show value"
                )
            }
        },
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun SwitchSetting(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = MaterialTheme.shapes.large
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
