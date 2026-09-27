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

        // 0. Network Offline Audit
        if (networkReport != null && !networkReport.isConnected && !prefs.forceLocal) {
            issues.add(
                ConfigAuditIssue(
                    title = "Device is Offline / No Internet",
                    description = "Device has no active internet connection (${networkReport.connectionType}). All relay-based file transfers will fail immediately.",
                    severity = IssueSeverity.ERROR,
                    recommendation = "Connect to Wi-Fi or turn on Mobile Data before starting a transfer."
                )
            )
        }

        // 1. Configured Relay Reachability & DNS Audit
        if (networkReport != null && networkReport.isConnected) {
            val dnsProbe = networkReport.configuredDnsProbe
            val relayProbe = networkReport.configuredRelayProbe

            if (dnsProbe != null && !dnsProbe.isResolved) {
                issues.add(
                    ConfigAuditIssue(
                        title = "Configured Relay DNS Resolution Failed",
                        description = "Cannot resolve hostname '${dnsProbe.host}' for relay '${prefs.relayAddress}': ${dnsProbe.errorMessage ?: "UnknownHostException"}. Any transfers using this relay will fail with 'relay not found'.",
                        severity = IssueSeverity.ERROR,
                        recommendation = "Check for typos in Settings -> Relay Server. If using a custom relay, ensure the domain name is registered and publicly resolvable."
                    )
                )
            } else if (relayProbe != null && !relayProbe.isReachable) {
                issues.add(
                    ConfigAuditIssue(
                        title = "Configured Relay Server Unreachable",
                        description = "Could not connect to relay '${prefs.relayAddress}' on TCP port 9009: ${relayProbe.errorMessage ?: "Timed out"}. Transfers will hang in an infinite wait loop.",
                        severity = IssueSeverity.ERROR,
                        recommendation = "Verify the relay server is online and port 9009 is forwarded/open. In Settings -> Relay Server, you can revert to 'croc.schollz.com:9009'."
                    )
                )
            }
        }

        // 2. Relay Address Syntax Audit
        val relay = prefs.relayAddress.trim()
        if (relay.startsWith("http://", ignoreCase = true) || relay.startsWith("https://", ignoreCase = true)) {
            issues.add(
                ConfigAuditIssue(
                    title = "Invalid Relay Protocol (HTTP/HTTPS)",
                    description = "Relay address '$relay' includes a web scheme (http:// or https://). Croc communicates using raw TCP sockets, not HTTP.",
                    severity = IssueSeverity.ERROR,
                    recommendation = "Remove 'http://' or 'https://' in Settings -> Relay Server (e.g. use '${relay.removePrefix("http://").removePrefix("https://")}')."
                )
            )
        }

        if (relay.isNotBlank() && !relay.contains(":")) {
            issues.add(
                ConfigAuditIssue(
                    title = "Missing Relay Port",
                    description = "Relay address '$relay' has no port specified. Android socket pre-resolution requires an explicit port.",
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
                    recommendation = "Remove any spaces from the relay address in Settings."
                )
            )
        }

        if (relay == "croc.schollz.com:9009" || relay.contains("croc.schollz.com")) {
            issues.add(
                ConfigAuditIssue(
                    title = "Single Relay vs Desktop Pool (Infinite Loop Risk)",
                    description = "croc-app is using the single server 'croc.schollz.com:9009'. Standard desktop croc CLI (v11+) connects to the 1-4.getcroc.com public pool by default. If transferring with a PC, both devices will wait in an infinite loop unless the PC also uses --relay.",
                    severity = IssueSeverity.INFO,
                    recommendation = "When transferring with a computer, specify '--relay croc.schollz.com:9009' in your terminal: croc --relay croc.schollz.com:9009 <code>"
                )
            )
        }

        // 3. Force Local (LAN Only) Audit
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

        // 4. Proxy Audit
        if (prefs.socks5Proxy.isNotBlank()) {
            val proxyStr = prefs.socks5Proxy.trim()
            val reachable = testProxyReachability(proxyStr)
            if (!reachable) {
                issues.add(
                    ConfigAuditIssue(
                        title = "Configured SOCKS5 Proxy Unreachable",
                        description = "SOCKS5 proxy '$proxyStr' did not respond to TCP connection attempts. All croc transfers routed through this proxy will fail.",
                        severity = IssueSeverity.ERROR,
                        recommendation = "Check the SOCKS5 proxy IP/port in Settings -> Advanced -> Proxy, or clear the proxy field if not using a proxy."
                    )
                )
            }
        }

        if (prefs.httpProxy.isNotBlank()) {
            val proxyStr = prefs.httpProxy.trim()
            val reachable = testProxyReachability(proxyStr)
            if (!reachable) {
                issues.add(
                    ConfigAuditIssue(
                        title = "Configured HTTP Proxy Unreachable",
                        description = "HTTP proxy '$proxyStr' did not respond to TCP connection attempts.",
                        severity = IssueSeverity.ERROR,
                        recommendation = "Verify your HTTP proxy in Settings -> Advanced -> Proxy, or clear the field."
                    )
                )
            }
        }

        // 5. Built-in DNS vs UDP 53 Blocking Audit
        if (prefs.useInternalDns && networkReport != null) {
            val udpBlocked = networkReport.udpPort53Probes.isNotEmpty() && networkReport.udpPort53Probes.all { !it.isReachable }
            if (udpBlocked) {
                issues.add(
                    ConfigAuditIssue(
                        title = "Built-in DNS Active but UDP Port 53 Blocked",
                        description = "Internal DNS (--internal-dns) sends UDP queries directly to public DNS (1.1.1.1/8.8.8.8), but your current network/carrier is blocking outbound UDP port 53.",
                        severity = IssueSeverity.ERROR,
                        recommendation = "Turn OFF 'Use Built-in DNS' in Settings -> Network so croc uses Android system DNS."
                    )
                )
            }
        }

        // 6. Non-default PAKE Curve Audit
        if (prefs.pakeCurve.isNotBlank() && prefs.pakeCurve != "p256") {
            issues.add(
                ConfigAuditIssue(
                    title = "Non-Default Encryption Curve (${prefs.pakeCurve})",
                    description = "Using curve '${prefs.pakeCurve}'. Both sender and receiver MUST configure the exact same curve, otherwise the PAKE handshake will fail silently.",
                    severity = IssueSeverity.WARNING,
                    recommendation = "Ensure the peer device also uses '--curve ${prefs.pakeCurve}', or reset curve to 'p256' in Settings -> Advanced."
                )
            )
        }

        // 7. Relay Password Audit
        if (prefs.relayPassword.isNotBlank()) {
            issues.add(
                ConfigAuditIssue(
                    title = "Custom Relay Password Configured",
                    description = "A relay password is set. If the relay server is public and does not require authentication, transfers may be rejected with 'bad password'.",
                    severity = IssueSeverity.WARNING,
                    recommendation = "Clear the relay password in Settings -> Relay Server unless using a private relay with authentication."
                )
            )
        }

        issues
    }

    private fun testProxyReachability(proxyAddress: String): Boolean {
        return try {
            val clean = proxyAddress.trim()
                .removePrefix("socks5://")
                .removePrefix("http://")
                .removePrefix("https://")
            val host = clean.substringBefore(":")
            val port = clean.substringAfter(":", "1080").toIntOrNull() ?: 1080
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
