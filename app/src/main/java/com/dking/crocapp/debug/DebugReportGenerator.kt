package com.dking.crocapp.debug

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.dking.crocapp.BuildConfig
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugReportGenerator {

    fun generateMarkdownReport(
        context: Context,
        prefs: UserPreferencesRepository.CrocPreferences,
        networkReport: NetworkDiagnosticReport?,
        auditIssues: List<ConfigAuditIssue>,
        historyReport: HistoryAuditReport?,
        logs: List<DebugLogEntry>,
        lastSession: TransferSession? = TransferSessionTracker.lastSession.value
    ): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        val totalBytes = stat.blockCountLong * stat.blockSizeLong

        val sb = StringBuilder()
        sb.appendLine("# 🐊 croc-app Diagnostic & Telemetry Report")
        sb.appendLine("**Generated:** $now")
        sb.appendLine("**App Version:** v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        sb.appendLine("**Build Type:** DEBUG (`debug/6.1.0`)")
        sb.appendLine()

        // 1. Device & OS Telemetry
        sb.appendLine("## 📱 Device & Hardware Telemetry")
        sb.appendLine("- **Manufacturer / Model:** ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("- **Android OS:** Android ${Build.VERSION.RELEASE} (API Level ${Build.VERSION.SDK_INT})")
        sb.appendLine("- **Supported ABIs:** ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("- **Storage:** ${freeBytes / (1024 * 1024 * 1024)} GB free / ${totalBytes / (1024 * 1024 * 1024)} GB total")
        sb.appendLine()

        // 2. Network Diagnostics
        sb.appendLine("## 🌐 Network Environment & DNS")
        if (networkReport != null) {
            sb.appendLine("- **Active Configured Relay:** `${networkReport.configuredRelayAddress}` (${if (networkReport.isConfiguredRelayReachable) "✅ REACHABLE (${networkReport.configuredRelayProbe?.latencyMs}ms)" else "❌ UNREACHABLE (${networkReport.configuredRelayProbe?.errorMessage ?: "Connection failed"})"})")
            sb.appendLine("- **Network Connection:** ${networkReport.connectionType}")
            sb.appendLine("- **Internet Connected:** ${if (networkReport.isConnected) "✅ YES" else "❌ NO"}")
            sb.appendLine("- **VPN Tunnel Active:** ${if (networkReport.isVpnActive) "⚠️ YES (Active VPN may intercept or split DNS)" else "NO"}")
            sb.appendLine("- **System DNS Servers:** ${if (networkReport.systemDnsServers.isNotEmpty()) networkReport.systemDnsServers.joinToString(", ") else "None detected"}")
            sb.appendLine("- **Local Network IPs:** ${if (networkReport.localIps.isNotEmpty()) networkReport.localIps.joinToString(", ") else "None"}")

            sb.appendLine()
            sb.appendLine("### DNS Host Resolution Probes")
            sb.appendLine("| Host | Status | Resolved IPs | Duration |")
            sb.appendLine("| :--- | :--- | :--- | :--- |")
            networkReport.dnsProbes.forEach { probe ->
                val status = if (probe.isResolved) "✅ OK" else "❌ FAILED"
                val ips = if (probe.isResolved) probe.resolvedIps.joinToString("<br>") else probe.errorMessage ?: "Failed"
                sb.appendLine("| `${probe.host}` | $status | $ips | ${probe.durationMs}ms |")
            }

            sb.appendLine()
            sb.appendLine("### Outbound UDP Port 53 Check (Public Resolver Reachability)")
            sb.appendLine("| Resolver | Status | Latency | Note |")
            sb.appendLine("| :--- | :--- | :--- | :--- |")
            networkReport.udpPort53Probes.forEach { probe ->
                val status = if (probe.isReachable) "✅ Reachable" else "❌ BLOCKED"
                val note = if (probe.isReachable) "UDP 53 open" else "Carrier/Wi-Fi blocks port 53"
                sb.appendLine("| `${probe.target}` | $status | ${probe.durationMs}ms | $note |")
            }

            sb.appendLine()
            sb.appendLine("### TCP Relay Port 9009 Socket Probes")
            sb.appendLine("| Relay Address | Status | Latency | Details |")
            sb.appendLine("| :--- | :--- | :--- | :--- |")
            networkReport.relayProbes.forEach { probe ->
                val status = if (probe.isReachable) "✅ CONNECTED" else "❌ FAILED"
                val details = if (probe.isReachable) "${probe.latencyMs}ms" else probe.errorMessage ?: "Unreachable"
                sb.appendLine("| `${probe.address}` | $status | ${if (probe.isReachable) "${probe.latencyMs}ms" else "-"} | $details |")
            }
        } else {
            sb.appendLine("*Network diagnostics not executed yet.*")
        }
        sb.appendLine()

        // 3. Last Transfer Session & Diagnostic Breakdown
        sb.appendLine("## 🔬 Last Transfer Session & Diagnostic Breakdown")
        if (lastSession != null) {
            sb.appendLine("- **Operation:** ${lastSession.opName} (${lastSession.engine})")
            sb.appendLine("- **Configured Relay:** `${lastSession.relayConfigured}`")
            sb.appendLine("- **PAKE Curve:** `${lastSession.curve}`")
            sb.appendLine("- **Start Time:** ${lastSession.formattedStartTime}")
            sb.appendLine("- **Duration:** ${lastSession.durationSeconds}s")
            sb.appendLine("- **Network at Start:** ${lastSession.networkAtStart}")
            sb.appendLine("- **Network at End:** ${lastSession.networkAtEnd.ifBlank { "Ongoing / Unrecorded" }}")
            sb.appendLine("- **Network Dropped Mid-Transfer:** ${if (lastSession.networkDroppedDuringTransfer) "⚠️ YES (Network loss detected during active transfer!)" else "NO"}")
            sb.appendLine("- **Final Status:** ${lastSession.state::class.simpleName} (Exit code: ${lastSession.exitCode ?: "N/A"})")

            if (lastSession.failureAnalysis != null) {
                val f = lastSession.failureAnalysis
                sb.appendLine()
                sb.appendLine("### ⚠️ Root Cause Analysis")
                sb.appendLine("- **Category:** ${f.category}")
                sb.appendLine("- **Headline:** ${f.headline}")
                sb.appendLine("- **Detailed Explanation:** ${f.explanation}")
                sb.appendLine()
                sb.appendLine("**Probable Causes:**")
                f.probableCauses.forEach { cause ->
                    sb.appendLine("- $cause")
                }
                sb.appendLine()
                sb.appendLine("**Recommended Actionable Steps:**")
                f.actionItems.forEachIndexed { idx, fix ->
                    sb.appendLine("${idx + 1}. $fix")
                }
            } else if (lastSession.isSuccess) {
                sb.appendLine("- **Result:** ✅ Transfer completed successfully.")
            }

            if (lastSession.stdoutTail.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("### croc Output Tail (Last ${lastSession.stdoutTail.size} lines)")
                sb.appendLine("```text")
                lastSession.stdoutTail.forEach { line ->
                    sb.appendLine(DebugLogManager.sanitize(line))
                }
                sb.appendLine("```")
            }
        } else {
            sb.appendLine("*No transfer attempted in this session yet.*")
        }
        sb.appendLine()

        // 3. Configuration & Sus Settings Audit
        sb.appendLine("## ⚙️ Configuration & Sus Settings Audit")
        if (auditIssues.isEmpty()) {
            sb.appendLine("✅ **No broken or suspicious settings detected.**")
        } else {
            sb.appendLine("Detected **${auditIssues.size} configuration alert(s)**:")
            auditIssues.forEach { issue ->
                val icon = when (issue.severity) {
                    IssueSeverity.ERROR -> "🔴 **[ERROR]**"
                    IssueSeverity.WARNING -> "🟡 **[WARNING]**"
                    IssueSeverity.INFO -> "ℹ️ **[INFO]**"
                }
                sb.appendLine("### $icon ${issue.title}")
                sb.appendLine("- **Description:** ${issue.description}")
                sb.appendLine("- **Recommended Action:** ${issue.recommendation}")
                sb.appendLine()
            }
        }

        sb.appendLine("### Active Settings Snapshot")
        sb.appendLine("- `Relay Address`: `${prefs.relayAddress}`")
        sb.appendLine("- `Relay Password`: ${if (prefs.relayPassword == "pass123") "default (pass123)" else "custom [configured]"}")
        sb.appendLine("- `PAKE Curve`: `${prefs.pakeCurve}`")
        sb.appendLine("- `Force LAN Only`: `${prefs.forceLocal}`")
        sb.appendLine("- `Built-in DNS`: `${prefs.useInternalDns}`")
        sb.appendLine("- `SOCKS5 Proxy`: `${prefs.socks5Proxy.ifBlank { "disabled" }}`")
        sb.appendLine("- `HTTP Proxy`: `${prefs.httpProxy.ifBlank { "disabled" }}`")
        sb.appendLine("- `Transfer Transport`: `${prefs.transferTransport}`")
        sb.appendLine("- `Multiplexing Disabled`: `${prefs.disableMultiplexing}`")
        sb.appendLine("- `Compression Disabled`: `${prefs.disableCompression}`")
        sb.appendLine("- `Upload Throttle`: `${prefs.uploadThrottle.ifBlank { "unlimited" }}`")
        sb.appendLine("- `Conflict Action`: `${prefs.receiveConflictStrategy}`")
        sb.appendLine()

        // 4. Past Transfer History
        sb.appendLine("## 📊 Past Transfer History Audit")
        if (historyReport != null) {
            sb.appendLine("- **Total Recorded Transfers:** ${historyReport.totalTransfers}")
            sb.appendLine("- **Completed Successfully:** ${historyReport.completedCount}")
            sb.appendLine("- **Failed Transfers:** ${historyReport.failedCount}")
            sb.appendLine("- **Cancelled Transfers:** ${historyReport.cancelledCount}")
            sb.appendLine("- **Overall Success Rate:** ${historyReport.successRatePercent}%")

            if (historyReport.diagnosedPatterns.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("**Observed Diagnostics:**")
                historyReport.diagnosedPatterns.forEach { pattern ->
                    sb.appendLine("- ⚠️ $pattern")
                }
            }

            if (historyReport.recentFailures.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("### Recent Failed Transfers")
                sb.appendLine("| Timestamp | Type | File Name | Size |")
                sb.appendLine("| :--- | :--- | :--- | :--- |")
                historyReport.recentFailures.forEach { fail ->
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(fail.timestamp))
                    sb.appendLine("| $date | ${fail.type} | `${DebugLogManager.sanitize(fail.fileName)}` | ${fail.fileSize} B |")
                }
            }
        } else {
            sb.appendLine("*No transfer history data available.*")
        }
        sb.appendLine()

        // 5. Sanitized Process & Debug Log Tail
        sb.appendLine("## 📋 Live Diagnostic Log Stream (Last ${logs.takeLast(100).size} Entries)")
        sb.appendLine("```text")
        if (logs.isEmpty()) {
            sb.appendLine("(No log events captured yet)")
        } else {
            logs.takeLast(100).forEach { entry ->
                sb.appendLine(entry.toDisplayString())
            }
        }
        sb.appendLine("```")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("*Note: This report is generated strictly on-device. No telemetry or user data is transmitted to external servers.*")

        return sb.toString()
    }
}
