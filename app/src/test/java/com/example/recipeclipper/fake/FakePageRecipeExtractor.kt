package com.example.recipeclipper.fake

import com.example.recipeclipper.data.PageRecipeExtractor
import com.example.recipeclipper.data.model.PageSelection

/**
 * The on-device model reading a page (#103), faked: [picks] is what it "picks" from any text,
 * [languages] the languages it reads, with a window of [windowChars]. [asked] records every
 * text it was given.
 */
class FakePageRecipeExtractor(
    var picks: PageSelection? = null,
    var languages: Set<String> = setOf("en"),
    var windowChars: Int = 6_000
) : PageRecipeExtractor {
    val asked = mutableListOf<String>()

    override suspend fun windowChars(language: String): Int? = windowChars.takeIf { language in languages }

    override suspend fun extract(text: String, language: String): PageSelection? {
        asked += text
        return picks
    }
}
