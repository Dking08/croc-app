package com.crocworks.app.croc

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
        val bytesTransferred: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0,
        val etaSeconds: Int = 0
    ) : CrocTransferState() {
        val progress: Float
            get() = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
        val progressPercent: Int
            get() = (progress * 100).toInt()
    }

    data class Completed(
        val fileNames: List<String>,
        val totalBytes: Long
    ) : CrocTransferState()

    data class Error(
        val message: String
    ) : CrocTransferState()

    data object Cancelled : CrocTransferState()
}
