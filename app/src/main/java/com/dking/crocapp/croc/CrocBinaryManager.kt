package com.dking.crocapp.croc

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

///*
// * Manages the croc binary lifecycle.
// *
// * Strategy:
// * 1. Primary: Try nativeLibraryDir (if binary was packaged via jniLibs)
// * 2. Fallback: Extract from assets/croc/*.tar.gz (or .tar) to app's filesDir
// *    and set execute permission via Os.chmod()
//*/
class CrocBinaryManager(private val context: Context) {

    companion object {
        private const val TAG = "CrocBinaryManager"
        private const val ASSETS_DIR = "croc"
        private const val BINARY_NAME = "croc"
        private const val LIB_NAME = "libcroc.so"
        private const val TAR_BLOCK_SIZE = 512
    }

    private val extractedBinary = File(File(context.filesDir, "bin"), BINARY_NAME)
    private val nativeLibBinary = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)

    /**
     * Returns the absolute path to an executable croc binary.
     */
    fun getBinaryPath(): String {
        // Strategy 1: Check nativeLibraryDir (jniLibs approach)
        if (nativeLibBinary.exists() && nativeLibBinary.canExecute()) {
            Log.i(TAG, "Using native lib: ${nativeLibBinary.absolutePath}")
            return nativeLibBinary.absolutePath
        }

        // Strategy 2: Extract from assets to filesDir
        if (!extractedBinary.exists() || extractedBinary.length() == 0L || shouldUpdate()) {
            extractFromAssets()
        }

        if (!extractedBinary.exists() || extractedBinary.length() == 0L) {
            throw IllegalStateException(
                "Failed to extract croc binary. Check logcat '$TAG' for details."
            )
        }

        // Ensure execute permission using POSIX chmod
        ensureExecutable(extractedBinary)

        if (!extractedBinary.canExecute()) {
            throw IllegalStateException(
                "Croc binary exists but is not executable. Device may block execution from app data dir."
            )
        }

        Log.i(TAG, "Using extracted binary: ${extractedBinary.absolutePath} (${extractedBinary.length()} bytes)")
        return extractedBinary.absolutePath
    }

    fun isBinaryReady(): Boolean {
        return (nativeLibBinary.exists() && nativeLibBinary.canExecute()) ||
                (extractedBinary.exists() && extractedBinary.length() > 0 && extractedBinary.canExecute())
    }

    fun initialize(): Boolean {
        return try {
            getBinaryPath()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Binary initialization failed", e)
            false
        }
    }

    fun getVersion(): String? {
        if (!isBinaryReady()) return null
        return try {
            val path = getBinaryPath()
            val process = ProcessBuilder(path, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version", e)
            null
        }
    }

    fun reinstallBinary() {
        extractedBinary.delete()
        File(extractedBinary.parentFile, ".version").delete()
        extractFromAssets()
    }

    /**
     * Set execute permission using Os.chmod (POSIX-level, more reliable than File.setExecutable).
     */
    private fun ensureExecutable(file: File) {
        try {
            // 0x1ED = octal 755 = rwxr-xr-x
            Os.chmod(file.absolutePath, 493) // 493 decimal = 0755 octal
            Log.i(TAG, "chmod 755 applied to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Os.chmod failed, trying File.setExecutable", e)
            val result = file.setExecutable(true, false)
            Log.i(TAG, "File.setExecutable result: $result")
        }
    }

    private fun shouldUpdate(): Boolean {
        val versionFile = File(extractedBinary.parentFile, ".version")
        val currentVersion = getAppVersionCode()
        if (!versionFile.exists()) return true
        return versionFile.readText().trim() != currentVersion
    }

    private fun extractFromAssets() {
        val binDir = extractedBinary.parentFile!!
        binDir.mkdirs()
        Log.i(TAG, "Extracting binary to ${binDir.absolutePath}")

        try {
            val assetFiles = context.assets.list(ASSETS_DIR)
            Log.i(TAG, "Assets in '$ASSETS_DIR/': ${assetFiles?.joinToString() ?: "null"}")

            if (assetFiles.isNullOrEmpty()) {
                Log.e(TAG, "No files in assets/$ASSETS_DIR/")
                return
            }

            // Find the archive or raw binary
            val tarGz = assetFiles.firstOrNull { it.endsWith(".tar.gz") }
            val tar = assetFiles.firstOrNull { it.endsWith(".tar") }
            val rawBin = assetFiles.firstOrNull { !it.contains(".") || it == BINARY_NAME }

            when {
                tarGz != null -> {
                    Log.i(TAG, "Extracting from tar.gz: $tarGz")
                    extractFromTar("$ASSETS_DIR/$tarGz", isGzipped = true)
                }
                tar != null -> {
                    Log.i(TAG, "Extracting from tar: $tar")
                    extractFromTar("$ASSETS_DIR/$tar", isGzipped = false)
                }
                rawBin != null -> {
                    Log.i(TAG, "Copying raw binary: $rawBin")
                    context.assets.open("$ASSETS_DIR/$rawBin").use { input ->
                        FileOutputStream(extractedBinary).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                else -> {
                    Log.e(TAG, "No extractable binary found in assets/$ASSETS_DIR/")
                    return
                }
            }

            ensureExecutable(extractedBinary)
            Log.i(TAG, "Extracted: ${extractedBinary.absolutePath}, size=${extractedBinary.length()}, exec=${extractedBinary.canExecute()}")

            // Version stamp
            File(binDir, ".version").writeText(getAppVersionCode())
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            if (extractedBinary.exists() && extractedBinary.length() == 0L) {
                extractedBinary.delete()
            }
        }
    }

    private fun extractFromTar(assetPath: String, isGzipped: Boolean) {
        context.assets.open(assetPath).use { rawInput ->
            val stream: java.io.InputStream = if (isGzipped) {
                GZIPInputStream(BufferedInputStream(rawInput, 65536))
            } else {
                BufferedInputStream(rawInput, 65536)
            }

            val header = ByteArray(TAR_BLOCK_SIZE)

            while (true) {
                val headerRead = readExactly(stream, header, TAR_BLOCK_SIZE)
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
                    FileOutputStream(extractedBinary).use { out ->
                        val buf = ByteArray(8192)
                        var remaining = entrySize
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = stream.read(buf, 0, toRead)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                        out.flush()
                    }
                    Log.i(TAG, "Extracted ${extractedBinary.length()} bytes")
                    return
                } else if (paddedSize > 0) {
                    skipExactly(stream, paddedSize)
                }
            }
        }
        Log.e(TAG, "Binary '$BINARY_NAME' not found in tar archive")
    }

    private fun readExactly(input: java.io.InputStream, buf: ByteArray, count: Int): Int {
        var read = 0
        while (read < count) {
            val n = input.read(buf, read, count - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    private fun skipExactly(input: java.io.InputStream, n: Long) {
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

    private fun getAppVersionCode(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
    } catch (_: Exception) { "1" }
}