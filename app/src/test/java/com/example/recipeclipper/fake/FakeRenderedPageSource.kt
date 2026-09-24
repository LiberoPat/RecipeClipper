package com.example.recipeclipper.fake

import com.example.recipeclipper.data.remote.RenderedPageSource

/**
 * A [RenderedPageSource] that answers with [answer] and records every URL it was asked to
 * render. [answer] may suspend (to stand in for a slow page, or one cancelled mid-load).
 */
class FakeRenderedPageSource(
    private val answer: suspend (url: String) -> String? = { null }
) : RenderedPageSource {

    val requests = mutableListOf<String>()

    override suspend fun render(url: String): String? {
        requests += url
        return answer(url)
    }
}
