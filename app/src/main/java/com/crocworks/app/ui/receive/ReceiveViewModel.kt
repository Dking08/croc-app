package com.crocworks.app.ui.receive

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crocworks.app.CrocApp
import com.crocworks.app.croc.CrocBinaryManager
import com.crocworks.app.croc.CrocProcess
import com.crocworks.app.croc.CrocTransferState
import com.crocworks.app.data.db.TransferHistory
import com.crocworks.app.data.db.TransferStatus
import com.crocworks.app.data.db.TransferType
import com.crocworks.app.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ReceiveUiState(
    val codePhrase: String = "",
    val transferState: CrocTransferState = CrocTransferState.Idle,
    val receivedFilePaths: List<String> = emptyList()
)

class ReceiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CrocApp
    private val prefsRepo = UserPreferencesRepository(application)
    private val binaryManager = CrocBinaryManager(application)
    private val crocProcess = CrocProcess(application, binaryManager, prefsRepo)

    private val _uiState = MutableStateFlow(ReceiveUiState())
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            crocProcess.state.collect { state ->
                _uiState.update { it.copy(transferState = state) }
                if (state is CrocTransferState.Completed) {
                    saveToHistory(state)
                }
            }
        }
    }

    fun updateCodePhrase(code: String) {
        // Replace spaces with dashes, like the original app
        _uiState.update { it.copy(codePhrase = code.replace(" ", "-")) }
    }

    fun setCodeFromQr(code: String) {
        _uiState.update { it.copy(codePhrase = code.trim()) }
    }

    fun startReceive() {
        val code = _uiState.value.codePhrase.trim()
        if (code.isBlank()) return

        viewModelScope.launch {
            val outputDir = File(
                getApplication<CrocApp>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "croc-received"
            ).apply { mkdirs() }

            crocProcess.receive(code, outputDir)
        }
    }

    fun cancelTransfer() {
        crocProcess.cancel()
    }

    fun resetTransfer() {
        crocProcess.reset()
        _uiState.update { it.copy(codePhrase = "", receivedFilePaths = emptyList()) }
    }

    private suspend fun saveToHistory(state: CrocTransferState.Completed) {
        val code = _uiState.value.codePhrase
        state.fileNames.forEach { fileName ->
            app.database.transferHistoryDao().insert(
                TransferHistory(
                    code = code,
                    type = TransferType.RECEIVE,
                    fileName = fileName,
                    fileSize = state.totalBytes,
                    status = TransferStatus.COMPLETED
                )
            )
        }
    }
}
