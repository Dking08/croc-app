package com.dking.crocapp.debug

import com.dking.crocapp.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

enum class IssueSeverity {
    ERROR, WARNING, INFO
}

data class ConfigAuditIssue(
    val title: String,
    val description: String,
    val severity: IssueSeverity,
    val recommendation: String
)

object ConfigAuditor {

    suspend fun audit(
        prefs: UserPreferencesRepository.CrocPreferences,
        networkReport: NetworkDiagnosticReport?
    ): List<ConfigAuditIssue> = withContext(Dispatchers.IO) {
        val issues = mutableListOf<ConfigAuditIssue>()

        // 1. Relay Address Audit
        val relay = prefs.relayAddress.trim()
        if (relay.startsWith("http://", ignoreCase = true) || relay.startsWith("https://", ignoreCase = true)) {
            issues.add(
                ConfigAuditIssue(
                    title = "Invalid Relay Protocol (HTTP/HTTPS)",
                    description = "Relay address '$relay' includes a web scheme (http:// or https://). Croc communicates using raw TCP sockets, not HTTP.",
                    severity = IssueSeverity.ERROR,
                    recommendation = "Remove 'http://' or 'https://' in Settings -> Relay Server (e.g. use 'myrelay.com:9009')."
                )
            )
        }

        if (relay.isNotBlank() && !relay.contains(":")) {
            issues.add(
                ConfigAuditIssue(
                    title = "Missing Relay Port",
                    description = "Relay address '$relay' has no port specified. Android socket pre-resolution may fail without an explicit port.",
                    severity = IssueSeverity.WARNING,
                    recommendation = "Append ':9009' to the relay address in Settings (e.g. '$relay:9009')."
                )
            )
        }

        if (relay.contains(" ")) {
            issues.add(
                ConfigAuditIssue(
                    title = "Spaces in Relay Address",
                    description = "Relay address '$relay' contains whitespace characters.",
                    severity = IssueSeverity.ERROR,
                    recommendation = "Remove any spaces from the relay address."
                )
            )
        }

        if (relay == "croc.schollz.com:9009") {
            issues.add(
                ConfigAuditIssue(
                    title = "Single Relay vs Desktop Pool (Infinite Loop Risk)",
                    description = "croc-app is using the single server 'croc.schollz.com:9009'. Standard desktop croc CLI (v11+) connects to the 1-4.getcroc.com public pool by default. If transferring with a PC, both devices may wait in an infinite loop unless the PC also uses --relay.",
                    severity = IssueSeverity.INFO,
                    recommendation = "When transferring with a computer, specify '--relay croc.schollz.com:9009' in your terminal: croc --relay croc.schollz.com:9009 <code>"
                )
            )
        }

        // 2. Force Local (LAN Only) Audit
        if (prefs.forceLocal) {
            val isCellular = networkReport?.connectionType?.contains("Cellular", ignoreCase = true) == true
            val isVpn = networkReport?.isVpnActive == true
            if (isCellular || isVpn) {
                issues.add(
                    ConfigAuditIssue(
                        title = "Force LAN Only Enabled on Incompatible Network",
                        description = "'Force LAN Only' is turned ON, but device is connected via ${networkReport?.connectionType ?: "Cellular"} (VPN: $isVpn). Multicast local discovery will fail.",
                        severity = IssueSeverity.ERROR,
                        recommendation = "Turn OFF 'Force LAN Only' in Settings -> Network, or connect both devices to the same local Wi-Fi network."
                    )
                )
            } else {
                issues.add(
                    ConfigAuditIssue(
                        title = "Force LAN Only Active",
                        description = "Public relay transfers are disabled. Transfers only work if both devices are on the exact same Wi-Fi subnet without client isolation.",
                        severity = IssueSeverity.WARNING,
                        recommendation = "If transfers fail to connect, disable 'Force LAN Only' in Settings -> Network."
                    )
                )
            }
        }

        // 3. Proxy Audit
        if (prefs.socks5Proxy.isNotBlank()) {
            val reachable = probeProxy(prefs.socks5Proxy)
            if (!reachable) {
                issues.add(
                    ConfigAuditIssue(
                        title = "SOCKS5 Proxy Offline / Unreachable",
                        description = "Configured SOCKS5 proxy '${prefs.socks5Proxy}' is not responding. All transfers routed through this proxy will fail immediately.",
                        severity = IssueSeverity.ERROR,
                        recommendation = "Ensure your proxy app (e.g. Orbot / Tor) is running and listening on '${prefs.socks5Proxy}', or clear the proxy setting in Settings."
                    )
                )
            }
        }

        if (prefs.httpProxy.isNotBlank()) {
            val reachable = probeProxy(prefs.httpProxy)
            if (!reachable) {
                issues.add(
                    ConfigAuditIssue(
                        title = "HTTP Proxy Offline / Unreachable",
                        description = "Configured HTTP proxy '${prefs.httpProxy}' is not responding.",
                        severity = IssueSeverity.ERROR,
                        recommendation = "Ensure your HTTP proxy is running, or clear the HTTP proxy setting in Settings."
                    )
                )
            }
        }

        // 4. Built-in DNS Audit
        if (prefs.useInternalDns) {
            val udp53Blocked = networkReport?.udpPort53Probes?.all { !it.isReachable } == true
            if (udp53Blocked) {
                issues.add(
                    ConfigAuditIssue(
                        title = "Built-in DNS Active but UDP Port 53 Blocked",
                        description = "Built-in DNS resolver is ON, but outbound UDP port 53 is blocked on this network. Croc will fail to resolve relay addresses.",
                        severity = IssueSeverity.ERROR,
                        recommendation = "Turn OFF 'Built-in DNS' in Settings -> Network to use Android's system DNS."
                    )
                )
            } else {
                issues.add(
                    ConfigAuditIssue(
                        title = "Built-in DNS Active",
                        description = "Using internal Go stub resolver instead of Android system DNS. Queries are sent directly to public resolvers (1.1.1.1, 8.8.8.8).",
                        severity = IssueSeverity.INFO,
                        recommendation = "If using a corporate or school network with private internal domains, disable Built-in DNS."
                    )
                )
            }
        }

        // 5. Relay Password
        if (prefs.relayPassword.isNotBlank() && prefs.relayPassword != "pass123") {
            issues.add(
                ConfigAuditIssue(
                    title = "Custom Relay Password Configured",
                    description = "A custom relay password is configured. Both devices and the relay server must match this password.",
                    severity = IssueSeverity.WARNING,
                    recommendation = "If the other device fails with 'Incorrect relay password', verify that both devices have the same password configured."
                )
            )
        }

        // 6. PAKE Curve
        if (prefs.pakeCurve.isNotBlank() && prefs.pakeCurve != "p256") {
            issues.add(
                ConfigAuditIssue(
                    title = "Non-Default Encryption Curve (${prefs.pakeCurve})",
                    description = "PAKE curve is set to '${prefs.pakeCurve}' instead of default 'p256'. The other device must specify the exact same curve (--curve ${prefs.pakeCurve}).",
                    severity = IssueSeverity.WARNING,
                    recommendation = "Reset PAKE Curve to 'p256' in Settings -> Transfer Options if peer handshake fails."
                )
            )
        }

        // 7. Upload Throttle Check
        if (prefs.uploadThrottle.isNotBlank()) {
            val valid = prefs.uploadThrottle.trim().matches(Regex("""^\d+(\.\d+)?(k|m|g)?$""", RegexOption.IGNORE_CASE))
            if (!valid) {
                issues.add(
                    ConfigAuditIssue(
                        title = "Invalid Upload Throttle Format",
                        description = "Upload throttle '${prefs.uploadThrottle}' is not recognized. Format must be e.g. '500k', '1m', or '10m'.",
                        severity = IssueSeverity.WARNING,
                        recommendation = "Correct format in Settings -> Transfer Options (e.g. '1m')."
                    )
                )
            }
        }

        issues
    }

    private fun probeProxy(proxyAddr: String): Boolean {
        return try {
            val cleaned = proxyAddr.trim().removePrefix("http://").removePrefix("https://").removePrefix("socks5://")
            val parts = cleaned.split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: return false else return false
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
