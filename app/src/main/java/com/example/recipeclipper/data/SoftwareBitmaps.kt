package com.example.recipeclipper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult

/**
 * Pictures through Coil as software bitmaps, whose pixels can be read (ML Kit, #198) or drawn
 * on a PDF canvas (#318): the screen's hardware bitmaps can't be.
 */
object SoftwareBitmaps {

    /** [url] decoded by the app's image loader, the request shaped by [configure]; null when it can't be. */
    suspend fun load(context: Context, url: String, configure: ImageRequest.Builder.() -> Unit): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .apply(configure)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        return (result.drawable as? BitmapDrawable)?.bitmap
    }
}
