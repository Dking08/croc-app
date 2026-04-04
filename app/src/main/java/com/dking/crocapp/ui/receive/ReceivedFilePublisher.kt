package com.dking.crocapp.ui.receive

import android.app.Application
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLConnection

internal object ReceivedFilePublisher {

    fun publish(application: Application, outputDir: File): List<ReceivedFile> {
        val files = outputDir.listFiles()?.filter { it.isFile } ?: return emptyList()
        return files.mapNotNull { source ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(application, source)
            } else {
                publishToPublicDownloads(application, source)
            }
        }
    }

    private fun publishToMediaStore(application: Application, source: File): ReceivedFile? {
        val resolver = application.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/croc-received"
        val mimeType = URLConnection.guessContentTypeFromName(source.name) ?: "application/octet-stream"

        val existingUri = findExistingDownloadUri(application, source.name, relativePath)
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

    private fun publishToPublicDownloads(application: Application, source: File): ReceivedFile? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "croc-received").apply { mkdirs() }
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
                name = target.name,
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
