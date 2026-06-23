package com.dking.crocapp.ui.receive

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dking.crocapp.CrocApp
import com.dking.crocapp.croc.CrocBinaryManager
import com.dking.crocapp.croc.CrocProcess
import com.dking.crocapp.croc.CrocTransferState
import com.dking.crocapp.data.db.TransferHistory
import com.dking.crocapp.data.db.TransferStatus
import com.dking.crocapp.data.db.TransferType
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ReceivedFile(
    val name: String,
    val savedLocation: String,
    val uri: Uri,
    val mimeType: String
)

data class ReceiveUiState(
    val codePhrase: String = "",
    val transferState: CrocTransferState = CrocTransferState.Idle,
    val defaultCodePhrase: String = "",
    val savedCodePhrases: List<String> = emptyList(),
    val receivedFiles: List<ReceivedFile> = emptyList(),
    val sessionOverrideUri: Uri? = null,
    val receiveLocationLabel: String = "Downloads/croc-received"
)

class ReceiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CrocApp
    private val prefsRepo = UserPreferencesRepository(application)
    private val binaryManager = CrocBinaryManager(application)
    private val crocProcess = CrocProcess(application, binaryManager, prefsRepo)
    private var currentOutputDir: File? = null

    private val _uiState = MutableStateFlow(ReceiveUiState())
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            crocProcess.state.collect { state ->
                _uiState.update { it.copy(transferState = state) }
                if (state is CrocTransferState.Completed) {
                    val prefs = prefsRepo.preferencesFlow.first()
                    // Session override takes priority, then settings, then default
                    val customUri = _uiState.value.sessionOverrideUri
                        ?: prefs.receiveLocationUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                    val receivedFiles = publishReceivedFiles(customUri)
                    _uiState.update { it.copy(receivedFiles = receivedFiles) }
                    // Use actual file names for history, not truncated parser names
                    val actualNames = receivedFiles.map { it.name }
                    saveToHistory(
                        state.copy(
                            fileNames = actualNames,
                            totalFileCount = receivedFiles.size
                        ),
                        receivedFiles
                    )
                }
            }
        }

        viewModelScope.launch {
            prefsRepo.preferencesFlow.collect { prefs ->
                val label = resolveLocationLabel(
                    _uiState.value.sessionOverrideUri,
                    prefs.receiveLocationUri
                )
                _uiState.update { state ->
                    state.copy(
                        codePhrase = if (state.codePhrase.isBlank()) prefs.defaultCodePhrase else state.codePhrase,
                        defaultCodePhrase = prefs.defaultCodePhrase,
                        savedCodePhrases = prefs.savedCodePhrases,
                        receiveLocationLabel = label
                    )
                }
            }
        }
    }

    fun updateCodePhrase(code: String) {
        // Replace spaces with dashes, like the original app
        _uiState.update { it.copy(codePhrase = code.replace(" ", "-")) }
    }

    fun setCodeFromQr(code: String) {
        _uiState.update { it.copy(codePhrase = normalizeCodePhrase(code)) }
    }

    fun startReceiveWithCode(code: String) {
        updateCodePhrase(code)
        startReceive()
    }

    fun saveCurrentCode() {
        viewModelScope.launch {
            prefsRepo.saveCodePhrase(_uiState.value.codePhrase)
        }
    }

    fun startReceive() {
        val code = _uiState.value.codePhrase.trim()
        if (code.isBlank()) return

        viewModelScope.launch {
            // Always reset before starting a new transfer
            crocProcess.reset()
            kotlinx.coroutines.delay(50)

            // Unique sub-directory per transfer so we only scan THIS transfer's files
            val outputDir = File(
                getApplication<CrocApp>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "croc-received/${System.currentTimeMillis()}"
            ).apply { mkdirs() }
            currentOutputDir = outputDir

            _uiState.update { it.copy(receivedFiles = emptyList()) }
            crocProcess.receive(code, outputDir)
        }
    }

    fun cancelTransfer() {
        crocProcess.cancel()
    }

    fun dismissTransferResult() {
        crocProcess.reset()
    }

    fun resetTransfer() {
        crocProcess.reset()
        _uiState.update {
            it.copy(
                codePhrase = it.defaultCodePhrase,
                receivedFiles = emptyList(),
                sessionOverrideUri = null
            )
        }
        // Recalculate label since override was cleared
        viewModelScope.launch {
            val prefs = prefsRepo.preferencesFlow.first()
            val label = resolveLocationLabel(null, prefs.receiveLocationUri)
            _uiState.update { it.copy(receiveLocationLabel = label) }
        }
    }

    fun setSessionOverrideLocation(uri: Uri) {
        val label = try {
            uri.lastPathSegment?.replace(":", "/") ?: "Custom folder"
        } catch (_: Exception) { "Custom folder" }
        _uiState.update {
            it.copy(
                sessionOverrideUri = uri,
                receiveLocationLabel = label
            )
        }
    }

    fun clearSessionOverrideLocation() {
        _uiState.update { it.copy(sessionOverrideUri = null) }
        viewModelScope.launch {
            val prefs = prefsRepo.preferencesFlow.first()
            val label = resolveLocationLabel(null, prefs.receiveLocationUri)
            _uiState.update { it.copy(receiveLocationLabel = label) }
        }
    }

    private fun resolveLocationLabel(overrideUri: Uri?, settingsUri: String): String {
        val uri = overrideUri ?: settingsUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        return if (uri != null) {
            try {
                uri.lastPathSegment?.replace(":", "/") ?: "Custom folder"
            } catch (_: Exception) { "Custom folder" }
        } else {
            "Downloads/croc-received"
        }
    }

    private suspend fun saveToHistory(
        state: CrocTransferState.Completed,
        receivedFiles: List<ReceivedFile>
    ) {
        val code = _uiState.value.codePhrase
        if (state.isTextTransfer || receivedFiles.isEmpty()) {
            app.database.transferHistoryDao().insert(
                TransferHistory(
                    code = code,
                    type = TransferType.RECEIVE,
                    fileName = state.fileNames.firstOrNull() ?: "text",
                    fileSize = state.totalBytes,
                    status = TransferStatus.COMPLETED
                )
            )
            return
        }

        receivedFiles.forEach { file ->
            app.database.transferHistoryDao().insert(
                TransferHistory(
                    code = code,
                    type = TransferType.RECEIVE,
                    fileName = file.name,
                    fileSize = state.totalBytes,
                    fileUri = file.uri.toString(),
                    mimeType = file.mimeType,
                    savedLocation = file.savedLocation,
                    status = TransferStatus.COMPLETED
                )
            )
        }
    }

    /**
     * Scan the output directory for all files and publish them.
     * We don't rely on [fileNames] from the parser because croc truncates
     * long names in its progress output (e.g. "4200-funn...").
     */
    private fun publishReceivedFiles(customTreeUri: Uri? = null): List<ReceivedFile> {
        val outputDir = currentOutputDir ?: return emptyList()
        return ReceivedFilePublisher.publish(getApplication(), outputDir, customTreeUri)
    }

    private fun normalizeCodePhrase(code: String): String {
        return code.trim().replace(" ", "-")
    }
}
