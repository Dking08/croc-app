package com.crocworks.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crocworks.app.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = UserPreferencesRepository(application)

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
}
