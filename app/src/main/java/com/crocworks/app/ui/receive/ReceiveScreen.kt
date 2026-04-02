package com.crocworks.app.ui.receive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
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
import com.crocworks.app.ui.components.progressBorder

private const val MAX_VISIBLE_FILES = 6

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

    val isTransferActive = uiState.transferState is CrocTransferState.Preparing ||
            uiState.transferState is CrocTransferState.WaitingForPeer ||
            uiState.transferState is CrocTransferState.Transferring
    val isTransferFinished = uiState.transferState is CrocTransferState.Completed ||
            uiState.transferState is CrocTransferState.Error ||
            uiState.transferState is CrocTransferState.Cancelled
    val canReceive = uiState.codePhrase.isNotBlank()
    val fabLabel = when (uiState.transferState) {
        is CrocTransferState.Completed -> "Receive Again"
        is CrocTransferState.Error, CrocTransferState.Cancelled -> "Retry"
        else -> "Receive"
    }

    // Animated progress for the code card border
    val transferProgress = when (val state = uiState.transferState) {
        is CrocTransferState.Transferring -> state.fileCountProgress
        is CrocTransferState.Completed -> 1f
        else -> 0f
    }
    val animatedBorderProgress by animateFloatAsState(
        targetValue = transferProgress,
        animationSpec = tween(400),
        label = "codeBorderProgress"
    )
    val showCodeBorder = isTransferActive || uiState.transferState is CrocTransferState.Completed
    val borderColor = MaterialTheme.colorScheme.tertiary

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

            // ──── Code Entry Card — with animated progress border ────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (showCodeBorder) {
                            Modifier.progressBorder(
                                progress = animatedBorderProgress,
                                color = borderColor,
                                cornerRadius = 28.dp
                            )
                        } else Modifier
                    ),
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

                    // Saved codes — scrollable 2-row horizontal grid
                    if (uiState.savedCodePhrases.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.savedCodePhrases.chunked(2).forEach { column ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    column.forEach { savedCode ->
                                        SavedCodeChipWithActions(
                                            code = savedCode,
                                            enabled = !isTransferActive,
                                            onUse = { viewModel.startReceiveWithCode(savedCode) },
                                            onCopy = {
                                                clipboardManager.setText(
                                                    androidx.compose.ui.text.AnnotatedString(savedCode)
                                                )
                                            },
                                            onShare = {
                                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(android.content.Intent.EXTRA_TEXT, savedCode)
                                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "croc code")
                                                }
                                                context.startActivity(android.content.Intent.createChooser(shareIntent, "Share code"))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ──── Transfer Progress — between code and received files ────
            AnimatedVisibility(
                visible = isTransferActive || isTransferFinished,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
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

            // ──── Received Files ────
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

            // ──── Received Text ────
            val completedState = uiState.transferState as? CrocTransferState.Completed
            if (completedState?.receivedText != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
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
                                text = "Received Text",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row {
                                Text(
                                    text = formatBytes(completedState.totalBytes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                )
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(
                                            androidx.compose.ui.text.AnnotatedString(completedState.receivedText ?: "")
                                        )
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.ContentPaste,
                                        contentDescription = "Copy text",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = completedState.receivedText ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 12,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedCodeChipWithActions(
    code: String,
    enabled: Boolean,
    onUse: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        AssistChip(
            onClick = onUse,
            enabled = enabled,
            label = {
                Text(
                    code,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier.combinedClickable(
                enabled = enabled,
                onClick = onUse,
                onLongClick = { showMenu = true }
            )
        )

        androidx.compose.material3.DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Receive on Code") },
                onClick = {
                    showMenu = false
                    onUse()
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    showMenu = false
                    onCopy()
                },
                leadingIcon = {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Share") },
                onClick = {
                    showMenu = false
                    onShare()
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            )
        }
    }
}
