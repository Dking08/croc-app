package com.crocworks.app.ui.quick

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FlashOn
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.crocworks.app.ui.components.TransferProgressCard

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
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Quick",
                            fontWeight = FontWeight.Bold
                        )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ═══════════════════════════════════════════
            // Section A: Status / Info Area (top half)
            // ═══════════════════════════════════════════
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isTransferActive || isTransferFinished) {
                    TransferStatusSection(
                        state = uiState.transferState,
                        lastAction = uiState.lastAction,
                        receivedText = uiState.receivedText,
                        onCancel = { viewModel.cancelTransfer() },
                        onDismiss = { viewModel.dismissResult() },
                        onCopyText = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                        }
                    )
                } else {
                    IdleStatusSection()
                }
            }

            // ═══════════════════════════════════════════
            // Section B: Action Buttons (bottom half)
            // ═══════════════════════════════════════════
            QuickActionButtons(
                enabled = !isTransferActive,
                savedCodes = uiState.savedCodePhrases,
                onSendTap = {
                    if (isTransferFinished) viewModel.dismissResult()
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onClipboardSendTap = {
                    if (isTransferFinished) viewModel.dismissResult()
                    val text = clipboardManager.getText()?.text ?: ""
                    if (text.isNotBlank()) {
                        viewModel.sendClipboardText(text)
                    }
                },
                onReceiveTap = {
                    if (isTransferFinished) viewModel.dismissResult()
                    viewModel.startReceive()
                },
                onReceiveWithCode = { code ->
                    if (isTransferFinished) viewModel.dismissResult()
                    viewModel.startReceiveWithCode(code)
                },
                onQrReceiveTap = {
                    if (isTransferFinished) viewModel.dismissResult()
                    onOpenScanner { code ->
                        viewModel.startReceiveFromQr(code)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Section A: Status Components
// ═══════════════════════════════════════════════════════════════

@Composable
private fun IdleStatusSection() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.FlashOn,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = "Quick Transfer",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "Send files, text, or receive instantly",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TransferStatusSection(
    state: CrocTransferState,
    lastAction: String,
    receivedText: String?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onCopyText: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TransferProgressCard(
            state = state,
            isSending = lastAction in listOf("send", "clipboard"),
            onCancel = onCancel
        )

        // Received text display
        if (state is CrocTransferState.Completed && receivedText != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                ),
                shape = MaterialTheme.shapes.large
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
                        IconButton(
                            onClick = { onCopyText(receivedText) },
                            modifier = Modifier.size(32.dp)
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
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val isFinished = state is CrocTransferState.Completed ||
                state is CrocTransferState.Error ||
                state is CrocTransferState.Cancelled

        if (isFinished) {
            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Text("Dismiss")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Section B: Quick Action Buttons — proper grid layout
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

    // Clean two-row layout:
    //   Row 1 (small):   [Clipboard]          [QR Scan]
    //   Row 2 (large):       [SEND]    [RECEIVE ▾]
    Column(
        modifier = modifier
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Row 1: Small auxiliary buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickCircleButton(
                icon = Icons.Rounded.ContentPaste,
                label = "Clipboard",
                size = 56.dp,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                enabled = enabled,
                onClick = onClipboardSendTap,
                elevation = 3.dp,
                iconSize = 22.dp
            )

            QuickCircleButton(
                icon = Icons.Rounded.QrCodeScanner,
                label = "QR Scan",
                size = 56.dp,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                enabled = enabled,
                onClick = onQrReceiveTap,
                elevation = 3.dp,
                iconSize = 22.dp
            )
        }

        // Row 2: Main Send & Receive buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            QuickCircleButton(
                icon = Icons.Rounded.Upload,
                label = "Send",
                size = 88.dp,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                enabled = enabled,
                onClick = onSendTap,
                elevation = 6.dp,
                iconSize = 32.dp
            )

            // Receive button with long-press for saved codes
            Box {
                QuickCircleButtonWithLongPress(
                    icon = Icons.Rounded.Download,
                    label = "Receive",
                    size = 88.dp,
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
                    iconSize = 32.dp
                )

                // Dropdown menu for saved codes (appears on long-press)
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
        }

        Spacer(modifier = Modifier.height(4.dp))
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
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickCircleButtonWithLongPress(
    icon: ImageVector,
    label: String,
    size: Dp,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
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
