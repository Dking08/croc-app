package com.dking.crocapp.ui.send

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
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

enum class SendMode { FILES, FOLDER, TEXT }

data class SendUiState(
    val selectedFiles: List<SelectedFile> = emptyList(),
    val selectedBytes: Long = 0,
    val codePhrase: String = "",
    val defaultCodePhrase: String = "",
    val savedCodePhrases: List<String> = emptyList(),
    val sendMode: SendMode = SendMode.FILES,
    val textToSend: String = "",
    val transferState: CrocTransferState = CrocTransferState.Idle,
    // Folder mode
    val selectedFolderName: String? = null,
    val selectedFolderFileCount: Int = 0,
    val selectedFolderSize: Long = 0,
    val selectedFolderPath: String? = null
) {
    // Backward compat helper
    val isTextMode: Boolean get() = sendMode == SendMode.TEXT

    val hasContent: Boolean
        get() = when (sendMode) {
            SendMode.FILES -> selectedFiles.isNotEmpty()
            SendMode.FOLDER -> selectedFolderPath != null
            SendMode.TEXT -> textToSend.isNotBlank()
        }
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

    fun addFolder(treeUri: Uri) {
        val context = getApplication<CrocApp>()
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return
        val folderName = treeDoc.name ?: "folder"

        // Stage the entire folder tree into cache so croc can access it as a filesystem path
        val stagingDir = File(context.cacheDir, "croc-send-folder/$folderName").apply {
            deleteRecursively()
            mkdirs()
        }

        var fileCount = 0
        var totalSize = 0L

        fun copyTree(doc: DocumentFile, destDir: File) {
            doc.listFiles().forEach { child ->
                if (child.isDirectory) {
                    val subDir = File(destDir, child.name ?: "dir").apply { mkdirs() }
                    copyTree(child, subDir)
                } else if (child.isFile) {
                    val destFile = File(destDir, child.name ?: "file")
                    try {
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            FileOutputStream(destFile).use { output -> input.copyTo(output) }
                        }
                        fileCount++
                        totalSize += destFile.length()
                    } catch (_: Exception) { }
                }
            }
        }

        copyTree(treeDoc, stagingDir)

        _uiState.update {
            it.copy(
                selectedFolderName = folderName,
                selectedFolderFileCount = fileCount,
                selectedFolderSize = totalSize,
                selectedFolderPath = stagingDir.absolutePath
            )
        }
    }

    fun clearFolder() {
        val path = _uiState.value.selectedFolderPath
        if (path != null) {
            File(path).deleteRecursively()
        }
        _uiState.update {
            it.copy(
                selectedFolderName = null,
                selectedFolderFileCount = 0,
                selectedFolderSize = 0,
                selectedFolderPath = null
            )
        }
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

    fun setSendMode(mode: SendMode) {
        _uiState.update { it.copy(sendMode = mode) }
    }

    fun toggleTextMode() {
        _uiState.update {
            it.copy(sendMode = if (it.sendMode == SendMode.TEXT) SendMode.FILES else SendMode.TEXT)
        }
    }

    fun updateTextToSend(text: String) {
        _uiState.update { it.copy(textToSend = text) }
    }

    fun setSharedText(text: String) {
        _uiState.update { it.copy(sendMode = SendMode.TEXT, textToSend = text) }
    }

    fun startSend() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!state.hasContent) return@launch

            // Always reset before starting a new transfer
            crocProcess.reset()
            // Give state a moment to settle
            kotlinx.coroutines.delay(50)

            when (state.sendMode) {
                SendMode.TEXT -> {
                    if (state.textToSend.isNotBlank()) {
                        crocProcess.sendText(state.textToSend, state.codePhrase)
                    }
                }
                SendMode.FILES -> {
                    if (state.selectedFiles.isNotEmpty()) {
                        val filePaths = copyFilesToInternal(state.selectedFiles)
                        crocProcess.send(filePaths, state.codePhrase)
                    }
                }
                SendMode.FOLDER -> {
                    val folderPath = state.selectedFolderPath
                    if (folderPath != null) {
                        crocProcess.send(listOf(folderPath), state.codePhrase)
                    }
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
        val path = _uiState.value.selectedFolderPath
        if (path != null) {
            File(path).deleteRecursively()
        }
        _uiState.update {
            it.copy(
                selectedFiles = emptyList(),
                selectedBytes = 0,
                codePhrase = it.defaultCodePhrase.ifBlank { generateRandomCode() },
                textToSend = "",
                selectedFolderName = null,
                selectedFolderFileCount = 0,
                selectedFolderSize = 0,
                selectedFolderPath = null
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
        val uiState = _uiState.value

        when (uiState.sendMode) {
            SendMode.FILES -> {
                val files = uiState.selectedFiles
                if (files.isNotEmpty()) {
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
                    // Fallback: use parser data
                    app.database.transferHistoryDao().insert(
                        TransferHistory(
                            code = code,
                            type = TransferType.SEND,
                            fileName = state.fileNames.firstOrNull() ?: "file",
                            fileSize = state.totalBytes,
                            status = TransferStatus.COMPLETED
                        )
                    )
                }
            }
            SendMode.FOLDER -> {
                app.database.transferHistoryDao().insert(
                    TransferHistory(
                        code = code,
                        type = TransferType.SEND,
                        fileName = uiState.selectedFolderName ?: "folder",
                        fileSize = uiState.selectedFolderSize,
                        status = TransferStatus.COMPLETED
                    )
                )
            }
            SendMode.TEXT -> {
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

