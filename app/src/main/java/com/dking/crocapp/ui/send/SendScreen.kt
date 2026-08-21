package com.dking.crocapp.ui.send

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import android.widget.Toast
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dking.crocapp.R
import com.dking.crocapp.croc.CrocEngine
import com.dking.crocapp.croc.CrocTransferState
import com.dking.crocapp.ui.components.EngineBadge
import com.dking.crocapp.ui.components.QrCodeExpandedDialog
import com.dking.crocapp.ui.components.QrCodeImage
import com.dking.crocapp.util.QrCodeParser
import com.dking.crocapp.ui.components.TransferProgressCard
import com.dking.crocapp.ui.components.formatBytes
import com.dking.crocapp.ui.components.generateQrCodeBitmap
import com.dking.crocapp.ui.components.progressBorder
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

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.addFolder(uri)
        }
    }

    val isTransferActive = uiState.transferState is CrocTransferState.Preparing ||
            uiState.transferState is CrocTransferState.WaitingForPeer ||
            uiState.transferState is CrocTransferState.Transferring
    val isStoreCompleted = uiState.isStoreMode && uiState.transferState is CrocTransferState.StoreCompleted
    val isDirectCompleted = !uiState.isStoreMode && uiState.transferState is CrocTransferState.Completed
    val isTransferFinished = isDirectCompleted ||
            isStoreCompleted ||
            uiState.transferState is CrocTransferState.Error ||
            uiState.transferState is CrocTransferState.Cancelled
    val isLegacyFallback = uiState.transferState is CrocTransferState.LegacyFallbackAvailable
    val showTransferSection = isTransferActive || isTransferFinished || isLegacyFallback
    val canSend = (uiState.isStoreMode || uiState.codePhrase.isNotBlank()) && uiState.hasContent

    val fabLabel = when {
        isStoreCompleted -> "Normal Mode"
        isDirectCompleted -> stringResource(R.string.send_again)
        isLegacyFallback -> stringResource(R.string.action_retry_legacy)
        uiState.transferState is CrocTransferState.Error || uiState.transferState is CrocTransferState.Cancelled -> stringResource(R.string.action_retry)
        uiState.isStoreMode -> stringResource(R.string.send_action_store)
        else -> stringResource(R.string.action_send)
    }

    // Animated progress for the file card border
    val transferProgress = when (val state = uiState.transferState) {
        is CrocTransferState.Transferring -> state.fileCountProgress
        is CrocTransferState.Completed -> if (!uiState.isStoreMode) 1f else 0f
        is CrocTransferState.StoreCompleted -> if (uiState.isStoreMode) 1f else 0f
        else -> 0f
    }
    val animatedBorderProgress by animateFloatAsState(
        targetValue = transferProgress,
        animationSpec = tween(400),
        label = "borderProgress"
    )
    val showFileBorder = isTransferActive || isDirectCompleted || isStoreCompleted
    val borderColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.nav_send),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.nav_history))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.nav_settings))
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
                        if (isLegacyFallback) {
                            viewModel.retryWithLegacy()
                        } else if (isStoreCompleted) {
                            viewModel.dismissTransferResult()
                            viewModel.setDeliveryMode(DeliveryMode.DIRECT)
                        } else {
                            if (isTransferFinished) {
                                viewModel.dismissTransferResult()
                            }
                            viewModel.startSend()
                        }
                    },
                    icon = {
                        Icon(
                            when {
                                isLegacyFallback -> Icons.Rounded.History
                                isStoreCompleted -> Icons.AutoMirrored.Rounded.Send
                                uiState.isStoreMode -> Icons.Rounded.CloudUpload
                                else -> Icons.Rounded.Upload
                            },
                            contentDescription = null
                        )
                    },
                    text = { Text(fabLabel, fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 3.dp
                    ),
                    expanded = canSend || isLegacyFallback || isStoreCompleted
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

            // Mode Toggle: Files / Folder / Text (Store mode only supports Files & Text)
            if (uiState.isStoreMode) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        onClick = { viewModel.setSendMode(SendMode.FILES) },
                        selected = uiState.sendMode == SendMode.FILES,
                        icon = {
                            Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    ) {
                        Text(stringResource(R.string.send_mode_files))
                    }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        onClick = { viewModel.setSendMode(SendMode.TEXT) },
                        selected = uiState.sendMode == SendMode.TEXT,
                        icon = {
                            Icon(Icons.Outlined.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    ) {
                        Text(stringResource(R.string.send_mode_text))
                    }
                }
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        onClick = { viewModel.setSendMode(SendMode.FILES) },
                        selected = uiState.sendMode == SendMode.FILES,
                        icon = {
                            Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    ) {
                        Text(stringResource(R.string.send_mode_files))
                    }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        onClick = { viewModel.setSendMode(SendMode.FOLDER) },
                        selected = uiState.sendMode == SendMode.FOLDER,
                        icon = {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    ) {
                        Text(stringResource(R.string.send_mode_folder))
                    }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        onClick = { viewModel.setSendMode(SendMode.TEXT) },
                        selected = uiState.sendMode == SendMode.TEXT,
                        icon = {
                            Icon(Icons.Outlined.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    ) {
                        Text(stringResource(R.string.send_mode_text))
                    }
                }
            }

            // ──── File Selection Card — with animated progress border ────
            AnimatedVisibility(
                visible = uiState.sendMode == SendMode.FILES,
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
                                text = stringResource(R.string.send_files_title),
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
                                Text(if (uiState.selectedFiles.isEmpty()) stringResource(R.string.send_pick_files) else stringResource(R.string.send_add_more))
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
                                    Text(stringResource(R.string.action_clear))
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
                                text = stringResource(R.string.send_no_files),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // ──── Folder Selection Card ────
            AnimatedVisibility(
                visible = uiState.sendMode == SendMode.FOLDER,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (showFileBorder && uiState.sendMode == SendMode.FOLDER) {
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
                                text = "Folder",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (uiState.selectedFolderName != null) {
                                Text(
                                    text = "${uiState.selectedFolderFileCount} files • ${formatBytes(uiState.selectedFolderSize)}",
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
                                onClick = { folderPickerLauncher.launch(null) },
                                enabled = !isTransferActive,
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (uiState.selectedFolderName == null) "Pick Folder" else "Change")
                            }
                            if (uiState.selectedFolderName != null) {
                                OutlinedButton(
                                    onClick = { viewModel.clearFolder() },
                                    enabled = !isTransferActive,
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.action_clear))
                                }
                            }
                        }

                        if (uiState.selectedFolderName != null) {
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
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Column {
                                    Text(
                                        text = uiState.selectedFolderName ?: "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${uiState.selectedFolderFileCount} files, ${formatBytes(uiState.selectedFolderSize)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.send_no_folder),
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
                visible = uiState.sendMode == SendMode.TEXT,
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
                            text = stringResource(R.string.send_quick_text),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = uiState.textToSend,
                            onValueChange = { viewModel.updateTextToSend(it) },
                            placeholder = { Text(stringResource(R.string.send_text_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            maxLines = 6,
                            enabled = !isTransferActive,
                            shape = MaterialTheme.shapes.large
                        )
                    }
                }
            }

            // ──── Transfer Progress / Result ────
            AnimatedVisibility(
                visible = showTransferSection,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isStoreCompleted) {
                        StoreCompletedCard(
                            state = uiState.transferState as CrocTransferState.StoreCompleted,
                            onDismiss = { viewModel.dismissTransferResult() },
                            onRevoke = { id -> viewModel.revokeCurrentStore(id) }
                        )
                    } else {
                        TransferProgressCard(
                            state = uiState.transferState,
                            isSending = true,
                            onCancel = { viewModel.cancelTransfer() },
                            onRetryLegacy = { viewModel.retryWithLegacy() },
                            activeEngine = uiState.activeEngine
                        )
                        if (isTransferFinished) {
                            OutlinedButton(
                                onClick = { viewModel.dismissTransferResult() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Text(stringResource(R.string.action_dismiss))
                            }
                        }
                    }
                }
            }

            // ──── Delivery Options: Store Config OR Direct Code Phrase ────
            if (uiState.isStoreMode) {
                StoreConfigurationCard(
                    expiration = uiState.storeExpiration,
                    downloads = uiState.storeDownloads,
                    enabled = !isTransferActive,
                    onExpirationChange = { viewModel.setStoreExpiration(it) },
                    onDownloadsChange = { viewModel.setStoreDownloads(it) }
                )
            } else {
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.send_secret_code),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (uiState.defaultCodePhrase.isNotBlank() && uiState.defaultCodePhrase == uiState.codePhrase) {
                                    Text(
                                        text = stringResource(R.string.label_default),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            EngineBadge(
                                engine = uiState.activeEngine,
                                onClick = if (!isTransferActive) { { viewModel.toggleEngine() } } else null,
                                showCurrentMode = true
                            )
                        }
                    OutlinedTextField(
                        value = uiState.codePhrase,
                        onValueChange = { viewModel.updateCodePhrase(it) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTransferActive,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.send_code_placeholder)) },
                        shape = MaterialTheme.shapes.large,
                        visualTransformation = if (hideCodePhrase) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { hideCodePhrase = !hideCodePhrase }) {
                                    Icon(
                                        imageVector = if (hideCodePhrase) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        contentDescription = if (hideCodePhrase) stringResource(R.string.send_show_code) else stringResource(R.string.send_hide_code)
                                    )
                                }
                                IconButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(uiState.codePhrase))
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.send_copy_code))
                                }
                            }
                        }
                    )

                    // Action chips
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AssistChip(
                            onClick = { viewModel.regenerateCode() },
                            enabled = !isTransferActive,
                            label = { Text(stringResource(R.string.send_regenerate)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        AssistChip(
                            onClick = { viewModel.saveCurrentCode() },
                            enabled = !isTransferActive && uiState.codePhrase.isNotBlank(),
                            label = { Text(stringResource(R.string.action_save)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        if (uiState.defaultCodePhrase.isNotBlank() && uiState.defaultCodePhrase != uiState.codePhrase) {
                            AssistChip(
                                onClick = { viewModel.useCodePhrase(uiState.defaultCodePhrase) },
                                enabled = !isTransferActive,
                                label = { Text(stringResource(R.string.action_reset)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Home, contentDescription = stringResource(R.string.send_use_default_code), modifier = Modifier.size(18.dp))
                                }
                            )
                        }
                        AssistChip(
                            onClick = { showQrCode = !showQrCode },
                            enabled = uiState.codePhrase.isNotBlank(),
                            label = { Text(if (showQrCode) stringResource(R.string.send_hide_qr) else stringResource(R.string.send_qr_code)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        AssistChip(
                            onClick = {
                                shareQrCode(
                                    context = context,
                                    codePhrase = uiState.codePhrase
                                )
                            },
                            enabled = uiState.codePhrase.isNotBlank(),
                            label = { Text(stringResource(R.string.send_share_qr)) },
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
                                        SavedCodeChipWithActions(
                                            code = savedCode,
                                            enabled = !isTransferActive,
                                            onUse = { viewModel.useCodePhrase(savedCode) },
                                            onCopy = { clipboardManager.setText(AnnotatedString(savedCode)) },
                                            onShare = {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, savedCode)
                                                    putExtra(Intent.EXTRA_SUBJECT, "croc code")
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Share code"))
                                            },
                                            onShareQr = {
                                                shareQrCode(
                                                    context = context,
                                                    codePhrase = savedCode
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
                                // Deep link, so a phone camera opens the app directly.
                                data = QrCodeParser.receiveDeepLink(uiState.codePhrase),
                                size = 180.dp
                            )
                        }
                    }
                }
            }
        }

        // ──── Mode Switch Banners at the Bottom ────
        if (!isTransferActive && !isStoreCompleted) {
                if (!uiState.isStoreMode) {
                    // Switch to Store Mode Card
                    Surface(
                        onClick = { viewModel.setDeliveryMode(DeliveryMode.STORE) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.CloudUpload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "Store",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Upload encrypted files to store for later pickup",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    // Switch to Normal Direct Mode Card
                    Surface(
                        onClick = { viewModel.setDeliveryMode(DeliveryMode.DIRECT) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Send,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "Normal Mode",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Direct peer-to-peer real-time transfer",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
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
    codePhrase: String
) {
    val cleanCode = QrCodeParser.parseCode(codePhrase)
    if (cleanCode.isBlank()) return
    val deepLinkUrl = QrCodeParser.receiveDeepLink(cleanCode)

    // Always use black-on-white for universally scannable QR codes
    val bitmap = generateQrCodeBitmap(
        deepLinkUrl, 1024,
        android.graphics.Color.BLACK,
        android.graphics.Color.WHITE
    ) ?: return

    runCatching {
        val shareDir = File(context.cacheDir, "qr-share").apply { mkdirs() }
        val safeName = cleanCode.replace(Regex("[^a-zA-Z0-9-_]"), "_")
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
            putExtra(Intent.EXTRA_TEXT, "Use this croc code: $cleanCode\n$deepLinkUrl")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share QR code"))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedCodeChipWithActions(
    code: String,
    enabled: Boolean,
    onUse: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShareQr: () -> Unit = {}
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
                text = { Text(stringResource(R.string.history_use_code)) },
                onClick = {
                    showMenu = false
                    onUse()
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy)) },
                onClick = {
                    showMenu = false
                    onCopy()
                },
                leadingIcon = {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                onClick = {
                    showMenu = false
                    onShare()
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.send_share_qr)) },
                onClick = {
                    showMenu = false
                    onShareQr()
                },
                leadingIcon = {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            )
        }
    }
}

private enum class StoreResultTab {
    BROWSER_LINK,
    CLI_TOKEN
}

private fun formatStoreExpirationParts(expiresAt: Long, rawText: String): Pair<String, String> {
    if (expiresAt > 0) {
        val date = java.util.Date(expiresAt)
        val dayFormat = java.text.SimpleDateFormat("d MMM", java.util.Locale.US)
        val timeFormat = java.text.SimpleDateFormat("HH:mm z", java.util.Locale.US)
        return Pair(dayFormat.format(date), timeFormat.format(date))
    }
    if (rawText.isNotBlank()) {
        return Pair("In $rawText", "auto-delete")
    }
    return Pair("Active", "temporary")
}

@Composable
private fun StoreConfigurationCard(
    expiration: String,
    downloads: Int,
    enabled: Boolean,
    onExpirationChange: (String) -> Unit,
    onDownloadsChange: (Int) -> Unit
) {
    val presetExpirations = listOf("1h", "12h", "1d", "3d", "1w", "2w")
    val presetDownloads = listOf(1, 2, 5, 10)

    var isCustomExpiration by remember(expiration) {
        mutableStateOf(expiration !in presetExpirations)
    }
    var customExpirationInput by remember(expiration) {
        mutableStateOf(if (expiration !in presetExpirations) expiration else "")
    }

    var isCustomDownloads by remember(downloads) {
        mutableStateOf(downloads !in presetDownloads)
    }
    var customDownloadsInput by remember(downloads) {
        mutableStateOf(if (downloads !in presetDownloads) downloads.toString() else "")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.store_configuration_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.store_info_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                EngineBadge(
                    engine = CrocEngine.CURRENT,
                    onClick = null,
                    showCurrentMode = true
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )

            // Expiration Lifetime Section (Line 1: Scrollable Chips, Line 2: Custom Input)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.store_expiration_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                // Line 1: Single scrollable row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    presetExpirations.forEach { preset ->
                        FilterChip(
                            selected = !isCustomExpiration && expiration == preset,
                            onClick = {
                                if (enabled) {
                                    isCustomExpiration = false
                                    onExpirationChange(preset)
                                }
                            },
                            enabled = enabled,
                            label = { Text(preset) },
                            shape = MaterialTheme.shapes.medium
                        )
                    }
                    FilterChip(
                        selected = isCustomExpiration,
                        onClick = {
                            if (enabled) {
                                isCustomExpiration = true
                                if (customExpirationInput.isNotBlank() && customExpirationInput.matches(Regex("^[1-9][0-9]*[mhdw]$"))) {
                                    onExpirationChange(customExpirationInput)
                                }
                            }
                        },
                        enabled = enabled,
                        label = { Text(stringResource(R.string.store_custom_label)) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        shape = MaterialTheme.shapes.medium
                    )
                }

                // Line 2: Custom input with real-time validation (m, h, d, w)
                AnimatedVisibility(visible = isCustomExpiration) {
                    val isInvalid = customExpirationInput.isNotBlank() && !customExpirationInput.matches(Regex("^[1-9][0-9]*[mhdw]$"))
                    OutlinedTextField(
                        value = customExpirationInput,
                        onValueChange = { input ->
                            val clean = input.trim().lowercase()
                            customExpirationInput = clean
                            if (clean.matches(Regex("^[1-9][0-9]*[mhdw]$"))) {
                                onExpirationChange(clean)
                            }
                        },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        placeholder = { Text(stringResource(R.string.store_custom_expiration_hint)) },
                        supportingText = {
                            Text(
                                if (isInvalid) "Invalid format. Suffix must be m, h, d, or w (e.g. 45m, 12h, 3d, 2w)"
                                else stringResource(R.string.store_custom_expiration_desc)
                            )
                        },
                        isError = isInvalid,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )

            // Allowed Downloads Section (Line 1: Scrollable Numbers, Line 2: Custom Input)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.store_downloads_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                // Line 1: Single scrollable row with just 1, 2, 5, 10, Custom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    presetDownloads.forEach { count ->
                        FilterChip(
                            selected = !isCustomDownloads && downloads == count,
                            onClick = {
                                if (enabled) {
                                    isCustomDownloads = false
                                    onDownloadsChange(count)
                                }
                            },
                            enabled = enabled,
                            label = { Text(count.toString()) },
                            shape = MaterialTheme.shapes.medium
                        )
                    }
                    FilterChip(
                        selected = isCustomDownloads,
                        onClick = {
                            if (enabled) {
                                isCustomDownloads = true
                                val parsed = customDownloadsInput.toIntOrNull()
                                if (parsed != null && parsed > 0) {
                                    onDownloadsChange(parsed)
                                }
                            }
                        },
                        enabled = enabled,
                        label = { Text(stringResource(R.string.store_custom_label)) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        shape = MaterialTheme.shapes.medium
                    )
                }

                // Line 2: Custom input for downloads count
                AnimatedVisibility(visible = isCustomDownloads) {
                    OutlinedTextField(
                        value = customDownloadsInput,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }
                            customDownloadsInput = digits
                            val parsed = digits.toIntOrNull()
                            if (parsed != null && parsed > 0) {
                                onDownloadsChange(parsed)
                            }
                        },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        placeholder = { Text(stringResource(R.string.store_custom_downloads_hint)) },
                        supportingText = { Text(stringResource(R.string.store_custom_downloads_desc)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        }
    }
}

@Composable
private fun StoreCompletedCard(
    state: CrocTransferState.StoreCompleted,
    onDismiss: () -> Unit,
    onRevoke: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var selectedTab by remember { mutableStateOf(StoreResultTab.BROWSER_LINK) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showRevokeDialog by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    val qrData = if (selectedTab == StoreResultTab.BROWSER_LINK && state.browserLink.isNotBlank()) {
        state.browserLink
    } else if (state.cliToken.isNotBlank()) {
        state.cliToken
    } else {
        state.browserLink
    }

    if (showQrDialog && qrData.isNotBlank()) {
        QrCodeExpandedDialog(
            data = qrData,
            onDismiss = { showQrDialog = false }
        )
    }

    if (showRevokeDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            title = { Text(stringResource(R.string.store_revoke_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.store_revoke_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showRevokeDialog = false
                        onRevoke(state.storeId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.store_revoke_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ──── 1. Hero Header ────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.CloudUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.store_result_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.store_result_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                EngineBadge(
                    engine = CrocEngine.CURRENT,
                    onClick = null,
                    showCurrentMode = true
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )

            // ──── 2. 3-Column Stats Grid (Expires | Size | Downloads) ────
            val (expiresDate, expiresTime) = formatStoreExpirationParts(state.expiresAt, state.rawExpirationText)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Column 1: Expires
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Expires",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = expiresDate,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = expiresTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                )

                // Column 2: Size
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Size",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatBytes(state.totalBytes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${state.fileCount} ${if (state.fileCount == 1) "file" else "files"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                )

                // Column 3: Downloads
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Downloads",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (state.downloadsLimit > 0) "${state.downloadsLimit}" else "∞",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "available",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )

            // ──── 3. Tab Selector ([ Browser link ] [ CLI token ]) ────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Browser Link Tab
                val isBrowserSelected = selectedTab == StoreResultTab.BROWSER_LINK
                Surface(
                    onClick = {
                        selectedTab = StoreResultTab.BROWSER_LINK
                        copied = false
                    },
                    shape = CircleShape,
                    color = if (isBrowserSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (isBrowserSelected) 2.dp else 0.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isBrowserSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.store_browser_link),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isBrowserSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isBrowserSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // CLI Token Tab
                val isCliSelected = selectedTab == StoreResultTab.CLI_TOKEN
                Surface(
                    onClick = {
                        selectedTab = StoreResultTab.CLI_TOKEN
                        copied = false
                    },
                    shape = CircleShape,
                    color = if (isCliSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (isCliSelected) 2.dp else 0.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isCliSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.store_cli_token),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isCliSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCliSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ──── 4. Received Text Card with Embedded Action Icons (Matching QuickScreen / ReceiveScreen) ────
            val activeContent = if (selectedTab == StoreResultTab.BROWSER_LINK) state.browserLink else state.cliToken

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedTab == StoreResultTab.BROWSER_LINK) stringResource(R.string.store_browser_link) else stringResource(R.string.store_cli_token),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (selectedTab == StoreResultTab.BROWSER_LINK) {
                                IconButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(activeContent.trim()))
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.OpenInNew,
                                        contentDescription = stringResource(R.string.quick_open_url),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, activeContent)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share"))
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Share,
                                    contentDescription = "Share",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(activeContent))
                                    copied = true
                                    Toast.makeText(
                                        context,
                                        if (selectedTab == StoreResultTab.BROWSER_LINK) context.getString(R.string.qr_url_copied)
                                        else context.getString(R.string.qr_code_copied),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = stringResource(R.string.quick_copy_text),
                                    modifier = Modifier.size(18.dp),
                                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    SelectionContainer {
                        Text(
                            text = activeContent,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ──── 5. Secondary Outlined Actions: Show QR & Revoke (Split 50-50 across the width) ────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showQrDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        Icons.Rounded.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.store_qr_code),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (state.storeId.isNotBlank()) {
                    OutlinedButton(
                        onClick = { showRevokeDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.store_revoke_action),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ──── 6. Bottom Dismiss Button ────
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.action_dismiss),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}
