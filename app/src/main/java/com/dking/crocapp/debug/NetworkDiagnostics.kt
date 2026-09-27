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
import java.net.UnknownHostException

data class DnsProbeResult(
    val host: String,
    val isConfiguredRelayHost: Boolean = false,
    val isResolved: Boolean,
    val resolvedIps: List<String> = emptyList(),
    val durationMs: Long = 0,
    val errorMessage: String? = null
)

data class TcpRelayProbeResult(
    val address: String,
    val isConfiguredRelay: Boolean = false,
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
    val configuredRelayAddress: String = "croc.schollz.com:9009",
    val isConnected: Boolean,
    val connectionType: String,
    val isVpnActive: Boolean,
    val systemDnsServers: List<String>,
    val localIps: List<String>,
    val configuredRelayProbe: TcpRelayProbeResult?,
    val configuredDnsProbe: DnsProbeResult?,
    val isConfiguredRelayReachable: Boolean,
    val dnsProbes: List<DnsProbeResult>,
    val udpPort53Probes: List<UdpPort53Result>,
    val relayProbes: List<TcpRelayProbeResult>
)

class NetworkDiagnostics(private val context: Context) {

    suspend fun runDiagnostics(configuredRelay: String? = null): NetworkDiagnosticReport = withContext(Dispatchers.IO) {
        val effectiveRelay = if (!configuredRelay.isNullOrBlank()) configuredRelay.trim() else "croc.schollz.com:9009"
        DebugLogManager.i(DebugLogTag.NETWORK, "Starting network diagnostics for configured relay: '$effectiveRelay'...")

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val linkProps = activeNetwork?.let { cm.getLinkProperties(it) }

        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val connType = when {
            caps == null -> "Disconnected / Offline"
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
            "Network: $connType (Internet: $isConnected, VPN: $isVpn), System DNS: [${systemDns.joinToString(", ")}], Local IPs: [${localIps.joinToString(", ")}]"
        )

        // Clean configured relay
        val cleanRelay = effectiveRelay
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("relay://")
        val customHost = cleanRelay.substringBefore(":")
        val customPort = cleanRelay.substringAfter(":", "9009").toIntOrNull() ?: 9009

        // 1. Probe Configured Relay Host DNS FIRST
        val configuredDns = if (customHost.isNotBlank()) {
            probeDns(customHost, isConfigured = true)
        } else null

        // 2. Probe Configured Relay TCP 9009 FIRST
        val configuredRelayProbe = if (cleanRelay.isNotBlank()) {
            probeTcpRelay(
                relayAddress = "$customHost:$customPort",
                isConfigured = true,
                dnsResolved = configuredDns?.isResolved ?: true
            )
        } else null

        // 3. Reference DNS Probes (Public croc relays)
        val referenceHosts = listOf("croc.schollz.com", "1.getcroc.com")
            .filter { it != customHost }

        val allDnsProbes = mutableListOf<DnsProbeResult>()
        if (configuredDns != null) allDnsProbes.add(configuredDns)
        referenceHosts.forEach { host ->
            allDnsProbes.add(probeDns(host, isConfigured = false))
        }

        // 4. UDP 53 Probes (Cloudflare & Google public resolvers)
        val udp53Results = if (isConnected) {
            listOf(
                probeUdp53("1.1.1.1"),
                probeUdp53("8.8.8.8")
            )
        } else {
            listOf(
                UdpPort53Result("1.1.1.1:53", false, 0, "Network offline"),
                UdpPort53Result("8.8.8.8:53", false, 0, "Network offline")
            )
        }

        // 5. Reference TCP Relay Probes
        val referenceRelays = listOf("croc.schollz.com:9009", "1.getcroc.com:9009")
            .filter { it != "$customHost:$customPort" }

        val allRelayProbes = mutableListOf<TcpRelayProbeResult>()
        if (configuredRelayProbe != null) allRelayProbes.add(configuredRelayProbe)
        referenceRelays.forEach { relay ->
            allRelayProbes.add(probeTcpRelay(relay, isConfigured = false, dnsResolved = true))
        }

        val isRelayReachable = configuredRelayProbe?.isReachable == true

        val report = NetworkDiagnosticReport(
            configuredRelayAddress = effectiveRelay,
            isConnected = isConnected,
            connectionType = connType,
            isVpnActive = isVpn,
            systemDnsServers = systemDns,
            localIps = localIps,
            configuredRelayProbe = configuredRelayProbe,
            configuredDnsProbe = configuredDns,
            isConfiguredRelayReachable = isRelayReachable,
            dnsProbes = allDnsProbes,
            udpPort53Probes = udp53Results,
            relayProbes = allRelayProbes
        )

        DebugLogManager.i(
            DebugLogTag.NETWORK,
            "Diagnostics complete: Configured relay '$effectiveRelay' reachable: $isRelayReachable (${configuredRelayProbe?.latencyMs}ms)"
        )

        report
    }

    private fun probeDns(host: String, isConfigured: Boolean): DnsProbeResult {
        val start = System.currentTimeMillis()
        val tag = if (isConfigured) "Configured Relay DNS" else "Reference DNS"
        return try {
            val addresses = InetAddress.getAllByName(host)
            val duration = System.currentTimeMillis() - start
            val ips = addresses.mapNotNull { it.hostAddress }
            DebugLogManager.d(DebugLogTag.DNS, "[$tag] '$host' -> [${ips.joinToString(", ")}] in ${duration}ms")
            DnsProbeResult(
                host = host,
                isConfiguredRelayHost = isConfigured,
                isResolved = true,
                resolvedIps = ips,
                durationMs = duration
            )
        } catch (e: UnknownHostException) {
            val duration = System.currentTimeMillis() - start
            val msg = "Host not found (UnknownHostException): ${e.message}"
            DebugLogManager.w(DebugLogTag.DNS, "[$tag] '$host' FAILED in ${duration}ms: $msg")
            DnsProbeResult(
                host = host,
                isConfiguredRelayHost = isConfigured,
                isResolved = false,
                durationMs = duration,
                errorMessage = msg
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            val msg = "${e.javaClass.simpleName}: ${e.message ?: "Resolution failure"}"
            DebugLogManager.w(DebugLogTag.DNS, "[$tag] '$host' FAILED in ${duration}ms: $msg")
            DnsProbeResult(
                host = host,
                isConfiguredRelayHost = isConfigured,
                isResolved = false,
                durationMs = duration,
                errorMessage = msg
            )
        }
    }

    private fun probeTcpRelay(relayAddress: String, isConfigured: Boolean, dnsResolved: Boolean): TcpRelayProbeResult {
        if (!dnsResolved) {
            val msg = "DNS resolution failed for host; TCP probe skipped"
            DebugLogManager.w(DebugLogTag.RELAY, "TCP connection to '$relayAddress' skipped ($msg)")
            return TcpRelayProbeResult(
                address = relayAddress,
                isConfiguredRelay = isConfigured,
                isReachable = false,
                errorMessage = msg
            )
        }

        val start = System.currentTimeMillis()
        val clean = relayAddress.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("relay://")
        val host = clean.substringBefore(":")
        val port = clean.substringAfter(":", "9009").toIntOrNull() ?: 9009

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 3500)
                val duration = System.currentTimeMillis() - start
                val tag = if (isConfigured) "Configured Relay" else "Reference Relay"
                DebugLogManager.i(DebugLogTag.RELAY, "[$tag] TCP connection to '$clean' SUCCEEDED in ${duration}ms")
                TcpRelayProbeResult(
                    address = clean,
                    isConfiguredRelay = isConfigured,
                    isReachable = true,
                    latencyMs = duration
                )
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            val tag = if (isConfigured) "Configured Relay" else "Reference Relay"
            val errMsg = when {
                e is UnknownHostException -> "Host not found (DNS lookup failed)"
                e.message?.contains("ECONNREFUSED", ignoreCase = true) == true -> "Connection refused (Port $port closed)"
                e.message?.contains("ETIMEDOUT", ignoreCase = true) == true || e.message?.contains("timed out", ignoreCase = true) == true -> "Connection timed out (Firewall/Unreachable)"
                e.message?.contains("ENETUNREACH", ignoreCase = true) == true -> "Network unreachable (Offline)"
                else -> "${e.javaClass.simpleName}: ${e.message ?: "Failed"}"
            }
            DebugLogManager.e(DebugLogTag.RELAY, "[$tag] TCP connection to '$clean' FAILED in ${duration}ms: $errMsg")
            TcpRelayProbeResult(
                address = clean,
                isConfiguredRelay = isConfigured,
                isReachable = false,
                latencyMs = duration,
                errorMessage = errMsg
            )
        }
    }

    private fun probeUdp53(targetIp: String): UdpPort53Result {
        val start = System.currentTimeMillis()
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                val dummyDnsQuery = byteArrayOf(
                    0xAA.toByte(), 0xAA.toByte(),
                    0x01, 0x00,
                    0x00, 0x01,
                    0x00, 0x00, 0x00, 0x00,
                    0x00,
                    0x00, 0x01,
                    0x00, 0x01
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
            DebugLogManager.w(DebugLogTag.DNS, "UDP 53 query to $targetIp:53 FAILED in ${duration}ms: ${e.message}")
            UdpPort53Result(
                target = "$targetIp:53",
                isReachable = false,
                durationMs = duration,
                errorMessage = e.message ?: "UDP port 53 query timed out"
            )
        }
    }

    private fun getLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogManager.w(DebugLogTag.NETWORK, "Failed to enumerate local IPs: ${e.message}")
        }
        return ips
    }
}
