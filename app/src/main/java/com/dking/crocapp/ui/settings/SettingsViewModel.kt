package com.dking.crocapp.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dking.crocapp.CrocApp
import com.dking.crocapp.croc.CrocEngine
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CrocApp
    private val prefsRepo = UserPreferencesRepository(application)
    private val binaryManager = app.binaryManager

    val preferences = prefsRepo.preferencesFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UserPreferencesRepository.CrocPreferences()
    )

    fun updateRelayAddress(value: String) = viewModelScope.launch { prefsRepo.updateRelayAddress(value) }
    fun updateRelayPassword(value: String) = viewModelScope.launch { prefsRepo.updateRelayPassword(value) }
    fun updatePakeCurve(value: String) = viewModelScope.launch { prefsRepo.updatePakeCurve(value) }
    fun updateForceLocal(value: Boolean) = viewModelScope.launch { prefsRepo.updateForceLocal(value) }
    fun updateDisableCompression(value: Boolean) = viewModelScope.launch { prefsRepo.updateDisableCompression(value) }
    fun updateUploadThrottle(value: String) = viewModelScope.launch { prefsRepo.updateUploadThrottle(value) }
    fun updateMulticastAddress(value: String) = viewModelScope.launch { prefsRepo.updateMulticastAddress(value) }
    fun updateUseInternalDns(value: Boolean) = viewModelScope.launch { prefsRepo.updateUseInternalDns(value) }
    fun updateThemeMode(value: String) = viewModelScope.launch { prefsRepo.updateThemeMode(value) }
    fun updateAmoledDark(value: Boolean) = viewModelScope.launch { prefsRepo.updateAmoledDark(value) }
    fun updateDefaultCodePhrase(value: String) = viewModelScope.launch { prefsRepo.updateDefaultCodePhrase(value) }
    fun saveCodePhrase(value: String) = viewModelScope.launch { prefsRepo.saveCodePhrase(value) }
    fun deleteCodePhrase(value: String) = viewModelScope.launch { prefsRepo.deleteCodePhrase(value) }
    fun updateQuickSendCode(value: String) = viewModelScope.launch { prefsRepo.updateQuickSendCode(value) }
    fun updateQuickReceiveCode(value: String) = viewModelScope.launch { prefsRepo.updateQuickReceiveCode(value) }
    fun updateReceiveLocation(uri: String) = viewModelScope.launch { prefsRepo.updateReceiveLocationUri(uri) }
    fun clearReceiveLocation() = viewModelScope.launch { prefsRepo.clearReceiveLocationUri() }
    fun updateTryLegacyFirst(value: Boolean) = viewModelScope.launch { prefsRepo.updateTryLegacyFirst(value) }
    fun updateSocks5Proxy(value: String) = viewModelScope.launch { prefsRepo.updateSocks5Proxy(value) }
    fun updateHttpProxy(value: String) = viewModelScope.launch { prefsRepo.updateHttpProxy(value) }
    fun updateReceiveConflictStrategy(value: String) = viewModelScope.launch { prefsRepo.updateReceiveConflictStrategy(value) }
    fun updateRelay6Address(value: String) = viewModelScope.launch { prefsRepo.updateRelay6Address(value) }
    fun updateSenderIp(value: String) = viewModelScope.launch { prefsRepo.updateSenderIp(value) }
    fun updateHashAlgorithm(value: String) = viewModelScope.launch { prefsRepo.updateHashAlgorithm(value) }
    fun updateDisableMultiplexing(value: Boolean) = viewModelScope.launch { prefsRepo.updateDisableMultiplexing(value) }
    fun updateTransferPorts(value: String) = viewModelScope.launch { prefsRepo.updateTransferPorts(value) }
    fun updateZipFolderBeforeSend(value: Boolean) = viewModelScope.launch { prefsRepo.updateZipFolderBeforeSend(value) }
    fun updateShowAdvancedSettings(value: Boolean) = viewModelScope.launch { prefsRepo.updateShowAdvancedSettings(value) }
    fun clearLegacyBinary() = viewModelScope.launch { binaryManager.clearBinary(CrocEngine.LEGACY) }
    fun isLegacyBinaryCached(): Boolean = binaryManager.isBinaryCached(CrocEngine.LEGACY)
}
