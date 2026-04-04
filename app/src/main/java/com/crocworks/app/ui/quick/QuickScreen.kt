package com.crocworks.app.ui.quick

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
//import androidx.compose.foundation.layout.RowScope.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crocworks.app.croc.CrocTransferState
import com.crocworks.app.ui.components.QrCodeImage
import com.crocworks.app.ui.components.formatBytes
import com.crocworks.app.ui.receive.ReceivedFile
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickScreen(
    viewModel: QuickViewModel = viewModel(),
    onOpenScanner: (onCodeScanned: (String) -> Unit) -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current

    val isTransferActive = uiState.transferState is CrocTransferState.Preparing ||
        uiState.transferState is CrocTransferState.WaitingForPeer ||
        uiState.transferState is CrocTransferState.Transferring
    val isTransferFinished = uiState.transferState is CrocTransferState.Completed ||
        uiState.transferState is CrocTransferState.Error ||
        uiState.transferState is CrocTransferState.Cancelled
    val showActionButtons = !isTransferActive && !isTransferFinished

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.sendFiles(uris)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.FlashOn,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Quick", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = if (showActionButtons) 244.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                QuickBrandHeader()

                if (isTransferActive || isTransferFinished) {
                    TransferStatusSection(
                        state = uiState.transferState,
                        lastAction = uiState.lastAction,
                        activeCode = uiState.activeCode,
                        sharePreview = uiState.sharePreview,
                        receivedText = uiState.receivedText,
                        receivedFiles = uiState.receivedFiles,
                        onCancel = { viewModel.cancelTransfer() },
                        onDismiss = { viewModel.dismissResult() },
                        onCopyText = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                        }
                    )
                }
            }

            if (showActionButtons) {
                QuickActionButtons(
                    enabled = true,
                    savedCodes = uiState.savedCodePhrases,
                    onSendTap = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onClipboardSendTap = {
                        val text = clipboardManager.getText()?.text ?: ""
                        if (text.isNotBlank()) {
                            viewModel.sendClipboardText(text)
                        }
                    },
                    onReceiveTap = {
                        viewModel.startReceive()
                    },
                    onReceiveWithCode = { code ->
                        viewModel.startReceiveWithCode(code)
                    },
                    onQrReceiveTap = {
                        onOpenScanner { code ->
                            viewModel.startReceiveFromQr(code)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickBrandHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.86f)
                    )
                )
            )
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.FlashOn,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "croc-app",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Send files, text, or receive instantly",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "Hold Receive for saved codes",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TransferStatusSection(
    state: CrocTransferState,
    lastAction: String,
    activeCode: String,
    sharePreview: List<QuickSharePreview>,
    receivedText: String?,
    receivedFiles: List<ReceivedFile>,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onCopyText: (String) -> Unit
) {
    val isSending = lastAction == "send" || lastAction == "clipboard"
    val isFinished = state is CrocTransferState.Completed ||
        state is CrocTransferState.Error ||
        state is CrocTransferState.Cancelled

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isSending) {
            QuickSendTransferCard(
                state = state,
                code = activeCode,
                sharePreview = sharePreview
            )
        } else {
            QuickReceiveTransferCard(
                state = state,
                code = activeCode,
                receivedFiles = receivedFiles
            )
        }

        if (state is CrocTransferState.Completed && receivedFiles.isNotEmpty()) {
            QuickReceivedFilesCard(receivedFiles = receivedFiles)
        }

        if (state is CrocTransferState.Completed && receivedText != null) {
            QuickReceivedTextCard(
                receivedText = receivedText,
                totalBytes = state.totalBytes,
                onCopyText = onCopyText
            )
        }

        FilledTonalButton(
            onClick = if (isFinished) onDismiss else onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) {
            Text(if (isFinished) "Dismiss" else "Cancel")
        }
    }
}

@Composable
private fun QuickSendTransferCard(
    state: CrocTransferState,
    code: String,
    sharePreview: List<QuickSharePreview>
) {
    val hasSidePanel = state !is CrocTransferState.Error

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Quick Send",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = quickTransferTitle(state, isSending = true),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = quickTransferSubtitle(state, isSending = true),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state is CrocTransferState.Error) {
                        QuickErrorPanel(message = state.message)
                    }
                    QuickManifestList(
                        items = sharePreview,
                        emptyLabel = "Preparing your selection..."
                    )
                }

                if (hasSidePanel) {
                    Column(
                        modifier = Modifier.widthIn(min = 108.dp, max = 126.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (code.isNotBlank()) {
                            QrCodeImage(
                                data = code,
                                size = 86.dp,
                                padding = 8.dp
                            )
                        } else {
                            QuickStatusBadge(
                                icon = Icons.Rounded.Upload,
                                label = "Preparing"
                            )
                        }
                        Text(
                            text = if (code.isNotBlank()) code else "Generating code",
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            QuickTransferProgress(state = state, isSending = true)
        }
    }
}

@Composable
private fun QuickReceiveTransferCard(
    state: CrocTransferState,
    code: String,
    receivedFiles: List<ReceivedFile>
) {
    val hasSideTile = state !is CrocTransferState.Error

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Quick Receive",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = quickTransferTitle(state, isSending = false),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = quickTransferSubtitle(state, isSending = false),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state is CrocTransferState.Error) {
                        QuickErrorPanel(message = state.message)
                    }
                }

                if (hasSideTile) {
                    QuickReceiveStatusTile(
                        state = state,
                        code = code,
                        receivedFiles = receivedFiles
                    )
                }
            }

            if (state !is CrocTransferState.Error) {
                QuickDetailPill(
                    label = if (state is CrocTransferState.Completed && receivedFiles.isNotEmpty()) {
                        "Saved to Downloads/croc-received"
                    } else {
                        "Incoming files will land in Downloads/croc-received"
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state is CrocTransferState.Transferring && state.fileName.isNotBlank()) {
                QuickManifestList(
                    items = listOf(
                        QuickSharePreview(
                            title = state.fileName,
                            subtitle = "${state.currentFile}/${state.totalFiles} in progress"
                        )
                    ),
                    emptyLabel = ""
                )
            }

            QuickTransferProgress(state = state, isSending = false)
        }
    }
}

@Composable
private fun QuickReceiveStatusTile(
    state: CrocTransferState,
    code: String,
    receivedFiles: List<ReceivedFile>
) {
    Column(
        modifier = Modifier
            .size(108.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val completedFileCount = (state as? CrocTransferState.Completed)?.fileCount ?: 0
        val fileCount = if (receivedFiles.isNotEmpty()) receivedFiles.size else completedFileCount
        val headline = when (state) {
            is CrocTransferState.Completed -> {
                if (state.receivedText != null) "Text"
                else "${fileCount} file${if (fileCount == 1) "" else "s"}"
            }
            is CrocTransferState.Transferring -> "${state.progressPercent}%"
            is CrocTransferState.Error -> "Issue"
            is CrocTransferState.Cancelled -> "Stopped"
            else -> if (code.isNotBlank()) code else "Waiting"
        }

        Text(
            text = headline,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when (state) {
                is CrocTransferState.Completed -> "Downloads"
                is CrocTransferState.Transferring -> "Saving"
                is CrocTransferState.Error -> "Retry"
                is CrocTransferState.Cancelled -> "Idle"
                else -> "Secret code"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QuickManifestList(
    items: List<QuickSharePreview>,
    emptyLabel: String
) {
    if (items.isEmpty()) {
        if (emptyLabel.isNotBlank()) {
            QuickDetailPill(label = emptyLabel)
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(4).forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (items.size > 4) {
            QuickDetailPill(label = "+${items.size - 4} more item${if (items.size - 4 == 1) "" else "s"}")
        }
    }
}

@Composable
private fun QuickTransferProgress(
    state: CrocTransferState,
    isSending: Boolean
) {
    val targetProgress = when (state) {
        is CrocTransferState.Transferring -> state.progress
        is CrocTransferState.Completed -> 1f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "quickTransferProgress"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is CrocTransferState.Preparing,
            is CrocTransferState.WaitingForPeer -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(MaterialTheme.shapes.small)
                )
            }
            is CrocTransferState.Transferring,
            is CrocTransferState.Completed -> {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.small)
                )
            }
            else -> Unit
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = quickProgressLabel(state, isSending),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            val trailing = quickProgressMetric(state)
            if (trailing.isNotBlank()) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuickReceivedFilesCard(receivedFiles: List<ReceivedFile>) {
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
            Text(
                text = "Received Files",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            receivedFiles.take(5).forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
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
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = file.savedLocation,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (receivedFiles.size > 5) {
                QuickDetailPill(label = "+${receivedFiles.size - 5} more saved")
            }
        }
    }
}

@Composable
private fun QuickReceivedTextCard(
    receivedText: String,
    totalBytes: Long,
    onCopyText: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Received Text",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = formatBytes(totalBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onCopyText(receivedText) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "Copy text",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = receivedText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickStatusBadge(
    icon: ImageVector,
    label: String
) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QuickErrorPanel(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
private fun QuickDetailPill(
    label: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    )
}

private fun quickTransferTitle(state: CrocTransferState, isSending: Boolean): String {
    return when (state) {
        is CrocTransferState.Preparing -> if (isSending) "Preparing your quick share" else "Getting ready to receive"
        is CrocTransferState.WaitingForPeer -> if (isSending) "Waiting for your peer" else "Listening for the sender"
        is CrocTransferState.Transferring -> if (isSending) "Sharing in progress" else "Receiving in progress"
        is CrocTransferState.Completed -> if (isSending) "Quick share delivered" else "Quick receive complete"
        is CrocTransferState.Error -> if (isSending) "Quick send hit an issue" else "Quick receive hit an issue"
        is CrocTransferState.Cancelled -> if (isSending) "Quick send cancelled" else "Quick receive cancelled"
        else -> "Quick transfer"
    }
}

private fun quickTransferSubtitle(state: CrocTransferState, isSending: Boolean): String {
    return when (state) {
        is CrocTransferState.Preparing -> "Setting up the encrypted transfer session."
        is CrocTransferState.WaitingForPeer -> {
            if (isSending) "Share the QR or code so the receiver can join."
            else "Using the active code to connect and save directly into Downloads."
        }
        is CrocTransferState.Transferring -> {
            if (isSending) "${state.fileName} is on the wire right now."
            else "Incoming data is being written and prepared for Downloads."
        }
        is CrocTransferState.Completed -> {
            if (isSending) {
                "${state.fileCount} item${if (state.fileCount == 1) "" else "s"} shared successfully."
            } else if (state.receivedText != null) {
                "The text payload is ready below."
            } else {
                "${state.fileCount} item${if (state.fileCount == 1) "" else "s"} saved for you."
            }
        }
        is CrocTransferState.Error -> if (isSending) {
            "The share stopped before completion."
        } else {
            "The receive stopped before completion."
        }
        is CrocTransferState.Cancelled -> "Start another transfer whenever you're ready."
        else -> ""
    }
}

private fun quickProgressLabel(state: CrocTransferState, isSending: Boolean): String {
    return when (state) {
        is CrocTransferState.Preparing -> "Preparing"
        is CrocTransferState.WaitingForPeer -> if (isSending) "Waiting for receiver" else "Waiting for sender"
        is CrocTransferState.Transferring -> if (isSending) "Live transfer" else "Saving to Downloads"
        is CrocTransferState.Completed -> "Complete"
        is CrocTransferState.Error -> "Needs attention"
        is CrocTransferState.Cancelled -> "Stopped"
        else -> ""
    }
}

private fun quickProgressMetric(state: CrocTransferState): String {
    return when (state) {
        is CrocTransferState.Transferring -> "${state.progressPercent}% • ${formatBytes(state.bytesTransferred)} / ${formatBytes(state.totalBytes)}"
        is CrocTransferState.Completed -> formatBytes(state.totalBytes)
        else -> ""
    }
}

// ═══════════════════════════════════════════════════════════════
// Section B: Quick Action Buttons — diamond cluster layout
// ═══════════════════════════════════════════════════════════════

@Composable
private fun QuickActionButtons(
    enabled: Boolean,
    savedCodes: List<String>,
    onSendTap: () -> Unit,
    onClipboardSendTap: () -> Unit,
    onReceiveTap: () -> Unit,
    onReceiveWithCode: (String) -> Unit,
    onQrReceiveTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSavedCodesMenu by remember { mutableStateOf(false) }

    // Use a custom layout to position 4 buttons in a diamond:
    //
    //         [Clipboard]
    //   [SEND]           [RECEIVE]
    //         [QR Scan]
    //
    // Small buttons are at the 12 and 6 o'clock positions (top/bottom)
    // Large buttons are at the 9 and 3 o'clock positions (left/right)
    //
    // But user wants small at ~45°: Clipboard near Send (10-11 o'clock), QR near Receive (1-2 o'clock)
    // So layout is:
    //
    //  [Clip]               [QR]
    //       [SEND]    [RECEIVE]
    //
    // Using trigonometry with the large buttons as anchors.

    val mainSize = 100.dp
    val smallSize = 48.dp
    val mainIconSize = 36.dp
    val smallIconSize = 20.dp

    DiamondButtonCluster(
        modifier = modifier,
        mainSize = mainSize,
        smallSize = smallSize,
        gapBetweenMains = 24.dp,
        smallAngleDeg = 50f,      // angle above horizontal
        smallRadiusExtra = 16.dp  // distance from edge of main button to center of small button
    ) {
        // Slot 0: Send (left main)
        QuickCircleButton(
            icon = Icons.Rounded.Upload,
            label = "Send",
            size = mainSize,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            enabled = enabled,
            onClick = onSendTap,
            elevation = 6.dp,
            iconSize = mainIconSize
        )

        // Slot 1: Receive (right main) — with long-press
        Box {
            QuickCircleButtonWithLongPress(
                icon = Icons.Rounded.Download,
                label = "Receive",
                size = mainSize,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                enabled = enabled,
                onClick = onReceiveTap,
                onLongPress = {
                    if (savedCodes.isNotEmpty()) {
                        showSavedCodesMenu = true
                    } else {
                        onReceiveTap()
                    }
                },
                elevation = 6.dp,
                iconSize = mainIconSize
            )

            DropdownMenu(
                expanded = showSavedCodesMenu,
                onDismissRequest = { showSavedCodesMenu = false }
            ) {
                savedCodes.take(10).forEach { code ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = code,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        onClick = {
                            showSavedCodesMenu = false
                            onReceiveWithCode(code)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
        }

        // Slot 2: Clipboard Send (small, upper-left of Send)
        SmallCircleButton(
            icon = Icons.Rounded.ContentPaste,
            size = smallSize,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            enabled = enabled,
            onClick = onClipboardSendTap,
            iconSize = smallIconSize
        )

        // Slot 3: QR Receive (small, upper-right of Receive)
        SmallCircleButton(
            icon = Icons.Rounded.QrCodeScanner,
            size = smallSize,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            enabled = enabled,
            onClick = onQrReceiveTap,
            iconSize = smallIconSize
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Custom Layout: Diamond Button Cluster
// ═══════════════════════════════════════════════════════════════

/**
 * Places 4 children in a diamond cluster:
 *   child[0] = left main button (Send)
 *   child[1] = right main button (Receive)
 *   child[2] = small button orbiting child[0] at upper-left angle
 *   child[3] = small button orbiting child[1] at upper-right angle
 *
 * This uses Compose [Layout] so positioning is pixel-perfect and never overlaps.
 */
@Composable
private fun DiamondButtonCluster(
    modifier: Modifier = Modifier,
    mainSize: Dp,
    smallSize: Dp,
    gapBetweenMains: Dp,
    smallAngleDeg: Float,
    smallRadiusExtra: Dp,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        require(measurables.size == 4) { "DiamondButtonCluster requires exactly 4 children" }

        val mainPx = mainSize.roundToPx()
        val smallPx = smallSize.roundToPx()
        val gapPx = gapBetweenMains.roundToPx()
        val radiusExtraPx = smallRadiusExtra.roundToPx()

        // Measure all children
        val mainConstraints = constraints.copy(
            minWidth = 0, minHeight = 0,
            maxWidth = mainPx + 80, // allow for label text
            maxHeight = mainPx + 60 // allow for label below
        )
        val smallConstraints = constraints.copy(
            minWidth = 0, minHeight = 0,
            maxWidth = smallPx,
            maxHeight = smallPx
        )

        val sendPlaceable = measurables[0].measure(mainConstraints)
        val receivePlaceable = measurables[1].measure(mainConstraints)
        val clipPlaceable = measurables[2].measure(smallConstraints)
        val qrPlaceable = measurables[3].measure(smallConstraints)

        // Calculate positions
        // Total width = sendWidth + gap + receiveWidth
        val totalMainWidth = sendPlaceable.width + gapPx + receivePlaceable.width
        val centerX = constraints.maxWidth / 2

        // Send button center
        val sendCenterX = centerX - gapPx / 2 - sendPlaceable.width / 2
        // Receive button center
        val recvCenterX = centerX + gapPx / 2 + receivePlaceable.width / 2

        // Orbit radius: from center of main to center of small
        val orbitRadius = mainPx / 2 + radiusExtraPx + smallPx / 2

        // Clipboard: orbits Send at upper-left (angle measured from horizontal, going up-left)
        val clipAngleRad = Math.toRadians(smallAngleDeg.toDouble())
        val clipCenterX = sendCenterX - (orbitRadius * cos(clipAngleRad)).roundToInt()
        val clipCenterY = -(orbitRadius * sin(clipAngleRad)).roundToInt() // negative = up

        // QR: orbits Receive at upper-right (mirrored)
        val qrCenterX = recvCenterX + (orbitRadius * cos(clipAngleRad)).roundToInt()
        val qrCenterY = -(orbitRadius * sin(clipAngleRad)).roundToInt()

        // We need to find the bounding box. Main buttons are at y=0 (their top).
        // Small buttons are above, so we need to offset everything down.
        val smallTopOffset = minOf(clipCenterY, qrCenterY) - smallPx / 2
        val yShift = if (smallTopOffset < 0) -smallTopOffset else 0

        // Main button Y: centered vertically on their circle center
        val mainY = yShift
        val totalHeight = yShift + maxOf(sendPlaceable.height, receivePlaceable.height)

        layout(constraints.maxWidth, totalHeight) {
            // Place Send
            sendPlaceable.place(
                x = sendCenterX - sendPlaceable.width / 2,
                y = mainY
            )
            // Place Receive
            receivePlaceable.place(
                x = recvCenterX - receivePlaceable.width / 2,
                y = mainY
            )
            // Place Clipboard (small, upper-left of Send)
            clipPlaceable.place(
                x = clipCenterX - clipPlaceable.width / 2,
                y = yShift + clipCenterY - clipPlaceable.height / 2
            )
            // Place QR (small, upper-right of Receive)
            qrPlaceable.place(
                x = qrCenterX - qrPlaceable.width / 2,
                y = yShift + qrCenterY - qrPlaceable.height / 2
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Button Components
// ═══════════════════════════════════════════════════════════════

@Composable
private fun QuickCircleButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    elevation: Dp = 4.dp,
    iconSize: Dp = 32.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    val animatedElevation by animateDpAsState(
        targetValue = if (isPressed) 2.dp else elevation,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "elevation"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
            contentColor = contentColor,
            tonalElevation = 2.dp,
            shadowElevation = animatedElevation,
            interactionSource = interactionSource,
            modifier = Modifier
                .size(size)
                .scale(scale)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun SmallCircleButton(
    icon: ImageVector,
    size: Dp,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    iconSize: Dp = 20.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scale"
    )

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
        contentColor = contentColor,
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(size)
            .scale(scale)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickCircleButtonWithLongPress(
    icon: ImageVector,
    label: String,
    size: Dp,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    elevation: Dp = 4.dp,
    iconSize: Dp = 32.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    val animatedElevation by animateDpAsState(
        targetValue = if (isPressed) 2.dp else elevation,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "elevation"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
            contentColor = contentColor,
            tonalElevation = 2.dp,
            shadowElevation = animatedElevation,
            modifier = Modifier
                .size(size)
                .scale(scale)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongPress,
                    interactionSource = interactionSource,
                    indication = null
                )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}
