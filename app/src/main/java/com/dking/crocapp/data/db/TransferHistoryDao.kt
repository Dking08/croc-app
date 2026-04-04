package com.dking.crocapp.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferHistoryDao {

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferHistory>>

    @Query("SELECT * FROM transfer_history WHERE isFavorite = 1 ORDER BY timestamp DESC")
    fun getFavorites(): Flow<List<TransferHistory>>

    @Query("SELECT * FROM transfer_history WHERE type = :type ORDER BY timestamp DESC")
    fun getTransfersByType(type: TransferType): Flow<List<TransferHistory>>

    @Query("SELECT * FROM transfer_history WHERE code LIKE '%' || :query || '%' OR fileName LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchTransfers(query: String): Flow<List<TransferHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: TransferHistory): Long

    @Update
    suspend fun update(transfer: TransferHistory)

    @Delete
    suspend fun delete(transfer: TransferHistory)

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE transfer_history SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long, isFavorite: Boolean)
}
