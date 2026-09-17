package com.dking.crocapp.croc

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.dking.crocapp.data.preferences.UserPreferencesRepository
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
 * croc v11.5.2 global flags:
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
        val totalFileCount: Int = 0,
        val receivedText: String? = null,
        val isLegacyFallback: Boolean = false,
        val announcedCode: String = "",
        val isStoreTransfer: Boolean = false,
        val storeBrowserLink: String = "",
        val storeCliToken: String = "",
        val storeId: String = "",
        val storeExpiresAt: Long = 0L,
        val storeRawExpiration: String = "",
        val storeDownloadsLimit: Int = 1
    )

    private val homeDir: File
        get() = File(context.filesDir, "croc-home").also { it.mkdirs() }

    private val tmpDir: File
        get() = File(context.cacheDir, "croc-tmp").also { it.mkdirs() }

    fun isStoredToken(code: String?): Boolean {
        if (code == null) return false
        val trimmed = code.trim()
        return trimmed.startsWith("croc-store-v1.") ||
                ((trimmed.startsWith("http://") || trimmed.startsWith("https://")) && trimmed.contains("/s/"))
    }

    private fun secretEnv(code: String?): Map<String, String> {
        if (code.isNullOrBlank()) return emptyMap()
        val trimmed = code.trim()
        return if (isStoredToken(trimmed)) {
            mapOf(
                "CROC_STORE_TOKEN" to trimmed,
                "CROC_SECRET" to trimmed
            )
        } else {
            mapOf("CROC_SECRET" to trimmed)
        }
    }

    /**
     * Build common global flags from preferences.
     * Only includes flags that actually exist in croc v11.5.2 and v10.6.0.
     */
    private fun buildGlobalFlags(prefs: UserPreferencesRepository.CrocPreferences): List<String> {
        val relayAddress = resolveRelayAddress(prefs.relayAddress)

        return buildList {
            if (prefs.useInternalDns) add("--internal-dns")

            if (relayAddress.isNotBlank()) {
                add("--relay"); add(relayAddress)
            }
            if (prefs.relay6Address.isNotBlank()) {
                add("--relay6"); add(prefs.relay6Address)
            } else {
                add("--relay6"); add("")
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
            if (prefs.socks5Proxy.isNotBlank()) {
                add("--socks5"); add(prefs.socks5Proxy)
            }
            if (prefs.httpProxy.isNotBlank()) {
                add("--connect"); add(prefs.httpProxy)
            }
            if (prefs.senderIp.isNotBlank()) {
                add("--ip"); add(prefs.senderIp)
            }
        }
    }

    private fun getSystemDnsServers(): List<String> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptyList()
            val activeNetwork = cm.activeNetwork ?: return emptyList()
            val linkProps = cm.getLinkProperties(activeNetwork) ?: return emptyList()
            linkProps.dnsServers.mapNotNull { it.hostAddress }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get system DNS servers", e)
            emptyList()
        }
    }

    private fun resolveRelayAddress(relayAddress: String): String {
        if (relayAddress.isBlank()) return relayAddress

        val parsed = parseRelayHostPort(relayAddress) ?: return relayAddress
        val (host, port) = parsed
        if (isIpLiteral(host)) return relayAddress

        return try {
            val resolved = InetAddress.getAllByName(host)
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
    
    suspend fun send(filePaths: List<String>, code: String? = null, engine: CrocEngine = CrocEngine.CURRENT) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()
                val binaryPath = binaryManager.getBinaryPath(engine)

                val command = mutableListOf(binaryPath, "--yes").apply {
                    addAll(buildGlobalFlags(prefs))
                    add("--ignore-stdin")
                    add("send")
                    // Only disable local relay if not forcing LAN mode
                    if (!prefs.forceLocal) {
                        add("--no-local")
                    }
                    if (engine == CrocEngine.CURRENT && !prefs.forceLocal && prefs.transferTransport.isNotBlank() && prefs.transferTransport != "auto") {
                        add("--transport"); add(prefs.transferTransport)
                    }
                    // Multiplexing & transfer streams
                    if (prefs.disableMultiplexing) {
                        add("--no-multi")
                    } else if (prefs.transferPorts.isNotBlank() && prefs.transferPorts != "4") {
                        add("--transfers"); add(prefs.transferPorts)
                    }
                    // Hash algorithm
                    if (prefs.hashAlgorithm.isNotBlank() && prefs.hashAlgorithm != "xxhash") {
                        add("--hash"); add(prefs.hashAlgorithm)
                    }
                    // Zip folder before sending
                    if (prefs.zipFolderBeforeSend) {
                        add("--zip")
                    }
                    addAll(filePaths)
                }
                val workDir = File(filePaths.first()).parentFile ?: homeDir

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = workDir,
                    waitingState = CrocTransferState.WaitingForPeer(code ?: "generating..."),
                    extraEnv = secretEnv(code),
                    prefs = prefs,
                    opName = "Send",
                    code = code,
                    engine = engine,
                    initialFileNames = filePaths.map { File(it).name }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun sendText(text: String, code: String? = null, engine: CrocEngine = CrocEngine.CURRENT) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()
                val binaryPath = binaryManager.getBinaryPath(engine)

                val command = mutableListOf(binaryPath, "--yes").apply {
                    addAll(buildGlobalFlags(prefs))
                    add("--ignore-stdin")
                    add("send")
                    if (!prefs.forceLocal) {
                        add("--no-local")
                    }
                    if (engine == CrocEngine.CURRENT && !prefs.forceLocal && prefs.transferTransport.isNotBlank() && prefs.transferTransport != "auto") {
                        add("--transport"); add(prefs.transferTransport)
                    }
                    if (prefs.disableMultiplexing) {
                        add("--no-multi")
                    } else if (prefs.transferPorts.isNotBlank() && prefs.transferPorts != "4") {
                        add("--transfers"); add(prefs.transferPorts)
                    }
                    if (prefs.hashAlgorithm.isNotBlank() && prefs.hashAlgorithm != "xxhash") {
                        add("--hash"); add(prefs.hashAlgorithm)
                    }
                    add("--text"); add(text)
                }

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = homeDir,
                    waitingState = CrocTransferState.WaitingForPeer(code ?: "generating..."),
                    extraEnv = secretEnv(code),
                    prefs = prefs,
                    opName = "SendText",
                    code = code,
                    engine = engine,
                    initialFileNames = listOf("text")
                )
            } catch (e: Exception) {
                Log.e(TAG, "SendText failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun receive(code: String, outputDir: File, engine: CrocEngine = CrocEngine.CURRENT) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()
                val isStore = isStoredToken(code)
                val effectiveEngine = if (isStore) CrocEngine.CURRENT else engine
                val binaryPath = binaryManager.getBinaryPath(effectiveEngine)
                outputDir.mkdirs()

                val conflictFlag = if (prefs.receiveConflictStrategy == "rename") "--rename" else "--overwrite"
                val command = mutableListOf(binaryPath, "--yes", "--ignore-stdin", conflictFlag).apply {
                    if (prefs.useInternalDns) add("--internal-dns")
                    if (prefs.socks5Proxy.isNotBlank()) {
                        add("--socks5"); add(prefs.socks5Proxy)
                    }
                    if (prefs.httpProxy.isNotBlank()) {
                        add("--connect"); add(prefs.httpProxy)
                    }
                    if (!isStore) {
                        if (prefs.relayAddress.isNotBlank()) {
                            add("--relay"); add(resolveRelayAddress(prefs.relayAddress))
                        }
                        if (prefs.relay6Address.isNotBlank()) {
                            add("--relay6"); add(prefs.relay6Address)
                        } else {
                            add("--relay6"); add("")
                        }
                        if (prefs.relayPassword.isNotBlank()) {
                            add("--pass"); add(prefs.relayPassword)
                        }
                        if (prefs.pakeCurve.isNotBlank()) {
                            add("--curve"); add(prefs.pakeCurve)
                        }
                        if (prefs.forceLocal) add("--local")
                        if (prefs.disableCompression) add("--no-compress")
                        if (prefs.multicastAddress.isNotBlank() && prefs.multicastAddress != "239.255.255.250") {
                            add("--multicast"); add(prefs.multicastAddress)
                        }
                        if (prefs.senderIp.isNotBlank()) {
                            add("--ip"); add(prefs.senderIp)
                        }
                    }
                }

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = outputDir,
                    waitingState = CrocTransferState.WaitingForPeer(if (isStore) "Connecting to secure store..." else code),
                    extraEnv = secretEnv(code),
                    prefs = prefs,
                    opName = if (isStore) "ReceiveStore" else "Receive",
                    code = code,
                    engine = effectiveEngine
                )
            } catch (e: Exception) {
                Log.e(TAG, "Receive failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private var lastStoreExpiration: String = "1d"
    private var lastStoreDownloads: Int = 1

    private fun parseExpirationDurationMillis(exp: String): Long {
        val trimmed = exp.trim().lowercase()
        val value = trimmed.dropLast(1).toLongOrNull() ?: return 86_400_000L
        return when (trimmed.takeLast(1)) {
            "m" -> value * 60_000L
            "h" -> value * 3_600_000L
            "d" -> value * 86_400_000L
            "w" -> value * 7 * 86_400_000L
            else -> 86_400_000L
        }
    }

    suspend fun sendStore(
        filePaths: List<String>,
        expiration: String = "1d",
        downloads: Int = 1,
        customStoreUrl: String? = null
    ) {
        lastStoreExpiration = expiration
        lastStoreDownloads = downloads
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()
                // Store is only supported on CURRENT engine (croc v11+)
                val binaryPath = binaryManager.getBinaryPath(CrocEngine.CURRENT)

                val effectiveStoreUrl = customStoreUrl?.takeIf { it.isNotBlank() }
                    ?: prefs.customStoreUrl.takeIf { it.isNotBlank() }

                val command = mutableListOf(binaryPath, "--yes", "--ignore-stdin").apply {
                    if (prefs.useInternalDns) add("--internal-dns")
                    if (prefs.socks5Proxy.isNotBlank()) {
                        add("--socks5"); add(prefs.socks5Proxy)
                    }
                    if (prefs.httpProxy.isNotBlank()) {
                        add("--connect"); add(prefs.httpProxy)
                    }
                    add("store")
                    if (expiration.isNotBlank()) {
                        add("--expiration"); add(expiration)
                    }
                    if (downloads > 0) {
                        add("--downloads"); add(downloads.toString())
                    }
                    if (!effectiveStoreUrl.isNullOrBlank()) {
                        add("--url"); add(effectiveStoreUrl)
                    }
                    addAll(filePaths)
                }

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = homeDir,
                    waitingState = CrocTransferState.WaitingForPeer("Uploading to secure store..."),
                    extraEnv = emptyMap(),
                    prefs = prefs,
                    opName = "SendStore",
                    code = null,
                    engine = CrocEngine.CURRENT,
                    initialFileNames = filePaths.map { File(it).name }
                )
            } catch (e: Exception) {
                Log.e(TAG, "SendStore failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun revokeStore(storeId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val binaryPath = binaryManager.getBinaryPath(CrocEngine.CURRENT)
                val prefs = prefsRepository.preferencesFlow.first()
                val command = mutableListOf(binaryPath, "--yes", "--revoke", storeId.trim()).apply {
                    if (prefs.socks5Proxy.isNotBlank()) {
                        add("--socks5"); add(prefs.socks5Proxy)
                    }
                    if (prefs.httpProxy.isNotBlank()) {
                        add("--connect"); add(prefs.httpProxy)
                    }
                }

                val systemDns = getSystemDnsServers()
                val env = buildMap {
                    put("HOME", homeDir.absolutePath)
                    put("TMPDIR", tmpDir.absolutePath)
                    if (systemDns.isNotEmpty()) {
                        put("CROC_DNS", systemDns.joinToString(","))
                    }
                }

                val process = binaryManager.startProcess(
                    command = command,
                    workDir = homeDir,
                    extraEnv = env,
                    engine = CrocEngine.CURRENT
                ) ?: return@withContext Result.failure(Exception("Failed to start croc process"))

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = waitForExitCode(process, timeoutMs = 15_000)

                if (exitCode == 0 || output.lowercase().contains("revoked")) {
                    Result.success(output.trim())
                } else {
                    val errorMsg = output.lines().filter { it.isNotBlank() }.lastOrNull() ?: "Revoke failed with code $exitCode"
                    Result.failure(Exception(errorMsg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "revokeStore failed", e)
                Result.failure(e)
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
        opName: String,
        code: String?,
        engine: CrocEngine,
        initialFileNames: List<String> = emptyList()
    ) {
        Log.d(TAG, "$opName ($engine) command: ${redactCommandForLog(baseCommand)}")

        var result = runCommand(baseCommand, workDir, waitingState, extraEnv, engine, initialFileNames)

        if (result.isLegacyFallback) {
            val effectiveRoom = if (!code.isNullOrBlank()) code else result.announcedCode.ifBlank { "" }
            _state.value = CrocTransferState.LegacyFallbackAvailable(
                room = effectiveRoom,
                reason = "The other device is using an older croc version (PAKE protocol version mismatch)."
            )
            return
        }

        if (shouldRetryWithInternalDns(result, prefs, baseCommand)) {
            val retryCommand = baseCommand.toMutableList()
            addInternalDnsFlag(retryCommand)
            Log.w(TAG, "$opName ($engine) retry with --internal-dns: ${redactCommandForLog(retryCommand)}")
            result = runCommand(retryCommand, workDir, waitingState, extraEnv, engine, initialFileNames)

            if (result.isLegacyFallback) {
                val effectiveRoom = if (!code.isNullOrBlank()) code else result.announcedCode.ifBlank { "" }
                _state.value = CrocTransferState.LegacyFallbackAvailable(
                    room = effectiveRoom,
                    reason = "The other device is using an older croc version (PAKE protocol version mismatch)."
                )
                return
            }
        }

        // Check cancelled state FIRST — cancel() may have been called while parseOutput was running.
        // Without this guard, a force-killed process can exit with code 0 and be mis-reported as Completed.
        if (_state.value is CrocTransferState.Cancelled) {
            return // keep the Cancelled state intact
        }

        if (isSuccessfulTransfer(result)) {
            if (opName == "SendStore" || result.storeBrowserLink.isNotBlank()) {
                val effectiveStoreId = result.storeId.ifBlank {
                    if (result.storeBrowserLink.contains("/s/")) {
                        result.storeBrowserLink.substringAfter("/s/").substringBefore("#").trim()
                    } else ""
                }
                val calculatedExpiresAt = if (result.storeExpiresAt > 0L) {
                    result.storeExpiresAt
                } else {
                    System.currentTimeMillis() + parseExpirationDurationMillis(lastStoreExpiration)
                }
                val effectiveDownloads = if (result.storeDownloadsLimit > 0) result.storeDownloadsLimit else lastStoreDownloads
                val effectiveRawExpiration = result.storeRawExpiration.ifBlank { lastStoreExpiration }

                _state.value = CrocTransferState.StoreCompleted(
                    browserLink = result.storeBrowserLink,
                    cliToken = result.storeCliToken,
                    storeId = effectiveStoreId,
                    expiresAt = calculatedExpiresAt,
                    fileNames = result.fileNames,
                    totalBytes = result.totalBytes,
                    rawExpirationText = effectiveRawExpiration,
                    downloadsLimit = effectiveDownloads
                )
            } else {
                _state.value = CrocTransferState.Completed(
                    fileNames = result.fileNames,
                    totalBytes = result.totalBytes,
                    peerIp = result.peerIp,
                    totalFileCount = result.totalFileCount.coerceAtLeast(result.fileNames.size),
                    receivedText = result.receivedText
                )
            }
        } else {
            _state.value = CrocTransferState.Error(errorMessageFor(result))
        }
    }

    private suspend fun runCommand(
        command: List<String>,
        workDir: File,
        waitingState: CrocTransferState,
        extraEnv: Map<String, String>,
        engine: CrocEngine,
        initialFileNames: List<String> = emptyList()
    ): ProcessResult {
        val systemDns = getSystemDnsServers()
        val env = buildMap {
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            if (systemDns.isNotEmpty()) {
                put("CROC_DNS", systemDns.joinToString(","))
            }
            putAll(extraEnv)
        }
        currentProcess = binaryManager.startProcess(
            command = command,
            workDir = workDir,
            extraEnv = env,
            engine = engine
        )
        _state.value = waitingState
        return parseOutput(currentProcess!!, initialFileNames)
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
        if (result.isStoreTransfer || result.storeBrowserLink.isNotBlank() || result.storeId.isNotBlank()) return true
        if (result.fileNames.isNotEmpty() || result.totalBytes > 0L) return true
        // If we captured a peer IP, the transfer happened
        if (result.peerIp.isNotBlank()) return true

        return result.outputTail.any {
            val line = it.lowercase()
            "sending '" in line || "receiving '" in line ||
                    "sending (" in line || "receiving (" in line ||
                    "stored transfer is encrypted" in line ||
                    "browser link:" in line ||
                    "verified download committed" in line ||
                    "encrypted upload complete" in line
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

    private suspend fun parseOutput(process: Process, initialFileNames: List<String> = emptyList()): ProcessResult {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val fileNames = initialFileNames.toMutableList()
        var totalBytes = 0L
        var currentFileName = fileNames.firstOrNull() ?: ""
        var peerIp = ""
        var totalFilesFromProgress = fileNames.size
        val outputTail = ArrayDeque<String>()
        var isTextTransfer = false
        var capturingText = false
        val receivedTextLines = mutableListOf<String>()
        var isLegacyFallback = false
        var announcedCode = ""
        var isStoreTransfer = false
        var storeBrowserLink = ""
        var storeCliToken = ""
        var storeId = ""
        var storeRawExpiration = ""
        var nextIsBrowserLink = false
        var nextIsCliToken = false

        // Regex patterns for the latest (v11.5.2) and legacy (v10.6.0) output formats
        // Matches: "Sending (->1.2.3.4:9009)", "Receiving (<-1.2.3.4:9009)", or "Sending (10.0.0.1->1.2.3.4)"
        val peerIpRegex = Regex("""(?:->|<-)([0-9a-fA-F:.]+)""")
        // Matches progress lines: "filename... 42% |...| (size) N/M" or "Uploading file.txt... 42% |...| (size)"
        val progressLineRegex = Regex("""^\s*(.+?)\s+(\d+)%\s*\|.*?\|\s*\((.+?)\)\s*(?:(\d+)/(\d+))?""")
        // Matches size: "(42/100 kB)", "(450 kB / 1.0 MB, 1.2 MB/s)", or "(23/23 B)"
        val sizeInProgressRegex = Regex("""(\d+(?:\.\d+)?)\s*([a-zA-Z]+)?\s*/\s*(\d+(?:\.\d+)?)\s*([a-zA-Z]+)""")
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

                // Narrow match: only on literal substring "unsupported PAKE protocol version"
                if (l.lowercase().contains("unsupported pake protocol version")) {
                    isLegacyFallback = true
                    try { process.destroyForcibly() } catch (_: Exception) {}
                    break
                }

                // Skip blank / whitespace-only lines
                if (l.isBlank()) continue

                // Code announcement
                if (l.contains("Code is:")) {
                    val code = l.substringAfter("Code is:").trim()
                    announcedCode = code
                    _state.value = CrocTransferState.WaitingForPeer(code)
                    continue
                }

                // Detect text transfer: "Receiving text message (5 B)"
                // MUST be checked before the generic "Receiving" check below
                if (l.contains("Receiving text message")) {
                    isTextTransfer = true
                    // Parse text size from the prompt
                    oldSizeRegex.find(l)?.let { match ->
                        val num = match.groupValues[1].toDoubleOrNull() ?: 0.0
                        val unit = match.groupValues[2]
                        totalBytes = parseSize(num, unit)
                    }
                    continue
                }

                // If we are capturing text content, collect lines
                if (capturingText) {
                    receivedTextLines.add(l)
                    continue
                }

                // Peer connection line: "Sending (->IP:PORT)" or "Receiving (<-IP:PORT)"
                if (l.contains("Sending") || l.contains("Receiving")) {
                    peerIpRegex.find(l)?.let { match ->
                        val rawIp = match.groupValues[1]
                        peerIp = if (rawIp.contains(":") && !rawIp.contains("::")) rawIp.substringBefore(":") else rawIp
                    }
                    // If this is a text transfer, start capturing text after the Receiving line
                    if (isTextTransfer && l.contains("Receiving")) {
                        capturingText = true
                        continue
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

                // File preparation / hashing detection (e.g. "Hashing 1/3: filename.txt" or "Hashing filename.txt... 50% |...| (114/229 MB)")
                if (l.contains("Hashing")) {
                    val hashingName = when {
                        ":" in l -> l.substringAfter(":").trim()
                        l.contains("Hashing ") -> l.substringAfter("Hashing ").substringBefore("...").substringBefore("%").trim()
                        else -> ""
                    }.removeSuffix("...").trim()

                    // If size information is present in the hashing line, pre-populate fileSizeMap
                    sizeInProgressRegex.find(l)?.let { sizeMatch ->
                        val totalNum = sizeMatch.groupValues[3].toDoubleOrNull() ?: 0.0
                        val totalUnit = sizeMatch.groupValues[4]
                        val fileTotalBytes = parseSize(totalNum, totalUnit)
                        if (fileTotalBytes > 0L) {
                            val matchedFile = if (initialFileNames.isNotEmpty()) {
                                fileNames.firstOrNull { it.startsWith(hashingName) || hashingName.startsWith(it) }
                                    ?: fileNames.firstOrNull()
                            } else {
                                hashingName.ifBlank { null }
                            }
                            if (matchedFile != null) {
                                fileSizeMap[matchedFile] = fileTotalBytes
                                if (totalBytes == 0L) {
                                    totalBytes = fileTotalBytes
                                }
                            }
                        }
                    }
                    // Stay in Preparing state during hashing; do not treat hashing as active transfer
                    continue
                }

                // Progress line: "filename... 42% |████   | (42/100 kB, 1.2 MB/s) 1/3" or "Uploading file.txt... 45% |...|"
                val progressMatch = progressLineRegex.find(l)
                if (progressMatch != null) {
                    val match = progressMatch
                    val rawName = match.groupValues[1].trim()
                    if (rawName.startsWith("Hashing", ignoreCase = true)) {
                        continue
                    }
                    val percent = match.groupValues[2].toIntOrNull() ?: 0
                    val sizeSection = match.groupValues[3]
                    val currentFileNum = match.groupValues[4].toIntOrNull()
                    val totalFileNum = match.groupValues[5].toIntOrNull()

                    var cleanedName = rawName
                    if (cleanedName.startsWith("Uploading ", ignoreCase = true)) {
                        cleanedName = cleanedName.substring(10).trim()
                    } else if (cleanedName.startsWith("Downloading ", ignoreCase = true)) {
                        cleanedName = cleanedName.substring(12).trim()
                    }

                    val multiFilesMatch = Regex("""^(\d+)\s+files""").find(cleanedName)
                    if (multiFilesMatch != null) {
                        val totalCount = multiFilesMatch.groupValues[1].toIntOrNull()
                        if (totalCount != null && totalCount > 0) {
                            totalFilesFromProgress = totalCount
                        }
                    } else {
                        val unElided = cleanedName.removeSuffix("...").trim()
                        val existingFullName = fileNames.firstOrNull { it.startsWith(unElided) || it == cleanedName }
                        currentFileName = existingFullName ?: unElided.ifBlank { cleanedName }
                        if (currentFileName.isNotBlank() && currentFileName !in fileNames) {
                            // In send mode, do not add unknown phantom files
                            if (initialFileNames.isEmpty()) {
                                fileNames.add(currentFileName)
                            }
                        }
                    }

                    // Parse per-file size from "(current/total unit)" or "(currentUnit / totalUnit)"
                    sizeInProgressRegex.find(sizeSection)?.let { sizeMatch ->
                        val curNum = sizeMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                        val curUnitGroup = sizeMatch.groupValues[2]
                        val totalNum = sizeMatch.groupValues[3].toDoubleOrNull() ?: 0.0
                        val totalUnit = sizeMatch.groupValues[4]
                        val curUnit = if (curUnitGroup.isNotBlank()) curUnitGroup else totalUnit

                        val fileTotalBytes = parseSize(totalNum, totalUnit)
                        fileSizeMap[currentFileName] = fileTotalBytes
                        if (fileTotalBytes > 0L && totalBytes == 0L) {
                            totalBytes = fileTotalBytes
                        }
                    }

                    // Update file count from N/M suffix
                    if (totalFileNum != null && totalFileNum > 0) {
                        totalFilesFromProgress = totalFileNum
                    }

                    // Compute cumulative total bytes from all known file sizes
                    val cumulativeTotal = fileSizeMap.values.sum()
                    if (cumulativeTotal > 0) {
                        totalBytes = cumulativeTotal
                    }

                    // Compute bytes transferred
                    val completedBytes = fileNames.filter { it != currentFileName }
                        .sumOf { fileSizeMap[it] ?: 0L }
                    val currentFileSize = fileSizeMap[currentFileName] ?: 0L
                    val currentFileTransferred = if (currentFileSize > 0) {
                        (currentFileSize * percent / 100)
                    } else {
                        (totalBytes * percent / 100)
                    }
                    val bytesTransferred = completedBytes + currentFileTransferred

                    val effectiveTotalFiles = if (initialFileNames.isNotEmpty()) {
                        initialFileNames.size
                    } else {
                        totalFilesFromProgress.coerceAtLeast(fileNames.size).coerceAtLeast(1)
                    }
                    val effectiveCurrentFile = if (currentFileNum != null) {
                        currentFileNum
                    } else {
                        val idx = fileNames.indexOf(currentFileName)
                        if (idx >= 0) idx + 1 else 1
                    }.coerceIn(1, effectiveTotalFiles)

                    _state.value = CrocTransferState.Transferring(
                        fileName = currentFileName,
                        currentFile = effectiveCurrentFile,
                        totalFiles = effectiveTotalFiles,
                        currentFilePercent = percent,
                        bytesTransferred = bytesTransferred.coerceAtMost(totalBytes.coerceAtLeast(1)),
                        totalBytes = totalBytes.coerceAtLeast(1),
                        peerIp = peerIp
                    )
                    continue
                }

                // Store output parsing:
                if (l.contains("Stored transfer is encrypted and available until") ||
                    l.contains("Encrypted stored transfer")) {
                    isStoreTransfer = true
                    if (l.contains("available until")) {
                        storeRawExpiration = l.substringAfter("available until").substringBefore("or").trim()
                    }
                }

                if (l.contains("Downloading ")) {
                    val fName = l.substringAfter("Downloading ").trim()
                    if (fName.isNotBlank()) {
                        currentFileName = fName
                        if (currentFileName !in fileNames) fileNames.add(currentFileName)
                    }
                    isStoreTransfer = true
                }

                if (l.contains("Verifying ")) {
                    val fName = l.substringAfter("Verifying ").trim()
                    if (fName.isNotBlank() && fName !in fileNames) {
                        fileNames.add(fName)
                    }
                    isStoreTransfer = true
                }

                if (l.contains("Total:")) {
                    Regex("""Total:\s*(\d+(?:\.\d+)?)\s*(\w+)""").find(l)?.let { m ->
                        val num = m.groupValues[1].toDoubleOrNull() ?: 0.0
                        val unit = m.groupValues[2]
                        totalBytes = parseSize(num, unit)
                    }
                }

                if (l.contains("Verified download committed") || l.contains("Encrypted upload complete")) {
                    isStoreTransfer = true
                }

                if (l.contains("Browser link:")) {
                    nextIsBrowserLink = true
                    continue
                }
                if (nextIsBrowserLink) {
                    if (l.trim().startsWith("http")) {
                        storeBrowserLink = l.trim()
                        nextIsBrowserLink = false
                    }
                } else if (l.trim().startsWith("http") && (l.contains("/s/") || l.contains("#v1."))) {
                    storeBrowserLink = l.trim()
                    isStoreTransfer = true
                }

                if (l.contains("CLI recipient:")) {
                    nextIsCliToken = true
                    continue
                }
                if (nextIsCliToken) {
                    if (l.trim().startsWith("croc-store-v1")) {
                        storeCliToken = l.trim()
                        nextIsCliToken = false
                    }
                } else if (l.trim().startsWith("croc-store-v1")) {
                    storeCliToken = l.trim()
                    isStoreTransfer = true
                }

                if (l.contains("croc --revoke")) {
                    storeId = l.substringAfter("croc --revoke").trim()
                    isStoreTransfer = true
                }

                // File count completion marker printed by croc: " 1/3" or "1/3"
                Regex("""^\s*(\d+)/(\d+)\s*$""").find(l)?.let { m ->
                    val total = m.groupValues[2].toIntOrNull()
                    if (total != null && total > 0) {
                        totalFilesFromProgress = total
                    }
                }

                // Fallback: simple percent match for lines we didn't parse above
                if (!l.contains("Hashing")) {
                    Regex("(\\d+)%").find(l)?.let { match ->
                        val percent = match.groupValues[1].toIntOrNull() ?: 0
                        val effectiveTotalFiles = if (initialFileNames.isNotEmpty()) {
                            initialFileNames.size
                        } else {
                            totalFilesFromProgress.coerceAtLeast(fileNames.size).coerceAtLeast(1)
                        }
                        val effectiveCurrentFile = (fileNames.indexOf(currentFileName) + 1).coerceIn(1, effectiveTotalFiles)
                        _state.value = CrocTransferState.Transferring(
                            fileName = currentFileName,
                            currentFile = effectiveCurrentFile,
                            totalFiles = effectiveTotalFiles,
                            currentFilePercent = percent,
                            bytesTransferred = totalBytes * percent / 100,
                            totalBytes = totalBytes.coerceAtLeast(1),
                            peerIp = peerIp
                        )
                    }
                }
            }

            val exitCode = waitForExitCode(process)
            val receivedText = if (isTextTransfer && receivedTextLines.isNotEmpty()) {
                receivedTextLines.joinToString("\n")
            } else null
            val effectiveLegacyFallback = isLegacyFallback || outputTail.any {
                it.lowercase().contains("unsupported pake protocol version")
            }
            val effectiveStoreId = storeId.ifBlank {
                if (storeBrowserLink.contains("/s/")) {
                    storeBrowserLink.substringAfter("/s/").substringBefore("#").trim()
                } else ""
            }
            return ProcessResult(
                exitCode = exitCode,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = outputTail.toList(),
                peerIp = peerIp,
                totalFileCount = totalFilesFromProgress,
                receivedText = receivedText,
                isLegacyFallback = effectiveLegacyFallback,
                announcedCode = announcedCode,
                isStoreTransfer = isStoreTransfer || storeBrowserLink.isNotBlank(),
                storeBrowserLink = storeBrowserLink,
                storeCliToken = storeCliToken,
                storeId = effectiveStoreId,
                storeExpiresAt = 0L,
                storeRawExpiration = storeRawExpiration
            )
        } catch (e: InterruptedIOException) {
            val exitCode = waitForExitCode(process)
            if (_state.value is CrocTransferState.Cancelled || !coroutineContext.isActive) {
                Log.i(TAG, "croc output interrupted during cancellation")
            } else {
                Log.w(TAG, "croc output stream interrupted; using process exit state", e)
            }
            val effectiveLegacyFallback = isLegacyFallback || outputTail.any {
                it.lowercase().contains("unsupported pake protocol version")
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
                totalFileCount = totalFilesFromProgress,
                isLegacyFallback = effectiveLegacyFallback,
                announcedCode = announcedCode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
            val effectiveLegacyFallback = isLegacyFallback || outputTail.any {
                it.lowercase().contains("unsupported pake protocol version")
            }
            return ProcessResult(
                exitCode = -1,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = listOf(e.message ?: "Unknown error"),
                peerIp = peerIp,
                totalFileCount = totalFilesFromProgress,
                isLegacyFallback = effectiveLegacyFallback,
                announcedCode = announcedCode
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
