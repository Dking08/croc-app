package com.dking.crocapp.ui.receive

import android.app.Application
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.URLConnection

internal object ReceivedFilePublisher {

    fun publish(application: Application, outputDir: File): List<ReceivedFile> {
        return publish(application, outputDir, customTreeUri = null)
    }

    fun publish(application: Application, outputDir: File, customTreeUri: Uri?): List<ReceivedFile> {
        // Recursively collect ALL files, including those inside subdirectories
        // that croc creates when receiving folders.
        val files = collectAllFiles(outputDir)
        if (files.isEmpty()) return emptyList()

        return files.mapNotNull { source ->
            // Compute the relative path from outputDir to give context for folder structure.
            // e.g. if outputDir=/data/.../1234 and source=/data/.../1234/myfolder/file.txt
            //      then relativePath = "myfolder/file.txt"
            val relativePath = source.relativeTo(outputDir).path

            if (customTreeUri != null) {
                publishToSafTree(application, source, customTreeUri, relativePath)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(application, source, relativePath)
            } else {
                publishToPublicDownloads(application, source, relativePath)
            }
        }
    }

    /**
     * Recursively collect all files under [dir], skipping directories themselves.
     * This is critical for folder transfers where croc creates subdirectories
     * inside the working directory (e.g., myfolder/subfolder/file.txt).
     *
     * Uses Kotlin's built-in walkTopDown() which lazily traverses the tree.
     * This runs on a per-transfer temp directory that only contains files from
     * the current transfer, so even large folder transfers are instant to scan.
     */
    private fun collectAllFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile }.toList()

    /**
     * Extract just the parent-relative directory portion from a relative path.
     * e.g. "myfolder/subfolder/file.txt" -> "myfolder/subfolder"
     *      "file.txt" -> "" (no subfolder)
     */
    private fun relativeSubdir(relativePath: String): String {
        val idx = relativePath.lastIndexOf('/')
        return if (idx > 0) relativePath.substring(0, idx) else ""
    }

    private fun publishToSafTree(
        application: Application,
        source: File,
        treeUri: Uri,
        relativePath: String
    ): ReceivedFile? {
        val resolver = application.contentResolver
        val treeDoc = DocumentFile.fromTreeUri(application, treeUri) ?: return null

        // Create croc-received subdirectory inside the user-picked tree
        var targetDir = treeDoc.findFile("croc-received")
            ?: treeDoc.createDirectory("croc-received")
            ?: return null

        // If the file came from a folder transfer, recreate the subdirectory structure
        val subdir = relativeSubdir(relativePath)
        if (subdir.isNotEmpty()) {
            for (segment in subdir.split("/")) {
                targetDir = targetDir.findFile(segment)
                    ?: targetDir.createDirectory(segment)
                    ?: return null
            }
        }

        // Delete existing file with same name to overwrite
        targetDir.findFile(source.name)?.delete()

        val mimeType = URLConnection.guessContentTypeFromName(source.name) ?: "application/octet-stream"
        val destDoc = targetDir.createFile(mimeType, source.name) ?: return null

        return try {
            resolver.openOutputStream(destDoc.uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return null

            val displayPath = buildString {
                treeDoc.name?.let { append("$it/") }
                append("croc-received/")
                if (subdir.isNotEmpty()) append("$subdir/")
                append(source.name)
            }

            ReceivedFile(
                name = if (subdir.isNotEmpty()) "$subdir/${source.name}" else source.name,
                savedLocation = displayPath,
                uri = destDoc.uri,
                mimeType = mimeType
            )
        } catch (_: Exception) {
            destDoc.delete()
            null
        }
    }

    private fun publishToMediaStore(
        application: Application,
        source: File,
        relativePath: String
    ): ReceivedFile? {
        val resolver = application.contentResolver
        // For folder transfers, preserve subdirectory structure under croc-received
        val subdir = relativeSubdir(relativePath)
        val mediaRelativePath = if (subdir.isNotEmpty()) {
            "${Environment.DIRECTORY_DOWNLOADS}/croc-received/$subdir"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/croc-received"
        }
        val mimeType = URLConnection.guessContentTypeFromName(source.name) ?: "application/octet-stream"

        val existingUri = findExistingDownloadUri(application, source.name, mediaRelativePath)
        if (existingUri != null) {
            resolver.delete(existingUri, null, null)
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, mediaRelativePath)
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

            val displayLocation = if (subdir.isNotEmpty()) {
                "Downloads/croc-received/$subdir/${source.name}"
            } else {
                "Downloads/croc-received/${source.name}"
            }

            ReceivedFile(
                name = if (subdir.isNotEmpty()) "$subdir/${source.name}" else source.name,
                savedLocation = displayLocation,
                uri = uri,
                mimeType = mimeType
            )
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun findExistingDownloadUri(
        application: Application,
        displayName: String,
        relativePath: String
    ): Uri? {
        val resolver = application.contentResolver
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
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }

    private fun publishToPublicDownloads(
        application: Application,
        source: File,
        relativePath: String
    ): ReceivedFile? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        // For folder transfers, preserve subdirectory structure
        val subdir = relativeSubdir(relativePath)
        val targetDir = if (subdir.isNotEmpty()) {
            File(downloadsDir, "croc-received/$subdir").apply { mkdirs() }
        } else {
            File(downloadsDir, "croc-received").apply { mkdirs() }
        }
        val target = File(targetDir, source.name)

        return try {
            source.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(
                application,
                arrayOf(target.absolutePath),
                null,
                null
            )
            ReceivedFile(
                name = if (subdir.isNotEmpty()) "$subdir/${source.name}" else source.name,
                savedLocation = target.absolutePath,
                uri = FileProvider.getUriForFile(
                    application,
                    "${application.packageName}.fileprovider",
                    target
                ),
                mimeType = URLConnection.guessContentTypeFromName(target.name) ?: "application/octet-stream"
            )
        } catch (_: Exception) {
            null
        }
    }
}
