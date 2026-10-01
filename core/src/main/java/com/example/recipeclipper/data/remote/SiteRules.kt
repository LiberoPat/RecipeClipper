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
 * when the card lines up with JSON-LD's lines) or drop known noise from the end of the last
 * step. Pure. The iOS app's `SiteRules` is the same.
 */
internal object SiteRules {

    class Site(val cards: List<CardHeadings.Card>, val stepNoise: List<String>)

    private val table = SharedTables.read("site-rules")

    /** Raised on every edit of the table, so a copy fetched later can tell which is newer. */
    val version: Int = table.getInt("version")

    /** The ingredient cards any site may have: Tasty Recipes', Mediavine Create's. */
    val cards: List<CardHeadings.Card> = cards(table.getJSONArray("cards"))

    private val sites: Map<String, Site> = table.getJSONObject("sites").let { sites ->
        sites.keys().asSequence().associateWith { host ->
            val site = sites.getJSONObject(host)
            Site(cards(site.optJSONArray("cards")), SharedTables.strings(site.optJSONArray("stepNoise")))
        }
    }

    /** The rules for [url]'s site, if it has any. */
    fun site(url: String): Site? = SourceDomain.of(url)?.let { sites[it] }

    /** [lines], with the headings of [url]'s site's cards, else of any site's; as they are if none lines up. */
    fun ingredients(page: Element, url: String, lines: List<String>): List<String> =
        CardHeadings.refine(page, lines, site(url)?.cards.orEmpty() + cards)

    /** [steps], without [url]'s site's known noise at the end of the last one. */
    fun steps(url: String, steps: List<String>): List<String> =
        site(url)?.let { dropNoise(steps, it.stepNoise) } ?: steps

    /**
     * For the weekly site check: whether each of [url]'s site's rules still matches its page,
     * named ("ingredients": a card lines up with JSON-LD's [lines]; "step noise": found in
     * [steps]). Null when the site has no rules.
     */
    fun check(page: Element, url: String, lines: List<String>, steps: List<String>): Map<String, Boolean>? {
        val site = site(url) ?: return null
        return buildMap {
            if (site.cards.isNotEmpty()) put("ingredients", CardHeadings.lineUp(page, lines, site.cards) != null)
            if (site.stepNoise.isNotEmpty()) put("step noise", dropNoise(steps, site.stepNoise) != steps)
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
        )
    }
}
