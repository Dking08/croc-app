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
            CrocProcess.relayFlags(UserPreferencesRepository.DEFAULT_RELAY_ADDRESS, "", resolveToIp)
        )
        assertEquals(emptyList<String>(), CrocProcess.relayFlags("  ", "", resolveToIp))
    }

    @Test
    fun customRelay_isResolvedAndDisablesIpv6Default() {
        assertEquals(
            listOf("--relay", "203.0.113.7:9009", "--relay6", ""),
            CrocProcess.relayFlags("4.getcroc.com:9009", "", resolveToIp)
        )
    }

    @Test
    fun builtinRelay_canBePinned() {
        assertEquals(
            listOf("--relay", "203.0.113.7:9009", "--relay6", ""),
            CrocProcess.relayFlags(UserPreferencesRepository.CROC_BUILTIN_RELAY, "", resolveToIp)
        )
    }

    @Test
    fun builtinRelayUnresolved_omitsRelay6_soCrocKeepsIt() {
        assertEquals(
            listOf("--relay", UserPreferencesRepository.CROC_BUILTIN_RELAY),
            CrocProcess.relayFlags(UserPreferencesRepository.CROC_BUILTIN_RELAY, "")
        )
    }

    @Test
    fun ipv6RelayOnly_passesOnlyRelay6() {
        assertEquals(
            listOf("--relay6", "[2001:db8::1]:9009"),
            CrocProcess.relayFlags("", "[2001:db8::1]:9009", resolveToIp)
        )
    }
}
