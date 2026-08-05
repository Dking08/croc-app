package com.dking.crocapp.util

import java.net.URI
import java.net.URLDecoder

object QrCodeParser {
    /**
     * Parses scanned QR code raw text or string input into a valid croc code phrase.
     * Supported formats:
     * 1. Direct code (e.g. "1234-test", "word-word-word")
     * 2. URL format (e.g. "https://getcroc.com/?code=1234-test", "http://getcroc.com/?code=1234-test", "getcroc.com/?code=1234-test")
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
        // Check if input contains code= query parameter (e.g. https://getcroc.com/?code=1234-test)
        if (input.contains("code=", ignoreCase = true)) {
            val codeParam = extractCodeQueryParam(input)
            if (!codeParam.isNullOrBlank()) {
                return codeParam
            }
        }

        // Try parsing standard URL if input starts with a scheme
        if (input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true) ||
            input.startsWith("croc://", ignoreCase = true)
        ) {
            try {
                val uri = URI(input)
                val query = uri.rawQuery
                if (!query.isNullOrBlank()) {
                    val codeParam = parseQueryParam(query, "code")
                    if (!codeParam.isNullOrBlank()) {
                        return codeParam
                    }
                }
            } catch (_: Exception) {
                // Fall back to raw input if URL parsing fails
            }
        }

        return input
    }

    private fun extractCodeQueryParam(input: String): String? {
        val regex = Regex("""[?&]code=([^&]+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(input) ?: return null
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
}
