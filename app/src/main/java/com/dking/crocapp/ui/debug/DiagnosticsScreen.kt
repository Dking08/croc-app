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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dking.crocapp.debug.ConfigAuditIssue
import com.dking.crocapp.debug.DebugLogEntry
import com.dking.crocapp.debug.DebugLogLevel
import com.dking.crocapp.debug.DebugLogTag
import com.dking.crocapp.debug.DnsProbeResult
import com.dking.crocapp.debug.IssueSeverity
import com.dking.crocapp.debug.TcpRelayProbeResult
import com.dking.crocapp.debug.TransferSession
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.runFullDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
            // Watchdog Alert Banner (If transfer wait loop detected or network disconnected)
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
                            val alertTitle = if (uiState.watchdogAlert.isNetworkDisconnected) {
                                "Transfer Disconnected (Network Lost)"
                            } else {
                                "Stalled Transfer Detected (${uiState.watchdogAlert.durationSeconds}s)"
                            }
                            Text(
                                alertTitle,
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
    val configuredRelay = state.currentPrefs?.relayAddress ?: "croc.schollz.com:9009"
    val isRelayReachable = state.networkReport?.isConfiguredRelayReachable == true
    val isNetConnected = state.networkReport?.isConnected == true

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

        state.lastSession?.let { lastSession ->
            item {
                LastTransferDiagnosticCard(session = lastSession)
            }
        }

        item {
            // Active Configured Relay Card (Highlights current setting!)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRelayReachable) {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                    }
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (isRelayReachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Active Configured Relay",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isRelayReachable) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.error
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isRelayReachable) "REACHABLE" else "UNREACHABLE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isRelayReachable) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onError
                            )
                        }
                    }

                    Text(
                        text = configuredRelay,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )

                    val probeDetail = state.networkReport?.configuredRelayProbe?.let { probe ->
                        if (probe.isReachable) "TCP 9009 handshake verified in ${probe.latencyMs}ms."
                        else "Connection failed: ${probe.errorMessage ?: "Socket timed out"}"
                    } ?: "Testing connection..."

                    Text(
                        text = probeDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRelayReachable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
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
                    Text("Environment Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    DiagnosticRow(
                        icon = Icons.Rounded.Wifi,
                        title = "Network Connection",
                        value = if (isNetConnected) "Online (${state.networkReport?.connectionType})" else "OFFLINE (No connection)",
                        isSuccess = isNetConnected
                    )
                    DiagnosticRow(
                        icon = Icons.Rounded.VpnKey,
                        title = "VPN Active",
                        value = if (state.networkReport?.isVpnActive == true) "Active (May isolate DNS)" else "Inactive",
                        isSuccess = state.networkReport?.isVpnActive != true
                    )
                    DiagnosticRow(
                        icon = Icons.Rounded.Cloud,
                        title = "Active Relay Reachability",
                        value = state.networkReport?.configuredRelayProbe?.let {
                            if (it.isReachable) "Reachable (${it.latencyMs}ms)" else "Unreachable (${it.errorMessage ?: "Failed"})"
                        } ?: "Probing...",
                        isSuccess = isRelayReachable
                    )
                    DiagnosticRow(
                        icon = Icons.Rounded.Dns,
                        title = "Public DNS (UDP 53)",
                        value = state.networkReport?.udpPort53Probes?.firstOrNull()?.let {
                            if (it.isReachable) "Open (1.1.1.1)" else "Blocked by network"
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
        Row(modifier = Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.width(8.dp))
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

        // Active Relay Probes Header
        item {
            Text("Active Configured Relay (Settings)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        report.configuredRelayProbe?.let { relayProbe ->
            item {
                RelayProbeCard(probe = relayProbe, isConfigured = true)
            }
        }

        report.configuredDnsProbe?.let { dnsProbe ->
            item {
                DnsProbeCard(probe = dnsProbe, isConfigured = true)
            }
        }

        // Reference Probes
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Reference Public Relays (croc pool)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        items(report.relayProbes.filter { !it.isConfiguredRelay }) { probe ->
            RelayProbeCard(probe = probe, isConfigured = false)
        }

        // DNS Host Probes
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Reference DNS Lookups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(report.dnsProbes.filter { !it.isConfiguredRelayHost }) { probe ->
            DnsProbeCard(probe = probe, isConfigured = false)
        }

        // UDP 53 Probes
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Public Resolver Reachability (UDP Port 53)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(report.udpPort53Probes) { probe ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (probe.isReachable) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = if (probe.isReachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(probe.target, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        val note = if (probe.isReachable) "Outbound UDP port 53 is open" else probe.errorMessage ?: "Carrier / firewall blocking UDP 53"
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (probe.isReachable) {
                        Text("${probe.durationMs}ms", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayProbeCard(probe: TcpRelayProbeResult, isConfigured: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (probe.isReachable) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        )
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (probe.isReachable) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (probe.isReachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(probe.address, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    if (isConfigured) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                val details = if (probe.isReachable) "TCP port 9009 connection established"
                else probe.errorMessage ?: "Failed to connect to TCP port 9009"
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (probe.isReachable) {
                Text("${probe.latencyMs}ms", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DnsProbeCard(probe: DnsProbeResult, isConfigured: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (probe.isResolved) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        )
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (probe.isResolved) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (probe.isResolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(probe.host, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    if (isConfigured) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                val details = if (probe.isResolved) probe.resolvedIps.joinToString(", ") else probe.errorMessage ?: "Failed to resolve host"
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (probe.isResolved) {
                Text("${probe.durationMs}ms", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AuditTab(state: DiagnosticsUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        state.lastSession?.takeIf { it.failureAnalysis != null }?.let { failedSession ->
            item {
                Text("Last Transfer Failure Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                LastTransferDiagnosticCard(session = failedSession)
            }
        }

        item {
            Text("Configuration & Sus Settings Audit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (state.auditIssues.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("All preferences are healthy. No suspicious or incompatible settings found.")
                    }
                }
            }
        } else {
            items(state.auditIssues) { issue ->
                val cardColor = when (issue.severity) {
                    IssueSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    IssueSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                    IssueSeverity.INFO -> MaterialTheme.colorScheme.surfaceContainerLow
                }
                val icon = when (issue.severity) {
                    IssueSeverity.ERROR -> Icons.Rounded.ErrorOutline
                    IssueSeverity.WARNING -> Icons.Rounded.WarningAmber
                    IssueSeverity.INFO -> Icons.Rounded.Info
                }
                val iconTint = when (issue.severity) {
                    IssueSeverity.ERROR -> MaterialTheme.colorScheme.error
                    IssueSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                    IssueSeverity.INFO -> MaterialTheme.colorScheme.primary
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(issue.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(issue.description, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = "💡 Fix: ${issue.recommendation}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
    val filteredLogs = if (selectedFilter == null) logs else logs.filter { it.tag == selectedFilter }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tag Filters & Clear Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { onSelectFilter(null) },
                label = { Text("All (${logs.size})") }
            )
            DebugLogTag.entries.forEach { tag ->
                val count = logs.count { it.tag == tag }
                FilterChip(
                    selected = selectedFilter == tag,
                    onClick = { onSelectFilter(if (selectedFilter == tag) null else tag) },
                    label = { Text("${tag.name} ($count)") }
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onClearLogs,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No log entries found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredLogs) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: DebugLogEntry) {
    val levelColor = when (entry.level) {
        DebugLogLevel.ERROR -> MaterialTheme.colorScheme.error
        DebugLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        DebugLogLevel.INFO -> MaterialTheme.colorScheme.primary
        DebugLogLevel.DEBUG -> MaterialTheme.colorScheme.secondary
        DebugLogLevel.VERBOSE -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.formattedTime,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(levelColor.copy(alpha = 0.2f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = entry.level.name.take(1),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = levelColor
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "[${entry.tag.name}]",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LastTransferDiagnosticCard(session: TransferSession) {
    val isSuccess = session.isSuccess
    val failure = session.failureAnalysis

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                failure != null -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                isSuccess -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            failure != null -> Icons.Rounded.ErrorOutline
                            isSuccess -> Icons.Rounded.CheckCircle
                            else -> Icons.Rounded.Info
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = when {
                            failure != null -> MaterialTheme.colorScheme.error
                            isSuccess -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Last Transfer: ${session.opName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                failure != null -> MaterialTheme.colorScheme.error
                                isSuccess -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = when {
                            failure != null -> failure.category.name.replace('_', ' ')
                            isSuccess -> "COMPLETED"
                            else -> session.state::class.simpleName ?: "IN PROGRESS"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            failure != null -> MaterialTheme.colorScheme.onError
                            isSuccess -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Session metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Time: ${session.formattedStartTime} (${session.durationSeconds}s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Relay: ${session.relayConfigured.ifBlank { "default" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Network dropped alert
            if (session.networkDroppedDuringTransfer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Network connection dropped while transfer was active!",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Failure breakdown
            if (failure != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                Text(
                    text = failure.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )

                Text(
                    text = failure.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (failure.probableCauses.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Probable Causes:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        for (cause in failure.probableCauses) {
                            Text(
                                text = "• $cause",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (failure.actionItems.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "What You Can Do:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        for ((idx, fix) in failure.actionItems.withIndex()) {
                            Text(
                                text = "${idx + 1}. $fix",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Process output tail
                if (session.stdoutTail.isNotEmpty()) {
                    var showStdout by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showStdout = !showStdout },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(if (showStdout) "Hide Process Output Tail" else "View Process Output Tail (${session.stdoutTail.size} lines)")
                    }

                    if (showStdout) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = session.stdoutTail.takeLast(25).joinToString("\n"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
