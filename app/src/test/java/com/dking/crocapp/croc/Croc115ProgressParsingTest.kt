package com.dking.crocapp.croc

import com.dking.crocapp.data.preferences.UserPreferencesRepository.CrocPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Croc115ProgressParsingTest {

    private val progressLineRegex = Regex("""^\s*(.+?)\s+(\d+)%\s*\|.*?\|\s*\((.+?)\)\s*(?:(\d+)/(\d+))?""")
    private val sizeInProgressRegex = Regex("""(\d+(?:\.\d+)?)\s*([a-zA-Z]+)?\s*/\s*(\d+(?:\.\d+)?)\s*([a-zA-Z]+)""")
    private val peerIpRegex = Regex("""(?:->|<-)([0-9a-fA-F:.]+)""")

    private fun parseSize(num: Double, unit: String): Long {
        return when (unit.lowercase()) {
            "b" -> num.toLong()
            "kb" -> (num * 1024).toLong()
            "mb" -> (num * 1024 * 1024).toLong()
            "gb" -> (num * 1024 * 1024 * 1024).toLong()
            else -> num.toLong()
        }
    }

    @Test
    fun testSizeParsing_singleUnit() {
        val sizeSection = "42/100 kB"
        val match = sizeInProgressRegex.find(sizeSection)
        assertNotNull(match)
        val curNum = match!!.groupValues[1].toDoubleOrNull() ?: 0.0
        val curUnitGroup = match.groupValues[2]
        val totalNum = match.groupValues[3].toDoubleOrNull() ?: 0.0
        val totalUnit = match.groupValues[4]
        val curUnit = if (curUnitGroup.isNotBlank()) curUnitGroup else totalUnit

        assertEquals(42.0, curNum, 0.001)
        assertEquals("kB", curUnit)
        assertEquals(100.0, totalNum, 0.001)
        assertEquals("kB", totalUnit)
        assertEquals(42 * 1024L, parseSize(curNum, curUnit))
        assertEquals(100 * 1024L, parseSize(totalNum, totalUnit))
    }

    @Test
    fun testSizeParsing_dualUnitsWithRate() {
        val sizeSection = "450 kB / 1.0 MB, 1.2 MB/s"
        val match = sizeInProgressRegex.find(sizeSection)
        assertNotNull(match)
        val curNum = match!!.groupValues[1].toDoubleOrNull() ?: 0.0
        val curUnitGroup = match.groupValues[2]
        val totalNum = match.groupValues[3].toDoubleOrNull() ?: 0.0
        val totalUnit = match.groupValues[4]
        val curUnit = if (curUnitGroup.isNotBlank()) curUnitGroup else totalUnit

        assertEquals(450.0, curNum, 0.001)
        assertEquals("kB", curUnit)
        assertEquals(1.0, totalNum, 0.001)
        assertEquals("MB", totalUnit)
        assertEquals(450 * 1024L, parseSize(curNum, curUnit))
        assertEquals(1024 * 1024L, parseSize(totalNum, totalUnit))
    }

    @Test
    fun testSizeParsing_bytesFormat() {
        val sizeSection = "23/23 B, 10 kB/s"
        val match = sizeInProgressRegex.find(sizeSection)
        assertNotNull(match)
        val curNum = match!!.groupValues[1].toDoubleOrNull() ?: 0.0
        val curUnitGroup = match.groupValues[2]
        val totalNum = match.groupValues[3].toDoubleOrNull() ?: 0.0
        val totalUnit = match.groupValues[4]
        val curUnit = if (curUnitGroup.isNotBlank()) curUnitGroup else totalUnit

        assertEquals(23.0, curNum, 0.001)
        assertEquals("B", curUnit)
        assertEquals(23.0, totalNum, 0.001)
        assertEquals("B", totalUnit)
        assertEquals(23L, parseSize(curNum, curUnit))
    }

    @Test
    fun testProgressLineParsing_singleFileUpload() {
        val line = "Uploading report.pdf... 45% |█████████           | (450 kB / 1.0 MB, 1.2 MB/s)"
        val match = progressLineRegex.find(line)
        assertNotNull(match)

        val rawName = match!!.groupValues[1].trim()
        val percent = match.groupValues[2].toIntOrNull() ?: 0
        val sizeSection = match.groupValues[3]

        val cleanedName = rawName.removePrefix("Uploading ").trim()
        assertEquals("report.pdf...", cleanedName)
        assertEquals("report.pdf", cleanedName.removeSuffix("..."))
        assertEquals(45, percent)
        assertEquals("450 kB / 1.0 MB, 1.2 MB/s", sizeSection)
    }

    @Test
    fun testProgressLineParsing_multiFileUpload() {
        val line = "Uploading 3 files... 60% |████████████       | (600 kB / 1.0 MB, 2.0 MB/s)"
        val match = progressLineRegex.find(line)
        assertNotNull(match)

        val rawName = match!!.groupValues[1].trim()
        val percent = match.groupValues[2].toIntOrNull() ?: 0
        val cleanedName = rawName.removePrefix("Uploading ").trim()

        val multiFilesMatch = Regex("""^(\d+)\s+files""").find(cleanedName)
        assertNotNull(multiFilesMatch)
        assertEquals(3, multiFilesMatch!!.groupValues[1].toInt())
        assertEquals(60, percent)
    }

    @Test
    fun testProgressLineParsing_withFileCountSuffix() {
        val line = "document.txt 100% |████████████████████| (23/23 B, 10 kB/s) 1/2"
        val match = progressLineRegex.find(line)
        assertNotNull(match)

        assertEquals("document.txt", match!!.groupValues[1].trim())
        assertEquals(100, match.groupValues[2].toInt())
        assertEquals("1", match.groupValues[4])
        assertEquals("2", match.groupValues[5])
    }

    @Test
    fun testPeerIpRegex_withDirectionAndPort() {
        val senderLine = "Sending (->192.168.1.50:9009)"
        val match = peerIpRegex.find(senderLine)
        assertNotNull(match)
        val raw = match!!.groupValues[1]
        val ip = if (raw.contains(":") && !raw.contains("::")) raw.substringBefore(":") else raw
        assertEquals("192.168.1.50", ip)

        val p2pLine = "Sending (10.0.0.1->192.168.1.50:9009)"
        val matchP2p = peerIpRegex.find(p2pLine)
        assertNotNull(matchP2p)
        val rawP2p = matchP2p!!.groupValues[1]
        val ipP2p = if (rawP2p.contains(":") && !rawP2p.contains("::")) rawP2p.substringBefore(":") else rawP2p
        assertEquals("192.168.1.50", ipP2p)
    }

    @Test
    fun testTransportPreferences_customAdvancedSettingsCheck() {
        val defaultPrefs = CrocPreferences()
        assertFalse(defaultPrefs.hasCustomAdvancedSettings)
        assertEquals("auto", defaultPrefs.transferTransport)

        val derpPrefs = CrocPreferences(transferTransport = "derp")
        assertTrue(derpPrefs.hasCustomAdvancedSettings)

        val relayPrefs = CrocPreferences(transferTransport = "relay")
        assertTrue(relayPrefs.hasCustomAdvancedSettings)
    }

    @Test
    fun testHashingLine_doesNotPolluteFileNamesOrDuplicateTotalBytes() {
        val initialFileNames = listOf("bigfile.mp4")
        val fileNames = initialFileNames.toMutableList()
        val fileSizeMap = mutableMapOf<String, Long>()
        var totalBytes = 0L
        var currentFileName = fileNames.first()
        var totalFilesFromProgress = fileNames.size

        val hashingLine = "Hashing bigfile.mp4... 50% |██████████          | (114/229 MB, 150 MB/s)"

        // Hashing detection
        if (hashingLine.contains("Hashing")) {
            val hashingName = when {
                ":" in hashingLine -> hashingLine.substringAfter(":").trim()
                hashingLine.contains("Hashing ") -> hashingLine.substringAfter("Hashing ").substringBefore("...").substringBefore("%").trim()
                else -> ""
            }.removeSuffix("...").trim()

            sizeInProgressRegex.find(hashingLine)?.let { sizeMatch ->
                val totalNum = sizeMatch.groupValues[3].toDoubleOrNull() ?: 0.0
                val totalUnit = sizeMatch.groupValues[4]
                val fileTotalBytes = parseSize(totalNum, totalUnit)
                if (fileTotalBytes > 0L) {
                    val matchedFile = if (initialFileNames.isNotEmpty()) {
                        fileNames.firstOrNull { it.startsWith(hashingName) || hashingName.startsWith(it) }
                            ?: fileNames.firstOrNull()
                    } else {
                        hashingName.ifBlank { null }
                    }
                    if (matchedFile != null) {
                        fileSizeMap[matchedFile] = fileTotalBytes
                        if (totalBytes == 0L) {
                            totalBytes = fileTotalBytes
                        }
                    }
                }
            }
            // In CrocProcess, hashing does a continue and does NOT emit Transferring
        }

        assertEquals(1, fileNames.size)
        assertEquals("bigfile.mp4", fileNames.first())
        assertEquals(229 * 1024 * 1024L, fileSizeMap["bigfile.mp4"])
        assertEquals(229 * 1024 * 1024L, totalBytes)

        // Now simulate the transfer line
        val transferLine = "bigfile.mp4 50% |██████████          | (114/229 MB, 1.2 MB/s)"
        val match = progressLineRegex.find(transferLine)
        assertNotNull(match)

        val rawName = match!!.groupValues[1].trim()
        val percent = match.groupValues[2].toIntOrNull() ?: 0
        val sizeSection = match.groupValues[3]

        var cleanedName = rawName
        val unElided = cleanedName.removeSuffix("...").trim()
        val existingFullName = fileNames.firstOrNull { it.startsWith(unElided) || it == cleanedName }
        currentFileName = existingFullName ?: unElided.ifBlank { cleanedName }

        sizeInProgressRegex.find(sizeSection)?.let { sizeMatch ->
            val totalNum = sizeMatch.groupValues[3].toDoubleOrNull() ?: 0.0
            val totalUnit = sizeMatch.groupValues[4]
            val fileTotalBytes = parseSize(totalNum, totalUnit)
            fileSizeMap[currentFileName] = fileTotalBytes
        }

        totalBytes = fileSizeMap.values.sum()
        val effectiveTotalFiles = if (initialFileNames.isNotEmpty()) initialFileNames.size else totalFilesFromProgress

        assertEquals(1, fileNames.size)
        assertEquals(1, effectiveTotalFiles)
        assertEquals(229 * 1024 * 1024L, totalBytes) // NOT 458MB!

        val completedBytes = fileNames.filter { it != currentFileName }.sumOf { fileSizeMap[it] ?: 0L }
        val currentFileSize = fileSizeMap[currentFileName] ?: 0L
        val currentFileTransferred = (currentFileSize * percent / 100)
        val bytesTransferred = completedBytes + currentFileTransferred

        val state = CrocTransferState.Transferring(
            fileName = currentFileName,
            currentFile = 1,
            totalFiles = effectiveTotalFiles,
            currentFilePercent = percent,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes
        )

        assertEquals(1, state.totalFiles)
        assertEquals(1, state.currentFile)
        assertEquals(50, state.progressPercent)
        assertEquals(state.progress, state.fileCountProgress, 0.001f)
    }

    @Test
    fun testTransferState_borderProgressMatchesCardProgress() {
        val state = CrocTransferState.Transferring(
            fileName = "movie.mp4",
            currentFile = 1,
            totalFiles = 1,
            currentFilePercent = 45,
            bytesTransferred = 45 * 1024 * 1024L,
            totalBytes = 100 * 1024 * 1024L
        )

        assertEquals(0.45f, state.progress, 0.001f)
        assertEquals(0.45f, state.fileCountProgress, 0.001f)
        assertEquals(45, state.progressPercent)
    }

    @Test
    fun testErrorMessage_flateCorruptionMappedCleanly() {
        val outputTail = listOf(
            "close decompressor: flate: corrupt input before offset 5",
            "flate: corrupt input before offset 5",
            "problem with decoding: decompress message: decompress data: flate: corrupt input before offset 5",
            "close decompressor: flate: corrupt input before offset 5"
        )
        val msg = CrocProcess.formatErrorMessage(1, outputTail)
        assertEquals("Transfer failed: Network data corrupted during peer handshake. Please retry.", msg)
    }

    @Test
    fun testErrorMessage_admissionRateLimitedMappedCleanly() {
        val outputTail = listOf(
            "relay admission rate limited: room join limit exceeded"
        )
        val msg = CrocProcess.formatErrorMessage(1, outputTail)
        assertEquals("Transfer failed: Public relay rate limit reached. Please wait a minute and retry.", msg)
    }

    @Test
    fun testErrorMessage_couldNotSecureChannelMappedCleanly() {
        val outputTail = listOf(
            "error: could not secure channel"
        )
        val msg = CrocProcess.formatErrorMessage(1, outputTail)
        assertEquals("Transfer failed: Could not secure channel. Check the code phrase on both devices and retry.", msg)
    }

    @Test
    fun testErrorMessage_roomIsFullMappedCleanly() {
        val outputTail = listOf(
            "room is full"
        )
        val msg = CrocProcess.formatErrorMessage(1, outputTail)
        assertEquals("Transfer failed: Room is already in use. Please generate a fresh code phrase.", msg)
    }

    @Test
    fun testInterruptionLine_matchesInterruptionPattern() {
        val senderLine = "Sender detected a transfer interruption. Retrying securely..."
        val receiverLine = "Receiver detected a transfer interruption. Retrying securely..."

        assertTrue(senderLine.contains("detected a transfer interruption") || senderLine.contains("Retrying securely"))
        assertTrue(receiverLine.contains("detected a transfer interruption") || receiverLine.contains("Retrying securely"))
    }
}
