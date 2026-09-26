package com.example.recipeclipper.ui.sharefile

import android.net.Uri
import androidx.core.content.FileProvider
import com.example.recipeclipper.data.backup.ShareFile

/**
 * The app's FileProvider, telling apps that ask that a sent file (#149) is a Recipe Clipper file.
 * A plain FileProvider names an unknown extension `application/octet-stream`, and a messaging
 * app passes on the type it was told, so the other phone wouldn't know to offer the app.
 */
class ShareFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".${ShareFile.EXTENSION}", ignoreCase = true) == true) {
            ShareFile.MIME_TYPE
        } else {
            super.getType(uri)
        }
}
