package com.dking.crocapp.ui.receive

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
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

    fun publish(
        application: Application,
        outputDir: File,
        customTreeUri: Uri? = null,
        conflictStrategy: String = "rename"
    ): List<ReceivedFile> {
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
                publishToSafTree(application, source, customTreeUri, relativePath, conflictStrategy)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(application, source, relativePath, conflictStrategy)
            } else {
                publishToPublicDownloads(application, source, relativePath, conflictStrategy)
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
        relativePath: String,
        conflictStrategy: String
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

        val mimeType = URLConnection.guessContentTypeFromName(source.name) ?: "application/octet-stream"

        val destDoc: DocumentFile = if (conflictStrategy == "overwrite") {
            val existingDoc = targetDir.findFile(source.name)
            if (existingDoc != null && existingDoc.isFile) {
                existingDoc
            } else {
                targetDir.createFile(mimeType, source.name) ?: return null
            }
        } else {
            // "rename": create a new file (SAF automatically appends (1), (2) on collision)
            targetDir.createFile(mimeType, source.name) ?: return null
        }

        return try {
            resolver.openOutputStream(destDoc.uri, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return null

            val actualName = destDoc.name ?: source.name
            val displayPath = buildString {
                treeDoc.name?.let { append("$it/") }
                append("croc-received/")
                if (subdir.isNotEmpty()) append("$subdir/")
                append(actualName)
            }

            ReceivedFile(
                name = if (subdir.isNotEmpty()) "$subdir/$actualName" else actualName,
                savedLocation = displayPath,
                uri = destDoc.uri,
                mimeType = mimeType
            )
        } catch (_: Exception) {
            if (conflictStrategy != "overwrite") {
                destDoc.delete()
            }
            null
        }
    }

    private fun publishToMediaStore(
        application: Application,
        source: File,
        relativePath: String,
        conflictStrategy: String
    ): ReceivedFile? {
        val resolver = application.contentResolver
        // For folder transfers, preserve subdirectory structure under croc-received
        val subdir = relativeSubdir(relativePath)
        val mediaRelativePath = if (subdir.isNotEmpty()) {
            "${Environment.DIRECTORY_DOWNLOADS}/croc-received/$subdir"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/croc-received"
        }
        val formattedPath = if (mediaRelativePath.endsWith("/")) mediaRelativePath else "$mediaRelativePath/"
        val mimeType = URLConnection.guessContentTypeFromName(source.name) ?: "application/octet-stream"

        if (conflictStrategy == "overwrite") {
            val existingUri = findExistingMediaUri(application, source.name, mediaRelativePath)
            if (existingUri != null) {
                val overwritten = try {
                    resolver.openOutputStream(existingUri, "wt")?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                    true
                } catch (_: Exception) {
                    try {
                        resolver.delete(existingUri, null, null)
                    } catch (_: Exception) {}
                    false
                }

                if (overwritten) {
                    val displayLocation = if (subdir.isNotEmpty()) {
                        "Downloads/croc-received/$subdir/${source.name}"
                    } else {
                        "Downloads/croc-received/${source.name}"
                    }
                    return ReceivedFile(
                        name = if (subdir.isNotEmpty()) "$subdir/${source.name}" else source.name,
                        savedLocation = displayLocation,
                        uri = existingUri,
                        mimeType = mimeType
                    )
                }
            }

            // Fallback for overwrite: ensure any existing physical file or MediaStore record is removed so (1) is not appended
            try {
                val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetPhysicalFile = File(publicDownloads, if (subdir.isNotEmpty()) "croc-received/$subdir/${source.name}" else "croc-received/${source.name}")
                if (targetPhysicalFile.exists()) {
                    targetPhysicalFile.delete()
                }
            } catch (_: Exception) {}

            try {
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Files.getContentUri("external")
                }
                resolver.delete(
                    collection,
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? COLLATE NOCASE AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf(source.name, "%croc-received%")
                )
            } catch (_: Exception) {}
        }

        // Insert into MediaStore Downloads collection
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, formattedPath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val insertUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        return try {
            resolver.openOutputStream(insertUri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: run {
                resolver.delete(insertUri, null, null)
                return null
            }

            val readyValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(insertUri, readyValues, null, null)

            val actualDisplayName = getDisplayNameFromUri(resolver, insertUri) ?: source.name
            val displayLocation = if (subdir.isNotEmpty()) {
                "Downloads/croc-received/$subdir/$actualDisplayName"
            } else {
                "Downloads/croc-received/$actualDisplayName"
            }

            ReceivedFile(
                name = if (subdir.isNotEmpty()) "$subdir/$actualDisplayName" else actualDisplayName,
                savedLocation = displayLocation,
                uri = insertUri,
                mimeType = mimeType
            )
        } catch (_: Exception) {
            resolver.delete(insertUri, null, null)
            null
        }
    }

    private fun findExistingMediaUri(
        application: Application,
        displayName: String,
        mediaRelativePath: String
    ): Uri? {
        val resolver = application.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )

        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? COLLATE NOCASE AND (${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?)"
        val pathPattern1 = "%${mediaRelativePath.trim('/')}%"
        val pathPattern2 = "%croc-received%"
        val selectionArgs = arrayOf(displayName, pathPattern1, pathPattern2)

        return try {
            resolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Files.FileColumns._ID} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getDisplayNameFromUri(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun publishToPublicDownloads(
        application: Application,
        source: File,
        relativePath: String,
        conflictStrategy: String
    ): ReceivedFile? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        // For folder transfers, preserve subdirectory structure
        val subdir = relativeSubdir(relativePath)
        val targetDir = if (subdir.isNotEmpty()) {
            File(downloadsDir, "croc-received/$subdir").apply { mkdirs() }
        } else {
            File(downloadsDir, "croc-received").apply { mkdirs() }
        }
        val targetFile = if (conflictStrategy == "overwrite") {
            File(targetDir, source.name)
        } else {
            getUniqueFile(targetDir, source.name)
        }

        return try {
            source.copyTo(targetFile, overwrite = true)
            MediaScannerConnection.scanFile(
                application,
                arrayOf(targetFile.absolutePath),
                null,
                null
            )
            ReceivedFile(
                name = if (subdir.isNotEmpty()) "$subdir/${targetFile.name}" else targetFile.name,
                savedLocation = targetFile.absolutePath,
                uri = FileProvider.getUriForFile(
                    application,
                    "${application.packageName}.fileprovider",
                    targetFile
                ),
                mimeType = URLConnection.guessContentTypeFromName(targetFile.name) ?: "application/octet-stream"
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getUniqueFile(dir: File, fileName: String): File {
        var file = File(dir, fileName)
        if (!file.exists()) return file

        val nameWithoutExt = file.nameWithoutExtension
        val ext = file.extension
        val extWithDot = if (ext.isNotEmpty()) ".$ext" else ""

        var counter = 1
        while (file.exists()) {
            file = File(dir, "$nameWithoutExt ($counter)$extWithDot")
            counter++
        }
        return file
    }
}
