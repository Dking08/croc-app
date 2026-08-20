package com.dking.crocapp.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dking.crocapp.CrocApp
import com.dking.crocapp.data.db.TransferHistory
import com.dking.crocapp.data.db.TransferType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val transfers: List<TransferHistory> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val searchQuery: String = ""
)

enum class HistoryFilter {
    ALL, SENT, RECEIVED, STORED, FAVORITES
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = (application as CrocApp).database.transferHistoryDao()

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    // Track the current collection job to cancel it when filter/search changes
    private var collectionJob: Job? = null

    init {
        loadTransfers()
    }

    fun setFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(filter = filter) }
        loadTransfers()
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        loadTransfers()
    }

    fun toggleFavorite(transfer: TransferHistory) {
        viewModelScope.launch {
            dao.toggleFavorite(transfer.id, !transfer.isFavorite)
        }
    }

    fun deleteTransfer(transfer: TransferHistory) {
        viewModelScope.launch {
            dao.delete(transfer)
        }
    }

    fun revokeStoredTransfer(transfer: TransferHistory, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val storeId = transfer.storeId ?: return
        viewModelScope.launch {
            val app = getApplication<CrocApp>()
            val crocProcess = com.dking.crocapp.croc.CrocProcess(
                app,
                com.dking.crocapp.croc.CrocBinaryManager(app),
                com.dking.crocapp.data.preferences.UserPreferencesRepository(app)
            )
            val result = crocProcess.revokeStore(storeId)
            result.onSuccess { msg ->
                dao.markAsRevoked(transfer.id)
                onResult(true, msg)
            }.onFailure { err ->
                onResult(false, err.message ?: "Revoke failed")
            }
        }
    }

    private fun loadTransfers() {
        // Cancel previous collector to prevent leaks
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            val flow = when {
                _uiState.value.searchQuery.isNotBlank() ->
                    dao.searchTransfers(_uiState.value.searchQuery)
                _uiState.value.filter == HistoryFilter.FAVORITES ->
                    dao.getFavorites()
                _uiState.value.filter == HistoryFilter.SENT ->
                    dao.getTransfersByType(TransferType.SEND)
                _uiState.value.filter == HistoryFilter.RECEIVED ->
                    dao.getTransfersByType(TransferType.RECEIVE)
                _uiState.value.filter == HistoryFilter.STORED ->
                    dao.getStoredTransfers()
                else -> dao.getAllTransfers()
            }
            flow.collect { transfers ->
                _uiState.update { it.copy(transfers = transfers) }
            }
        }
    }
}
