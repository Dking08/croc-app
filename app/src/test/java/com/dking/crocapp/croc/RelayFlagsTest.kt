package com.dking.crocapp.croc

import com.dking.crocapp.data.preferences.UserPreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayFlagsTest {

    private val resolveToIp: (String) -> String = { "203.0.113.7:9009" }

    @Test
    fun automaticRelay_passesNoRelayFlags_soCrocDerivesPublicRelayFromCode() {
        assertEquals(
            emptyList<String>(),
            CrocProcess.relayFlags(UserPreferencesRepository.DEFAULT_RELAY_ADDRESS, "", CrocEngine.CURRENT, resolveToIp)
        )
        assertEquals(emptyList<String>(), CrocProcess.relayFlags("  ", "", CrocEngine.CURRENT, resolveToIp))
    }

    @Test
    fun customRelay_isResolved() {
        assertEquals(
            listOf("--relay", "203.0.113.7:9009"),
            CrocProcess.relayFlags(" 4.getcroc.com:9009 ", "", CrocEngine.CURRENT, resolveToIp)
        )
    }

    @Test
    fun ipv6RelayOnly_passesOnlyRelay6() {
        assertEquals(
            listOf("--relay6", "[2001:db8::1]:9009"),
            CrocProcess.relayFlags("", "[2001:db8::1]:9009", CrocEngine.CURRENT, resolveToIp)
        )
    }

    @Test
    fun bothRelays_passesBoth() {
        assertEquals(
            listOf("--relay", "203.0.113.7:9009", "--relay6", "[2001:db8::1]:9009"),
            CrocProcess.relayFlags("relay.example:9009", "[2001:db8::1]:9009", CrocEngine.CURRENT, resolveToIp)
        )
    }

    @Test
    fun legacyEngine_automaticRelay_passesResolvedLegacyDefault() {
        val resolved = mutableListOf<String>()
        assertEquals(
            listOf("--relay", "203.0.113.7:9009"),
            CrocProcess.relayFlags("", "", CrocEngine.LEGACY) { resolved += it; resolveToIp(it) }
        )
        assertEquals(listOf(CrocProcess.LEGACY_DEFAULT_RELAY), resolved)
    }

    @Test
    fun legacyEngine_customRelay_isKept() {
        assertEquals(
            listOf("--relay", "relay.example:9009"),
            CrocProcess.relayFlags("relay.example:9009", "", CrocEngine.LEGACY)
        )
    }
}
