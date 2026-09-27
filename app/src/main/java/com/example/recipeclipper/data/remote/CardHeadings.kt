package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.remote.CardIngredients.Group
import com.example.recipeclipper.data.remote.CardIngredients.Item
import org.jsoup.nodes.Element

/**
 * Ingredient cards that hold whole lines, read only for the group headings their JSON-LD drops:
 * Tasty Recipes' and Mediavine Create's (#119), and the big sites' own (#120). Unlike WP Recipe
 * Maker, none marks an ingredient's parts: each card item is the whole line, as in JSON-LD, so
 * JSON-LD's lines stay exactly as they are, and a named group adds a heading line ("For the
 * crust:") before its ingredients.
 *
 * What a card is comes from `shared/tables/site-rules.json` ([SiteRules]), as selectors. A
 * heading is what the card shows between its lists: one its selector finds that is a heading
 * element, ends in a colon or is wholly bold (Tasty's own test for what to leave out of its
 * JSON-LD). Used only when the card's items line up one-to-one with JSON-LD's
 * `recipeIngredient`: the same count, and each item's letters and digits found in its JSON-LD
 * line (WordPress curls the card's quotes and dashes, not the JSON-LD's). Pure. The iOS app's
 * `CardHeadings` is the same.
 */
internal object CardHeadings {

    /**
     * One kind of ingredient card: [list] holds it; [item] is one ingredient and [heading] a
     * group's name, both looked for only inside the list, never inside an item or the list's
     * [title]; [amount] is left out of an item's text when matching it to its JSON-LD line.
     */
    class Card(
        val list: CardSelector,
        val item: CardSelector,
        val heading: CardSelector,
        val title: CardSelector? = null,
        val amount: CardSelector? = null,
    )

    private val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /** [lines], with the headings of the first Tasty Recipes or Mediavine Create list in [page] that lines up with them. */
    fun refine(page: Element, lines: List<String>): List<String> = refine(page, lines, SiteRules.cards)

    /** [lines], with the headings of the first list of [cards] (in their order) in [page] that lines up with them. */
    fun refine(page: Element, lines: List<String>, cards: List<Card>): List<String> = lineUp(page, lines, cards) ?: lines

    /** The first list of [cards] in [page] that lines up with [lines], as lines with its headings; null if none does. */
    fun lineUp(page: Element, lines: List<String>, cards: List<Card>): List<String>? {
        for (card in cards) {
            for (container in card.list.select(page)) {
                val groups = read(container, card) ?: continue
                return CardIngredients.lineUp(groups, lines, ::letters) ?: continue
            }
        }
        return null
    }

    /** The list's groups, in page order, or null if an item is empty. */
    private fun read(container: Element, card: Card): List<Group>? {
        val names = mutableListOf("")
        val items = mutableListOf(mutableListOf<Item>())
        for (e in container.allElements) {
            val isItem = card.item.matches(e)
            if (!isItem && !card.heading.matches(e)) continue
            // Not the list's title, and nothing inside an item.
            val within = generateSequence(e) { it.parent() }.takeWhile { it !== container }
            if (within.any { card.title?.matches(it) == true || (it !== e && card.item.matches(it)) }) continue
            if (isItem) {
                items.last() += Item(key(e, card.amount).ifEmpty { return null }, null)
            } else {
                heading(e)?.let { names += it; items += mutableListOf<Item>() }
            }
        }
        // A heading after the last ingredient heads nothing.
        return names.indices.map { Group(names[it], items[it]) }.dropLastWhile { it.items.isEmpty() }
    }

    /** The item's text, without the elements inside it that [amount] finds. */
    private fun key(item: Element, amount: CardSelector?): String {
        if (amount == null) return CardIngredients.text(item)
        val copy = item.clone()
        copy.allElements.drop(1).filter(amount::matches).forEach { it.remove() }
        return CardIngredients.text(copy)
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
