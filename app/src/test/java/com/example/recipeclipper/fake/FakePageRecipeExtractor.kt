package com.example.recipeclipper.fake

import com.example.recipeclipper.data.PageRecipeExtractor
import com.example.recipeclipper.data.model.PageSelection

/**
 * The on-device model reading a page (#103), faked: [picks] is what it "picks" from any text,
 * [languages] the languages it reads, with a window of [windowChars]. Asked in two parts (#128):
 * [extract] answers [picks] without its steps and records the text in [asked]; [extractSteps]
 * answers its steps (none while [answersSteps] is false) and records the name in [askedSteps].
 */
class FakePageRecipeExtractor(
    var picks: PageSelection? = null,
    var languages: Set<String> = setOf("en"),
    var windowChars: Int = 6_000
) : PageRecipeExtractor {
    val asked = mutableListOf<String>()
    val askedSteps = mutableListOf<String>()
    var answersSteps = true

    override suspend fun windowChars(language: String): Int? = windowChars.takeIf { language in languages }

    override suspend fun extract(text: String, language: String): PageSelection? {
        asked += text
        return picks?.copy(steps = emptyList())
    }

    override suspend fun extractSteps(text: String, language: String, name: String): List<String>? {
        askedSteps += name
        return picks?.steps?.takeIf { answersSteps }
    }
}
