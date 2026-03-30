package com.crocworks.app.ui.send

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crocworks.app.croc.CrocTransferState
import com.crocworks.app.ui.components.FileChip
import com.crocworks.app.ui.components.QrCodeImage
import com.crocworks.app.ui.components.TransferProgressCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SendScreen(
    viewModel: SendViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    var showQrCode by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addFiles(uris)
        }
    }

    val isTransferActive = uiState.transferState !is CrocTransferState.Idle &&
            uiState.transferState !is CrocTransferState.Completed &&
            uiState.transferState !is CrocTransferState.Error &&
            uiState.transferState !is CrocTransferState.Cancelled

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Send Files",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (!isTransferActive && uiState.transferState is CrocTransferState.Idle) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.startSend() },
                    icon = {
                        Icon(
                            Icons.Rounded.Send,
                            contentDescription = null
                        )
                    },
                    text = { Text("Send") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Mode Toggle: File / Text
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    onClick = { if (uiState.isTextMode) viewModel.toggleTextMode() },
                    selected = !uiState.isTextMode,
                    icon = {
                        Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                ) {
                    Text("Files")
                }
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    onClick = { if (!uiState.isTextMode) viewModel.toggleTextMode() },
                    selected = uiState.isTextMode,
                    icon = {
                        Icon(Icons.Outlined.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                ) {
                    Text("Text")
                }
            }

            // File Selection or Text Input
            AnimatedVisibility(
                visible = !uiState.isTextMode,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Selected Files",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                enabled = !isTransferActive
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Files")
                            }
                        }

                        if (uiState.selectedFiles.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                uiState.selectedFiles.forEach { file ->
                                    FileChip(
                                        fileName = file.name,
                                        onRemove = if (!isTransferActive) {
                                            { viewModel.removeFile(file) }
                                        } else null
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Tap 'Add Files' to select files to send",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = uiState.isTextMode,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                OutlinedTextField(
                    value = uiState.textToSend,
                    onValueChange = { viewModel.updateTextToSend(it) },
                    label = { Text("Text to send") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    enabled = !isTransferActive,
                    shape = MaterialTheme.shapes.extraLarge
                )
            }

            // Code Phrase Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Code Phrase",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.codePhrase,
                        onValueChange = { viewModel.updateCodePhrase(it) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTransferActive,
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { viewModel.regenerateCode() }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Regenerate code")
                                }
                                IconButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(uiState.codePhrase))
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy code")
                                }
                            }
                        }
                    )

                    // QR Code toggle
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showQrCode = !showQrCode },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (showQrCode) "Hide QR Code" else "Show QR Code")
                    }

                    AnimatedVisibility(visible = showQrCode) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            QrCodeImage(
                                data = uiState.codePhrase,
                                size = 180.dp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Scan this code to receive",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // Transfer Progress
            AnimatedVisibility(
                visible = uiState.transferState !is CrocTransferState.Idle,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Column {
                    TransferProgressCard(
                        state = uiState.transferState,
                        isSending = true,
                        onCancel = { viewModel.cancelTransfer() }
                    )
                    if (uiState.transferState is CrocTransferState.Completed ||
                        uiState.transferState is CrocTransferState.Error ||
                        uiState.transferState is CrocTransferState.Cancelled
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { viewModel.resetTransfer() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Send Another")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
