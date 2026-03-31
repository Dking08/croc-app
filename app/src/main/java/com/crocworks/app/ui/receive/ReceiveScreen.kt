package com.crocworks.app.ui.receive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crocworks.app.croc.CrocTransferState
import com.crocworks.app.ui.components.TransferProgressCard
import com.crocworks.app.ui.components.formatBytes

private const val MAX_VISIBLE_FILES = 6
private const val MAX_VISIBLE_CODES = 5

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReceiveScreen(
    viewModel: ReceiveViewModel = viewModel(),
    onOpenScanner: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var hideCodePhrase by remember { mutableStateOf(true) }
    var showAllFiles by remember { mutableStateOf(false) }

    val isTransferActive = uiState.transferState !is CrocTransferState.Idle &&
            uiState.transferState !is CrocTransferState.Completed &&
            uiState.transferState !is CrocTransferState.Error &&
            uiState.transferState !is CrocTransferState.Cancelled
    val isTransferFinished = uiState.transferState is CrocTransferState.Completed ||
            uiState.transferState is CrocTransferState.Error ||
            uiState.transferState is CrocTransferState.Cancelled
    val canReceive = uiState.codePhrase.isNotBlank()
    val fabLabel = when (uiState.transferState) {
        is CrocTransferState.Completed -> "Receive Again"
        is CrocTransferState.Error, CrocTransferState.Cancelled -> "Retry"
        else -> "Receive"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Receive",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Outlined.History, contentDescription = "History")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (!isTransferActive) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (isTransferFinished) {
                            viewModel.dismissTransferResult()
                        }
                        viewModel.startReceive()
                    },
                    icon = {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                    },
                    text = { Text(fabLabel, fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 3.dp
                    ),
                    expanded = canReceive
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // Hero Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
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
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Receive files",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Enter a code or scan a QR to receive",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Code Entry Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Secret Code",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (uiState.defaultCodePhrase.isNotBlank() && uiState.defaultCodePhrase == uiState.codePhrase) {
                            Text(
                                text = "Default",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.codePhrase,
                        onValueChange = { viewModel.updateCodePhrase(it) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTransferActive,
                        singleLine = true,
                        placeholder = { Text("Enter secret code") },
                        shape = MaterialTheme.shapes.large,
                        visualTransformation = if (hideCodePhrase) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { hideCodePhrase = !hideCodePhrase }) {
                                    Icon(
                                        imageVector = if (hideCodePhrase) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        contentDescription = if (hideCodePhrase) "Show code" else "Hide code"
                                    )
                                }
                                IconButton(onClick = {
                                    val clip = clipboardManager.getText()?.text ?: ""
                                    viewModel.updateCodePhrase(clip)
                                }) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
                                }
                            }
                        }
                    )

                    // Action chips
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        maxLines = 2
                    ) {
                        AssistChip(
                            onClick = onOpenScanner,
                            enabled = !isTransferActive,
                            label = { Text("Scan QR") },
                            leadingIcon = {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        AssistChip(
                            onClick = { viewModel.saveCurrentCode() },
                            enabled = !isTransferActive && uiState.codePhrase.isNotBlank(),
                            label = { Text("Save") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        AssistChip(
                            onClick = { viewModel.resetTransfer() },
                            enabled = !isTransferActive && (uiState.codePhrase.isNotBlank() || uiState.receivedFiles.isNotEmpty()),
                            label = { Text("Clear") },
                            leadingIcon = {
                                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }

                    // Saved codes — limited
                    if (uiState.savedCodePhrases.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            maxLines = 1
                        ) {
                            uiState.savedCodePhrases.take(MAX_VISIBLE_CODES).forEach { savedCode ->
                                AssistChip(
                                    onClick = { viewModel.startReceiveWithCode(savedCode) },
                                    enabled = !isTransferActive,
                                    label = {
                                        Text(
                                            savedCode,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                            if (uiState.savedCodePhrases.size > MAX_VISIBLE_CODES) {
                                AssistChip(
                                    onClick = { /* handled by settings */ },
                                    label = { Text("+${uiState.savedCodePhrases.size - MAX_VISIBLE_CODES}") }
                                )
                            }
                        }
                    }
                }
            }

            // Transfer Progress — inline
            AnimatedVisibility(
                visible = uiState.transferState !is CrocTransferState.Idle,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferProgressCard(
                        state = uiState.transferState,
                        isSending = false,
                        onCancel = { viewModel.cancelTransfer() }
                    )

                    if (isTransferFinished) {
                        OutlinedButton(
                            onClick = { viewModel.dismissTransferResult() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            // Received Files
            if (uiState.receivedFiles.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Received Files",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${uiState.receivedFiles.size} file${if (uiState.receivedFiles.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val filesToShow = if (showAllFiles) uiState.receivedFiles
                        else uiState.receivedFiles.take(MAX_VISIBLE_FILES)

                        filesToShow.forEach { file ->
                            ReceivedFileRow(
                                file = file,
                                onOpen = { openReceivedFile(context, file) },
                                onShare = { shareReceivedFile(context, file) }
                            )
                        }

                        if (uiState.receivedFiles.size > MAX_VISIBLE_FILES) {
                            val remaining = uiState.receivedFiles.size - MAX_VISIBLE_FILES
                            AssistChip(
                                onClick = { showAllFiles = !showAllFiles },
                                label = {
                                    Text(
                                        if (showAllFiles) "Show less"
                                        else "+$remaining more file${if (remaining > 1) "s" else ""}"
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Bottom padding for FAB clearance
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun ReceivedFileRow(
    file: ReceivedFile,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = file.savedLocation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Rounded.FolderOpen,
                contentDescription = "Open",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Rounded.Share,
                contentDescription = "Share",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun openReceivedFile(context: Context, file: ReceivedFile) {
    val openIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(file.uri, file.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(Intent.createChooser(openIntent, "Open file"))
    } catch (_: ActivityNotFoundException) {
    }
}

private fun shareReceivedFile(context: Context, file: ReceivedFile) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = file.mimeType
        putExtra(Intent.EXTRA_STREAM, file.uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        context.startActivity(Intent.createChooser(shareIntent, "Share file"))
    } catch (_: ActivityNotFoundException) {
    }
}
