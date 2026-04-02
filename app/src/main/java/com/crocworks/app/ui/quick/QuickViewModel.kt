package com.crocworks.app.ui.quick

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class QuickUiState(
    val transferState: CrocTransferState = CrocTransferState.Idle,
    val quickSendCode: String = "",
    val quickReceiveCode: String = "",
    val savedCodePhrases: List<String> = emptyList(),
    val lastAction: String = "", // "send", "clipboard", "receive", "qr"
    val statusMessage: String = "Ready",
    val statusDetail: String = "Tap Send or Receive to start",
    val receivedText: String? = null
)

class QuickViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CrocApp
    private val prefsRepo = UserPreferencesRepository(application)
    private val binaryManager = CrocBinaryManager(application)
    private val crocProcess = CrocProcess(application, binaryManager, prefsRepo)
    private var currentOutputDir: File? = null

    private val _uiState = MutableStateFlow(QuickUiState())
    val uiState: StateFlow<QuickUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            crocProcess.state.collect { state ->
                _uiState.update { it.copy(transferState = state) }

                when (state) {
                    is CrocTransferState.Preparing -> {
                        _uiState.update {
                            it.copy(
                                statusMessage = "Preparing...",
                                statusDetail = "Setting up transfer"
                            )
                        }
                    }
                    is CrocTransferState.WaitingForPeer -> {
                        _uiState.update {
                            it.copy(
                                statusMessage = "Waiting for peer",
                                statusDetail = "Code: ${state.code}"
                            )
                        }
                    }
                    is CrocTransferState.Transferring -> {
                        _uiState.update {
                            it.copy(
                                statusMessage = "Transferring...",
                                statusDetail = "${state.fileName} (${state.progressPercent}%)"
                            )
                        }
                    }
                    is CrocTransferState.Completed -> {
                        val receivedText = state.receivedText
                        _uiState.update {
                            it.copy(
                                statusMessage = if (state.isTextTransfer) "Text Received!" else "Transfer Complete!",
                                statusDetail = if (state.isTextTransfer) {
                                    receivedText?.take(100) ?: "Text transfer done"
                                } else {
                                    "${state.fileCount} file${if (state.fileCount != 1) "s" else ""} transferred"
                                },
                                receivedText = receivedText
                            )
                        }
                        saveToHistory(state)
                    }
                    is CrocTransferState.Error -> {
                        _uiState.update {
                            it.copy(
                                statusMessage = "Error",
                                statusDetail = state.message
                            )
                        }
                    }
                    is CrocTransferState.Cancelled -> {
                        _uiState.update {
                            it.copy(
                                statusMessage = "Cancelled",
                                statusDetail = "Transfer was cancelled"
                            )
                        }
                    }
                    else -> {}
                }
            }
        }

        viewModelScope.launch {
            prefsRepo.preferencesFlow.collect { prefs ->
                _uiState.update { state ->
                    state.copy(
                        quickSendCode = prefs.effectiveQuickSendCode,
                        quickReceiveCode = prefs.effectiveQuickReceiveCode,
                        savedCodePhrases = prefs.savedCodePhrases
                    )
                }
            }
        }
    }

    fun sendFiles(uris: List<Uri>) {
        val code = _uiState.value.quickSendCode
        if (code.isBlank() || uris.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(lastAction = "send") }
            crocProcess.reset()
            kotlinx.coroutines.delay(50)

            val filePaths = copyFilesToInternal(uris)
            crocProcess.send(filePaths, code)
        }
    }

    fun sendClipboardText(text: String) {
        val code = _uiState.value.quickSendCode
        if (code.isBlank() || text.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(lastAction = "clipboard") }
            crocProcess.reset()
            kotlinx.coroutines.delay(50)
            crocProcess.sendText(text, code)
        }
    }

    fun startReceive() {
        val code = _uiState.value.quickReceiveCode
        if (code.isBlank()) return
        startReceiveWithCode(code)
    }

    fun startReceiveWithCode(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(lastAction = "receive") }
            crocProcess.reset()
            kotlinx.coroutines.delay(50)

            val outputDir = File(
                getApplication<CrocApp>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "croc-received/${System.currentTimeMillis()}"
            ).apply { mkdirs() }
            currentOutputDir = outputDir

            crocProcess.receive(code, outputDir)
        }
    }

    fun startReceiveFromQr(code: String) {
        _uiState.update { it.copy(lastAction = "qr") }
        startReceiveWithCode(code)
    }

    fun cancelTransfer() {
        crocProcess.cancel()
    }

    fun dismissResult() {
        crocProcess.reset()
        _uiState.update {
            it.copy(
                statusMessage = "Ready",
                statusDetail = "Tap Send or Receive to start",
                receivedText = null
            )
        }
    }

    val isTransferActive: Boolean
        get() {
            val state = _uiState.value.transferState
            return state is CrocTransferState.Preparing ||
                    state is CrocTransferState.WaitingForPeer ||
                    state is CrocTransferState.Transferring
        }

    private fun copyFilesToInternal(uris: List<Uri>): List<String> {
        val context = getApplication<CrocApp>()
        val sendDir = File(context.cacheDir, "croc-send").apply { mkdirs() }
        return uris.mapNotNull { uri ->
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                var name = "unknown"
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) name = it.getString(nameIdx)
                    }
                }
                val dest = File(sendDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                dest.absolutePath
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun saveToHistory(state: CrocTransferState.Completed) {
        val code = _uiState.value.let {
            if (it.lastAction == "send" || it.lastAction == "clipboard") it.quickSendCode
            else it.quickReceiveCode
        }
        val type = if (_uiState.value.lastAction in listOf("send", "clipboard")) TransferType.SEND else TransferType.RECEIVE

        if (state.isTextTransfer) {
            app.database.transferHistoryDao().insert(
                TransferHistory(
                    code = code,
                    type = type,
                    fileName = "text",
                    fileSize = state.totalBytes,
                    status = TransferStatus.COMPLETED
                )
            )
        } else {
            state.fileNames.forEach { fileName ->
                app.database.transferHistoryDao().insert(
                    TransferHistory(
                        code = code,
                        type = type,
                        fileName = fileName,
                        fileSize = state.totalBytes,
                        status = TransferStatus.COMPLETED
                    )
                )
            }
        }
    }
}
