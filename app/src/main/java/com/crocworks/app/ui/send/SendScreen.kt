package com.crocworks.app.ui.send

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crocworks.app.croc.CrocTransferState
import com.crocworks.app.ui.components.QrCodeImage
import com.crocworks.app.ui.components.TransferProgressCard
import com.crocworks.app.ui.components.formatBytes
import com.crocworks.app.ui.components.generateQrCodeBitmap
import com.crocworks.app.ui.components.progressBorder
import java.io.File
import java.io.FileOutputStream

private const val MAX_VISIBLE_FILES = 6

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SendScreen(
    viewModel: SendViewModel = viewModel(),
    onNavigateToHistory: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showQrCode by remember { mutableStateOf(false) }
    var hideCodePhrase by remember { mutableStateOf(true) }
    var showAllFiles by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addFiles(uris)
        }
    }

    val isTransferActive = uiState.transferState is CrocTransferState.Preparing ||
            uiState.transferState is CrocTransferState.WaitingForPeer ||
            uiState.transferState is CrocTransferState.Transferring
    val isTransferFinished = uiState.transferState is CrocTransferState.Completed ||
            uiState.transferState is CrocTransferState.Error ||
            uiState.transferState is CrocTransferState.Cancelled
    val canSend = uiState.codePhrase.isNotBlank() && uiState.hasContent
    val qrForegroundColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val qrBackgroundColor = MaterialTheme.colorScheme.surface.toArgb()
    val fabLabel = when (uiState.transferState) {
        is CrocTransferState.Completed -> "Send Again"
        is CrocTransferState.Error, CrocTransferState.Cancelled -> "Retry"
        else -> "Send"
    }

    // Animated progress for the file card border
    val transferProgress = when (val state = uiState.transferState) {
        is CrocTransferState.Transferring -> state.progress
        is CrocTransferState.Completed -> 1f
        else -> 0f
    }
    val animatedBorderProgress by animateFloatAsState(
        targetValue = transferProgress,
        animationSpec = tween(400),
        label = "borderProgress"
    )
    val showFileBorder = isTransferActive || uiState.transferState is CrocTransferState.Completed
    val borderColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Send",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text("End-to-end encrypted peer-to-peer file transfer")
                            }
                        },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = {}) {
                            Icon(Icons.Outlined.Info, contentDescription = "Info")
                        }
                    }
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
                        viewModel.startSend()
                    },
                    icon = {
                        Icon(Icons.Rounded.Upload, contentDescription = null)
                    },
                    text = { Text(fabLabel, fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 3.dp
                    ),
                    expanded = canSend
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

            // ──── File Selection Card — with animated progress border ────
            AnimatedVisibility(
                visible = !uiState.isTextMode,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (showFileBorder) {
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
                                text = "Files",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (uiState.selectedFiles.isNotEmpty()) {
                                Text(
                                    text = "${uiState.selectedFiles.size} • ${formatBytes(uiState.selectedBytes)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                enabled = !isTransferActive,
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (uiState.selectedFiles.isEmpty()) "Pick Files" else "Add More")
                            }
                            if (uiState.selectedFiles.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { viewModel.clearFiles() },
                                    enabled = !isTransferActive,
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Clear")
                                }
                            }
                        }

                        if (uiState.selectedFiles.isNotEmpty()) {
                            val filesToShow = if (showAllFiles) uiState.selectedFiles
                            else uiState.selectedFiles.take(MAX_VISIBLE_FILES)

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                filesToShow.forEach { file ->
                                    CompactFileRow(
                                        fileName = file.name,
                                        fileSize = file.size,
                                        onRemove = if (!isTransferActive) {
                                            { viewModel.removeFile(file) }
                                        } else null
                                    )
                                }
                            }

                            if (uiState.selectedFiles.size > MAX_VISIBLE_FILES) {
                                val remaining = uiState.selectedFiles.size - MAX_VISIBLE_FILES
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
                        } else {
                            Text(
                                text = "No files selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // ──── Text Input ────
            AnimatedVisibility(
                visible = uiState.isTextMode,
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
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Quick Text",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = uiState.textToSend,
                            onValueChange = { viewModel.updateTextToSend(it) },
                            placeholder = { Text("Text to send") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            maxLines = 6,
                            enabled = !isTransferActive,
                            shape = MaterialTheme.shapes.large
                        )
                    }
                }
            }

            // ──── Transfer Progress — between files and secret code ────
            AnimatedVisibility(
                visible = isTransferActive || isTransferFinished,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferProgressCard(
                        state = uiState.transferState,
                        isSending = true,
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

            // ──── Code Phrase Card ────
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
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    OutlinedTextField(
                        value = uiState.codePhrase,
                        onValueChange = { viewModel.updateCodePhrase(it) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTransferActive,
                        singleLine = true,
                        placeholder = { Text("Secret code") },
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
                                    clipboardManager.setText(AnnotatedString(uiState.codePhrase))
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy code")
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
                            onClick = { viewModel.regenerateCode() },
                            enabled = !isTransferActive,
                            label = { Text("Regenerate") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
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
                        if (uiState.defaultCodePhrase.isNotBlank() && uiState.defaultCodePhrase != uiState.codePhrase) {
                            AssistChip(
                                onClick = { viewModel.useCodePhrase(uiState.defaultCodePhrase) },
                                enabled = !isTransferActive,
                                label = { Text("Default") }
                            )
                        }
                        AssistChip(
                            onClick = { showQrCode = !showQrCode },
                            enabled = uiState.codePhrase.isNotBlank(),
                            label = { Text(if (showQrCode) "Hide QR" else "QR Code") },
                            leadingIcon = {
                                Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        AssistChip(
                            onClick = {
                                shareQrCode(
                                    context = context,
                                    codePhrase = uiState.codePhrase,
                                    foregroundColor = qrForegroundColor,
                                    backgroundColor = qrBackgroundColor
                                )
                            },
                            enabled = uiState.codePhrase.isNotBlank(),
                            label = { Text("Share QR") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
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
                                        AssistChip(
                                            onClick = { viewModel.useCodePhrase(savedCode) },
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
                                }
                            }
                        }
                    }

                    // QR Code display
                    AnimatedVisibility(visible = showQrCode && uiState.codePhrase.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            QrCodeImage(
                                data = uiState.codePhrase,
                                size = 180.dp
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
private fun CompactFileRow(
    fileName: String,
    fileSize: Long,
    onRemove: (() -> Unit)? = null
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
        Icon(
            Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatBytes(fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove file",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun shareQrCode(
    context: Context,
    codePhrase: String,
    foregroundColor: Int,
    backgroundColor: Int
) {
    if (codePhrase.isBlank()) return
    val bitmap = generateQrCodeBitmap(codePhrase, 1024, foregroundColor, backgroundColor) ?: return

    runCatching {
        val shareDir = File(context.cacheDir, "qr-share").apply { mkdirs() }
        val safeName = codePhrase.replace(Regex("[^a-zA-Z0-9-_]"), "_")
        val file = File(shareDir, "croc-$safeName.png")

        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "croc receive QR")
            putExtra(Intent.EXTRA_TEXT, "Use this croc code: $codePhrase")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share QR code"))
    }
}
