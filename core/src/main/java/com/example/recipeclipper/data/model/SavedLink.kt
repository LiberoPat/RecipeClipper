package com.example.recipeclipper.data.model

/**
 * Finding the saved recipe a plan names by its link (#367): an AI told to name a recipe of the cook's
 * by the link it was given ([AskAiPrompt.PlanCandidate.link], the recipe's `sourceUrl`) writes it
 * back as `isBasedOn` or `url`. The match is the same as a version's original (#354): the link as
 * written, else, for an `http` link, cleaned by [UrlCleaner] (an AI may keep or drop tracking).
 * Nothing else matches: a link that names nothing here is no recipe. Pure; the iOS port is
 * `SavedLink.swift`.
 */
object SavedLink {

    /** The recipe of [saved] that [link] names, or null. */
    fun find(link: String, saved: List<RecipeSummary>): RecipeSummary? {
        val written = link.trim().takeIf { it.isNotEmpty() } ?: return null
        saved.firstOrNull { it.sourceUrl == written }?.let { return it }
        if (!written.startsWith("http", ignoreCase = true)) return null
        val cleaned = UrlCleaner.clean(written)
        return saved.firstOrNull { it.sourceUrl == cleaned }
    }
}
