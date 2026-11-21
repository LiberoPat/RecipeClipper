package com.example.recipeclipper.fake

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.IOException

/**
 * An image loader for Compose tests that never touches the network: any image at
 * [FakeImages.OK_HOST] loads at once as a plain tile, any at [FakeImages.FAILING_HOST] fails at
 * once. Provide it with `LocalImageLoader provides FakeImages.loader(context)`.
 */
object FakeImages {
    const val OK_HOST = "img.example"
    const val FAILING_HOST = "fail.example"

    fun ok(name: String) = "https://$OK_HOST/$name.jpg"
    fun failing(name: String) = "https://$FAILING_HOST/$name.jpg"

    fun loader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(Factory()) }
        .memoryCache(null)
        .diskCache(null)
        .build()

    private class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? = when (data.host) {
            OK_HOST -> Fetcher {
                DrawableResult(ColorDrawable(Color.rgb(200, 150, 100)), isSampled = false, dataSource = DataSource.MEMORY)
            }
            FAILING_HOST -> Fetcher { throw IOException("no such photo") }
            else -> null
        }
    }

    // A Fetcher from a lambda, as Coil's interface has one suspend method.
    private fun Fetcher(block: suspend () -> FetchResult): Fetcher = object : Fetcher {
        override suspend fun fetch(): FetchResult = block()
    }
}
