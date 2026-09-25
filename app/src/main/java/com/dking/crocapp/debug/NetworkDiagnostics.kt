package com.dking.crocapp.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class DnsProbeResult(
    val host: String,
    val isResolved: Boolean,
    val resolvedIps: List<String> = emptyList(),
    val durationMs: Long = 0,
    val errorMessage: String? = null
)

data class TcpRelayProbeResult(
    val address: String,
    val isReachable: Boolean,
    val latencyMs: Long = 0,
    val errorMessage: String? = null
)

data class UdpPort53Result(
    val target: String,
    val isReachable: Boolean,
    val durationMs: Long = 0,
    val errorMessage: String? = null
)

data class NetworkDiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val isConnected: Boolean,
    val connectionType: String,
    val isVpnActive: Boolean,
    val systemDnsServers: List<String>,
    val localIps: List<String>,
    val dnsProbes: List<DnsProbeResult>,
    val udpPort53Probes: List<UdpPort53Result>,
    val relayProbes: List<TcpRelayProbeResult>
)

class NetworkDiagnostics(private val context: Context) {

    suspend fun runDiagnostics(customRelay: String? = null): NetworkDiagnosticReport = withContext(Dispatchers.IO) {
        DebugLogManager.i(DebugLogTag.NETWORK, "Starting on-device network diagnostics...")

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val linkProps = activeNetwork?.let { cm.getLinkProperties(it) }

        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val connType = when {
            caps == null -> "Disconnected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }

        val systemDns = linkProps?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        val localIps = getLocalIpAddresses()

        DebugLogManager.i(
            DebugLogTag.NETWORK,
            "Network: $connType (VPN: $isVpn), System DNS: [${systemDns.joinToString(", ")}], Local IPs: [${localIps.joinToString(", ")}]"
        )

        // DNS Host Probes
        val hostsToProbe = mutableListOf("croc.schollz.com", "1.getcroc.com")
        if (!customRelay.isNullOrBlank()) {
            val cleanCustom = customRelay.trim().removePrefix("relay://").substringBefore(":")
            if (cleanCustom.isNotBlank() && cleanCustom !in hostsToProbe) {
                hostsToProbe.add(cleanCustom)
            }
        }

        val dnsResults = hostsToProbe.map { host -> probeDns(host) }

        // UDP 53 Probes (Cloudflare & Google public resolvers)
        val udp53Results = listOf(
            probeUdp53("1.1.1.1"),
            probeUdp53("8.8.8.8")
        )

        // TCP Relay Probes (Port 9009)
        val relaysToProbe = mutableListOf("croc.schollz.com:9009", "1.getcroc.com:9009")
        if (!customRelay.isNullOrBlank()) {
            val trimmed = customRelay.trim().removePrefix("relay://")
            val withPort = if (trimmed.contains(":")) trimmed else "$trimmed:9009"
            if (withPort !in relaysToProbe) {
                relaysToProbe.add(withPort)
            }
        }

        val relayResults = relaysToProbe.map { relay -> probeTcpRelay(relay) }

        val report = NetworkDiagnosticReport(
            isConnected = isConnected,
            connectionType = connType,
            isVpnActive = isVpn,
            systemDnsServers = systemDns,
            localIps = localIps,
            dnsProbes = dnsResults,
            udpPort53Probes = udp53Results,
            relayProbes = relayResults
        )

        DebugLogManager.i(
            DebugLogTag.NETWORK,
            "Diagnostics complete: ${dnsResults.count { it.isResolved }}/${dnsResults.size} DNS hosts resolved, ${relayResults.count { it.isReachable }}/${relayResults.size} relays reachable via TCP 9009."
        )

        report
    }

    private fun probeDns(host: String): DnsProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val addresses = InetAddress.getAllByName(host)
            val duration = System.currentTimeMillis() - start
            val ips = addresses.mapNotNull { it.hostAddress }
            DebugLogManager.d(DebugLogTag.DNS, "DNS lookup '$host' -> [${ips.joinToString(", ")}] in ${duration}ms")
            DnsProbeResult(
                host = host,
                isResolved = true,
                resolvedIps = ips,
                durationMs = duration
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            DebugLogManager.w(DebugLogTag.DNS, "DNS lookup '$host' FAILED in ${duration}ms: ${e.message}")
            DnsProbeResult(
                host = host,
                isResolved = false,
                durationMs = duration,
                errorMessage = e.message ?: "Unknown resolution failure"
            )
        }
    }

    private fun probeTcpRelay(relayAddress: String): TcpRelayProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = relayAddress.trim().split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 9009 else 9009

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 3500)
                val duration = System.currentTimeMillis() - start
                DebugLogManager.i(DebugLogTag.RELAY, "TCP 9009 connection to '$relayAddress' SUCCEEDED in ${duration}ms")
                TcpRelayProbeResult(
                    address = relayAddress,
                    isReachable = true,
                    latencyMs = duration
                )
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            DebugLogManager.e(DebugLogTag.RELAY, "TCP 9009 connection to '$relayAddress' FAILED in ${duration}ms: ${e.message}")
            TcpRelayProbeResult(
                address = relayAddress,
                isReachable = false,
                latencyMs = duration,
                errorMessage = e.message ?: "Connection failed"
            )
        }
    }

    private fun probeUdp53(targetIp: String): UdpPort53Result {
        val start = System.currentTimeMillis()
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                // Minimal DNS query header for root "."
                val dummyDnsQuery = byteArrayOf(
                    0xAA.toByte(), 0xAA.toByte(), // ID
                    0x01, 0x00,                   // Standard query
                    0x00, 0x01,                   // 1 question
                    0x00, 0x00, 0x00, 0x00,       // 0 answers, authorities, additional
                    0x00,                         // root name
                    0x00, 0x01,                   // Type A
                    0x00, 0x01                    // Class IN
                )
                val packet = DatagramPacket(dummyDnsQuery, dummyDnsQuery.size, InetAddress.getByName(targetIp), 53)
                socket.send(packet)

                val buffer = ByteArray(512)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)
                val duration = System.currentTimeMillis() - start
                DebugLogManager.d(DebugLogTag.DNS, "UDP 53 query to $targetIp:53 succeeded in ${duration}ms")
                UdpPort53Result(
                    target = "$targetIp:53",
                    isReachable = true,
                    durationMs = duration
                )
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            DebugLogManager.w(DebugLogTag.DNS, "UDP 53 query to $targetIp:53 failed (${e.javaClass.simpleName}: ${e.message})")
            UdpPort53Result(
                target = "$targetIp:53",
                isReachable = false,
                durationMs = duration,
                errorMessage = e.message ?: "UDP timeout or blocked"
            )
        }
    }

    private fun getLocalIpAddresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("127.") || host == "::1") continue
                    // Clean IPv6 zone index if present
                    val cleanHost = host.substringBefore("%")
                    list.add(cleanHost)
                }
            }
        } catch (e: Exception) {
            DebugLogManager.w(DebugLogTag.NETWORK, "Could not enumerate local IPs", e.message)
        }
        return list
    }
}
