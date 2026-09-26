package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.remote.CardIngredients.Group
import com.example.recipeclipper.data.remote.CardIngredients.Item
import org.jsoup.nodes.Element

/**
 * Tasty Recipes' and Mediavine Create's ingredient cards (#119), read only for the group headings
 * their JSON-LD drops. Unlike WP Recipe Maker, neither marks an ingredient's parts: each card
 * item is the whole line, as in JSON-LD, so JSON-LD's lines stay exactly as they are, and a
 * named group adds a heading line ("For the crust:") before its ingredients.
 *
 * Headings are what the card shows between its lists: a heading element, or a paragraph that
 * ends in a colon or is wholly bold (Tasty's own test for what to leave out of its JSON-LD).
 * Used only when the card's items line up one-to-one with JSON-LD's `recipeIngredient`: the same
 * count, and each item's letters and digits found in its JSON-LD line (WordPress curls the
 * card's quotes and dashes, not the JSON-LD's). Pure. The iOS app's `CardHeadings` is the same.
 */
internal object CardHeadings {

    /** Each plugin's ingredient list, and the element inside it that titles the whole list. */
    private val cards = listOf(
        "tasty-recipes-ingredients" to "tasty-recipes-ingredients-header",
        "mv-create-ingredients" to "mv-create-ingredients-title",
    )

    private val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /** [lines], with the headings of the first Tasty Recipes or Mediavine Create list in [page] that lines up with them. */
    fun refine(page: Element, lines: List<String>): List<String> {
        for ((list, title) in cards) {
            for (container in page.getElementsByClass(list)) {
                val groups = read(container, title) ?: continue
                return CardIngredients.lineUp(groups, lines, ::letters) ?: continue
            }
        }
        return lines
    }

    /** The list's groups, in page order, or null if an item is empty. */
    private fun read(container: Element, title: String): List<Group>? {
        val names = mutableListOf("")
        val items = mutableListOf(mutableListOf<Item>())
        for (e in container.allElements) {
            val tag = e.tagName()
            if (tag != "li" && tag != "p" && tag !in headingTags) continue
            // Not the list's title, and not a paragraph inside an item.
            val within = generateSequence(e) { it.parent() }.takeWhile { it !== container }
            if (within.any { it.hasClass(title) || (it !== e && it.tagName() == "li") }) continue
            if (tag == "li") {
                items.last() += Item(CardIngredients.text(e).ifEmpty { return null }, null)
            } else {
                heading(e)?.let { names += it; items += mutableListOf<Item>() }
            }
        }
        // A heading after the last ingredient heads nothing.
        return names.indices.map { Group(names[it], items[it]) }.dropLastWhile { it.items.isEmpty() }
    }

    private fun heading(e: Element): String? {
        val text = CardIngredients.text(e)
        val bold = e.children().singleOrNull()?.takeIf { it.tagName() == "strong" || it.tagName() == "b" }
        return when {
            text.isEmpty() -> null
            e.tagName() in headingTags -> text
            text.endsWith(":") -> text
            bold != null && CardIngredients.text(bold) == text -> text
            else -> null
        }
    }

    /** Lowercased letters and digits only, so curled quotes, dashes and spacing don't count. */
    private fun letters(s: String): String = buildString {
        s.lowercase().codePoints().forEach { if (Character.isLetterOrDigit(it)) appendCodePoint(it) }
    }
}
