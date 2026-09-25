package com.dking.crocapp.debug

import com.dking.crocapp.data.db.AppDatabase
import com.dking.crocapp.data.db.TransferHistory
import com.dking.crocapp.data.db.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class HistoryAuditReport(
    val totalTransfers: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val cancelledCount: Int = 0,
    val successRatePercent: Int = 100,
    val recentFailures: List<TransferHistory> = emptyList(),
    val diagnosedPatterns: List<String> = emptyList()
)

object TransferHistoryAuditor {

    suspend fun audit(database: AppDatabase): HistoryAuditReport = withContext(Dispatchers.IO) {
        val allTransfers = try {
            database.transferHistoryDao().getAllTransfers().first()
        } catch (_: Exception) {
            emptyList()
        }

        if (allTransfers.isEmpty()) {
            return@withContext HistoryAuditReport()
        }

        val total = allTransfers.size
        val completed = allTransfers.count { it.status == TransferStatus.COMPLETED }
        val failed = allTransfers.count { it.status == TransferStatus.FAILED }
        val cancelled = allTransfers.count { it.status == TransferStatus.CANCELLED }
        val successRate = if (total > 0) ((completed.toDouble() / total) * 100).toInt() else 100

        val recentFails = allTransfers
            .filter { it.status == TransferStatus.FAILED }
            .take(10)

        val patterns = mutableListOf<String>()

        if (failed > 0 && failed >= completed) {
            patterns.add("High failure rate detected ($failed failures out of $total total transfers). Check network connectivity and relay server status.")
        }

        if (cancelled > 0 && cancelled > completed) {
            patterns.add("High number of cancelled transfers ($cancelled). This often indicates transfers being aborted while stuck in infinite 'Waiting for peer' loops.")
        }

        HistoryAuditReport(
            totalTransfers = total,
            completedCount = completed,
            failedCount = failed,
            cancelledCount = cancelled,
            successRatePercent = successRate,
            recentFailures = recentFails,
            diagnosedPatterns = patterns
        )
    }
}
