package com.dking.crocapp.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dking.crocapp.CrocApp
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import com.dking.crocapp.debug.ConfigAuditIssue
import com.dking.crocapp.debug.ConfigAuditor
import com.dking.crocapp.debug.DebugLogEntry
import com.dking.crocapp.debug.DebugLogLevel
import com.dking.crocapp.debug.DebugLogManager
import com.dking.crocapp.debug.DebugLogTag
import com.dking.crocapp.debug.DebugReportGenerator
import com.dking.crocapp.debug.HistoryAuditReport
import com.dking.crocapp.debug.NetworkDiagnosticReport
import com.dking.crocapp.debug.NetworkDiagnostics
import com.dking.crocapp.debug.TransferHistoryAuditor
import com.dking.crocapp.debug.TransferWatchdog
import com.dking.crocapp.debug.WatchdogAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val isRunning: Boolean = false,
    val networkReport: NetworkDiagnosticReport? = null,
    val auditIssues: List<ConfigAuditIssue> = emptyList(),
    val historyReport: HistoryAuditReport? = null,
    val logs: List<DebugLogEntry> = emptyList(),
    val watchdogAlert: WatchdogAlert = WatchdogAlert(),
    val selectedTagFilter: DebugLogTag? = null
)

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CrocApp
    private val prefsRepo = UserPreferencesRepository(application)
    private val networkDiagnostics = NetworkDiagnostics(application)

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        // Collect live logs from DebugLogManager
        viewModelScope.launch {
            DebugLogManager.logsFlow.collect { logList ->
                _uiState.update { it.copy(logs = logList) }
            }
        }

        // Collect transfer watchdog alerts
        viewModelScope.launch {
            TransferWatchdog.alertState.collect { alert ->
                _uiState.update { it.copy(watchdogAlert = alert) }
            }
        }

        // Run initial quick audit on screen open
        runFullDiagnostics()
    }

    fun runFullDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true) }
            DebugLogManager.i(DebugLogTag.SYSTEM, "User requested full diagnostics check.")

            try {
                val prefs = prefsRepo.preferencesFlow.first()
                val netReport = networkDiagnostics.runDiagnostics(prefs.relayAddress)
                val configIssues = ConfigAuditor.audit(prefs, netReport)
                val histReport = TransferHistoryAuditor.audit(app.database)

                _uiState.update {
                    it.copy(
                        isRunning = false,
                        networkReport = netReport,
                        auditIssues = configIssues,
                        historyReport = histReport
                    )
                }
                DebugLogManager.i(DebugLogTag.SYSTEM, "Diagnostics completed successfully.")
            } catch (e: Exception) {
                DebugLogManager.e(DebugLogTag.SYSTEM, "Diagnostics check failed: ${e.message}")
                _uiState.update { it.copy(isRunning = false) }
            }
        }
    }

    suspend fun generateReport(): String {
        val prefs = prefsRepo.preferencesFlow.first()
        val state = _uiState.value
        return DebugReportGenerator.generateMarkdownReport(
            context = getApplication(),
            prefs = prefs,
            networkReport = state.networkReport,
            auditIssues = state.auditIssues,
            historyReport = state.historyReport,
            logs = state.logs
        )
    }

    fun clearLogs() {
        DebugLogManager.clear()
        DebugLogManager.i(DebugLogTag.SYSTEM, "Diagnostic logs cleared by user.")
    }

    fun setTagFilter(tag: DebugLogTag?) {
        _uiState.update { it.copy(selectedTagFilter = tag) }
    }
}
