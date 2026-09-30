package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.PageText
import com.example.recipeclipper.data.model.ParseResult

/**
 * Something that turns a link into a recipe, or a cause for why it couldn't. An interface so
 * the repository can be tested with a fake source (the iOS app has the same seam). Never
 * persists anything; that is the repository's job.
 */
interface RecipeSource {
    suspend fun fetch(url: String): ParseResult

    /**
     * [fetch], plus the page's text when it loaded but held no recipe data (#103), for the
     * on-device model to pick one from. A source that can't say gives no text.
     */
    suspend fun fetchPage(url: String): FetchedPage = FetchedPage(fetch(url))

    /**
     * Whether a failed fetch of [url] may go on to the page as an off-screen browser renders
     * it, and to the on-device model reading its text: only for pages the blog parsers read.
     * A Reddit post (#11) is read only through its `.json` listing.
     */
    fun readsRenderedPage(url: String): Boolean = true
}

/**
 * A fetch's result, and [page] only when that is `NoRecipeFound` on a page that loaded.
 * [challenge]: the refusal was Cloudflare's bot check ([CloudflareChallenge], #220), which a
 * second plain fetch wouldn't pass, so the repository skips its retry.
 */
data class FetchedPage(val result: ParseResult, val page: PageText? = null, val challenge: Boolean = false)
