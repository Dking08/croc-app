package com.dking.crocapp.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferDiagnosticClassifierTest {

    @Test
    fun testOfflineDeviceClassification() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 1,
            outputLines = listOf("connecting to relay..."),
            isNetworkConnected = false,
            relayAddress = "croc.schollz.com:9009"
        )

        assertEquals(FailureCategory.NETWORK_CONNECTIVITY, result.category)
        assertTrue(result.headline.contains("Offline"))
        assertTrue(result.actionItems.any { it.contains("Wi-Fi") || it.contains("Mobile Data") })
    }

    @Test
    fun testPakeLegacyVersionMismatchClassification() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 1,
            outputLines = listOf(
                "connecting...",
                "error: unsupported PAKE protocol version",
                "failed to handshake"
            ),
            isNetworkConnected = true,
            relayAddress = "croc.schollz.com:9009"
        )

        assertEquals(FailureCategory.PAKE_HANDSHAKE, result.category)
        assertTrue(result.headline.contains("Protocol Version Mismatch"))
        assertTrue(result.actionItems.any { it.contains("Legacy Mode") })
    }

    @Test
    fun testCouldNotSecureChannelClassification() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 1,
            outputLines = listOf(
                "Securing channel...",
                "error: could not secure channel"
            ),
            isNetworkConnected = true,
            relayAddress = "croc.schollz.com:9009",
            curve = "p256"
        )

        assertEquals(FailureCategory.PAKE_HANDSHAKE, result.category)
        assertTrue(result.headline.contains("Could Not Secure Channel"))
        assertTrue(result.probableCauses.any { it.contains("Code phrase typo") })
    }

    @Test
    fun testDnsResolutionFailureClassification() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 1,
            outputLines = listOf(
                "dial tcp: lookup fake-server-999.invalid: no such host"
            ),
            isNetworkConnected = true,
            relayAddress = "fake-server-999.invalid:9009"
        )

        assertEquals(FailureCategory.RELAY_UNAVAILABLE, result.category)
        assertTrue(result.headline.contains("DNS Lookup Failed") || result.headline.contains("Relay Not Found"))
        assertTrue(result.explanation.contains("fake-server-999.invalid"))
        assertTrue(result.actionItems.any { it.contains("croc.schollz.com:9009") })
    }

    @Test
    fun testConnectionRefusedClassification() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 1,
            outputLines = listOf(
                "dial tcp 127.0.0.1:9009: connect: connection refused"
            ),
            isNetworkConnected = true,
            relayAddress = "127.0.0.1:9009"
        )

        assertEquals(FailureCategory.RELAY_UNAVAILABLE, result.category)
        assertTrue(result.headline.contains("Connection Refused") || result.headline.contains("Port 9009"))
    }

    @Test
    fun testRateLimitClassification() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 1,
            outputLines = listOf(
                "relay admission rate limited: room join limit exceeded"
            ),
            isNetworkConnected = true,
            relayAddress = "croc.schollz.com:9009"
        )

        assertEquals(FailureCategory.RELAY_CAPACITY, result.category)
        assertTrue(result.headline.contains("Rate Limit"))
    }

    @Test
    fun testFlateCorruptionClassification() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 1,
            outputLines = listOf(
                "flate: corrupt input before offset 5"
            ),
            isNetworkConnected = true,
            relayAddress = "croc.schollz.com:9009"
        )

        assertEquals(FailureCategory.DATA_CORRUPTION, result.category)
        assertTrue(result.headline.contains("Corrupted") || result.headline.contains("Stream"))
    }

    @Test
    fun testRoomFullClassification() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 1,
            outputLines = listOf(
                "room is full"
            ),
            isNetworkConnected = true,
            relayAddress = "croc.schollz.com:9009"
        )

        assertEquals(FailureCategory.RELAY_CAPACITY, result.category)
        assertTrue(result.headline.contains("Room"))
    }

    @Test
    fun testPermissionDeniedClassification() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 1,
            outputLines = listOf(
                "open /storage/emulated/0/Download/file.pdf: permission denied"
            ),
            isNetworkConnected = true,
            relayAddress = "croc.schollz.com:9009"
        )

        assertEquals(FailureCategory.STORAGE_PERMISSION, result.category)
        assertTrue(result.headline.contains("Permission Denied") || result.headline.contains("Storage"))
    }

    @Test
    fun testUnknownErrorFallback() {
        val result = TransferDiagnosticClassifier.classify(
            exitCode = 137,
            outputLines = listOf(
                "Killed"
            ),
            isNetworkConnected = true,
            relayAddress = "croc.schollz.com:9009"
        )

        assertFalse(result.actionItems.isEmpty())
        assertFalse(result.probableCauses.isEmpty())
    }
}
