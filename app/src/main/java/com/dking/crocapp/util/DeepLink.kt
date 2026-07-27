package com.dking.crocapp.util

import android.net.Uri

/**
 * Turn a scanned QR value (or a pasted string) into a croc transfer code.
 * Accepts a bare code, or a deep link — croc://receive?code=…, croc://<code>,
 * or https://…/croc/receive?code=… — returning just the code.
 */
fun extractCrocCode(raw: String): String {
    val t = raw.trim()
    val uri = runCatching { Uri.parse(t) }.getOrNull()
    if (uri != null && (uri.scheme == "croc" || uri.scheme == "http" || uri.scheme == "https")) {
        uri.getQueryParameter("code")?.trim()?.let { if (it.isNotEmpty()) return it }
        val seg = (uri.host ?: uri.lastPathSegment)?.trim()
        if (!seg.isNullOrEmpty() && seg != "receive") return seg
    }
    return t
}

/**
 * The `croc://` deep link for a code. The QR encodes this rather than a bare code, so
 * scanning it with a phone camera or any generic QR app opens the app straight into
 * receiving instead of just showing text. In-app scanners still read either form via
 * [extractCrocCode].
 */
fun receiveDeepLink(code: String): String =
    "croc://receive?code=" + Uri.encode(code.trim())
