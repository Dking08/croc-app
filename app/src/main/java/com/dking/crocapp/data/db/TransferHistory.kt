package com.dking.crocapp.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_history")
data class TransferHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val code: String,
    val type: TransferType,
    val fileName: String,
    val fileSize: Long = 0,
    val fileUri: String? = null,
    val mimeType: String? = null,
    val savedLocation: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: TransferStatus = TransferStatus.COMPLETED,
    val isFavorite: Boolean = false,
    // Stored transfer metadata (croc v11)
    val isStored: Boolean = false,
    val storeId: String? = null,
    val storeLink: String? = null,
    val storeToken: String? = null,
    val expiresAt: Long? = null,
    val isRevoked: Boolean = false,
    val storeDownloads: Int = 1
)

enum class TransferType {
    SEND, RECEIVE
}

enum class TransferStatus {
    IN_PROGRESS, COMPLETED, FAILED, CANCELLED, REVOKED, EXPIRED
}
