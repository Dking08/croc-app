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
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Executes croc CLI commands and parses output for transfer progress.
 *
 * croc v10.4.2 global flags:
 *   --yes, --relay, --pass, --curve, --overwrite,
 *   --no-compress, --local, --throttleUpload, --internal-dns,
 *   --classic, --multicast, --ip, --relay6, --out, --quiet
 *
 * send-specific flags we also use on Android:
 *   --no-local, --no-multi, --ignore-stdin
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
        val outputTail: List<String>,
        val peerIp: String = "",
        val totalFileCount: Int = 0
    )

    private val homeDir: File
        get() = File(context.filesDir, "croc-home").also { it.mkdirs() }

    private val tmpDir: File
        get() = File(context.cacheDir, "croc-tmp").also { it.mkdirs() }

    private fun setupEnv(pb: ProcessBuilder, extraEnv: Map<String, String> = emptyMap()) {
        pb.environment()["HOME"] = homeDir.absolutePath
        pb.environment()["TMPDIR"] = tmpDir.absolutePath
        extraEnv.forEach { (key, value) ->
            pb.environment()[key] = value
        }
    }

    private fun secretEnv(code: String?): Map<String, String> {
        return if (code.isNullOrBlank()) emptyMap() else mapOf("CROC_SECRET" to code)
    }

    /**
     * Build common global flags from preferences.
     * Only includes flags that actually exist in croc v10.4.2.
     */
    private fun buildGlobalFlags(prefs: UserPreferencesRepository.CrocPreferences): List<String> {
        val relayAddress = resolveRelayAddress(prefs.relayAddress)

        return buildList {
            if (prefs.useInternalDns) add("--internal-dns")

            if (relayAddress.isNotBlank()) {
                add("--relay"); add(relayAddress)
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

    private fun resolveRelayAddress(relayAddress: String): String {
        if (relayAddress.isBlank()) return relayAddress

        val parsed = parseRelayHostPort(relayAddress) ?: return relayAddress
        val (host, port) = parsed
        if (isIpLiteral(host)) return relayAddress

        return try {
            val resolved = InetAddress.getAllByName(host)
                .sortedBy { if (it is Inet4Address) 0 else 1 }
                .firstOrNull()
                ?: return relayAddress

            val ip = when (resolved) {
                is Inet6Address -> "[${resolved.hostAddress}]"
                else -> resolved.hostAddress
            }
            val resolvedAddress = "$ip:$port"
            Log.i(TAG, "Resolved relay '$relayAddress' to '$resolvedAddress'")
            resolvedAddress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve relay '$relayAddress', using original", e)
            relayAddress
        }
    }

    private fun parseRelayHostPort(relayAddress: String): Pair<String, Int>? {
        return try {
            val uri = URI("relay://$relayAddress")
            if (uri.host.isNullOrBlank() || uri.port == -1) null else uri.host to uri.port
        } catch (_: Exception) {
            null
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        return host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || ":" in host
    }
    
    suspend fun send(filePaths: List<String>, code: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()
                val binaryPath = binaryManager.getBinaryPath()

                val command = mutableListOf(binaryPath, "--yes").apply {
                    addAll(buildGlobalFlags(prefs))
                    add("--ignore-stdin")
                    add("send")
                    add("--no-local")
                    add("--no-multi")
                    addAll(filePaths)
                }
                val workDir = File(filePaths.first()).parentFile ?: homeDir

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = workDir,
                    waitingState = CrocTransferState.WaitingForPeer(code ?: "generating..."),
                    extraEnv = secretEnv(code),
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
                    add("--ignore-stdin")
                    add("send")
                    add("--no-local")
                    add("--no-multi")
                    add("--text"); add(text)
                }

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = homeDir,
                    waitingState = CrocTransferState.WaitingForPeer(code ?: "generating..."),
                    extraEnv = secretEnv(code),
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
                }

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = outputDir,
                    waitingState = CrocTransferState.WaitingForPeer(code),
                    extraEnv = secretEnv(code),
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
        extraEnv: Map<String, String>,
        prefs: UserPreferencesRepository.CrocPreferences,
        opName: String
    ) {
        Log.d(TAG, "$opName command: ${redactCommandForLog(baseCommand)}")

        var result = runCommand(baseCommand, workDir, waitingState, extraEnv)

        if (shouldRetryWithInternalDns(result, prefs, baseCommand)) {
            val retryCommand = baseCommand.toMutableList()
            addInternalDnsFlag(retryCommand)
            Log.w(TAG, "$opName retry with --internal-dns: ${redactCommandForLog(retryCommand)}")
            result = runCommand(retryCommand, workDir, waitingState, extraEnv)
        }

        // Check cancelled state FIRST — cancel() may have been called while parseOutput was running.
        // Without this guard, a force-killed process can exit with code 0 and be mis-reported as Completed.
        if (_state.value is CrocTransferState.Cancelled) {
            return // keep the Cancelled state intact
        }

        if (isSuccessfulTransfer(result)) {
            _state.value = CrocTransferState.Completed(
                fileNames = result.fileNames,
                totalBytes = result.totalBytes,
                peerIp = result.peerIp,
                totalFileCount = result.totalFileCount.coerceAtLeast(result.fileNames.size)
            )
        } else {
            _state.value = CrocTransferState.Error(errorMessageFor(result))
        }
    }

    private suspend fun runCommand(
        command: List<String>,
        workDir: File,
        waitingState: CrocTransferState,
        extraEnv: Map<String, String>
    ): ProcessResult {
        val pb = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        setupEnv(pb, extraEnv)

        currentProcess = pb.start()
        _state.value = waitingState
        return parseOutput(currentProcess!!)
    }

    private fun redactCommandForLog(command: List<String>): String {
        val redacted = command.toMutableList()
        var i = 0
        while (i < redacted.size) {
            if ((redacted[i] == "--pass" || redacted[i] == "--code") && i + 1 < redacted.size) {
                redacted[i + 1] = "****"
                i++
            }
            i++
        }
        return redacted.joinToString(" ")
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
        if (hasCliUsageExit(result)) {
            return "Transfer failed: croc rejected the command syntax and printed usage help."
        }
        if (result.outputTail.any { "no files transferred" in it.lowercase() }) {
            return "Transfer failed: no files were transferred."
        }
        if (result.exitCode == 0) {
            return "Transfer failed: croc exited without starting a file transfer."
        }

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

    private fun hasCliUsageExit(result: ProcessResult): Boolean {
        return result.outputTail.any {
            val line = it.lowercase()
            "on unix systems, to receive with croc you either need" in line ||
                    "on unix systems, to send with a custom code phrase" in line
        }
    }

    private fun isSuccessfulTransfer(result: ProcessResult): Boolean {
        if (result.exitCode != 0 || hasCliUsageExit(result)) return false
        if (result.fileNames.isNotEmpty() || result.totalBytes > 0L) return true
        // If we captured a peer IP, the transfer happened
        if (result.peerIp.isNotBlank()) return true

        return result.outputTail.any {
            val line = it.lowercase()
            "sending '" in line || "receiving '" in line ||
                    "sending (" in line || "receiving (" in line
        }
    }

    private fun waitForExitCode(process: Process, timeoutMs: Long = 2_000): Int {
        return try {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                val exitCode = process.exitValue()
                Log.i(TAG, "croc exited: $exitCode")
                exitCode
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    private suspend fun parseOutput(process: Process): ProcessResult {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val fileNames = mutableListOf<String>()
        var totalBytes = 0L
        var currentFileName = ""
        var peerIp = ""
        var totalFilesFromProgress = 0
        val outputTail = ArrayDeque<String>()

        // Regex patterns for the v10.4.2 output format
        // Matches: "Sending (->1.2.3.4:9009)" or "Receiving (<-1.2.3.4:9009)"
        val peerIpRegex = Regex("""(?:->|<-)(\d+\.\d+\.\d+\.\d+)""")
        // Matches progress lines: "filename... 42% |...| (size) N/M" or "file.txt 42% |...| (size)"
        // Filename may or may not be truncated with "..."
        val progressLineRegex = Regex("""^\s*(.+?)\s+(\d+)%\s*\|.*\|\s*\((.+?)\)\s*(?:(\d+)/(\d+))?""")
        // Matches size: "(42/100 kB" or "(85/85 kB, 6.1 MB/s)"
        val sizeInProgressRegex = Regex("""(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)\s*(\w+)""")
        // Matches old format: Sending 'filename' (100 kB)
        val oldSendingRegex = Regex("""'([^']+)'""")
        val oldSizeRegex = Regex("""\((\d+(?:\.\d+)?)\s*(\w+)\)""")

        // Track per-file sizes to compute total
        val fileSizeMap = mutableMapOf<String, Long>()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null && coroutineContext.isActive) {
                val l = line ?: continue
                Log.d(TAG, "croc> $l")
                outputTail.addLast(l)
                if (outputTail.size > 50) outputTail.removeFirst()

                // Skip blank / whitespace-only lines
                if (l.isBlank()) continue

                // Code announcement
                if (l.contains("Code is:")) {
                    val code = l.substringAfter("Code is:").trim()
                    _state.value = CrocTransferState.WaitingForPeer(code)
                    continue
                }

                // Peer connection line: "Sending (->IP:PORT)" or "Receiving (<-IP:PORT)"
                if (l.contains("Sending") || l.contains("Receiving")) {
                    peerIpRegex.find(l)?.let { match ->
                        peerIp = match.groupValues[1]
                    }
                    // Old format: Sending 'filename' (100 kB)
                    oldSendingRegex.find(l)?.let { match ->
                        currentFileName = match.groupValues[1]
                        if (currentFileName !in fileNames) fileNames.add(currentFileName)
                    }
                    oldSizeRegex.find(l)?.let { match ->
                        val num = match.groupValues[1].toDoubleOrNull() ?: 0.0
                        val unit = match.groupValues[2]
                        totalBytes = parseSize(num, unit)
                    }
                    continue
                }

                // Progress line: "filename... 42% |████   | (42/100 kB, 1.2 MB/s) 1/3"
                val progressMatch = progressLineRegex.find(l)
                if (progressMatch != null) {
                    val match = progressMatch
                    val truncatedName = match.groupValues[1].trim()
                    val percent = match.groupValues[2].toIntOrNull() ?: 0
                    val sizeSection = match.groupValues[3]
                    val currentFileNum = match.groupValues[4].toIntOrNull()
                    val totalFileNum = match.groupValues[5].toIntOrNull()

                    // Update filename (use truncated name as display)
                    if (truncatedName.isNotBlank()) {
                        currentFileName = truncatedName
                    }

                    // Parse per-file size from "(current/total unit)"
                    sizeInProgressRegex.find(sizeSection)?.let { sizeMatch ->
                        val fileTotal = sizeMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                        val unit = sizeMatch.groupValues[3]
                        val fileTotalBytes = parseSize(fileTotal, unit)
                        fileSizeMap[currentFileName] = fileTotalBytes
                    }

                    // Update file count from N/M suffix
                    if (totalFileNum != null && totalFileNum > 0) {
                        totalFilesFromProgress = totalFileNum
                    }

                    // Track filenames from progress lines (100% = file done)
                    if (percent == 100 && currentFileName.isNotBlank()) {
                        if (currentFileName !in fileNames) {
                            fileNames.add(currentFileName)
                        }
                    }

                    // Compute cumulative total bytes from all known file sizes
                    val cumulativeTotal = fileSizeMap.values.sum()
                    if (cumulativeTotal > 0) {
                        totalBytes = cumulativeTotal
                    }

                    // Compute bytes transferred:
                    // sum of completed files + current file progress
                    val completedBytes = fileNames.filter { it != currentFileName }
                        .sumOf { fileSizeMap[it] ?: 0L }
                    val currentFileSize = fileSizeMap[currentFileName] ?: 0L
                    val currentFileTransferred = (currentFileSize * percent / 100)
                    val bytesTransferred = completedBytes + currentFileTransferred

                    val effectiveTotalFiles = totalFilesFromProgress.coerceAtLeast(fileNames.size).coerceAtLeast(1)
                    val effectiveCurrentFile = if (currentFileNum != null) currentFileNum else fileNames.indexOf(currentFileName) + 1

                    _state.value = CrocTransferState.Transferring(
                        fileName = currentFileName,
                        currentFile = effectiveCurrentFile.coerceAtLeast(1),
                        totalFiles = effectiveTotalFiles,
                        currentFilePercent = percent,
                        bytesTransferred = bytesTransferred.coerceAtMost(totalBytes.coerceAtLeast(1)),
                        totalBytes = totalBytes.coerceAtLeast(1),
                        peerIp = peerIp
                    )
                    continue
                }

                // Fallback: simple percent match for lines we didn't parse above
                Regex("(\\d+)%").find(l)?.let { match ->
                    val percent = match.groupValues[1].toIntOrNull() ?: 0
                    _state.value = CrocTransferState.Transferring(
                        fileName = currentFileName,
                        currentFile = fileNames.indexOf(currentFileName).coerceAtLeast(0) + 1,
                        totalFiles = totalFilesFromProgress.coerceAtLeast(fileNames.size).coerceAtLeast(1),
                        currentFilePercent = percent,
                        bytesTransferred = totalBytes * percent / 100,
                        totalBytes = totalBytes.coerceAtLeast(1),
                        peerIp = peerIp
                    )
                }
            }

            val exitCode = waitForExitCode(process)
            return ProcessResult(
                exitCode = exitCode,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = outputTail.toList(),
                peerIp = peerIp,
                totalFileCount = totalFilesFromProgress
            )
        } catch (e: InterruptedIOException) {
            val exitCode = waitForExitCode(process)
            if (_state.value is CrocTransferState.Cancelled || !coroutineContext.isActive) {
                Log.i(TAG, "croc output interrupted during cancellation")
            } else {
                Log.w(TAG, "croc output stream interrupted; using process exit state", e)
            }
            return ProcessResult(
                exitCode = exitCode,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = if (outputTail.isEmpty()) {
                    listOf(e.message ?: "Stream interrupted")
                } else {
                    outputTail.toList()
                },
                peerIp = peerIp,
                totalFileCount = totalFilesFromProgress
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
            return ProcessResult(
                exitCode = -1,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = listOf(e.message ?: "Unknown error"),
                peerIp = peerIp,
                totalFileCount = totalFilesFromProgress
            )
        }
    }

    private fun parseSize(num: Double, unit: String): Long {
        return when (unit.lowercase()) {
            "b" -> num.toLong()
            "kb" -> (num * 1024).toLong()
            "mb" -> (num * 1024 * 1024).toLong()
            "gb" -> (num * 1024 * 1024 * 1024).toLong()
            else -> num.toLong()
        }
    }
}
