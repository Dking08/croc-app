package com.dking.crocapp.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Utility responsible for managing and cleaning up temporary transfer files and cache staging.
 */
object StorageCleaner {
    private const val TAG = "StorageCleaner"

    /**
     * Cleans up temporary staging copies created in [context.cacheDir] for sending files or folders.
     * Deletes `croc-send` and `croc-send-folder`.
     */
    fun cleanSendStaging(context: Context) {
        try {
            val sendDir = File(context.cacheDir, "croc-send")
            if (sendDir.exists()) {
                sendDir.deleteRecursively()
            }
            val sendFolderDir = File(context.cacheDir, "croc-send-folder")
            if (sendFolderDir.exists()) {
                sendFolderDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean send staging directories", e)
        }
    }

    /**
     * Cleans up orphaned timestamped folders inside internal app storage staging:
     * `getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)/croc-received`.
     *
     * Note: This is internal app staging storage, NOT public storage where completed
     * received files are published.
     */
    fun cleanReceiveStaging(context: Context) {
        try {
            val receivedDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "croc-received"
            )
            if (receivedDir.exists()) {
                receivedDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean receive staging directory", e)
        }
    }

    /**
     * Cleans up temporary Go CLI runtime files (`croc-tmp`) and temporary QR share bitmaps (`qr-share`).
     */
    fun cleanTempFiles(context: Context) {
        try {
            val tmpDir = File(context.cacheDir, "croc-tmp")
            if (tmpDir.exists()) {
                tmpDir.deleteRecursively()
            }
            val qrDir = File(context.cacheDir, "qr-share")
            if (qrDir.exists()) {
                qrDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean temp directories", e)
        }
    }

    /**
     * Runs on cold application startup. Purges all leftover staging directories from previous crashed or
     * ungracefully closed sessions.
     */
    fun cleanStaleStorage(context: Context) {
        Log.i(TAG, "Purging stale transfer storage and staging files on startup...")
        cleanSendStaging(context)
        cleanReceiveStaging(context)
        cleanTempFiles(context)
    }
}
