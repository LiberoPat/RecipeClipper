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
}

/** A fetch's result, and [page] only when that is `NoRecipeFound` on a page that loaded. */
data class FetchedPage(val result: ParseResult, val page: PageText? = null)
