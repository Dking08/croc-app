package com.dking.crocapp.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dking.crocapp.debug.ConfigAuditIssue
import com.dking.crocapp.debug.DebugLogEntry
import com.dking.crocapp.debug.DebugLogLevel
import com.dking.crocapp.debug.DebugLogTag
import com.dking.crocapp.debug.IssueSeverity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = viewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Overview", "Network & Relays", "Audit & Sus Configs", "Logs")

    fun copyReport() {
        scope.launch {
            val report = viewModel.generateReport()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("croc-app Diagnostics", report))
            Toast.makeText(context, "Diagnostic report copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareReport() {
        scope.launch {
            val report = viewModel.generateReport()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "croc-app Diagnostics Report")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            context.startActivity(Intent.createChooser(intent, "Share Diagnostic Report"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Diagnostics & Telemetry", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "DEBUG",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.runFullDiagnostics() }, enabled = !uiState.isRunning) {
                        if (uiState.isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Rerun Diagnostics")
                        }
                    }
                    IconButton(onClick = { copyReport() }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy Report")
                    }
                    IconButton(onClick = { shareReport() }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share Report")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Watchdog Alert Banner (If transfer wait loop detected)
            AnimatedVisibility(visible = uiState.watchdogAlert.isStalled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Stalled Transfer Detected (${uiState.watchdogAlert.durationSeconds}s)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                uiState.watchdogAlert.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Tab Navigation
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            when (selectedTab) {
                0 -> OverviewTab(uiState, onRerun = { viewModel.runFullDiagnostics() }, onCopy = { copyReport() })
                1 -> NetworkTab(uiState)
                2 -> AuditTab(uiState)
                3 -> LogsTab(
                    logs = uiState.logs,
                    selectedFilter = uiState.selectedTagFilter,
                    onSelectFilter = { viewModel.setTagFilter(it) },
                    onClearLogs = { viewModel.clearLogs() }
                )
            }
        }
    }
}

@Composable
private fun OverviewTab(
    state: DiagnosticsUiState,
    onRerun: () -> Unit,
    onCopy: () -> Unit
) {
    val errorCount = state.auditIssues.count { it.severity == IssueSeverity.ERROR }
    val warnCount = state.auditIssues.count { it.severity == IssueSeverity.WARNING }
    val netOk = state.networkReport?.isConnected == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // Overall Status Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        errorCount > 0 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                        warnCount > 0 -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    }
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            errorCount > 0 -> Icons.Rounded.ErrorOutline
                            warnCount > 0 -> Icons.Rounded.WarningAmber
                            else -> Icons.Rounded.CheckCircle
                        },
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = when {
                            errorCount > 0 -> MaterialTheme.colorScheme.error
                            warnCount > 0 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = when {
                                state.isRunning -> "Running Full Diagnostics..."
                                errorCount > 0 -> "$errorCount Critical Configuration Issue(s)"
                                warnCount > 0 -> "$warnCount Warning(s) Detected"
                                else -> "All Systems & Relays Nominal"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (state.isRunning) "Probing DNS, sockets, and configs..."
                            else "Local audit completed. All diagnostics remain private on your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onRerun,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Re-test Now")
                }
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Report")
                }
            }
        }

        item {
            // Quick Status Matrix
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Diagnostic Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    DiagnosticRow(
                        icon = Icons.Rounded.Wifi,
                        title = "Network Connection",
                        value = state.networkReport?.connectionType ?: "Checking...",
                        isSuccess = state.networkReport?.isConnected == true
                    )
                    DiagnosticRow(
                        icon = Icons.Rounded.VpnKey,
                        title = "VPN Active",
                        value = if (state.networkReport?.isVpnActive == true) "Active (May isolate DNS)" else "Inactive",
                        isSuccess = state.networkReport?.isVpnActive != true
                    )
                    DiagnosticRow(
                        icon = Icons.Rounded.Cloud,
                        title = "Relay TCP Port 9009",
                        value = state.networkReport?.relayProbes?.firstOrNull()?.let {
                            if (it.isReachable) "Reachable (${it.latencyMs}ms)" else "Unreachable"
                        } ?: "Probing...",
                        isSuccess = state.networkReport?.relayProbes?.any { it.isReachable } == true
                    )
                    DiagnosticRow(
                        icon = Icons.Rounded.Dns,
                        title = "Public DNS (UDP 53)",
                        value = state.networkReport?.udpPort53Probes?.firstOrNull()?.let {
                            if (it.isReachable) "Open" else "Blocked by network"
                        } ?: "Probing...",
                        isSuccess = state.networkReport?.udpPort53Probes?.any { it.isReachable } == true
                    )
                    DiagnosticRow(
                        icon = Icons.Rounded.History,
                        title = "Transfer Success Rate",
                        value = state.historyReport?.let { "${it.successRatePercent}% (${it.completedCount} ok / ${it.failedCount} fail)" } ?: "No history",
                        isSuccess = (state.historyReport?.successRatePercent ?: 100) >= 75
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    icon: ImageVector,
    title: String,
    value: String,
    isSuccess: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun NetworkTab(state: DiagnosticsUiState) {
    val report = state.networkReport
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (report == null) {
            item { Text("Running network checks...") }
            return@LazyColumn
        }

        item {
            Text("DNS Host Resolution Probes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(report.dnsProbes) { probe ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (probe.isResolved) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = if (probe.isResolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(probe.host, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        val details = if (probe.isResolved) probe.resolvedIps.joinToString(", ") else probe.errorMessage ?: "Failed"
                        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${probe.durationMs}ms", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Relay Port 9009 Reachability", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(report.relayProbes) { relay ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (relay.isReachable) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = if (relay.isReachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(relay.address, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (relay.isReachable) "TCP handshake successful" else relay.errorMessage ?: "Cannot connect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (relay.isReachable) {
                        Text("${relay.latencyMs}ms", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Outbound UDP 53 Probes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(report.udpPort53Probes) { udp ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (udp.isReachable) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (udp.isReachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(udp.target, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (udp.isReachable) "Public UDP port 53 is open" else "Port 53 blocked by carrier or network",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("${udp.durationMs}ms", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AuditTab(state: DiagnosticsUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.auditIssues.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No Broken or Sus Configurations Found!", fontWeight = FontWeight.Bold)
                        Text("Your relay, network, proxy, and protocol settings match standard specifications.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            items(state.auditIssues) { issue ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (issue.severity) {
                            IssueSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            IssueSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                            IssueSeverity.INFO -> MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (issue.severity) {
                                    IssueSeverity.ERROR -> Icons.Rounded.ErrorOutline
                                    IssueSeverity.WARNING -> Icons.Rounded.WarningAmber
                                    IssueSeverity.INFO -> Icons.Rounded.Info
                                },
                                contentDescription = null,
                                tint = when (issue.severity) {
                                    IssueSeverity.ERROR -> MaterialTheme.colorScheme.error
                                    IssueSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                    IssueSeverity.INFO -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(issue.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(issue.description, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("Recommendation: ${issue.recommendation}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsTab(
    logs: List<DebugLogEntry>,
    selectedFilter: DebugLogTag?,
    onSelectFilter: (DebugLogTag?) -> Unit,
    onClearLogs: () -> Unit
) {
    val filtered = if (selectedFilter == null) logs else logs.filter { it.tag == selectedFilter }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tag filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { onSelectFilter(null) },
                label = { Text("ALL (${logs.size})") }
            )
            DebugLogTag.entries.forEach { tag ->
                val count = logs.count { it.tag == tag }
                if (count > 0) {
                    FilterChip(
                        selected = selectedFilter == tag,
                        onClick = { onSelectFilter(if (selectedFilter == tag) null else tag) },
                        label = { Text("${tag.name} ($count)") }
                    )
                }
            }
            IconButton(onClick = onClearLogs) {
                Icon(Icons.Rounded.Delete, contentDescription = "Clear Logs", modifier = Modifier.size(18.dp))
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filtered.reversed()) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "[${entry.formattedTime}]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "[${entry.tag.name}]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (entry.level) {
                            DebugLogLevel.ERROR -> MaterialTheme.colorScheme.error
                            DebugLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            DebugLogLevel.INFO -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entry.message,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
