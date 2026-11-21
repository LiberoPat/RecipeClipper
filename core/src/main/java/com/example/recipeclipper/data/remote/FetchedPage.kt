package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ArticleRecipe
import com.example.recipeclipper.data.model.PageText
import com.example.recipeclipper.data.model.ParseResult

/**
 * A fetch's result, and [page] only when that is `NoRecipeFound` on a page that loaded.
 * [challenge]: the refusal was Cloudflare's bot check ([CloudflareChallenge], #220), which a
 * second plain fetch wouldn't pass, so the repository skips its retry. [articles], with [page]: the
 * recipes written inside the page's article ([ArticleRecipeParser], #435), for the cook to check.
 */
data class FetchedPage(
    val result: ParseResult,
    val page: PageText? = null,
    val challenge: Boolean = false,
    val articles: List<ArticleRecipe> = emptyList()
)
