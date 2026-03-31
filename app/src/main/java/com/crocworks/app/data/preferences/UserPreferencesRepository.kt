package com.crocworks.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "croc_settings")

class UserPreferencesRepository(private val context: Context) {

    companion object {
        val RELAY_ADDRESS = stringPreferencesKey("relay_address")
        val RELAY_PASSWORD = stringPreferencesKey("relay_password")
        val PAKE_CURVE = stringPreferencesKey("pake_curve")
        val FORCE_LOCAL = booleanPreferencesKey("force_local")
        val DISABLE_COMPRESSION = booleanPreferencesKey("disable_compression")
        val UPLOAD_THROTTLE = stringPreferencesKey("upload_throttle")
        val MULTICAST_ADDRESS = stringPreferencesKey("multicast_address")
        val USE_INTERNAL_DNS = booleanPreferencesKey("use_internal_dns")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_CODE_PHRASE = stringPreferencesKey("default_code_phrase")
        val SAVED_CODE_PHRASES = stringSetPreferencesKey("saved_code_phrases")
    }

    data class CrocPreferences(
        val relayAddress: String = "croc.schollz.com:9009",
        val relayPassword: String = "pass123",
        val pakeCurve: String = "p256",
        val forceLocal: Boolean = false,
        val disableCompression: Boolean = false,
        val uploadThrottle: String = "",
        val multicastAddress: String = "239.255.255.250",
        val useInternalDns: Boolean = true,
        val themeMode: String = "system",
        val defaultCodePhrase: String = "",
        val savedCodePhrases: List<String> = emptyList()
    )

    val preferencesFlow: Flow<CrocPreferences> = context.dataStore.data.map { prefs ->
        val savedCodes = prefs[SAVED_CODE_PHRASES]
            ?.map(::normalizeCodePhrase)
            ?.filter { it.isNotBlank() }
            ?.sorted()
            .orEmpty()

        CrocPreferences(
            relayAddress = prefs[RELAY_ADDRESS] ?: "croc.schollz.com:9009",
            relayPassword = prefs[RELAY_PASSWORD] ?: "pass123",
            pakeCurve = prefs[PAKE_CURVE] ?: "p256",
            forceLocal = prefs[FORCE_LOCAL] ?: false,
            disableCompression = prefs[DISABLE_COMPRESSION] ?: false,
            uploadThrottle = prefs[UPLOAD_THROTTLE] ?: "",
            multicastAddress = prefs[MULTICAST_ADDRESS] ?: "239.255.255.250",
            // Android devices often have a broken localhost DNS path for the croc CLI.
            useInternalDns = if (prefs.contains(USE_INTERNAL_DNS)) prefs[USE_INTERNAL_DNS] ?: true else true,
            themeMode = prefs[THEME_MODE] ?: "system",
            defaultCodePhrase = normalizeCodePhrase(prefs[DEFAULT_CODE_PHRASE] ?: ""),
            savedCodePhrases = savedCodes
        )
    }

    suspend fun updateRelayAddress(value: String) {
        context.dataStore.edit { it[RELAY_ADDRESS] = value }
    }

    suspend fun updateRelayPassword(value: String) {
        context.dataStore.edit { it[RELAY_PASSWORD] = value }
    }

    suspend fun updatePakeCurve(value: String) {
        context.dataStore.edit { it[PAKE_CURVE] = value }
    }

    suspend fun updateForceLocal(value: Boolean) {
        context.dataStore.edit { it[FORCE_LOCAL] = value }
    }

    suspend fun updateDisableCompression(value: Boolean) {
        context.dataStore.edit { it[DISABLE_COMPRESSION] = value }
    }

    suspend fun updateUploadThrottle(value: String) {
        context.dataStore.edit { it[UPLOAD_THROTTLE] = value }
    }

    suspend fun updateMulticastAddress(value: String) {
        context.dataStore.edit { it[MULTICAST_ADDRESS] = value }
    }

    suspend fun updateUseInternalDns(value: Boolean) {
        context.dataStore.edit { it[USE_INTERNAL_DNS] = value }
    }

    suspend fun updateThemeMode(value: String) {
        context.dataStore.edit { it[THEME_MODE] = value }
    }

    suspend fun updateDefaultCodePhrase(value: String) {
        val normalized = normalizeCodePhrase(value)
        context.dataStore.edit { prefs ->
            if (normalized.isBlank()) {
                prefs.remove(DEFAULT_CODE_PHRASE)
            } else {
                prefs[DEFAULT_CODE_PHRASE] = normalized
            }
        }
    }

    suspend fun saveCodePhrase(value: String) {
        val normalized = normalizeCodePhrase(value)
        if (normalized.isBlank()) return

        context.dataStore.edit { prefs ->
            val updated = prefs[SAVED_CODE_PHRASES].orEmpty().toMutableSet()
            updated.add(normalized)
            prefs[SAVED_CODE_PHRASES] = updated
        }
    }

    suspend fun deleteCodePhrase(value: String) {
        val normalized = normalizeCodePhrase(value)
        context.dataStore.edit { prefs ->
            val updated = prefs[SAVED_CODE_PHRASES].orEmpty().toMutableSet()
            updated.remove(normalized)
            if (updated.isEmpty()) {
                prefs.remove(SAVED_CODE_PHRASES)
            } else {
                prefs[SAVED_CODE_PHRASES] = updated
            }
        }
    }

    private fun normalizeCodePhrase(value: String): String {
        return value.trim().replace(" ", "-")
    }
}
