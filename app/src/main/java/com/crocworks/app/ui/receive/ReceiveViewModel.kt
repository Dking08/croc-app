package com.crocworks.app.ui.receive

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
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
import java.net.URLConnection

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
    val receivedFiles: List<ReceivedFile> = emptyList()
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
                    // Scan the output directory for actual files, since parser
                    // fileNames may be truncated (e.g. "4200-funn...")
                    val receivedFiles = publishReceivedFiles()
                    _uiState.update { it.copy(receivedFiles = receivedFiles) }
                    // Use actual file names for history, not truncated parser names
                    val actualNames = receivedFiles.map { it.name }
                    saveToHistory(
                        state.copy(
                            fileNames = actualNames,
                            totalFileCount = receivedFiles.size
                        )
                    )
                }
            }
        }

        viewModelScope.launch {
            prefsRepo.preferencesFlow.collect { prefs ->
                _uiState.update { state ->
                    state.copy(
                        codePhrase = if (state.codePhrase.isBlank()) prefs.defaultCodePhrase else state.codePhrase,
                        defaultCodePhrase = prefs.defaultCodePhrase,
                        savedCodePhrases = prefs.savedCodePhrases
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
                receivedFiles = emptyList()
            )
        }
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

    /**
     * Scan the output directory for all files and publish them.
     * We don't rely on [fileNames] from the parser because croc truncates
     * long names in its progress output (e.g. "4200-funn...").
     */
    private fun publishReceivedFiles(): List<ReceivedFile> {
        val outputDir = currentOutputDir ?: return emptyList()
        val files = outputDir.listFiles()?.filter { it.isFile } ?: return emptyList()

        return files.mapNotNull { source ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(source)
            } else {
                publishToPublicDownloads(source)
            }
        }
    }

    private fun publishToMediaStore(source: File): ReceivedFile? {
        val resolver = getApplication<Application>().contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/croc-received"
        val mimeType = URLConnection.guessContentTypeFromName(source.name) ?: "application/octet-stream"

        val existingUri = findExistingDownloadUri(source.name, relativePath)
        if (existingUri != null) {
            resolver.delete(existingUri, null, null)
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return null

            val readyValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, readyValues, null, null)
            ReceivedFile(
                name = source.name,
                savedLocation = "Downloads/croc-received/${source.name}",
                uri = uri,
                mimeType = mimeType
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun findExistingDownloadUri(displayName: String, relativePath: String): android.net.Uri? {
        val resolver = getApplication<Application>().contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(displayName, "$relativePath/")

        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return android.net.Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }

    private fun publishToPublicDownloads(source: File): ReceivedFile? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "croc-received").apply { mkdirs() }
        val target = File(targetDir, source.name)

        return try {
            source.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(
                getApplication(),
                arrayOf(target.absolutePath),
                null,
                null
            )
            ReceivedFile(
                name = target.name,
                savedLocation = target.absolutePath,
                uri = FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    target
                ),
                mimeType = URLConnection.guessContentTypeFromName(target.name) ?: "application/octet-stream"
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeCodePhrase(code: String): String {
        return code.trim().replace(" ", "-")
    }
}
