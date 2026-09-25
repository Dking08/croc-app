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
    val relayReachable: Boolean? = null
)

/**
 * Monitors croc transfer execution to detect infinite wait loops, stalled handshakes,
 * and relay mismatches in real time.
 */
object TransferWatchdog {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var watchdogJob: Job? = null
    private var sessionStartTime: Long = 0L
    private var waitingStartTime: Long = 0L

    private val _alertState = MutableStateFlow(WatchdogAlert())
    val alertState: StateFlow<WatchdogAlert> = _alertState.asStateFlow()

    fun onTransferStarted(opName: String, engine: CrocEngine, relayAddress: String) {
        stop()
        sessionStartTime = System.currentTimeMillis()
        waitingStartTime = 0L
        _alertState.value = WatchdogAlert()

        DebugLogManager.i(
            DebugLogTag.TRANSFER,
            "Transfer started: $opName using engine $engine (Configured relay: $relayAddress)"
        )
    }

    fun onStateChanged(newState: CrocTransferState, configuredRelay: String) {
        when (newState) {
            is CrocTransferState.WaitingForPeer -> {
                if (waitingStartTime == 0L) {
                    waitingStartTime = System.currentTimeMillis()
                    startWaitingTimer(configuredRelay)
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

    private fun startWaitingTimer(configuredRelay: String) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var seconds = 0L
            while (isActive) {
                delay(1000L)
                seconds++

                if (seconds == 20L) {
                    DebugLogManager.w(
                        DebugLogTag.WATCHDOG,
                        "Transfer has been waiting for peer for 20s. Probing relay connectivity to '$configuredRelay'..."
                    )
                    val reachable = probeRelayPort(configuredRelay)
                    DebugLogManager.i(
                        DebugLogTag.RELAY,
                        "Relay '$configuredRelay' TCP 9009 reachability check: ${if (reachable) "REACHABLE" else "UNREACHABLE / TIMED OUT"}"
                    )
                    _alertState.value = WatchdogAlert(
                        isStalled = true,
                        durationSeconds = seconds,
                        message = if (reachable) {
                            "Waiting for peer (20s)... Relay is reachable. Waiting for second device to connect."
                        } else {
                            "Warning: Relay '$configuredRelay' is not responding on port 9009."
                        },
                        isLikelyRelayMismatch = false,
                        relayReachable = reachable
                    )
                }

                if (seconds == 45L) {
                    val isDefaultRelay = configuredRelay.contains("croc.schollz.com")
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

    private fun probeRelayPort(relayAddress: String): Boolean {
        return try {
            val parts = relayAddress.trim().removePrefix("relay://").split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 9009 else 9009
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 3000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        waitingStartTime = 0L
        _alertState.value = WatchdogAlert()
    }
}
