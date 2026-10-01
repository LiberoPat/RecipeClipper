package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.PageText
import com.example.recipeclipper.data.model.ParseResult

/**
 * A fetch's result, and [page] only when that is `NoRecipeFound` on a page that loaded.
 * [challenge]: the refusal was Cloudflare's bot check ([CloudflareChallenge], #220), which a
 * second plain fetch wouldn't pass, so the repository skips its retry.
 */
data class FetchedPage(val result: ParseResult, val page: PageText? = null, val challenge: Boolean = false)
