package com.crocworks.app.ui.quick

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
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
import com.crocworks.app.ui.components.TransferProgressCard
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
                    .padding(bottom = 32.dp)
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
            text = "Send files, text, or receive instantly\nHold Receive for saved codes",
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
