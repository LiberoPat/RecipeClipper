package com.example.recipeclipper.data.remote

import org.jsoup.nodes.Element

/**
 * What the recipe-card plugin adapters share (WP Recipe Maker #118, Tasty Recipes and Mediavine
 * Create #119): a card's ingredients, read as groups, refine JSON-LD's `recipeIngredient` lines
 * only when they line up one-to-one with them. A named group adds a heading line ("Batter:")
 * before its ingredients, the colon form the app reads as a heading everywhere else. Pure. The
 * iOS app's `CardIngredients` is the same.
 */
internal object CardIngredients {

    class Group(val name: String, val items: List<Item>)

    /** One ingredient on the card: [key], text its JSON-LD line must hold; [line], the line to show (null: JSON-LD's). */
    class Item(val key: String, val line: String?)

    /**
     * [groups] as lines, with their headings, if their items line up with [lines]: the same count,
     * and each item's key found in its JSON-LD line once both are [normalize]d. Otherwise null.
     */
    fun lineUp(groups: List<Group>, lines: List<String>, normalize: (String) -> String): List<String>? {
        val items = groups.flatMap { it.items }
        if (items.isEmpty() || items.size != lines.size) return null
        val linesUp = items.indices.all { i ->
            normalize(items[i].key).let { key -> key.isNotEmpty() && normalize(lines[i]).contains(key) }
        }
        if (!linesUp) return null
        var index = 0
        return groups.flatMap { g ->
            val heading = g.name.removeSuffix(":").trim()
            (if (heading.isEmpty()) emptyList() else listOf("$heading:")) + g.items.map { (it.line ?: lines[index]).also { index++ } }
        }
    }

    fun text(e: Element): String = collapse(e.text())

    /** Runs of whitespace (a no-break space too) as one space, trimmed, as the iOS port splits them. */
    fun collapse(s: String): String = String(CharArray(s.length) { if (s[it].isWhitespace()) ' ' else s[it] })
        .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
