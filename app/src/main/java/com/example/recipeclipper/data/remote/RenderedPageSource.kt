package com.example.recipeclipper.data.remote

/**
 * Loads a page in a real browser engine, off screen, lets its JavaScript run, and hands back
 * the resulting HTML. The repository's last resort after the direct fetch and its retry end
 * in [com.example.recipeclipper.data.model.ParseError.Blocked] or
 * [com.example.recipeclipper.data.model.ParseError.NoRecipeFound]: a browser gets past many
 * bot checks a plain HTTP fetch doesn't, and sees recipe data that only JavaScript adds.
 *
 * An interface because the real one needs a `Context` and the main thread
 * ([com.example.recipeclipper.data.WebViewRenderedPageSource], bound in `di/PlatformModule`),
 * which the repository must never touch, and so tests can stand in a fake. The iOS app has
 * the same seam. Returns HTML only: parsing is the pure parsers' job.
 */
interface RenderedPageSource {

    /**
     * The page's `document.documentElement.outerHTML` once it has loaded and settled, or null
     * if it couldn't be loaded at all. Never throws except `CancellationException`: cancelling
     * the caller stops the load and releases the browser. The caller caps the overall time.
     */
    suspend fun render(url: String): String?

    companion object {
        /** Renders nothing: the default for tests that aren't about the fallback. */
        val None: RenderedPageSource = object : RenderedPageSource {
            override suspend fun render(url: String): String? = null
        }
    }
}
