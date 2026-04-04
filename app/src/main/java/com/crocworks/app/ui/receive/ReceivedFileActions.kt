package com.crocworks.app.ui.receive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.crocworks.app.data.db.TransferHistory

fun openReceivedFile(context: Context, file: ReceivedFile) {
    openTransferUri(context, file.uri, file.mimeType)
}

fun shareReceivedFile(context: Context, file: ReceivedFile) {
    shareTransferUri(context, file.uri, file.mimeType, file.name)
}

fun openHistoryTransfer(context: Context, transfer: TransferHistory): Boolean {
    val uri = transfer.fileUri?.let(Uri::parse) ?: return false
    val mimeType = transfer.mimeType ?: "application/octet-stream"
    return openTransferUri(context, uri, mimeType)
}

private fun openTransferUri(
    context: Context,
    uri: Uri,
    mimeType: String
): Boolean {
    val openIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        context.startActivity(Intent.createChooser(openIntent, "Open file"))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun shareTransferUri(
    context: Context,
    uri: Uri,
    mimeType: String,
    subject: String
): Boolean {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    return try {
        context.startActivity(Intent.createChooser(shareIntent, "Share file"))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
