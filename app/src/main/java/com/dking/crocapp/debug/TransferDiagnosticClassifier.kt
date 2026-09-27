package com.dking.crocapp.debug

enum class FailureCategory {
    PAKE_HANDSHAKE,
    NETWORK_CONNECTIVITY,
    RELAY_UNAVAILABLE,
    RELAY_CAPACITY,
    DATA_CORRUPTION,
    STORAGE_PERMISSION,
    CLI_SYNTAX,
    TIMEOUT,
    UNKNOWN
}

data class TransferFailureAnalysis(
    val category: FailureCategory,
    val headline: String,
    val explanation: String,
    val probableCauses: List<String>,
    val actionItems: List<String>,
    val offendingLines: List<String>,
    val exitCode: Int?
)

/**
 * General, comprehensive diagnostic classifier for ANY and ALL transfer failures in croc.
 * Analyzes the entire process output stream, exit codes, and network state to pinpoint
 * root causes (PAKE failures, timeouts, relay unreachable, DNS failure, corruptions, rate limits).
 */
object TransferDiagnosticClassifier {

    fun classify(
        exitCode: Int,
        outputLines: List<String>,
        isNetworkConnected: Boolean,
        relayAddress: String,
        curve: String = "p256"
    ): TransferFailureAnalysis {
        val fullOutput = outputLines.joinToString("\n").lowercase()

        // 1. Device Offline / Network Disconnected
        if (!isNetworkConnected) {
            return TransferFailureAnalysis(
                category = FailureCategory.NETWORK_CONNECTIVITY,
                headline = "Transfer Failed: Device is Offline",
                explanation = "The device does not have an active internet connection. Croc cannot connect to the relay server or peer.",
                probableCauses = listOf(
                    "Wi-Fi or Mobile Data was toggled OFF",
                    "Airplane mode is active",
                    "Device is on a captive portal with no actual internet access"
                ),
                actionItems = listOf(
                    "Connect to Wi-Fi or turn on Mobile Data in Android Settings",
                    "Check Diagnostics -> Network to verify internet reachability",
                    "Retry the transfer once connected"
                ),
                offendingLines = outputLines.takeLast(5),
                exitCode = exitCode
            )
        }

        // 2. Unsupported PAKE Protocol Version (Legacy Croc)
        if ("unsupported pake protocol version" in fullOutput) {
            val matched = outputLines.filter { "unsupported pake protocol version" in it.lowercase() }
            return TransferFailureAnalysis(
                category = FailureCategory.PAKE_HANDSHAKE,
                headline = "PAKE Protocol Version Mismatch (Legacy Croc Detected)",
                explanation = "The other device is using an older croc version (v10 or earlier) which uses a different PAKE key exchange protocol than croc v11.",
                probableCauses = listOf(
                    "The peer device is running croc v10.x.x or older",
                    "Croc was installed on the peer machine long ago without updating"
                ),
                actionItems = listOf(
                    "Tap 'Retry with Legacy Mode' to use croc v10 engine",
                    "Or update croc on the peer device to the latest version (e.g. 'croc --version')"
                ),
                offendingLines = if (matched.isNotEmpty()) matched else outputLines.takeLast(3),
                exitCode = exitCode
            )
        }

        // 3. Could Not Secure Channel (PAKE Key Exchange Failure)
        if ("could not secure channel" in fullOutput) {
            val isDefaultRelay = relayAddress.contains("croc.schollz.com")
            val causes = mutableListOf(
                "Code phrase typo: The sender and receiver entered different code phrases",
                "Relay mismatch: One device is on '$relayAddress' while the other is on a different relay"
            )
            if (isDefaultRelay) {
                causes.add("Desktop CLI relay pool: Desktop croc v11 defaults to '1-4.getcroc.com' pool unless '--relay croc.schollz.com:9009' is specified")
            }
            if (curve != "p256") {
                causes.add("Encryption curve mismatch: App is configured with curve '$curve'")
            }

            val actions = mutableListOf(
                "Double-check that both devices enter the EXACT same code phrase",
                "Ensure both devices specify the exact same relay: croc --relay $relayAddress <code>"
            )
            if (curve != "p256") {
                actions.add("Reset encryption curve to default 'p256' in Settings -> Advanced")
            }

            return TransferFailureAnalysis(
                category = FailureCategory.PAKE_HANDSHAKE,
                headline = "PAKE Handshake Failed: Could Not Secure Channel",
                explanation = "Both devices reached the relay, but failed the Password-Authenticated Key Exchange (PAKE). The cryptographic secrets did not match.",
                probableCauses = causes,
                actionItems = actions,
                offendingLines = outputLines.filter { "could not secure channel" in it.lowercase() }.ifEmpty { outputLines.takeLast(4) },
                exitCode = exitCode
            )
        }

        // 4. Bad Password (Relay Password Authentication)
        if ("bad password" in fullOutput) {
            return TransferFailureAnalysis(
                category = FailureCategory.PAKE_HANDSHAKE,
                headline = "Relay Authentication Failed: Bad Password",
                explanation = "The relay server rejected the connection because the configured password was incorrect or the relay does not accept passwords.",
                probableCauses = listOf(
                    "A custom relay password was entered in Settings -> Relay Server that the relay doesn't recognize",
                    "Public croc relays do not accept custom passwords and will reject connections"
                ),
                actionItems = listOf(
                    "Clear the 'Relay Password' in Settings -> Relay Server",
                    "If using a private self-hosted relay, verify the password configured on the server"
                ),
                offendingLines = outputLines.filter { "bad password" in it.lowercase() }.ifEmpty { outputLines.takeLast(3) },
                exitCode = exitCode
            )
        }

        // 5. Cryptographic / Curve Mismatch
        if ("crypto/subtle" in fullOutput || "curve" in fullOutput && "mismatch" in fullOutput) {
            return TransferFailureAnalysis(
                category = FailureCategory.PAKE_HANDSHAKE,
                headline = "Cryptographic Curve / Key Mismatch",
                explanation = "The cryptographic handshake failed due to an encryption curve incompatibility.",
                probableCauses = listOf(
                    "The two devices are configured with different PAKE curves (e.g. p256 vs curve25519 vs p384)",
                    "Current configured curve is '$curve'"
                ),
                actionItems = listOf(
                    "Set curve to 'p256' in Settings -> Advanced -> Encryption Curve",
                    "Ensure the desktop CLI does not specify a conflicting '--curve' flag"
                ),
                offendingLines = outputLines.takeLast(5),
                exitCode = exitCode
            )
        }

        // 6. Relay DNS Resolution Failure (No such host)
        if ("no such host" in fullOutput || ("lookup" in fullOutput && "dial" in fullOutput)) {
            val matched = outputLines.filter { "no such host" in it.lowercase() || "lookup" in it.lowercase() }
            return TransferFailureAnalysis(
                category = FailureCategory.RELAY_UNAVAILABLE,
                headline = "Relay Not Found: DNS Lookup Failed",
                explanation = "The Android system could not resolve the hostname for relay '$relayAddress'.",
                probableCauses = listOf(
                    "Relay address '$relayAddress' has a typo or the domain does not exist",
                    "Local DNS server (Wi-Fi router or cellular carrier) failed to resolve the address",
                    "VPN or firewall is blocking DNS requests"
                ),
                actionItems = listOf(
                    "Check Settings -> Relay Server for typos (e.g. remove spaces or 'http://')",
                    "Revert to default relay 'croc.schollz.com:9009'",
                    "Try toggling 'Use Built-in DNS' in Settings -> Network"
                ),
                offendingLines = matched.ifEmpty { outputLines.takeLast(3) },
                exitCode = exitCode
            )
        }

        // 7. Connection Refused (Port 9009 closed)
        if ("connection refused" in fullOutput || "econnrefused" in fullOutput) {
            return TransferFailureAnalysis(
                category = FailureCategory.RELAY_UNAVAILABLE,
                headline = "Relay Connection Refused (Port 9009 Closed)",
                explanation = "The host was reached, but no server is listening on port 9009.",
                probableCauses = listOf(
                    "The relay service on '$relayAddress' is stopped or crashed",
                    "Port 9009 is not forwarded or is blocked by a host firewall",
                    "A web server is running on the host instead of croc relay"
                ),
                actionItems = listOf(
                    "Verify your self-hosted relay daemon is running: 'croc relay'",
                    "Revert to default public relay 'croc.schollz.com:9009' in Settings"
                ),
                offendingLines = outputLines.filter { "connection refused" in it.lowercase() }.ifEmpty { outputLines.takeLast(3) },
                exitCode = exitCode
            )
        }

        // 8. Connection Timed Out / i/o timeout
        if ("i/o timeout" in fullOutput || "timed out" in fullOutput || "etimedout" in fullOutput) {
            return TransferFailureAnalysis(
                category = FailureCategory.TIMEOUT,
                headline = "Network Timeout: Connection Timed Out",
                explanation = "Communication with relay '$relayAddress' or peer timed out without receiving a response.",
                probableCauses = listOf(
                    "Packets dropped by firewall or carrier network",
                    "High network latency or packet loss",
                    "Peer device disconnected or entered deep sleep"
                ),
                actionItems = listOf(
                    "Switch from Cellular to Wi-Fi (or vice-versa)",
                    "Check Diagnostics -> Network to verify relay latency",
                    "Ensure both devices remain unlocked with croc active"
                ),
                offendingLines = outputLines.filter { "timeout" in it.lowercase() || "timed out" in it.lowercase() }.ifEmpty { outputLines.takeLast(3) },
                exitCode = exitCode
            )
        }

        // 9. Room Collision (Room is full)
        if ("room is full" in fullOutput) {
            return TransferFailureAnalysis(
                category = FailureCategory.RELAY_CAPACITY,
                headline = "Room Collision: Room is Already Full",
                explanation = "The room generated by the code phrase is currently occupied on the relay.",
                probableCauses = listOf(
                    "Another transfer on the same relay is using this exact secret room",
                    "A previous interrupted transfer session did not clean up its room on the relay yet"
                ),
                actionItems = listOf(
                    "Generate a fresh code phrase and retry",
                    "Wait 60 seconds for the stale room to expire on the relay"
                ),
                offendingLines = outputLines.filter { "room is full" in it.lowercase() }.ifEmpty { outputLines.takeLast(3) },
                exitCode = exitCode
            )
        }

        // 10. Public Relay Rate Limit (Admission limit)
        if ("admission" in fullOutput && "limit" in fullOutput) {
            return TransferFailureAnalysis(
                category = FailureCategory.RELAY_CAPACITY,
                headline = "Public Relay Rate Limit Reached",
                explanation = "The public croc relay rate-limited requests from your IP address to prevent abuse.",
                probableCauses = listOf(
                    "Multiple rapid transfers started in quick succession",
                    "Shared public IP (e.g. university or carrier CGNAT) with many active croc users"
                ),
                actionItems = listOf(
                    "Wait 1 to 2 minutes before starting another transfer",
                    "Or configure a private self-hosted relay in Settings -> Relay Server"
                ),
                offendingLines = outputLines.filter { "admission" in it.lowercase() }.ifEmpty { outputLines.takeLast(3) },
                exitCode = exitCode
            )
        }

        // 11. Stream Corruption (Flate / Corrupt Input)
        if ("flate: corrupt input" in fullOutput || "problem with decoding" in fullOutput) {
            return TransferFailureAnalysis(
                category = FailureCategory.DATA_CORRUPTION,
                headline = "Data Stream Corrupted in Transit",
                explanation = "The encrypted network data stream was corrupted during transmission between devices.",
                probableCauses = listOf(
                    "Network packet alteration or truncation by carrier proxy/middlebox",
                    "Severe Wi-Fi packet loss during data stream"
                ),
                actionItems = listOf(
                    "Turn ON 'Disable Compression' in Settings -> Advanced",
                    "Switch to a more stable Wi-Fi connection and retry"
                ),
                offendingLines = outputLines.filter { "flate:" in it.lowercase() || "corrupt" in it.lowercase() }.ifEmpty { outputLines.takeLast(3) },
                exitCode = exitCode
            )
        }

        // 12. Storage & Permission Denied
        if ("permission denied" in fullOutput || "read-only file system" in fullOutput) {
            return TransferFailureAnalysis(
                category = FailureCategory.STORAGE_PERMISSION,
                headline = "Storage Permission Denied",
                explanation = "Croc cannot read the input file or write to the destination directory.",
                probableCauses = listOf(
                    "Selected download folder has restricted permissions",
                    "File was moved or deleted while staging"
                ),
                actionItems = listOf(
                    "Reset receive folder to default 'Downloads/croc-received' in Settings -> Receive",
                    "Ensure Android storage permissions are granted"
                ),
                offendingLines = outputLines.filter { "permission denied" in it.lowercase() }.ifEmpty { outputLines.takeLast(3) },
                exitCode = exitCode
            )
        }

        // 13. CLI Usage Rejection
        if ("on unix systems, to receive with croc" in fullOutput || "on unix systems, to send with" in fullOutput) {
            return TransferFailureAnalysis(
                category = FailureCategory.CLI_SYNTAX,
                headline = "Croc Command Syntax Error",
                explanation = "The croc binary rejected the command line flags and printed its help guide.",
                probableCauses = listOf(
                    "Invalid flag combination configured in settings",
                    "Empty or malformed code phrase argument"
                ),
                actionItems = listOf(
                    "Use 'Reset Advanced Settings' in Settings -> Advanced -> Reset",
                    "Ensure code phrase does not contain forbidden shell characters"
                ),
                offendingLines = outputLines.takeLast(5),
                exitCode = exitCode
            )
        }

        // 14. General / Unknown Non-Zero Exit
        val mostInformativeLine = outputLines.asReversed().firstOrNull { l ->
            val t = l.trim().lowercase()
            t.isNotBlank() &&
                    !t.startsWith("close decompressor") &&
                    !t.startsWith("flate:") &&
                    (t.contains("error") || t.contains("fail") || t.contains("panic") || t.contains("fatal") || t.contains("invalid") || t.contains("cannot") || t.contains("unable"))
        } ?: outputLines.asReversed().firstOrNull { it.isNotBlank() } ?: "Process exited with code $exitCode"

        return TransferFailureAnalysis(
            category = FailureCategory.UNKNOWN,
            headline = "Transfer Failed (Exit Code $exitCode)",
            explanation = "Croc exited unexpectedly with code $exitCode: $mostInformativeLine",
            probableCauses = listOf(
                "Unexpected network disconnection or socket reset",
                "Peer device cancelled or closed the transfer",
                "Process terminated by Android memory manager"
            ),
            actionItems = listOf(
                "Check Diagnostics -> Logs for the complete process output stream",
                "Verify network stability and retry",
                "Use 'Copy Report' to export full logs for analysis"
            ),
            offendingLines = outputLines.takeLast(6),
            exitCode = exitCode
        )
    }
}
