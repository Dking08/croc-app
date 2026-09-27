package com.dking.crocapp.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NetworkState(
    val isConnected: Boolean = false,
    val connectionType: String = "Disconnected",
    val isVpn: Boolean = false,
    val isInternetValidated: Boolean = false,
    val systemDnsServers: List<String> = emptyList()
)

/**
 * Monitors device network state in real-time, logging connectivity transitions
 * (Wi-Fi off/on, Airplane mode, Cellular loss) to DebugLogManager and alerting
 * TransferWatchdog if an active transfer is interrupted.
 */
object NetworkStateMonitor {

    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager = cm
        if (cm == null) {
            DebugLogManager.w(DebugLogTag.NETWORK, "ConnectivityManager unavailable on this device.")
            return
        }

        // Initial snapshot
        updateNetworkSnapshot(cm)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    val connType = getTransportName(caps)
                    DebugLogManager.i(
                        DebugLogTag.NETWORK,
                        "Network available: $connType (Internet: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true})"
                    )
                    updateNetworkSnapshot(cm)
                }

                override fun onLost(network: Network) {
                    DebugLogManager.w(
                        DebugLogTag.NETWORK,
                        "Network connection LOST! Device is now offline or disconnected."
                    )
                    updateNetworkSnapshot(cm)
                    TransferWatchdog.onNetworkLost()
                    TransferSessionTracker.onNetworkDropped()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val connType = getTransportName(networkCapabilities)
                    val wasConnected = _networkState.value.isConnected

                    updateNetworkSnapshot(cm)

                    if (!hasInternet && wasConnected) {
                        DebugLogManager.w(DebugLogTag.NETWORK, "Internet capability lost on active network ($connType).")
                        TransferWatchdog.onNetworkLost()
                    } else if (hasInternet && !wasConnected) {
                        DebugLogManager.i(DebugLogTag.NETWORK, "Internet capability restored on $connType (Validated: $isValidated).")
                    }
                }
            })
        } catch (e: Exception) {
            DebugLogManager.e(DebugLogTag.NETWORK, "Failed to register network callback: ${e.message}")
        }
    }

    fun updateNetworkSnapshot(cm: ConnectivityManager? = connectivityManager) {
        val manager = cm ?: return
        val active = manager.activeNetwork
        val caps = active?.let { manager.getNetworkCapabilities(it) }
        val props = active?.let { manager.getLinkProperties(it) }

        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val connType = getTransportName(caps)
        val dnsList = props?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

        _networkState.update {
            NetworkState(
                isConnected = hasInternet,
                connectionType = connType,
                isVpn = isVpn,
                isInternetValidated = isValidated,
                systemDnsServers = dnsList
            )
        }
    }

    private fun getTransportName(caps: NetworkCapabilities?): String {
        return when {
            caps == null -> "Disconnected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    }
}
