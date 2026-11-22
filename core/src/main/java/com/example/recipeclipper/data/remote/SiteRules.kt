package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.SharedTables
import com.example.recipeclipper.data.model.SourceDomain
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * Where a page says more than its JSON-LD (#120), from `shared/tables/site-rules.json`: data
 * only, read by both apps, so a site's quirk is a table edit, not code. [cards] are ingredient
 * cards any site may have (recipe plugins, #119); a site's own rules, by host without "www.",
 * come first. A rule can only add the group headings JSON-LD drops ([CardHeadings], and only
 * when the card lines up with JSON-LD's lines, or a microdata recipe's, #437), drop known noise
 * from the end of the last step, or the whole last step when it is only a pointer (#440), or add
 * the step photos a page's step numbers point at ([PageStepPhotos], #438). Pure. The iOS app's
 * `SiteRules` is the same.
 */
internal object SiteRules {

    class Site(
        val cards: List<CardHeadings.Card>,
        val stepNoise: List<String>,
        val wholeStepNoise: List<String> = emptyList(),
        val stepPhotos: PageStepPhotos.Card? = null,
    )

    private val table = SharedTables.read("site-rules")

    /** Raised on every edit of the table, so a copy fetched later can tell which is newer. */
    val version: Int = table.getInt("version")

    /** The ingredient cards any site may have: Tasty Recipes', Mediavine Create's. */
    val cards: List<CardHeadings.Card> = cards(table.getJSONArray("cards"))

    private val sites: Map<String, Site> = table.getJSONObject("sites").let { sites ->
        sites.keys().asSequence().associateWith { host ->
            val site = sites.getJSONObject(host)
            Site(
                cards(site.optJSONArray("cards")),
                SharedTables.strings(site.optJSONArray("stepNoise")),
                SharedTables.strings(site.optJSONArray("wholeStepNoise")),
                site.optJSONObject("stepPhotos")?.let(::photoCard),
            )
        }
    }

    /** The rules for [url]'s site, if it has any. */
    fun site(url: String): Site? = SourceDomain.of(url)?.let { sites[it] }

    /** [lines], with the headings of [url]'s site's cards, else of any site's; as they are if none lines up. */
    fun ingredients(page: Element, url: String, lines: List<String>): List<String> =
        CardHeadings.refine(page, lines, site(url)?.cards.orEmpty() + cards)

    /**
     * [steps], without [url]'s site's known noise at the end of the last one, then without a
     * last step that holds one of its whole-step phrases (#440).
     */
    fun steps(url: String, steps: List<String>): List<String> =
        site(url)?.let { dropWholeStep(dropNoise(steps, it.stepNoise), it.wholeStepNoise) } ?: steps

    /** The photos [url]'s site's page shows beside each of [steps] (#438), or null when it has none it proves. */
    fun stepPhotos(page: Element, url: String, steps: List<String>): List<List<String>>? =
        site(url)?.stepPhotos?.let { PageStepPhotos.read(page, steps, it) }

    /**
     * For the weekly site check: whether each of [url]'s site's rules still matches its page,
     * named ("ingredients": a card lines up with the recipe's [lines]; "step noise": found in
     * [steps]; "whole step noise": its last step dropped; "step photos": the page proves them).
     * Null when the site has no rules.
     */
    fun check(page: Element, url: String, lines: List<String>, steps: List<String>): Map<String, Boolean>? {
        val site = site(url) ?: return null
        return buildMap {
            if (site.cards.isNotEmpty()) put("ingredients", CardHeadings.lineUp(page, lines, site.cards) != null)
            if (site.stepNoise.isNotEmpty()) put("step noise", dropNoise(steps, site.stepNoise) != steps)
            if (site.wholeStepNoise.isNotEmpty()) {
                val cut = dropNoise(steps, site.stepNoise)
                put("whole step noise", dropWholeStep(cut, site.wholeStepNoise) != cut)
            }
            site.stepPhotos?.let { put("step photos", PageStepPhotos.read(page, steps, it) != null) }
        }
    }

    /**
     * [steps], with the last one cut where one of [phrases] first starts it or follows a space
     * in it (an editor's note after the method). A last step that was all noise goes, unless it
     * was the only one.
     */
    fun dropNoise(steps: List<String>, phrases: List<String>): List<String> {
        val last = steps.lastOrNull() ?: return steps
        val at = phrases.mapNotNull { noiseStart(last, it) }.minOrNull() ?: return steps
        val kept = last.substring(0, at).trim()
        return when {
            kept.isNotEmpty() -> steps.dropLast(1) + kept
            steps.size > 1 -> steps.dropLast(1)
            else -> steps
        }
    }

    /**
     * [steps] without the last one when it holds one of [phrases], in any case (#440: a last step
     * that only points at a video the app doesn't show), unless it is the only step. One step at
     * most; the words of the others are never touched.
     */
    fun dropWholeStep(steps: List<String>, phrases: List<String>): List<String> {
        if (steps.size < 2) return steps
        val last = steps.last().lowercase()
        return if (phrases.any { it.isNotEmpty() && last.contains(it.lowercase()) }) steps.dropLast(1) else steps
    }

    private fun noiseStart(step: String, phrase: String): Int? {
        if (phrase.isEmpty()) return null
        var i = step.indexOf(phrase)
        while (i >= 0) {
            if (i == 0 || step[i - 1] == ' ') return i
            i = step.indexOf(phrase, i + 1)
        }
        return null
    }

    private fun cards(array: JSONArray?): List<CardHeadings.Card> =
        if (array == null) emptyList() else SharedTables.objects(array).map(::card)

    private fun card(o: JSONObject): CardHeadings.Card {
        fun selector(key: String) = o.optString(key).ifEmpty { null }?.let(::CardSelector)
        return CardHeadings.Card(
            list = CardSelector(o.getString("list")),
            item = CardSelector(o.getString("item")),
            heading = CardSelector(o.getString("heading")),
            title = selector("title"),
            amount = selector("amount"),
            trustHeading = o.optBoolean("trustHeading", false),
        )
    }

    private fun photoCard(o: JSONObject): PageStepPhotos.Card = PageStepPhotos.Card(
        step = CardSelector(o.getString("step")),
        photo = CardSelector(o.getString("photo")),
        number = o.getString("number").ifEmpty { throw IllegalArgumentException("A stepPhotos card has no number") },
        marker = CardSelector(o.getString("marker")),
    )
}
