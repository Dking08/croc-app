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
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        private const val NATIVE_BINARY_NAME = "libcroc.so"
        private const val BINARY_VERSION = "10.4.2"
        private const val DOWNLOAD_URL =
            "https://github.com/schollz/croc/releases/download/v10.4.2/croc_v10.4.2_Linux-ARM64.tar.gz"
        private const val EXEC_MODE = 493 // 0755
        private const val MFD_EXEC = 0x0010
        private const val TAR_BLOCK_SIZE = 512
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private val binaryDir = File(context.filesDir, "bin")
    private val extractedBinary = File(binaryDir, BINARY_NAME)
    private val nativeLibBinary = File(context.applicationInfo.nativeLibraryDir, NATIVE_BINARY_NAME)
    private val versionFile = File(binaryDir, ".version")
    private val installLock = Any()
    private val _setupState = MutableStateFlow(BinarySetupState())
    val setupState: StateFlow<BinarySetupState> = _setupState.asStateFlow()

    /**
     * Returns the cached on-disk path for the downloaded croc binary.
     */
    fun getBinaryPath(): String = synchronized(installLock) {
        if (hasPackagedNativeBinary()) {
            markReady("Built-in transfer engine is ready.")
            return nativeLibBinary.absolutePath
        }

        val hasCachedBinary = hasInstalledBinary()
        if (hasCachedBinary && !shouldUpdate()) {
            markReady("Local transfer engine is ready.")
            return extractedBinary.absolutePath
        }

        val setupCopy = if (hasCachedBinary) {
            "Refreshing your local croc engine."
        } else {
            "Downloading croc for first-time setup."
        }
        updateSetupState(
            phase = BinarySetupPhase.Checking,
            title = "Getting croc ready",
            detail = setupCopy
        )

        try {
            installBinaryFromNetwork()
        } catch (e: Exception) {
            if (hasCachedBinary) {
                Log.w(TAG, "Binary refresh failed, falling back to cached version", e)
                markReady("Using your cached transfer engine.")
                return extractedBinary.absolutePath
            }
            markError("We couldn't finish setting up croc. Check your connection and try again.")
            throw e
        }

        if (!hasInstalledBinary()) {
            markError("croc setup finished without installing the transfer engine.")
            throw IllegalStateException(
                "Failed to install the croc binary. Check logcat '$TAG' for details."
            )
        }

        markReady("Transfer engine installed and ready.")
        extractedBinary.absolutePath
    }

    fun isBinaryReady(): Boolean {
        return hasPackagedNativeBinary() || hasInstalledBinary()
    }

    fun initialize(): Boolean {
        return try {
            getBinaryPath()
            true
        } catch (e: Exception) {
            if (!hasInstalledBinary() && !hasPackagedNativeBinary()) {
                markError("We couldn't finish setting up croc. Check your connection and try again.")
            }
            Log.e(TAG, "Binary initialization failed", e)
            false
        }
    }

    fun getVersion(): String? {
        if (!isBinaryReady()) return null
        return try {
            val path = getBinaryPath()
            val process = startProcess(listOf(path, "--version"))
            val output = process?.inputStream?.bufferedReader()?.readText()?.trim()
            process?.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version", e)
            null
        }
    }

    fun reinstallBinary() {
        synchronized(installLock) {
            versionFile.delete()
            installBinaryFromNetwork()
        }
    }

    fun startProcess(
        command: List<String>,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): java.lang.Process? {
        require(command.isNotEmpty()) { "Command must not be empty." }

        val preferredPath = getBinaryPath()
        val stagedBinary = if (preferredPath == nativeLibBinary.absolutePath) null else stageBinaryForExecution()
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
            throw wrapProcessStartException(e, preferredPath)
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

    private fun shouldUpdate(): Boolean {
        if (!versionFile.exists()) return true
        return runCatching { versionFile.readText().trim() != BINARY_VERSION }.getOrDefault(true)
    }

    private fun hasInstalledBinary(): Boolean {
        return extractedBinary.exists() && extractedBinary.length() > 0L
    }

    private fun hasPackagedNativeBinary(): Boolean {
        return nativeLibBinary.exists()
    }

    private fun installBinaryFromNetwork() {
        requireSupportedAbi()
        binaryDir.mkdirs()

        val tempBinary = File(binaryDir, "$BINARY_NAME.download")
        val backupBinary = File(binaryDir, "$BINARY_NAME.backup")
        tempBinary.delete()
        backupBinary.delete()

        try {
            Log.i(TAG, "Downloading croc $BINARY_VERSION from $DOWNLOAD_URL")
            downloadAndExtractBinary(tempBinary)
            ensureExecutable(tempBinary)

            if (!tempBinary.exists() || tempBinary.length() == 0L) {
                throw IllegalStateException("Downloaded croc binary is empty.")
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

            versionFile.writeText(BINARY_VERSION)
            backupBinary.delete()
            Log.i(TAG, "Installed croc binary at ${extractedBinary.absolutePath}")
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

    private fun downloadAndExtractBinary(outputFile: File) {
        // F-Droid forbids bundling prebuilt binaries in the APK, so we fetch the
        // upstream release on demand and cache the extracted executable locally.
        val connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }

        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Failed to download croc binary (HTTP $code).")
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
            connection.inputStream.use { rawInput ->
                ProgressInputStream(rawInput) { bytesRead ->
                    updateDownloadProgress(bytesRead, totalBytes)
                }.use { input ->
                    extractFromTar(
                        input = input,
                        isGzipped = true,
                        outputFile = outputFile,
                        onBinaryFound = {
                            val current = setupState.value
                            updateSetupState(
                                phase = BinarySetupPhase.Installing,
                                title = "Installing croc",
                                detail = "Unpacking the transfer engine and wiring it into the app.",
                                progress = current.progress,
                                downloadedBytes = current.downloadedBytes,
                                totalBytes = current.totalBytes
                            )
                        }
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun stageBinaryForExecution(): StagedBinary {
        if (!hasInstalledBinary()) {
            throw IllegalStateException("Croc binary is not installed.")
        }

        val ownerPid = Process.myPid()
        val memfd = createMemfd()
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
            throw IllegalStateException("Failed to stage croc for execution.", e)
        } finally {
            closeQuietly(memfd)
        }
    }

    private fun createMemfd(): FileDescriptor {
        return try {
            Os.memfd_create("croc-$BINARY_VERSION", MFD_EXEC)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EINVAL) {
                Log.w(TAG, "MFD_EXEC unsupported on this kernel, falling back to legacy memfd flags", e)
                return Os.memfd_create("croc-$BINARY_VERSION", 0)
            }
            throw IllegalStateException("Unable to create executable memfd for croc.", e)
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
        preferredPath: String
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

                if (baseName == BINARY_NAME && isFile && entrySize > 0) {
                    Log.i(TAG, "Found croc binary ($entrySize bytes), extracting...")
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
