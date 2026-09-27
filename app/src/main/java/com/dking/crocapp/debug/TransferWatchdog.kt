package com.dking.crocapp.debug

import com.dking.crocapp.croc.CrocEngine
import com.dking.crocapp.croc.CrocTransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

data class WatchdogAlert(
    val isStalled: Boolean = false,
    val durationSeconds: Long = 0,
    val message: String = "",
    val isLikelyRelayMismatch: Boolean = false,
    val isNetworkDisconnected: Boolean = false,
    val relayReachable: Boolean? = null
)

/**
 * Monitors croc transfer execution to detect infinite wait loops, stalled handshakes,
 * network drops, and relay mismatches in real time.
 */
object TransferWatchdog {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var watchdogJob: Job? = null
    private var sessionStartTime: Long = 0L
    private var waitingStartTime: Long = 0L

    private var activeRelayAddress: String = "croc.schollz.com:9009"
    private var activeOpName: String = ""
    private var isTransferActive: Boolean = false

    private val _alertState = MutableStateFlow(WatchdogAlert())
    val alertState: StateFlow<WatchdogAlert> = _alertState.asStateFlow()

    fun onTransferStarted(opName: String, engine: CrocEngine, relayAddress: String) {
        stop()
        isTransferActive = true
        activeOpName = opName
        activeRelayAddress = if (relayAddress.isNotBlank()) relayAddress.trim() else "croc.schollz.com:9009"
        sessionStartTime = System.currentTimeMillis()
        waitingStartTime = 0L
        _alertState.value = WatchdogAlert()

        val isConnected = NetworkStateMonitor.networkState.value.isConnected
        if (!isConnected) {
            DebugLogManager.e(
                DebugLogTag.NETWORK,
                "Transfer '$opName' started while device is OFFLINE! Internet connection required for relay '$activeRelayAddress'."
            )
            _alertState.value = WatchdogAlert(
                isStalled = true,
                isNetworkDisconnected = true,
                durationSeconds = 0,
                message = "Device is offline. Turn on Wi-Fi or Mobile Data to connect to relay '$activeRelayAddress'."
            )
        } else {
            DebugLogManager.i(
                DebugLogTag.TRANSFER,
                "Transfer started: $opName using engine $engine (Configured relay: $activeRelayAddress)"
            )
        }
    }

    fun onNetworkLost() {
        if (!isTransferActive) return

        val duration = if (sessionStartTime > 0) (System.currentTimeMillis() - sessionStartTime) / 1000 else 0
        DebugLogManager.e(
            DebugLogTag.TRANSFER,
            "CRITICAL: Internet dropped during active transfer '$activeOpName'! Connection to relay '$activeRelayAddress' was severed."
        )

        _alertState.value = WatchdogAlert(
            isStalled = true,
            durationSeconds = duration,
            isNetworkDisconnected = true,
            message = "Internet disconnected! Active connection to relay '$activeRelayAddress' was lost. Transfer cannot continue.",
            relayReachable = false
        )
    }

    fun onStateChanged(newState: CrocTransferState, configuredRelay: String = "") {
        if (configuredRelay.isNotBlank() && !configuredRelay.contains("-")) {
            activeRelayAddress = configuredRelay.trim()
        }

        when (newState) {
            is CrocTransferState.WaitingForPeer -> {
                if (waitingStartTime == 0L) {
                    waitingStartTime = System.currentTimeMillis()
                    startWaitingTimer(activeRelayAddress)
                }
            }
            is CrocTransferState.Transferring -> {
                val waitDurationMs = if (waitingStartTime > 0) System.currentTimeMillis() - waitingStartTime else 0
                DebugLogManager.i(
                    DebugLogTag.WATCHDOG,
                    "Peer connected successfully! Handshake wait time: ${waitDurationMs}ms"
                )
                stop()
            }
            is CrocTransferState.Completed, is CrocTransferState.StoreCompleted -> {
                val totalDuration = System.currentTimeMillis() - sessionStartTime
                DebugLogManager.i(
                    DebugLogTag.TRANSFER,
                    "Transfer finished successfully. Total duration: ${totalDuration / 1000}s"
                )
                stop()
            }
            is CrocTransferState.Error -> {
                DebugLogManager.e(
                    DebugLogTag.TRANSFER,
                    "Transfer failed with error: ${newState.message}"
                )
                stop()
            }
            is CrocTransferState.Cancelled -> {
                DebugLogManager.w(
                    DebugLogTag.TRANSFER,
                    "Transfer was cancelled by user."
                )
                stop()
            }
            else -> {}
        }
    }

    private fun startWaitingTimer(relayAddress: String) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var seconds = 0L
            while (isActive) {
                delay(1000L)
                seconds++

                // Immediate offline check
                if (!NetworkStateMonitor.networkState.value.isConnected) {
                    _alertState.value = WatchdogAlert(
                        isStalled = true,
                        durationSeconds = seconds,
                        isNetworkDisconnected = true,
                        message = "Device is offline. Turn on Wi-Fi or Mobile Data to connect to relay '$activeRelayAddress'.",
                        relayReachable = false
                    )
                    continue
                }

                if (seconds == 15L || seconds == 30L) {
                    DebugLogManager.d(
                        DebugLogTag.WATCHDOG,
                        "Still waiting for peer connection ($seconds seconds elapsed)..."
                    )
                }

                if (seconds == 20L) {
                    DebugLogManager.w(
                        DebugLogTag.WATCHDOG,
                        "Transfer has been waiting for peer for 20s. Probing relay connectivity to '$activeRelayAddress'..."
                    )
                    val reachable = probeRelayPort(activeRelayAddress)
                    DebugLogManager.i(
                        DebugLogTag.RELAY,
                        "Relay '$activeRelayAddress' TCP 9009 reachability check: ${if (reachable) "REACHABLE" else "UNREACHABLE / TIMED OUT"}"
                    )
                    _alertState.value = WatchdogAlert(
                        isStalled = true,
                        durationSeconds = seconds,
                        message = if (reachable) {
                            "Waiting for peer (20s)... Relay is reachable. Waiting for second device to connect."
                        } else {
                            "Warning: Relay '$activeRelayAddress' is not responding on port 9009."
                        },
                        isLikelyRelayMismatch = false,
                        relayReachable = reachable
                    )
                }

                if (seconds == 45L) {
                    val isDefaultRelay = activeRelayAddress.contains("croc.schollz.com")
                    val mismatchNote = if (isDefaultRelay) {
                        "croc-app is using croc.schollz.com. If the other device is using the standard croc CLI, it uses 1-4.getcroc.com by default. Ensure both devices specify the exact same relay!"
                    } else {
                        "Ensure both devices are configured with the exact same custom relay address."
                    }

                    DebugLogManager.w(
                        DebugLogTag.WATCHDOG,
                        "Stalled wait loop detected (45s). Potential relay mismatch. $mismatchNote"
                    )

                    _alertState.value = WatchdogAlert(
                        isStalled = true,
                        durationSeconds = seconds,
                        message = "Transfer potentially stuck in wait loop (>45s). Check if both devices are using the exact same relay and secret code.",
                        isLikelyRelayMismatch = isDefaultRelay,
                        relayReachable = _alertState.value.relayReachable
                    )
                }

                if (seconds == 90L) {
                    DebugLogManager.e(
                        DebugLogTag.WATCHDOG,
                        "Transfer wait loop timeout warning (>90s). Handshake did not occur."
                    )
                    _alertState.value = _alertState.value.copy(
                        durationSeconds = seconds,
                        message = "Stuck waiting for peer (>90s). Please verify network, code phrase, and relay match on both devices."
                    )
                }
            }
        }
    }

    fun probeRelayPort(relayAddress: String): Boolean {
        return try {
            val clean = relayAddress.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("relay://")
            val host = clean.substringBefore(":")
            val port = clean.substringAfter(":", "9009").toIntOrNull() ?: 9009

            if (host.isBlank()) return false

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 3000)
                true
            }
        } catch (e: Exception) {
            DebugLogManager.w(DebugLogTag.RELAY, "Probe error for '$relayAddress': ${e.message}")
            false
        }
    }

    fun stop() {
        isTransferActive = false
        activeOpName = ""
        watchdogJob?.cancel()
        watchdogJob = null
        waitingStartTime = 0L
        _alertState.value = WatchdogAlert()
    }
}
