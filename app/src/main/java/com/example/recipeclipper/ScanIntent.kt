package com.example.recipeclipper

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.example.recipeclipper.ui.edit.MAX_SCAN_PAGES

/**
 * Images shared into the app (#226): `ACTION_SEND` with one image stream (any `image/` type), or
 * `ACTION_SEND_MULTIPLE` with several, in the order sent, up to [MAX_SCAN_PAGES]. Their URIs are
 * read where they are, under the grant the share gave the activity; nothing is copied.
 */
object ScanIntent {
    fun pages(intent: Intent?): List<String>? {
        if (intent?.type?.startsWith("image/") != true) return null
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return null
        }
        return uris.map(Uri::toString).filter { it.isNotBlank() }.take(MAX_SCAN_PAGES).takeIf { it.isNotEmpty() }
    }
}
