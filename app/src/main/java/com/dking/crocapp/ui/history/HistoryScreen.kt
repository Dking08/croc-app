package com.dking.crocapp.ui.history

import android.content.Intent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dking.crocapp.R
import com.dking.crocapp.data.db.TransferHistory
import com.dking.crocapp.data.db.TransferType
import com.dking.crocapp.ui.components.EmptyState
import com.dking.crocapp.ui.components.QrCodeExpandedDialog
import com.dking.crocapp.ui.components.QrCodeImage
import com.dking.crocapp.ui.components.formatBytes
import com.dking.crocapp.ui.receive.openHistoryTransfer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel(),
    onCodeSelected: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Recents",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Search
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = if (uiState.searchQuery.isNotBlank()) {
                    {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.history_clear_search))
                        }
                    }
                } else null,
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Filter Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = uiState.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = {
                            Text(
                                when (filter) {
                                    HistoryFilter.STORED -> stringResource(R.string.store_filter_label)
                                    else -> filter.name.lowercase().replaceFirstChar { it.uppercase() }
                                }
                            )
                        },
                        leadingIcon = if (uiState.filter == filter) {
                            {
                                when (filter) {
                                    HistoryFilter.ALL -> null
                                    HistoryFilter.SENT -> Icon(
                                        Icons.Rounded.CloudUpload,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    HistoryFilter.RECEIVED -> Icon(
                                        Icons.Rounded.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    HistoryFilter.STORED -> Icon(
                                        Icons.Rounded.CloudDone,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    HistoryFilter.FAVORITES -> Icon(
                                        Icons.Rounded.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (uiState.transfers.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.history_empty_title),
                    subtitle = stringResource(R.string.history_empty_subtitle)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.animateContentSize()
                ) {
                    items(
                        items = uiState.transfers,
                        key = { it.id }
                    ) { transfer ->
                        CompactHistoryCard(
                            transfer = transfer,
                            onOpenTransfer = { openHistoryTransfer(context, transfer) },
                            onCodeSelected = onCodeSelected,
                            onCopyCode = {
                                clipboardManager.setText(AnnotatedString(transfer.code))
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(transfer) },
                            onDelete = { viewModel.deleteTransfer(transfer) },
                            onRevoke = { viewModel.revokeStoredTransfer(transfer) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactHistoryCard(
    transfer: TransferHistory,
    onOpenTransfer: () -> Boolean,
    onCodeSelected: (String) -> Unit,
    onCopyCode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onRevoke: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showRevokeDialog by remember { mutableStateOf(false) }

    val canOpenTransfer = transfer.type == TransferType.RECEIVE && transfer.fileUri != null && transfer.mimeType != null
    val isExpired = transfer.expiresAt != null && System.currentTimeMillis() > transfer.expiresAt
    val isStoreActive = transfer.isStored && !transfer.isRevoked && !isExpired

    if (showQrDialog && !transfer.storeLink.isNullOrBlank()) {
        QrCodeExpandedDialog(
            data = transfer.storeLink,
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
                        onRevoke()
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
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.large,
        onClick = {
            if (transfer.isStored && !transfer.storeLink.isNullOrBlank()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, transfer.storeLink)
                    type = "text/plain"
                }
                try {
                    context.startActivity(Intent.createChooser(sendIntent, null))
                } catch (_: Exception) {}
            } else if (canOpenTransfer) {
                onOpenTransfer()
            } else {
                onCodeSelected(transfer.code)
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction icon with tinted background
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            transfer.isStored && transfer.type == TransferType.RECEIVE -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            transfer.isStored -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            transfer.type == TransferType.SEND -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        transfer.isStored && transfer.type == TransferType.RECEIVE -> Icons.Rounded.CloudDownload
                        transfer.isStored -> Icons.Rounded.CloudUpload
                        transfer.type == TransferType.SEND -> Icons.Rounded.Upload
                        else -> Icons.Rounded.Download
                    },
                    contentDescription = null,
                    tint = when {
                        transfer.isStored && transfer.type == TransferType.RECEIVE -> MaterialTheme.colorScheme.secondary
                        transfer.isStored -> MaterialTheme.colorScheme.primary
                        transfer.type == TransferType.SEND -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 2-line info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = transfer.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (transfer.isFavorite) {
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (transfer.isStored) {
                        when {
                            transfer.type == TransferType.RECEIVE -> {
                                Text(
                                    text = stringResource(R.string.history_badge_received),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            transfer.isRevoked -> {
                                Text(
                                    text = stringResource(R.string.store_revoked_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            isExpired -> {
                                Text(
                                    text = stringResource(R.string.store_expired_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            else -> {
                                val expText = formatExpiration(transfer.expiresAt)
                                Text(
                                    text = if (expText.isNotBlank()) stringResource(R.string.store_expires_in, expText) else stringResource(R.string.history_badge_stored),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (transfer.isStored && !transfer.storeToken.isNullOrBlank()) transfer.storeToken else transfer.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = formatBytes(transfer.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = formatTimestamp(transfer.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 3-dot menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.history_more_options),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (transfer.isStored) {
                        if (!transfer.storeLink.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.store_copy_link)) },
                                onClick = {
                                    showMenu = false
                                    clipboardManager.setText(AnnotatedString(transfer.storeLink))
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.store_share_link)) },
                                onClick = {
                                    showMenu = false
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, transfer.storeLink)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, null))
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.store_qr_code)) },
                                onClick = {
                                    showMenu = false
                                    showQrDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                        }
                        if (!transfer.storeToken.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.store_copy_token)) },
                                onClick = {
                                    showMenu = false
                                    clipboardManager.setText(AnnotatedString(transfer.storeToken))
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                        }
                        if (isStoreActive && !transfer.storeId.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.store_revoke_action)) },
                                onClick = {
                                    showMenu = false
                                    showRevokeDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Block,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    } else {
                        DropdownMenuItem(
                            text = { Text(if (canOpenTransfer) stringResource(R.string.history_open_file) else stringResource(R.string.history_use_code)) },
                            onClick = {
                                showMenu = false
                                if (canOpenTransfer) {
                                    onOpenTransfer()
                                } else {
                                    onCodeSelected(transfer.code)
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    if (canOpenTransfer) Icons.Rounded.FolderOpen else Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_copy_code)) },
                            onClick = {
                                showMenu = false
                                onCopyCode()
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (transfer.isFavorite) stringResource(R.string.history_unfavorite) else stringResource(R.string.history_favorite)) },
                        onClick = {
                            showMenu = false
                            onToggleFavorite()
                        },
                        leadingIcon = {
                            Icon(
                                if (transfer.isFavorite) Icons.Rounded.Star else Icons.Outlined.Star,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

private fun formatExpiration(expiresAt: Long?): String {
    if (expiresAt == null) return ""
    val diff = expiresAt - System.currentTimeMillis()
    if (diff <= 0) return ""
    val hours = diff / 3_600_000
    val days = hours / 24
    val remHours = hours % 24
    return when {
        days > 0 -> "${days}d ${remHours}h"
        hours > 0 -> "${hours}h"
        else -> "${diff / 60_000}m"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> {
            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
