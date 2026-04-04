package com.dking.crocapp.croc

/**
 * Represents the state of a croc file transfer operation.
 */
sealed class CrocTransferState {
    data object Idle : CrocTransferState()
    data object Preparing : CrocTransferState()

    data class WaitingForPeer(
        val code: String
    ) : CrocTransferState()

    data class Transferring(
        val fileName: String,
        val currentFile: Int,
        val totalFiles: Int,
        val currentFilePercent: Int,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0,
        val etaSeconds: Int = 0,
        val peerIp: String = ""
    ) : CrocTransferState() {

        /** File-count-based overall progress (0..1).
         *  e.g. file 3/5 at 60% → (2 + 0.6) / 5 = 0.52
         *  This is used for the sweep-gradient border around cards. */
        val fileCountProgress: Float
            get() {
                if (totalFiles <= 0) return 0f
                return ((currentFile - 1f) + currentFilePercent / 100f) / totalFiles
            }

        /** Byte-based overall progress (0..1) for the linear progress bar. */
        val progress: Float
            get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

        val progressPercent: Int
            get() = (progress * 100).toInt()
    }

    data class Completed(
        val fileNames: List<String>,
        val totalBytes: Long,
        val peerIp: String = "",
        val totalFileCount: Int = 0,
        val receivedText: String? = null
    ) : CrocTransferState() {
        /** Actual number of files transferred. Prefers N/M from parser. */
        val fileCount: Int
            get() = if (totalFileCount > 0) totalFileCount else fileNames.size.coerceAtLeast(1)

        /** True when this transfer was a text-only transfer */
        val isTextTransfer: Boolean
            get() = receivedText != null
    }

    data class Error(
        val message: String
    ) : CrocTransferState()

    data object Cancelled : CrocTransferState()
}
