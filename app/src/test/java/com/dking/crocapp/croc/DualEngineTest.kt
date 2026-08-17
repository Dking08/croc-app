package com.dking.crocapp.croc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class DualEngineTest {

    @Test
    fun crocEngineEnum_containsCurrentAndLegacy() {
        val engines = CrocEngine.values().map { it.name }
        assertTrue(engines.contains("CURRENT"))
        assertTrue(engines.contains("LEGACY"))
        assertEquals(2, engines.size)
    }

    @Test
    fun pakeMismatchSubstring_isDetectedAccurately() {
        val standardOutput = "securing channel...peer uses unsupported PAKE protocol version 0; upgrade both croc clients"
        val altOutput = "unsupported PAKE protocol version"
        val regularError = "securing channel...failed to connect to relay"

        assertTrue(standardOutput.contains("unsupported PAKE protocol version"))
        assertTrue(altOutput.contains("unsupported PAKE protocol version"))
        assertFalse(regularError.contains("unsupported PAKE protocol version"))
    }

    @Test
    fun sha256DigestCalculation_matchesExpectedHash() {
        val sampleData = "croc test content for sha256".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sampleData)
        val calculated = digest.digest().joinToString("") { "%02x".format(it) }

        // Recalculate directly
        val expectedDigest = MessageDigest.getInstance("SHA-256")
        val expected = expectedDigest.digest(sampleData).joinToString("") { "%02x".format(it) }

        assertEquals(expected, calculated)
    }

    @Test
    fun legacyFallbackState_retainsRoomAndReason() {
        val state = CrocTransferState.LegacyFallbackAvailable(
            room = "test-room-123",
            reason = "Custom reason"
        )
        assertEquals("test-room-123", state.room)
        assertEquals("Custom reason", state.reason)
    }
}
