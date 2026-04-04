package com.dking.crocapp.ui.quick

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
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
import com.dking.crocapp.ui.receive.ReceivedFile
import com.dking.crocapp.ui.receive.ReceivedFilePublisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class QuickSharePreview(
    val title: String,
    val subtitle: String
)

data class QuickUiState(
    val transferState: CrocTransferState = CrocTransferState.Idle,
    val quickSendCode: String = "",
    val quickReceiveCode: String = "",
    val activeCode: String = "",
    val savedCodePhrases: List<String> = emptyList(),
    val lastAction: String = "", // "send", "clipboard", "receive", "qr"
    val sharePreview: List<QuickSharePreview> = emptyList(),
    val statusMessage: String = "Ready",
    val statusDetail: String = "Tap Send or Receive to start",
    val receivedText: String? = null,
    val receivedFiles: List<ReceivedFile> = emptyList()
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
                        val receivedFiles = if (state.isTextTransfer) {
                            emptyList()
                        } else {
                            publishReceivedFiles()
                        }
                        val actualNames = if (receivedFiles.isNotEmpty()) {
                            receivedFiles.map { it.name }
                        } else {
                            state.fileNames
                        }
                        val historyState = state.copy(
                            fileNames = actualNames,
                            totalFileCount = if (receivedFiles.isNotEmpty()) {
                                receivedFiles.size
                            } else {
                                state.totalFileCount
                            }
                        )
                        _uiState.update {
                            it.copy(
                                transferState = historyState,
                                statusMessage = if (state.isTextTransfer) "Text Received!" else "Transfer Complete!",
                                statusDetail = if (state.isTextTransfer) {
                                    receivedText?.take(100) ?: "Text transfer done"
                                } else {
                                    "${historyState.fileCount} file${if (historyState.fileCount != 1) "s" else ""} transferred"
                                },
                                receivedText = receivedText,
                                receivedFiles = receivedFiles
                            )
                        }
                        saveToHistory(historyState, receivedFiles)
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
            _uiState.update {
                it.copy(
                    lastAction = "send",
                    activeCode = code,
                    sharePreview = buildFileSharePreview(uris),
                    receivedText = null,
                    receivedFiles = emptyList()
                )
            }
            crocProcess.reset()
            kotlinx.coroutines.delay(50)

            val filePaths = copyFilesToInternal(uris)
            if (filePaths.isEmpty()) {
                _uiState.update {
                    it.copy(
                        transferState = CrocTransferState.Error("Could not prepare the selected files."),
                        statusMessage = "Error",
                        statusDetail = "Could not prepare the selected files."
                    )
                }
                return@launch
            }
            crocProcess.send(filePaths, code)
        }
    }

    fun sendClipboardText(text: String) {
        val code = _uiState.value.quickSendCode
        if (code.isBlank() || text.isBlank()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    lastAction = "clipboard",
                    activeCode = code,
                    sharePreview = listOf(
                        QuickSharePreview(
                            title = "Clipboard text",
                            subtitle = buildClipboardSubtitle(text)
                        )
                    ),
                    receivedText = null,
                    receivedFiles = emptyList()
                )
            }
            crocProcess.reset()
            kotlinx.coroutines.delay(50)
            crocProcess.sendText(text, code)
        }
    }

    fun startReceive() {
        val code = _uiState.value.quickReceiveCode
        if (code.isBlank()) return
        startReceiveInternal(code, "receive")
    }

    fun startReceiveWithCode(code: String) {
        startReceiveInternal(code, "receive")
    }

    fun startReceiveFromQr(code: String) {
        startReceiveInternal(code, "qr")
    }

    private fun startReceiveInternal(code: String, action: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    lastAction = action,
                    activeCode = code,
                    sharePreview = emptyList(),
                    receivedText = null,
                    receivedFiles = emptyList()
                )
            }
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

    fun cancelTransfer() {
        crocProcess.cancel()
    }

    fun dismissResult() {
        crocProcess.reset()
        _uiState.update {
            it.copy(
                lastAction = "",
                sharePreview = emptyList(),
                statusMessage = "Ready",
                statusDetail = "Tap Send or Receive to start",
                activeCode = "",
                receivedText = null,
                receivedFiles = emptyList()
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
                val name = resolveDisplayName(uri)
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

    private fun buildFileSharePreview(uris: List<Uri>): List<QuickSharePreview> {
        return uris.map { uri ->
            val name = resolveDisplayName(uri)
            val size = resolveFileSize(uri)
            QuickSharePreview(
                title = name,
                subtitle = size?.let(::formatBytes) ?: "Ready to share"
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val context = getApplication<CrocApp>()
        var name = "unknown"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) {
                    name = cursor.getString(nameIdx) ?: name
                }
            }
        }
        return name
    }

    private fun resolveFileSize(uri: Uri): Long? {
        val context = getApplication<CrocApp>()
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    return cursor.getLong(sizeIdx)
                }
            }
        }
        return null
    }

    private fun buildClipboardSubtitle(text: String): String {
        val trimmed = text.trim()
        val headline = trimmed.lineSequence().firstOrNull().orEmpty()
        val preview = if (headline.length > 56) {
            headline.take(53).trimEnd() + "..."
        } else {
            headline
        }
        val charCount = trimmed.length
        return if (preview.isBlank()) {
            "$charCount characters"
        } else {
            "$preview • $charCount characters"
        }
    }

    private fun publishReceivedFiles(): List<ReceivedFile> {
        val outputDir = currentOutputDir ?: return emptyList()
        return ReceivedFilePublisher.publish(getApplication(), outputDir)
    }

    private suspend fun saveToHistory(
        state: CrocTransferState.Completed,
        receivedFiles: List<ReceivedFile>
    ) {
        val code = _uiState.value.let {
            if (it.activeCode.isNotBlank()) {
                it.activeCode
            } else if (it.lastAction == "send" || it.lastAction == "clipboard") {
                it.quickSendCode
            } else {
                it.quickReceiveCode
            }
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
            val filesForHistory = if (type == TransferType.RECEIVE && receivedFiles.isNotEmpty()) {
                receivedFiles
            } else {
                emptyList()
            }

            if (filesForHistory.isNotEmpty()) {
                filesForHistory.forEach { file ->
                    app.database.transferHistoryDao().insert(
                        TransferHistory(
                            code = code,
                            type = type,
                            fileName = file.name,
                            fileSize = state.totalBytes,
                            fileUri = file.uri.toString(),
                            mimeType = file.mimeType,
                            savedLocation = file.savedLocation,
                            status = TransferStatus.COMPLETED
                        )
                    )
                }
                return
            }

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

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}
