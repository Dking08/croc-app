package com.dking.crocapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dking.crocapp.R
import com.dking.crocapp.ui.scanner.QrScannerScreen
import com.dking.crocapp.util.QrCodeParser

@Composable
fun ReceiveCodeInputDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (code: String, saveAsDefault: Boolean) -> Unit,
    initialCode: String = "",
    showSaveDefaultOption: Boolean = true
) {
    var codeText by remember { mutableStateOf(initialCode) }
    var saveAsDefault by remember { mutableStateOf(true) }
    var isScanning by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val trimmedCode = codeText.trim()
    val canSubmit = trimmedCode.isNotBlank()

    if (isScanning) {
        Dialog(
            onDismissRequest = { isScanning = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                QrScannerScreen(
                    onCodeScanned = { scanned ->
                        codeText = QrCodeParser.parseCode(scanned)
                    },
                    onNavigateBack = {
                        isScanning = false
                    }
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                Icons.Rounded.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.receive_input_code_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.receive_input_code_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = codeText,
                    onValueChange = { codeText = QrCodeParser.parseCode(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.receive_code_placeholder)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSubmit) {
                                onConfirm(trimmedCode, saveAsDefault)
                            }
                        }
                    ),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { isScanning = true }) {
                                Icon(
                                    Icons.Rounded.QrCodeScanner,
                                    contentDescription = stringResource(R.string.action_scan_qr)
                                )
                            }
                            IconButton(onClick = {
                                val clip = clipboardManager.getText()?.text ?: ""
                                if (clip.isNotBlank()) {
                                    codeText = QrCodeParser.parseCode(clip)
                                }
                            }) {
                                Icon(
                                    Icons.Rounded.ContentPaste,
                                    contentDescription = stringResource(R.string.receive_paste)
                                )
                            }
                        }
                    }
                )

                if (showSaveDefaultOption) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { saveAsDefault = !saveAsDefault }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = saveAsDefault,
                            onCheckedChange = { saveAsDefault = it }
                        )
                        Text(
                            text = stringResource(R.string.receive_input_code_save_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSubmit) {
                        onConfirm(trimmedCode, saveAsDefault)
                    }
                },
                enabled = canSubmit,
                shape = MaterialTheme.shapes.large
            ) {
                Text(stringResource(R.string.nav_receive))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                shape = MaterialTheme.shapes.large
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
