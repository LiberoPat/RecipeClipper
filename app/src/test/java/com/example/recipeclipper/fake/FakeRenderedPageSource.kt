package com.example.recipeclipper.fake

import com.example.recipeclipper.data.remote.RenderedPageSource

/**
 * A [RenderedPageSource] that answers with [answer] and records every URL it was asked to
 * render. [answer] may suspend (to stand in for a slow page, or one cancelled mid-load).
 * [before] runs first with the render's `onChallenge`, to stand in for Cloudflare's check
 * (#220): call it, then suspend for as long as the check lasts.
 */
class FakeRenderedPageSource(
    private val before: suspend (onChallenge: () -> Unit) -> Unit = {},
    private val answer: suspend (url: String) -> String? = { null }
) : RenderedPageSource {

    val requests = mutableListOf<String>()

    override suspend fun render(url: String, onChallenge: () -> Unit): String? {
        requests += url
        before(onChallenge)
        return answer(url)
    }
}
