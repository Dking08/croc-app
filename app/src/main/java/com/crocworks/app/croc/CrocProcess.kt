package com.crocworks.app.croc

import android.content.Context
import android.util.Log
import com.crocworks.app.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

/**
 * Executes croc CLI commands and parses output for transfer progress.
 *
 * croc v10.4.2 global flags:
 *   --yes, --relay, --pass, --curve, --overwrite,
 *   --no-compress, --local, --throttleUpload, --internal-dns,
 *   --classic, --multicast, --ip, --relay6, --out, --quiet
 */
class CrocProcess(
    private val context: Context,
    private val binaryManager: CrocBinaryManager,
    private val prefsRepository: UserPreferencesRepository
) {
    companion object {
        private const val TAG = "CrocProcess"
    }

    private val _state = MutableStateFlow<CrocTransferState>(CrocTransferState.Idle)
    val state: StateFlow<CrocTransferState> = _state.asStateFlow()

    private var currentProcess: Process? = null

    private data class ProcessResult(
        val exitCode: Int,
        val fileNames: List<String>,
        val totalBytes: Long,
        val outputTail: List<String>
    )

    private val homeDir: File
        get() = File(context.filesDir, "croc-home").also { it.mkdirs() }

    private val tmpDir: File
        get() = File(context.cacheDir, "croc-tmp").also { it.mkdirs() }

    private fun setupEnv(pb: ProcessBuilder) {
        pb.environment()["HOME"] = homeDir.absolutePath
        pb.environment()["TMPDIR"] = tmpDir.absolutePath
    }

    /**
     * Build common global flags from preferences.
     * Only includes flags that actually exist in croc v10.4.2.
     */
    private fun buildGlobalFlags(prefs: UserPreferencesRepository.CrocPreferences): List<String> {
        return buildList {
            if (prefs.useInternalDns) add("--internal-dns")

            if (prefs.relayAddress.isNotBlank()) {
                add("--relay"); add(prefs.relayAddress)
            }
            if (prefs.relayPassword.isNotBlank()) {
                add("--pass"); add(prefs.relayPassword)
            }
            if (prefs.pakeCurve.isNotBlank()) {
                add("--curve"); add(prefs.pakeCurve)
            }
            if (prefs.forceLocal) add("--local")
            if (prefs.disableCompression) add("--no-compress")
            if (prefs.uploadThrottle.isNotBlank()) {
                add("--throttleUpload"); add(prefs.uploadThrottle)
            }
            if (prefs.multicastAddress.isNotBlank() && prefs.multicastAddress != "239.255.255.250") {
                add("--multicast"); add(prefs.multicastAddress)
            }
        }
    }
    
    suspend fun send(filePaths: List<String>, code: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()
                val binaryPath = binaryManager.getBinaryPath()

                val command = mutableListOf(binaryPath, "--yes").apply {
                    addAll(buildGlobalFlags(prefs))
                    add("send")
                    if (!code.isNullOrBlank()) {
                        add("--code"); add(code)
                    }
                    addAll(filePaths)
                }
                val workDir = File(filePaths.first()).parentFile ?: homeDir

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = workDir,
                    waitingState = CrocTransferState.WaitingForPeer(code ?: "generating..."),
                    prefs = prefs,
                    opName = "Send"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun sendText(text: String, code: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()
                val binaryPath = binaryManager.getBinaryPath()

                val command = mutableListOf(binaryPath, "--yes").apply {
                    addAll(buildGlobalFlags(prefs))
                    add("send")
                    add("--text"); add(text)
                    if (!code.isNullOrBlank()) {
                        add("--code"); add(code)
                    }
                }

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = homeDir,
                    waitingState = CrocTransferState.WaitingForPeer(code ?: "generating..."),
                    prefs = prefs,
                    opName = "SendText"
                )
            } catch (e: Exception) {
                Log.e(TAG, "SendText failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun receive(code: String, outputDir: File) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()
                val binaryPath = binaryManager.getBinaryPath()
                outputDir.mkdirs()

                val command = mutableListOf(binaryPath, "--yes", "--overwrite").apply {
                    addAll(buildGlobalFlags(prefs))
                    add(code)
                }

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = outputDir,
                    waitingState = CrocTransferState.WaitingForPeer(code),
                    prefs = prefs,
                    opName = "Receive"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Receive failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun cancel() {
        currentProcess?.let { try { it.destroyForcibly() } catch (_: Exception) {} }
        currentProcess = null
        _state.value = CrocTransferState.Cancelled
    }

    fun reset() {
        cancel()
        _state.value = CrocTransferState.Idle
    }

    private suspend fun executeWithDnsFallback(
        baseCommand: MutableList<String>,
        workDir: File,
        waitingState: CrocTransferState,
        prefs: UserPreferencesRepository.CrocPreferences,
        opName: String
    ) {
        Log.d(TAG, "$opName command: ${baseCommand.joinToString(" ")}")

        var result = runCommand(baseCommand, workDir, waitingState)

        if (shouldRetryWithInternalDns(result, prefs, baseCommand)) {
            val retryCommand = baseCommand.toMutableList()
            addInternalDnsFlag(retryCommand)
            Log.w(TAG, "$opName retry with --internal-dns: ${retryCommand.joinToString(" ")}")
            result = runCommand(retryCommand, workDir, waitingState)
        }

        if (result.exitCode == 0) {
            _state.value = CrocTransferState.Completed(result.fileNames, result.totalBytes)
        } else if (_state.value !is CrocTransferState.Cancelled) {
            _state.value = CrocTransferState.Error(errorMessageFor(result))
        }
    }

    private suspend fun runCommand(
        command: List<String>,
        workDir: File,
        waitingState: CrocTransferState
    ): ProcessResult {
        val pb = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        setupEnv(pb)

        currentProcess = pb.start()
        _state.value = waitingState
        return parseOutput(currentProcess!!)
    }

    private fun shouldRetryWithInternalDns(
        result: ProcessResult,
        prefs: UserPreferencesRepository.CrocPreferences,
        command: List<String>
    ): Boolean {
        if (result.exitCode == 0) return false
        if (prefs.useInternalDns) return false
        if (command.contains("--internal-dns")) return false

        return result.outputTail.any {
            val line = it.lowercase()
            ("lookup" in line && "[::1]:53" in line) ||
                    "no such host" in line ||
                    "server misbehaving" in line
        }
    }

    private fun addInternalDnsFlag(command: MutableList<String>) {
        if (command.contains("--internal-dns")) return
        val index = if (command.size > 1) 2 else 1
        command.add(index, "--internal-dns")
    }

    private fun errorMessageFor(result: ProcessResult): String {
        val usefulLine = result.outputTail
            .asReversed()
            .firstOrNull { it.isNotBlank() }
            ?.trim()

        return if (usefulLine.isNullOrBlank()) {
            "Transfer failed (exit code ${result.exitCode})"
        } else {
            "Transfer failed: $usefulLine"
        }
    }

    private suspend fun parseOutput(process: Process): ProcessResult {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val fileNames = mutableListOf<String>()
        var totalBytes = 0L
        var currentFileName = ""
        val outputTail = ArrayDeque<String>()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null && coroutineContext.isActive) {
                val l = line ?: continue
                Log.d(TAG, "croc> $l")
                outputTail.addLast(l)
                if (outputTail.size > 50) outputTail.removeFirst()

                if (l.contains("Code is:")) {
                    val code = l.substringAfter("Code is:").trim()
                    _state.value = CrocTransferState.WaitingForPeer(code)
                    continue
                }

                if (l.contains("Sending") || l.contains("Receiving")) {
                    Regex("'([^']+)'").find(l)?.let { match ->
                        currentFileName = match.groupValues[1]
                        if (currentFileName !in fileNames) fileNames.add(currentFileName)
                    }
                    Regex("\\(([\\d.]+)\\s*(\\w+)\\)").find(l)?.let { match ->
                        val num = match.groupValues[1].toDoubleOrNull() ?: 0.0
                        val unit = match.groupValues[2]
                        totalBytes = when (unit.lowercase()) {
                            "b" -> num.toLong()
                            "kb" -> (num * 1024).toLong()
                            "mb" -> (num * 1024 * 1024).toLong()
                            "gb" -> (num * 1024 * 1024 * 1024).toLong()
                            else -> num.toLong()
                        }
                    }
                    continue
                }

                Regex("(\\d+)%").find(l)?.let { match ->
                    val percent = match.groupValues[1].toIntOrNull() ?: 0
                    _state.value = CrocTransferState.Transferring(
                        fileName = currentFileName,
                        currentFile = fileNames.indexOf(currentFileName) + 1,
                        totalFiles = fileNames.size.coerceAtLeast(1),
                        bytesTransferred = totalBytes * percent / 100,
                        totalBytes = totalBytes
                    )
                }
            }

            val exitCode = process.waitFor()
            Log.i(TAG, "croc exited: $exitCode")
            return ProcessResult(
                exitCode = exitCode,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = outputTail.toList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
            return ProcessResult(
                exitCode = -1,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = outputTail.toList() + listOf(e.message ?: "Unknown error")
            )
        } finally {
            currentProcess = null
        }
    }
}
