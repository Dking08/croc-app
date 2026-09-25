package com.dking.crocapp.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

enum class DebugLogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR
}

enum class DebugLogTag {
    RELAY, DNS, NETWORK, WATCHDOG, CONFIG, PROCESS, TRANSFER, SYSTEM
}

data class DebugLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: DebugLogLevel,
    val tag: DebugLogTag,
    val message: String,
    val details: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

    fun toDisplayString(): String {
        val detailPart = if (!details.isNullOrBlank()) " | $details" else ""
        return "[$formattedTime] [${level.name}] [${tag.name}] $message$detailPart"
    }
}

/**
 * In-memory, privacy-preserving ring buffer for diagnostic telemetry and croc CLI logs.
 * All user data (passwords, code phrases, private directory paths) is strictly sanitized
 * before storage. NOTHING ever leaves the device.
 */
object DebugLogManager {

    private const val MAX_ENTRIES = 500
    private const val LOG_TAG = "CrocDebug"

    private val entriesDeque = ConcurrentLinkedDeque<DebugLogEntry>()
    private val _logsFlow = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<DebugLogEntry>> = _logsFlow.asStateFlow()

    fun log(level: DebugLogLevel, tag: DebugLogTag, message: String, details: String? = null) {
        val sanitizedMsg = sanitize(message)
        val sanitizedDetails = details?.let { sanitize(it) }

        // Android logcat mirroring
        when (level) {
            DebugLogLevel.VERBOSE -> Log.v(LOG_TAG, "[${tag.name}] $sanitizedMsg")
            DebugLogLevel.DEBUG -> Log.d(LOG_TAG, "[${tag.name}] $sanitizedMsg")
            DebugLogLevel.INFO -> Log.i(LOG_TAG, "[${tag.name}] $sanitizedMsg")
            DebugLogLevel.WARN -> Log.w(LOG_TAG, "[${tag.name}] $sanitizedMsg")
            DebugLogLevel.ERROR -> Log.e(LOG_TAG, "[${tag.name}] $sanitizedMsg")
        }

        val entry = DebugLogEntry(
            level = level,
            tag = tag,
            message = sanitizedMsg,
            details = sanitizedDetails
        )

        entriesDeque.addLast(entry)
        while (entriesDeque.size > MAX_ENTRIES) {
            entriesDeque.pollFirst()
        }

        _logsFlow.value = entriesDeque.toList()
    }

    fun v(tag: DebugLogTag, msg: String, details: String? = null) = log(DebugLogLevel.VERBOSE, tag, msg, details)
    fun d(tag: DebugLogTag, msg: String, details: String? = null) = log(DebugLogLevel.DEBUG, tag, msg, details)
    fun i(tag: DebugLogTag, msg: String, details: String? = null) = log(DebugLogLevel.INFO, tag, msg, details)
    fun w(tag: DebugLogTag, msg: String, details: String? = null) = log(DebugLogLevel.WARN, tag, msg, details)
    fun e(tag: DebugLogTag, msg: String, details: String? = null) = log(DebugLogLevel.ERROR, tag, msg, details)

    fun clear() {
        entriesDeque.clear()
        _logsFlow.value = emptyList()
    }

    fun getLogs(): List<DebugLogEntry> = entriesDeque.toList()

    /**
     * Sanitizes sensitive fields like passwords, secrets, tokens, and absolute personal paths.
     */
    fun sanitize(input: String): String {
        var output = input

        // Redact passwords in flags
        output = output.replace(Regex("""--pass(\s+|=)[^\s]+""", RegexOption.IGNORE_CASE), "--pass ****")
        output = output.replace(Regex("""password(\s*[:=]\s*)[^\s,]+""", RegexOption.IGNORE_CASE), "password$1****")

        // Redact custom codes or secrets in flags
        output = output.replace(Regex("""--code(\s+|=)[^\s]+""", RegexOption.IGNORE_CASE), "--code ****")
        output = output.replace(Regex("""CROC_SECRET=[^\s]+""", RegexOption.IGNORE_CASE), "CROC_SECRET=****")
        output = output.replace(Regex("""CROC_STORE_TOKEN=[^\s]+""", RegexOption.IGNORE_CASE), "CROC_STORE_TOKEN=****")

        // Redact announced croc code: e.g. "Code is: 1234-word-word"
        output = output.replace(Regex("""(Code is:\s*)[^\s]+""", RegexOption.IGNORE_CASE), "$1****")

        // Sanitize personal Android absolute paths to generic labels
        output = output.replace(Regex("""/storage/emulated/\d+/[^\s]*?/([^/\s]+)"""), "[STORAGE]/.../$1")
        output = output.replace(Regex("""/data/user/\d+/[^\s]*?/([^/\s]+)"""), "[APP_DATA]/.../$1")

        return output
    }
}
