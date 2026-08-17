package com.dking.crocapp.croc

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CrocEngine {
    CURRENT,
    LEGACY
}

enum class BinarySetupPhase {
    Idle,
    Checking,
    Downloading,
    Installing,
    Ready,
    Error
}

data class BinarySetupState(
    val phase: BinarySetupPhase = BinarySetupPhase.Idle,
    val title: String = "Preparing croc",
    val detail: String = "Checking the transfer engine.",
    val progress: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null
)

class CrocBinaryManager(private val context: Context) {

    companion object {
        private const val TAG = "CrocBinaryManager"
        private const val BINARY_NAME = "croc"
        private const val BINARY_VERSION = "11.0.1"
        private const val DOWNLOAD_URL =
            "https://github.com/schollz/croc/releases/download/v11.0.1/croc_v11.0.1_Linux-ARM64.tar.gz"
        private const val CURRENT_SHA256 =
            "1626c4a5ce73da171146e0f336ca40118c0a2b4b617421aa53f6addc54de6459"

        private const val LEGACY_BINARY_NAME = "croc_legacy"
        private const val LEGACY_BINARY_VERSION = "10.6.0"
        private const val LEGACY_DOWNLOAD_URL =
            "https://github.com/schollz/croc/releases/download/v10.6.0/croc_v10.6.0_Linux-ARM64.tar.gz"
        private const val LEGACY_SHA256 =
            "ee950b1dcd1f284b3f2223d3ebac2767d1b3406f58c978c8854ddb88fe75a121"

        private const val EXEC_MODE = 493 // 0755
        private const val MFD_EXEC = 0x0010
        private const val TAR_BLOCK_SIZE = 512
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private data class EngineSpec(
        val engine: CrocEngine,
        val version: String,
        val downloadUrl: String,
        val nativeBinaryName: String,
        val cachedBinaryName: String,
        val versionFileName: String,
        val sha256: String
    )

    private fun specFor(engine: CrocEngine): EngineSpec {
        return when (engine) {
            CrocEngine.CURRENT -> EngineSpec(
                engine = CrocEngine.CURRENT,
                version = BINARY_VERSION,
                downloadUrl = DOWNLOAD_URL,
                nativeBinaryName = "libcroc.so",
                cachedBinaryName = BINARY_NAME,
                versionFileName = ".version",
                sha256 = CURRENT_SHA256
            )
            CrocEngine.LEGACY -> EngineSpec(
                engine = CrocEngine.LEGACY,
                version = LEGACY_BINARY_VERSION,
                downloadUrl = LEGACY_DOWNLOAD_URL,
                nativeBinaryName = "libcroc_legacy.so",
                cachedBinaryName = LEGACY_BINARY_NAME,
                versionFileName = ".version_legacy",
                sha256 = LEGACY_SHA256
            )
        }
    }

    private val binaryDir = File(context.filesDir, "bin")
    private val installLock = Any()
    private val _setupState = MutableStateFlow(BinarySetupState())
    val setupState: StateFlow<BinarySetupState> = _setupState.asStateFlow()

    /**
     * Returns the cached on-disk path for the specified croc binary.
     */
    fun getBinaryPath(engine: CrocEngine = CrocEngine.CURRENT): String = synchronized(installLock) {
        val spec = specFor(engine)
        val nativeLibBinary = File(context.applicationInfo.nativeLibraryDir, spec.nativeBinaryName)
        val extractedBinary = File(binaryDir, spec.cachedBinaryName)

        if (hasPackagedNativeBinary(spec)) {
            markReady("Built-in transfer engine is ready.")
            return nativeLibBinary.absolutePath
        }

        val hasCachedBinary = hasInstalledBinary(spec)
        if (hasCachedBinary && !shouldUpdate(spec)) {
            markReady("Local transfer engine is ready.")
            return extractedBinary.absolutePath
        }

        val setupCopy = if (hasCachedBinary) {
            "Refreshing your local croc engine."
        } else {
            "Downloading croc ${if (engine == CrocEngine.LEGACY) "legacy engine " else ""}for setup."
        }
        updateSetupState(
            phase = BinarySetupPhase.Checking,
            title = "Getting croc ready",
            detail = setupCopy
        )

        try {
            installBinaryFromNetwork(spec)
        } catch (e: Exception) {
            if (hasCachedBinary) {
                Log.w(TAG, "Binary refresh failed for $engine, falling back to cached version", e)
                markReady("Using your cached transfer engine.")
                return extractedBinary.absolutePath
            }
            markError("We couldn't finish setting up croc. Check your connection and try again.")
            throw e
        }

        if (!hasInstalledBinary(spec)) {
            markError("croc setup finished without installing the transfer engine.")
            throw IllegalStateException(
                "Failed to install the croc binary for $engine. Check logcat '$TAG' for details."
            )
        }

        markReady("Transfer engine installed and ready.")
        extractedBinary.absolutePath
    }

    fun isBinaryReady(engine: CrocEngine = CrocEngine.CURRENT): Boolean {
        val spec = specFor(engine)
        return hasPackagedNativeBinary(spec) || hasInstalledBinary(spec)
    }

    fun isBinaryCached(engine: CrocEngine): Boolean {
        val spec = specFor(engine)
        val extractedBinary = File(binaryDir, spec.cachedBinaryName)
        return extractedBinary.exists() && extractedBinary.length() > 0L
    }

    fun clearBinary(engine: CrocEngine) = synchronized(installLock) {
        val spec = specFor(engine)
        val extractedBinary = File(binaryDir, spec.cachedBinaryName)
        val versionFile = File(binaryDir, spec.versionFileName)
        extractedBinary.delete()
        versionFile.delete()
        Log.i(TAG, "Cleared cached binary for engine $engine")
    }

    fun initialize(engine: CrocEngine = CrocEngine.CURRENT): Boolean {
        return try {
            getBinaryPath(engine)
            true
        } catch (e: Exception) {
            val spec = specFor(engine)
            if (!hasInstalledBinary(spec) && !hasPackagedNativeBinary(spec)) {
                markError("We couldn't finish setting up croc. Check your connection and try again.")
            }
            Log.e(TAG, "Binary initialization failed for $engine", e)
            false
        }
    }

    fun getVersion(engine: CrocEngine = CrocEngine.CURRENT): String? {
        if (!isBinaryReady(engine)) return null
        return try {
            val path = getBinaryPath(engine)
            val process = startProcess(listOf(path, "--version"), engine = engine)
            val output = process?.inputStream?.bufferedReader()?.readText()?.trim()
            process?.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version for $engine", e)
            null
        }
    }

    fun reinstallBinary(engine: CrocEngine = CrocEngine.CURRENT) {
        synchronized(installLock) {
            val spec = specFor(engine)
            val versionFile = File(binaryDir, spec.versionFileName)
            versionFile.delete()
            installBinaryFromNetwork(spec)
        }
    }

    fun startProcess(
        command: List<String>,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        engine: CrocEngine = CrocEngine.CURRENT
    ): java.lang.Process? {
        require(command.isNotEmpty()) { "Command must not be empty." }

        val spec = specFor(engine)
        val nativeLibBinary = File(context.applicationInfo.nativeLibraryDir, spec.nativeBinaryName)
        val preferredPath = getBinaryPath(engine)
        val stagedBinary = if (preferredPath == nativeLibBinary.absolutePath) null else stageBinaryForExecution(spec)
        val processBuilder = ProcessBuilder(command.toMutableList().apply {
            this[0] = stagedBinary?.execPath ?: preferredPath
        }).redirectErrorStream(true)

        if (workDir != null) {
            processBuilder.directory(workDir)
        }
        extraEnv.forEach { (key, value) ->
            processBuilder.environment()[key] = value
        }

        return try {
            processBuilder.start()
        } catch (e: Exception) {
            throw wrapProcessStartException(e, preferredPath, nativeLibBinary)
        } finally {
            stagedBinary?.close()
        }
    }

    /**
     * Set execute permission using Os.chmod (POSIX-level, more reliable than File.setExecutable).
     */
    private fun ensureExecutable(file: File) {
        try {
            // 0x1ED = octal 755 = rwxr-xr-x
            Os.chmod(file.absolutePath, EXEC_MODE)
            Log.i(TAG, "chmod 755 applied to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Os.chmod failed, trying File.setExecutable", e)
            val result = file.setExecutable(true, false)
            Log.i(TAG, "File.setExecutable result: $result")
        }
    }

    private fun shouldUpdate(spec: EngineSpec): Boolean {
        val versionFile = File(binaryDir, spec.versionFileName)
        if (!versionFile.exists()) return true
        return runCatching { versionFile.readText().trim() != spec.version }.getOrDefault(true)
    }

    private fun hasInstalledBinary(spec: EngineSpec): Boolean {
        val extractedBinary = File(binaryDir, spec.cachedBinaryName)
        return extractedBinary.exists() && extractedBinary.length() > 0L
    }

    private fun hasPackagedNativeBinary(spec: EngineSpec): Boolean {
        val nativeLibBinary = File(context.applicationInfo.nativeLibraryDir, spec.nativeBinaryName)
        return nativeLibBinary.exists()
    }

    private fun installBinaryFromNetwork(spec: EngineSpec) {
        requireSupportedAbi()
        binaryDir.mkdirs()

        val extractedBinary = File(binaryDir, spec.cachedBinaryName)
        val versionFile = File(binaryDir, spec.versionFileName)
        val tempBinary = File(binaryDir, "${spec.cachedBinaryName}.download")
        val backupBinary = File(binaryDir, "${spec.cachedBinaryName}.backup")
        tempBinary.delete()
        backupBinary.delete()

        try {
            Log.i(TAG, "Downloading croc ${spec.version} (${spec.engine}) from ${spec.downloadUrl}")
            downloadAndExtractBinary(tempBinary, spec)
            ensureExecutable(tempBinary)

            if (!tempBinary.exists() || tempBinary.length() == 0L) {
                throw IllegalStateException("Downloaded croc binary (${spec.engine}) is empty.")
            }

            if (extractedBinary.exists()) {
                if (!extractedBinary.renameTo(backupBinary)) {
                    extractedBinary.copyTo(backupBinary, overwrite = true)
                    extractedBinary.delete()
                }
            }

            if (!tempBinary.renameTo(extractedBinary)) {
                tempBinary.copyTo(extractedBinary, overwrite = true)
                tempBinary.delete()
            }

            versionFile.writeText(spec.version)
            backupBinary.delete()
            Log.i(TAG, "Installed croc binary (${spec.engine}) at ${extractedBinary.absolutePath}")
        } catch (e: Exception) {
            tempBinary.delete()
            if (!extractedBinary.exists() && backupBinary.exists()) {
                if (!backupBinary.renameTo(extractedBinary)) {
                    backupBinary.copyTo(extractedBinary, overwrite = true)
                    backupBinary.delete()
                }
            }
            throw e
        } finally {
            tempBinary.delete()
            if (backupBinary.exists() && extractedBinary.exists()) {
                backupBinary.delete()
            }
        }
    }

    private fun requireSupportedAbi() {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            throw IllegalStateException(
                "This build currently downloads only the arm64 croc binary."
            )
        }
    }

    private fun downloadAndExtractBinary(outputFile: File, spec: EngineSpec) {
        // F-Droid forbids bundling prebuilt binaries in the APK, so we fetch the
        // upstream release on demand and cache the extracted executable locally.
        val tempTarFile = File(binaryDir, "${spec.cachedBinaryName}.tar.gz.download")
        tempTarFile.delete()

        val connection = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }

        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Failed to download croc binary for ${spec.engine} (HTTP $code).")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            updateSetupState(
                phase = BinarySetupPhase.Downloading,
                title = "Downloading croc",
                detail = "This is a one-time setup so transfers are ready when you need them.",
                progress = if (totalBytes != null) 0f else null,
                downloadedBytes = 0L,
                totalBytes = totalBytes
            )

            // Download raw tar.gz to tempTarFile while calculating SHA-256 checksum
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { rawInput ->
                ProgressInputStream(rawInput) { bytesRead ->
                    updateDownloadProgress(bytesRead, totalBytes)
                }.use { progressInput ->
                    FileOutputStream(tempTarFile).use { fileOut ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (progressInput.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                            fileOut.write(buffer, 0, bytesRead)
                        }
                        fileOut.flush()
                    }
                }
            }

            // Verify checksum
            val calculatedSha = digest.digest().joinToString("") { "%02x".format(it) }
            Log.i(TAG, "Downloaded ${spec.cachedBinaryName}, SHA-256: $calculatedSha, Expected: ${spec.sha256}")
            if (!calculatedSha.equals(spec.sha256, ignoreCase = true)) {
                tempTarFile.delete()
                throw IllegalStateException(
                    "Checksum mismatch for downloaded croc binary (${spec.engine}): expected ${spec.sha256}, got $calculatedSha"
                )
            }

            // Extract binary from verified tar archive
            val current = setupState.value
            updateSetupState(
                phase = BinarySetupPhase.Installing,
                title = "Installing croc",
                detail = "Unpacking the transfer engine and wiring it into the app.",
                progress = current.progress,
                downloadedBytes = current.downloadedBytes,
                totalBytes = current.totalBytes
            )

            FileInputStream(tempTarFile).use { tarInput ->
                extractFromTar(
                    input = tarInput,
                    isGzipped = true,
                    outputFile = outputFile
                )
            }
        } finally {
            connection.disconnect()
            tempTarFile.delete()
        }
    }

    private fun stageBinaryForExecution(spec: EngineSpec): StagedBinary {
        val extractedBinary = File(binaryDir, spec.cachedBinaryName)
        if (!hasInstalledBinary(spec)) {
            throw IllegalStateException("Croc binary (${spec.engine}) is not installed.")
        }

        val ownerPid = Process.myPid()
        val memfd = createMemfd(spec)
        try {
            FileInputStream(extractedBinary).use { input ->
                ParcelFileDescriptor.dup(memfd).use { writePfd ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writePfd).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val execPfd = ParcelFileDescriptor.dup(memfd)
            return StagedBinary(execPfd, "/proc/$ownerPid/fd/${execPfd.fd}")
        } catch (e: Exception) {
            closeQuietly(memfd)
            throw IllegalStateException("Failed to stage croc for execution (${spec.engine}).", e)
        } finally {
            closeQuietly(memfd)
        }
    }

    private fun createMemfd(spec: EngineSpec): FileDescriptor {
        return try {
            Os.memfd_create("croc-${spec.version}", MFD_EXEC)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EINVAL) {
                Log.w(TAG, "MFD_EXEC unsupported on this kernel, falling back to legacy memfd flags", e)
                return Os.memfd_create("croc-${spec.version}", 0)
            }
            throw IllegalStateException("Unable to create executable memfd for croc (${spec.engine}).", e)
        } catch (e: NoSuchMethodError) {
            throw IllegalStateException(
                "This Android version cannot execute the downloaded croc binary from app storage.",
                e
            )
        }
    }

    private fun closeQuietly(fd: FileDescriptor) {
        runCatching { Os.close(fd) }
    }

    private fun wrapProcessStartException(
        error: Exception,
        preferredPath: String,
        nativeLibBinary: File
    ): IllegalStateException {
        val message = if (preferredPath == nativeLibBinary.absolutePath) {
            "The packaged croc native library could not be executed."
        } else {
            "This device blocks executing downloaded croc binaries inside the Android app sandbox. " +
                "A packaged croc native library built from source is required for reliable transfers."
        }
        return IllegalStateException(message, error)
    }

    private fun extractFromTar(
        input: InputStream,
        isGzipped: Boolean,
        outputFile: File,
        onBinaryFound: (() -> Unit)? = null
    ) {
        val stream: InputStream = if (isGzipped) {
            GZIPInputStream(BufferedInputStream(input, 65_536))
        } else {
            BufferedInputStream(input, 65_536)
        }

        stream.use { tarStream ->
            val header = ByteArray(TAR_BLOCK_SIZE)

            while (true) {
                val headerRead = readExactly(tarStream, header, TAR_BLOCK_SIZE)
                if (headerRead < TAR_BLOCK_SIZE) break
                if (header.all { it == 0.toByte() }) break

                val entryName = extractString(header, 0, 100)
                val sizeStr = extractString(header, 124, 12)
                val entrySize = parseOctal(sizeStr)
                val typeFlag = header[156]

                Log.d(TAG, "tar entry: '$entryName' size=$entrySize type=${typeFlag.toInt().toChar()}")

                val dataBlocks = if (entrySize > 0) ((entrySize + TAR_BLOCK_SIZE - 1) / TAR_BLOCK_SIZE) else 0
                val paddedSize = dataBlocks * TAR_BLOCK_SIZE

                val baseName = entryName.trimEnd('/').substringAfterLast('/')
                val isFile = typeFlag == 0.toByte() || typeFlag == '0'.code.toByte()

                // Inside upstream Linux-ARM64 tarball, the binary is always named "croc"
                if (baseName == BINARY_NAME && isFile && entrySize > 0) {
                    Log.i(TAG, "Found croc binary ($entrySize bytes), extracting to ${outputFile.name}...")
                    onBinaryFound?.invoke()
                    FileOutputStream(outputFile).use { out ->
                        val buf = ByteArray(8192)
                        var remaining = entrySize
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = tarStream.read(buf, 0, toRead)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                        out.flush()
                    }
                    Log.i(TAG, "Extracted ${outputFile.length()} bytes")
                    return
                } else if (paddedSize > 0) {
                    skipExactly(tarStream, paddedSize)
                }
            }
        }
        Log.e(TAG, "Binary '$BINARY_NAME' not found in tar archive")
        outputFile.delete()
        throw IllegalStateException("Binary '$BINARY_NAME' not found in downloaded archive.")
    }

    private fun readExactly(input: InputStream, buf: ByteArray, count: Int): Int {
        var read = 0
        while (read < count) {
            val n = input.read(buf, read, count - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    private fun skipExactly(input: InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun extractString(buf: ByteArray, offset: Int, maxLen: Int): String {
        val end = (offset until (offset + maxLen)).firstOrNull { buf[it] == 0.toByte() } ?: (offset + maxLen)
        return String(buf, offset, end - offset, Charsets.US_ASCII).trim()
    }

    private fun parseOctal(s: String): Long {
        val clean = s.trim().trimEnd('\u0000')
        if (clean.isEmpty()) return 0
        return try { clean.toLong(8) } catch (_: NumberFormatException) { 0 }
    }

    private fun updateSetupState(
        phase: BinarySetupPhase,
        title: String,
        detail: String,
        progress: Float? = null,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
        errorMessage: String? = null
    ) {
        _setupState.value = BinarySetupState(
            phase = phase,
            title = title,
            detail = detail,
            progress = progress,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            errorMessage = errorMessage
        )
    }

    private fun updateDownloadProgress(bytesRead: Long, totalBytes: Long?) {
        val current = _setupState.value
        val phase = if (current.phase == BinarySetupPhase.Installing) {
            BinarySetupPhase.Installing
        } else {
            BinarySetupPhase.Downloading
        }
        val progress = totalBytes?.let { total ->
            (bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
        updateSetupState(
            phase = phase,
            title = if (phase == BinarySetupPhase.Installing) "Installing croc" else "Downloading croc",
            detail = if (phase == BinarySetupPhase.Installing) {
                "Unpacking the transfer engine and preparing secure transfers."
            } else {
                "Fetching the croc engine for first-time setup."
            },
            progress = progress,
            downloadedBytes = bytesRead,
            totalBytes = totalBytes
        )
    }

    private fun markReady(detail: String) {
        updateSetupState(
            phase = BinarySetupPhase.Ready,
            title = "croc is ready",
            detail = detail,
            progress = 1f
        )
    }

    private fun markError(message: String) {
        updateSetupState(
            phase = BinarySetupPhase.Error,
            title = "Setup needs attention",
            detail = message,
            errorMessage = message
        )
    }

    private class StagedBinary(
        private val parcelFd: ParcelFileDescriptor,
        val execPath: String
    ) {
        fun close() {
            runCatching { parcelFd.close() }
        }
    }

    private class ProgressInputStream(
        input: InputStream,
        private val onProgress: (Long) -> Unit
    ) : FilterInputStream(input) {
        private var bytesReadTotal = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                bytesReadTotal += 1
                onProgress(bytesReadTotal)
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) {
                bytesReadTotal += count.toLong()
                onProgress(bytesReadTotal)
            }
            return count
        }
    }
}
