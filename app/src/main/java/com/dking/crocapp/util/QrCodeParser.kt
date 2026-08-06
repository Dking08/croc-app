package com.dking.crocapp.util

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object QrCodeParser {
    private val CODE_PARAM_REGEX = Regex("""[?&]code=([^&]+)""", RegexOption.IGNORE_CASE)

    /**
     * Parses scanned QR code raw text, deep links, or user input into a valid croc code phrase.
     * Supported formats:
     * 1. Bare code phrase (e.g. "1234-test", "word-word-word")
     * 2. Deep link scheme (e.g. "croc://receive?code=1234-test", "croc://1234-test")
     * 3. Web URL format (e.g. "https://getcroc.com/?code=1234-test", "http://getcroc.com/?code=1234-test", "getcroc.com/?code=1234-test")
     * 4. Any HTTP/HTTPS URL carrying a `code=` query parameter.
     *
     * Returns the extracted and normalized code phrase.
     */
    fun parseCode(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""

        val extracted = extractCodeFromInput(trimmed)
        return normalizeCodePhrase(extracted)
    }

    private fun extractCodeFromInput(input: String): String {
        // 1. Check if input contains code= query parameter (e.g. croc://receive?code=..., https://getcroc.com/?code=...)
        if (input.contains("code=", ignoreCase = true)) {
            val codeParam = extractCodeQueryParam(input)
            if (!codeParam.isNullOrBlank()) {
                return codeParam
            }
        }

        // 2. Try parsing standard URI for custom schemes like croc://<code> or croc://receive/<code>
        if (input.startsWith("croc://", ignoreCase = true) ||
            input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        ) {
            try {
                val uri = URI(input)
                val host = uri.host?.trim()
                val pathSegment = uri.path?.trim()?.removePrefix("/")?.removeSuffix("/")

                // Check query params if any
                val query = uri.rawQuery
                if (!query.isNullOrBlank()) {
                    val codeParam = parseQueryParam(query, "code")
                    if (!codeParam.isNullOrBlank()) {
                        return codeParam
                    }
                }

                // If scheme is croc://, check path/host for bare code (e.g., croc://1234-test or croc://receive/1234-test)
                if (uri.scheme?.equals("croc", ignoreCase = true) == true) {
                    if (!pathSegment.isNullOrBlank() && !pathSegment.equals("receive", ignoreCase = true)) {
                        return pathSegment
                    }
                    if (!host.isNullOrBlank() && !host.equals("receive", ignoreCase = true)) {
                        return host
                    }
                }
            } catch (_: Exception) {
                // Fall back if URI parsing fails
            }
        }

        // 3. Fall back to raw input as direct code
        return input
    }

    private fun extractCodeQueryParam(input: String): String? {
        val match = CODE_PARAM_REGEX.find(input) ?: return null
        val rawVal = match.groupValues.getOrNull(1) ?: return null
        return try {
            URLDecoder.decode(rawVal, "UTF-8")
        } catch (_: Exception) {
            rawVal
        }
    }

    private fun parseQueryParam(query: String, paramName: String): String? {
        for (pair in query.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2 && parts[0].equals(paramName, ignoreCase = true)) {
                return try {
                    URLDecoder.decode(parts[1], "UTF-8")
                } catch (_: Exception) {
                    parts[1]
                }
            }
        }
        return null
    }

    fun normalizeCodePhrase(code: String): String {
        return code.trim().replace(" ", "-")
    }

    /**
     * Generates an App Link URL for a given transfer code.
     * Web format: https://croc-app.github.io/receive?code=...
     * Allows phone cameras, chat apps (WhatsApp/Telegram/SMS), and browsers to open the app or fallback page directly.
     */
    fun receiveDeepLink(code: String): String {
        val encodedCode = try {
            URLEncoder.encode(code.trim(), "UTF-8")
        } catch (_: Exception) {
            code.trim()
        }
        return "https://croc-app.github.io/receive?code=$encodedCode"
    }
}
