package com.crocworks.app.ui.send

import android.app.Application
import android.net.Uri
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val localPath: String? = null
)

data class SendUiState(
    val selectedFiles: List<SelectedFile> = emptyList(),
    val selectedBytes: Long = 0,
    val codePhrase: String = "",
    val defaultCodePhrase: String = "",
    val savedCodePhrases: List<String> = emptyList(),
    val isTextMode: Boolean = false,
    val textToSend: String = "",
    val transferState: CrocTransferState = CrocTransferState.Idle
) {
    val hasContent: Boolean
        get() = if (isTextMode) textToSend.isNotBlank() else selectedFiles.isNotEmpty()
}

class SendViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CrocApp
    private val prefsRepo = UserPreferencesRepository(application)
    private val binaryManager = CrocBinaryManager(application)
    private val crocProcess = CrocProcess(application, binaryManager, prefsRepo)

    private val _uiState = MutableStateFlow(SendUiState())
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            crocProcess.state.collect { state ->
                _uiState.update { it.copy(transferState = state) }

                // Save to history on completion
                if (state is CrocTransferState.Completed) {
                    saveToHistory(state)
                }
            }
        }

        viewModelScope.launch {
            prefsRepo.preferencesFlow.collect { prefs ->
                _uiState.update { state ->
                    state.copy(
                        codePhrase = if (state.codePhrase.isBlank()) {
                            prefs.defaultCodePhrase.ifBlank { generateRandomCode() }
                        } else state.codePhrase,
                        defaultCodePhrase = prefs.defaultCodePhrase,
                        savedCodePhrases = prefs.savedCodePhrases
                    )
                }
            }
        }
    }

    fun addFiles(uris: List<Uri>) {
        val context = getApplication<CrocApp>()
        val newFiles = uris.mapNotNull { uri ->
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                var name = "unknown"
                var size = 0L
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = it.getString(nameIdx)
                        if (sizeIdx >= 0) size = it.getLong(sizeIdx)
                    }
                }
                SelectedFile(uri = uri, name = name, size = size.coerceAtLeast(0))
            } catch (e: Exception) {
                null
            }
        }
        _uiState.update { state ->
            val merged = (state.selectedFiles + newFiles).distinctBy { it.uri.toString() }
            state.copy(
                selectedFiles = merged,
                selectedBytes = merged.sumOf { it.size }
            )
        }
    }

    fun removeFile(file: SelectedFile) {
        _uiState.update { state ->
            val updatedFiles = state.selectedFiles.filter { it.uri != file.uri }
            state.copy(
                selectedFiles = updatedFiles,
                selectedBytes = updatedFiles.sumOf { it.size }
            )
        }
    }

    fun clearFiles() {
        _uiState.update { it.copy(selectedFiles = emptyList(), selectedBytes = 0) }
    }

    fun updateCodePhrase(code: String) {
        _uiState.update { it.copy(codePhrase = normalizeCodePhrase(code)) }
    }

    fun useCodePhrase(code: String) {
        _uiState.update { it.copy(codePhrase = normalizeCodePhrase(code)) }
    }

    fun saveCurrentCode() {
        viewModelScope.launch {
            prefsRepo.saveCodePhrase(_uiState.value.codePhrase)
        }
    }

    fun regenerateCode() {
        _uiState.update { it.copy(codePhrase = generateRandomCode()) }
    }

    fun toggleTextMode() {
        _uiState.update { it.copy(isTextMode = !it.isTextMode) }
    }

    fun updateTextToSend(text: String) {
        _uiState.update { it.copy(textToSend = text) }
    }

    fun startSend() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!state.hasContent) return@launch

            // Always reset before starting a new transfer
            crocProcess.reset()
            // Give state a moment to settle
            kotlinx.coroutines.delay(50)

            if (state.isTextMode) {
                if (state.textToSend.isNotBlank()) {
                    crocProcess.sendText(state.textToSend, state.codePhrase)
                }
            } else {
                if (state.selectedFiles.isNotEmpty()) {
                    val filePaths = copyFilesToInternal(state.selectedFiles)
                    crocProcess.send(filePaths, state.codePhrase)
                }
            }
        }
    }

    fun cancelTransfer() {
        crocProcess.cancel()
    }

    fun dismissTransferResult() {
        crocProcess.reset()
    }

    fun resetSession() {
        crocProcess.reset()
        _uiState.update {
            it.copy(
                selectedFiles = emptyList(),
                selectedBytes = 0,
                codePhrase = it.defaultCodePhrase.ifBlank { generateRandomCode() },
                textToSend = ""
            )
        }
    }

    private fun copyFilesToInternal(files: List<SelectedFile>): List<String> {
        val context = getApplication<CrocApp>()
        val sendDir = File(context.cacheDir, "croc-send").apply { mkdirs() }
        return files.map { file ->
            val dest = File(sendDir, file.name)
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest.absolutePath
        }
    }

    private suspend fun saveToHistory(state: CrocTransferState.Completed) {
        val code = _uiState.value.codePhrase
        val files = _uiState.value.selectedFiles
        if (files.isNotEmpty()) {
            // Use actual selected file names, not truncated parser names
            files.forEach { file ->
                app.database.transferHistoryDao().insert(
                    TransferHistory(
                        code = code,
                        type = TransferType.SEND,
                        fileName = file.name,
                        fileSize = file.size,
                        status = TransferStatus.COMPLETED
                    )
                )
            }
        } else {
            // Fallback: use parser data (e.g. text mode)
            app.database.transferHistoryDao().insert(
                TransferHistory(
                    code = code,
                    type = TransferType.SEND,
                    fileName = state.fileNames.firstOrNull() ?: "text",
                    fileSize = state.totalBytes,
                    status = TransferStatus.COMPLETED
                )
            )
        }
    }

    private fun generateRandomCode(): String {
        // Short words only — resulting codes are always ≤15 chars including hyphens
        val adjectives = listOf(
            "red", "blue", "cold", "dark", "dry", "icy",
            "old", "shy", "bold", "cool", "wild", "dim",
            "fast", "big", "tiny", "soft", "warm", "new"
        )
        val nouns = listOf(
            "sun", "rain", "moon", "bird", "leaf", "fog",
            "dew", "sky", "hill", "lake", "pine", "fire",
            "snow", "wind", "wave", "dawn", "dust", "glow"
        )
        val num = (10..99).random()
        return "${adjectives.random()}-${nouns.random()}-$num"
    }

    private fun normalizeCodePhrase(code: String): String {
        return code.trim().replace(" ", "-")
    }
}
