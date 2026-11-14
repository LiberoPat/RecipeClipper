package com.example.recipeclipper.ui.sharefile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens the share sheet on a file at [uri] (a FileProvider's), typed [mimeType], letting only
 * the chosen app read it; [text] rides along as plain text when given (a photo's recipe name,
 * #116). The one share-a-file for exports, calendars, photos, sent files and the cookbook. No
 * app to take it does nothing: the file is still where it was written.
 */
fun shareFileUri(context: Context, uri: Uri, mimeType: String, title: String, text: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: ActivityNotFoundException) {
        // Nothing can receive a file: nothing to do.
    }
}
