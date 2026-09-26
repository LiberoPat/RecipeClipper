package com.example.recipeclipper.data.remote

import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * WP Recipe Maker's ingredient markup (#118), read only to refine the ingredient lines a
 * JSON-LD recipe already has. WPRM marks each ingredient's parts
 * (`wprm-recipe-ingredient-amount`, `-unit`, `-name`, `-notes`) and names its groups, but
 * writes its JSON-LD lines by wrapping the notes in brackets: "2 garlic cloves (, minced)",
 * "1 lb / 500 g zucchinis ((courgettes))". From the parts, the line reads as the card shows
 * it: "2 garlic cloves, minced", "1 lb / 500 g zucchinis (courgettes)".
 *
 * The notes stay on the line, after the name: a recipe's ingredients are plain lines, and the
 * scaler only reads a line's leading amount, so a note never changes what scales.
 *
 * Used only when a card's ingredients line up one-to-one with JSON-LD's `recipeIngredient`
 * (same count, each part's name found in its JSON-LD line); otherwise the JSON-LD lines stay.
 * A named group adds a heading line ("Batter:") before its ingredients, as headings are
 * written everywhere else. Pure: markup in, lines out. The iOS app's `WprmIngredients` is the same.
 */
internal object WprmIngredients {

    private class Group(val name: String, val items: List<Item>)
    private class Item(val name: String, val line: String)

    /** [lines], refined by the first WPRM ingredient list in [page] that lines up with them. */
    fun refine(page: Element, lines: List<String>): List<String> {
        for (container in page.select(".wprm-recipe-ingredients-container")) {
            val groups = read(container) ?: continue
            val items = groups.flatMap { it.items }
            if (items.size != lines.size || items.isEmpty()) continue
            val linesUp = items.indices.all { normalize(lines[it]).contains(normalize(items[it].name)) }
            if (!linesUp) continue
            return groups.flatMap { g ->
                val heading = g.name.removeSuffix(":").trim()
                (if (heading.isEmpty()) emptyList() else listOf("$heading:")) + g.items.map { it.line }
            }
        }
        return lines
    }

    /** The container's groups, or null if any ingredient has no name. */
    private fun read(container: Element): List<Group>? {
        val groupElements = container.select(".wprm-recipe-ingredient-group")
        if (groupElements.isEmpty()) return listOf(Group("", items(container) ?: return null))
        return groupElements.map { g ->
            val name = g.selectFirst(".wprm-recipe-ingredient-group-name")?.let { text(it) }.orEmpty()
            Group(name, items(g) ?: return null)
        }
    }

    private fun items(scope: Element): List<Item>? =
        scope.select("li.wprm-recipe-ingredient").map { item(it) ?: return null }

    private fun item(li: Element): Item? {
        fun part(name: String) = li.selectFirst(".wprm-recipe-ingredient-$name")?.let { text(it) }.orEmpty()
        val name = part("name").ifEmpty { return null }
        val line = listOf(part("amount"), part("unit"), name).filter { it.isNotEmpty() }.joinToString(" ")
        val notes = part("notes")
        return Item(name, line + noteSuffix(li, notes))
    }

    /** The notes as the card shows them after the name: a comma when the page puts one there. */
    private fun noteSuffix(li: Element, notes: String): String = when {
        notes.isEmpty() -> ""
        notes.startsWith(",") -> notes
        li.childNodes().any { it is TextNode && ',' in it.text() } -> ", $notes"
        else -> " $notes"
    }

    private fun text(e: Element): String = collapse(e.text())

    private fun normalize(s: String): String = collapse(s.lowercase())

    /** Runs of whitespace (a no-break space too) as one space, trimmed, as the iOS port splits them. */
    private fun collapse(s: String): String = String(CharArray(s.length) { if (s[it].isWhitespace()) ' ' else s[it] })
        .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
