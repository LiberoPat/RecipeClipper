package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ReceivedList

/**
 * Text shared into the app (#306): a note, an email, a message. Where it goes, and what the
 * editor opens with.
 *
 * - **A recipe** ([Kind.RECIPE]): the text splits ([RecipeTextSplitter], in the language its
 *   words say, #208). It wins over a link in it: the cook chose this text, and a link beside a
 *   recipe (a signature, "see also", a page's highlight link) may lead anywhere.
 * - **A link** ([Kind.LINK]): today's import of the first http(s) link.
 * - **A list** ([Kind.LIST]): bulleted lines (#149's "Add this list"), as the app's own "Send
 *   list" writes them.
 * - **Text** ([Kind.TEXT]): anything else, opened in the editor as written, nothing guessed.
 *
 * Pure: text in, data out. The iOS port (`SharedText.swift`) follows it line for line.
 */
object SharedText {

    enum class Kind { RECIPE, LINK, LIST, TEXT }

    /** Longer text isn't offered to the editor: it rides in the route, which is saved state. */
    const val MAX_LENGTH = 50_000

    private val LINK = Regex("https?://\\S+")
    private val NEWLINE = Regex("\r\n|[\n\u000B\u000C\r\u0085\u2028\u2029]")

    /** The first http(s) link in [text], as shared (the repository cleans it). */
    fun link(text: String): String? = LINK.find(text)?.value

    fun kind(text: String): Kind = when {
        text.length <= MAX_LENGTH && read(text).sorted -> Kind.RECIPE
        link(text) != null -> Kind.LINK
        ReceivedList.bulleted(text) && ReceivedList.lines(text).isNotEmpty() -> Kind.LIST
        else -> Kind.TEXT
    }

    /**
     * What the editor opens with: the split recipe when the text splits ([Reading.sorted]), else
     * every non-blank line, trimmed and otherwise as written, as the steps. Steps, because a step
     * is never scaled, converted, bought or used up: an unsorted line can't become a confident
     * wrong number. No name either way: the first line might be "Hi Mum," (as #226 decided for a
     * scan), so Save points out the rule.
     */
    fun read(text: String): Reading {
        val split = RecipeTextSplitter.detectAndSplit(text)
        if (split != null && (split.ingredients.isNotEmpty() || split.instructions.isNotEmpty())) {
            return Reading(split, sorted = true)
        }
        val lines = text.split(NEWLINE).map { it.trim() }.filter { it.isNotEmpty() }
        val language = RecipeTextSplitter.languageOf(text)
        return Reading(SplitRecipe(emptyList(), lines, null, null, null, null, language), sorted = false)
    }

    /** [recipe] is what the editor's fields start with; its language, the text's (#208). */
    data class Reading(val recipe: SplitRecipe, val sorted: Boolean)
}
