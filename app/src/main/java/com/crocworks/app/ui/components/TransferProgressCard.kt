package com.crocworks.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crocworks.app.croc.CrocTransferState

@Composable
fun TransferProgressCard(
    state: CrocTransferState,
    isSending: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (state) {
                is CrocTransferState.Preparing -> {
                    TransferHeader(
                        icon = if (isSending) Icons.Rounded.CloudUpload else Icons.Rounded.Download,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBackground = MaterialTheme.colorScheme.primaryContainer,
                        title = if (isSending) "Preparing upload..." else "Preparing download...",
                        subtitle = null
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(MaterialTheme.shapes.small),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round
                    )
                }

                is CrocTransferState.WaitingForPeer -> {
                    TransferHeader(
                        icon = if (isSending) Icons.Rounded.CloudUpload else Icons.Rounded.Download,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBackground = MaterialTheme.colorScheme.primaryContainer,
                        title = if (isSending) "Waiting for peer..." else "Connecting to sender...",
                        subtitle = if (isSending) "Share the code with the receiver" else "Verifying code phrase..."
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(MaterialTheme.shapes.small),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round
                    )
                    FilledTonalButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text("Cancel")
                    }
                }

                is CrocTransferState.Transferring -> {
                    TransferHeader(
                        icon = if (isSending) Icons.Rounded.CloudUpload else Icons.Rounded.Download,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBackground = MaterialTheme.colorScheme.primaryContainer,
                        title = if (isSending) "Uploading..." else "Downloading...",
                        subtitle = "${state.fileName} (${state.currentFile}/${state.totalFiles})"
                    )

                    val animatedProgress by animateFloatAsState(
                        targetValue = state.progress,
                        animationSpec = tween(300),
                        label = "progress"
                    )

                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(MaterialTheme.shapes.small),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${state.progressPercent}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatBytes(state.bytesTransferred) + " / " + formatBytes(state.totalBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FilledTonalButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text("Cancel")
                    }
                }

                is CrocTransferState.Completed -> {
                    TransferHeader(
                        icon = Icons.Rounded.CheckCircle,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBackground = MaterialTheme.colorScheme.primaryContainer,
                        title = if (isSending) "Upload Complete!" else "Download Complete!",
                        subtitle = "${state.fileNames.size} file(s) — ${formatBytes(state.totalBytes)}",
                        titleColor = MaterialTheme.colorScheme.primary
                    )
                }

                is CrocTransferState.Error -> {
                    TransferHeader(
                        icon = Icons.Rounded.Error,
                        iconTint = MaterialTheme.colorScheme.error,
                        iconBackground = MaterialTheme.colorScheme.errorContainer,
                        title = "Transfer Failed",
                        subtitle = state.message,
                        titleColor = MaterialTheme.colorScheme.error
                    )
                }

                is CrocTransferState.Cancelled -> {
                    TransferHeader(
                        icon = Icons.Rounded.Cancel,
                        iconTint = MaterialTheme.colorScheme.outline,
                        iconBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                        title = "Transfer Cancelled",
                        subtitle = null
                    )
                }

                else -> {}
            }
        }
    }
}

@Composable
private fun TransferHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    iconBackground: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String?,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Tinted circle behind the icon
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(iconBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
