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
import com.dking.crocapp.debug.NetworkStateMonitor
import com.dking.crocapp.debug.TransferHistoryAuditor
import com.dking.crocapp.debug.TransferWatchdog
import com.dking.crocapp.debug.WatchdogAlert
import com.dking.crocapp.debug.TransferSession
import com.dking.crocapp.debug.TransferSessionTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val isRunning: Boolean = false,
    val currentPrefs: UserPreferencesRepository.CrocPreferences? = null,
    val networkReport: NetworkDiagnosticReport? = null,
    val auditIssues: List<ConfigAuditIssue> = emptyList(),
    val historyReport: HistoryAuditReport? = null,
    val logs: List<DebugLogEntry> = emptyList(),
    val watchdogAlert: WatchdogAlert = WatchdogAlert(),
    val selectedTagFilter: DebugLogTag? = null,
    val lastSession: TransferSession? = null
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

        // Collect last transfer session & diagnostic analysis
        viewModelScope.launch {
            TransferSessionTracker.lastSession.collect { session ->
                _uiState.update { it.copy(lastSession = session) }
            }
        }

        // React dynamically to preference changes (e.g. user edited relay in Settings)
        viewModelScope.launch {
            prefsRepo.preferencesFlow.collect { prefs ->
                _uiState.update { it.copy(currentPrefs = prefs) }
                runFullDiagnostics()
            }
        }

        // React dynamically to network connection changes (e.g. Wi-Fi turned off/on)
        viewModelScope.launch {
            NetworkStateMonitor.networkState.collect {
                runFullDiagnostics()
            }
        }
    }

    fun runFullDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true) }
            DebugLogManager.d(DebugLogTag.SYSTEM, "Running diagnostics on current settings...")

            try {
                val prefs = prefsRepo.preferencesFlow.first()
                val netReport = networkDiagnostics.runDiagnostics(prefs.relayAddress)
                val configIssues = ConfigAuditor.audit(prefs, netReport)
                val histReport = TransferHistoryAuditor.audit(app.database)

                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentPrefs = prefs,
                        networkReport = netReport,
                        auditIssues = configIssues,
                        historyReport = histReport
                    )
                }
                DebugLogManager.d(DebugLogTag.SYSTEM, "Diagnostics completed.")
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
            logs = state.logs,
            lastSession = state.lastSession
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
