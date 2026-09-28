package com.example.recipeclipper.data

import com.example.recipeclipper.data.remote.PhotoLine

/**
 * A Reddit post with no recipe as text whose photos can be read (#198): its link (the shared
 * one), title and every picture, in order.
 */
data class PhotoPost(val url: String, val title: String, val imageUrls: List<String>)

/** What reading a post's photos came to. */
sealed class PhotoTextResult {
    /** Every line read, picture by picture in order; empty when the pictures hold no text. */
    data class Read(val lines: List<PhotoLine>) : PhotoTextResult()

    /** No picture could be fetched or read (offline, a refused or broken image). */
    data object Failed : PhotoTextResult()
}

/**
 * Reads the text in a post's photos on the device (#198): each picture is fetched at full size
 * only now, then recognised (ML Kit's bundled Latin model on Android, Vision on iOS). A seam,
 * so the ViewModel is tested with a fake and never sees Android.
 */
interface PhotoTextReader {
    /** The lines of [imageUrls], in order. A picture that fails is skipped; [PhotoTextResult.Failed]
     *  only when every one did. Cancellation propagates. */
    suspend fun read(imageUrls: List<String>): PhotoTextResult

    /** No reader (a test that never reads a photo): every read fails. */
    object Unavailable : PhotoTextReader {
        override suspend fun read(imageUrls: List<String>): PhotoTextResult = PhotoTextResult.Failed
    }
}
