package com.dking.crocapp.debug

import com.dking.crocapp.croc.CrocEngine
import com.dking.crocapp.croc.CrocTransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TransferSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val opName: String,
    val engine: CrocEngine,
    val relayConfigured: String,
    val codePhrase: String,
    val curve: String = "p256",
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = 0L,
    val durationSeconds: Long = 0L,
    val networkAtStart: String,
    val networkAtEnd: String = "",
    val networkDroppedDuringTransfer: Boolean = false,
    val state: CrocTransferState = CrocTransferState.Preparing,
    val exitCode: Int? = null,
    val stdoutTail: List<String> = emptyList(),
    val failureAnalysis: TransferFailureAnalysis? = null
) {
    val isSuccess: Boolean
        get() = state is CrocTransferState.Completed || state is CrocTransferState.StoreCompleted

    val formattedStartTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(startTime))
}

/**
 * Tracks and records active and recent transfer sessions with complete telemetry,
 * network state snapshots, stdout logs, and diagnostic failure analysis.
 * Stores everything strictly in-memory. Zero data leaves the device.
 */
object TransferSessionTracker {

    private val _lastSession = MutableStateFlow<TransferSession?>(null)
    val lastSession: StateFlow<TransferSession?> = _lastSession.asStateFlow()

    private val _recentSessions = MutableStateFlow<List<TransferSession>>(emptyList())
    val recentSessions: StateFlow<List<TransferSession>> = _recentSessions.asStateFlow()

    private var currentSession: TransferSession? = null
    private val currentStdoutLines = mutableListOf<String>()
    private var networkDropped = false

    fun startSession(
        opName: String,
        engine: CrocEngine,
        relayAddress: String,
        code: String?,
        curve: String = "p256"
    ) {
        val netState = NetworkStateMonitor.networkState.value
        val netSummary = "${netState.connectionType} (Internet: ${netState.isConnected}, VPN: ${netState.isVpn})"

        val session = TransferSession(
            opName = opName,
            engine = engine,
            relayConfigured = relayAddress,
            codePhrase = code ?: "auto-generated",
            curve = curve,
            networkAtStart = netSummary
        )

        currentSession = session
        currentStdoutLines.clear()
        networkDropped = false
        _lastSession.value = session
    }

    fun recordOutputLine(line: String) {
        if (currentStdoutLines.size > 100) {
            currentStdoutLines.removeAt(0)
        }
        currentStdoutLines.add(line)
    }

    fun onNetworkDropped() {
        networkDropped = true
        currentSession?.let { s ->
            currentSession = s.copy(networkDroppedDuringTransfer = true)
        }
    }

    fun finishSession(
        finalState: CrocTransferState,
        exitCode: Int?,
        relayAddress: String,
        curve: String = "p256"
    ) {
        val s = currentSession ?: return
        val end = System.currentTimeMillis()
        val duration = (end - s.startTime) / 1000

        val netState = NetworkStateMonitor.networkState.value
        val netSummary = "${netState.connectionType} (Internet: ${netState.isConnected}, VPN: ${netState.isVpn})"

        val failure = if (finalState is CrocTransferState.Error || (exitCode != null && exitCode != 0)) {
            TransferDiagnosticClassifier.classify(
                exitCode = exitCode ?: -1,
                outputLines = currentStdoutLines.toList(),
                isNetworkConnected = netState.isConnected && !networkDropped,
                relayAddress = relayAddress,
                curve = curve
            )
        } else null

        val finishedSession = s.copy(
            endTime = end,
            durationSeconds = duration,
            networkAtEnd = netSummary,
            networkDroppedDuringTransfer = networkDropped,
            state = finalState,
            exitCode = exitCode,
            stdoutTail = currentStdoutLines.toList(),
            failureAnalysis = failure
        )

        _lastSession.value = finishedSession
        _recentSessions.update { list ->
            listOf(finishedSession) + list.take(9)
        }

        currentSession = null
        networkDropped = false
    }
}
