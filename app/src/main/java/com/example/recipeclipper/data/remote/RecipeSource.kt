package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseResult

/**
 * Something that turns a link into a recipe, or a cause for why it couldn't. An interface so
 * the repository can be tested with a fake source (the iOS app has the same seam). Never
 * persists anything; that is the repository's job.
 */
interface RecipeSource {
    suspend fun fetch(url: String): ParseResult
}
