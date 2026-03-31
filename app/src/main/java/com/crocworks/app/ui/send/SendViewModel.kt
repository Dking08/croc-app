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
                val quickCodes = buildList {
                    if (prefs.defaultCodePhrase.isNotBlank()) {
                        add(prefs.defaultCodePhrase)
                    }
                    addAll(prefs.savedCodePhrases)
                }.distinct()

                _uiState.update { state ->
                    state.copy(
                        codePhrase = if (state.codePhrase.isBlank()) {
                            prefs.defaultCodePhrase.ifBlank { generateRandomCode() }
                        } else state.codePhrase,
                        defaultCodePhrase = prefs.defaultCodePhrase,
                        savedCodePhrases = quickCodes
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

            if (state.transferState is CrocTransferState.Completed ||
                state.transferState is CrocTransferState.Error ||
                state.transferState is CrocTransferState.Cancelled
            ) {
                crocProcess.reset()
            }

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
        state.fileNames.forEach { fileName ->
            app.database.transferHistoryDao().insert(
                TransferHistory(
                    code = code,
                    type = TransferType.SEND,
                    fileName = fileName,
                    fileSize = state.totalBytes,
                    status = TransferStatus.COMPLETED
                )
            )
        }
    }

    private fun generateRandomCode(): String {
        val adjectives = listOf(
            "autumn", "hidden", "bitter", "misty", "silent", "empty", "dry",
            "dark", "summer", "icy", "quiet", "white", "cool", "spring",
            "winter", "patient", "twilight", "dawn", "crimson", "wispy",
            "weathered", "blue", "billowing", "broken", "cold", "damp",
            "falling", "frosty", "green", "long", "late", "bold", "little",
            "morning", "muddy", "old", "red", "rough", "still", "small",
            "sparkling", "shy", "wandering", "wild", "black", "young",
            "holy", "solitary", "fragrant", "aged", "snowy", "proud",
            "floral", "restless", "divine", "ancient", "purple", "lively"
        )
        val nouns = listOf(
            "waterfall", "river", "breeze", "moon", "rain", "wind",
            "sea", "morning", "snow", "lake", "sunset", "pine",
            "shadow", "leaf", "dawn", "glitter", "forest", "hill",
            "cloud", "meadow", "sun", "glade", "bird", "brook",
            "butterfly", "bush", "dew", "dust", "field", "fire",
            "flower", "firefly", "feather", "grass", "haze", "mountain",
            "night", "pond", "darkness", "snowflake", "silence", "sound",
            "sky", "shape", "surf", "thunder", "violet", "water",
            "wildflower", "wave", "water", "resonance", "sun", "wood",
            "dream", "cherry", "tree", "fog", "frost", "voice"
        )
        val num = (1..9999).random()
        return "${adjectives.random()}-${nouns.random()}-$num"
    }

    private fun normalizeCodePhrase(code: String): String {
        return code.trim().replace(" ", "-")
    }
}
