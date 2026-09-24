package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.Servings
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The fallback for a page with no JSON-LD recipe: schema.org Recipe *microdata*, the
 * `itemscope`/`itemprop` attributes on the visible markup. Tried only when
 * [JsonLdRecipeParser] finds nothing, so a site that works today is never read this way.
 *
 * Written for WordPress's Jetpack recipe block, which Smitten Kitchen uses. It marks up the
 * name, ingredients, yield and total time as microdata, but puts the steps in
 * `<div class="jetpack-recipe-directions">` with no `recipeInstructions` itemprop. That div
 * is read as the steps when the microdata has none. Its first step is bare text before any
 * `<p>` (followed by a stray `</p>`), so steps are split at block boundaries rather than
 * taken one per `<p>`, which would lose step 1.
 *
 * Pure: takes a parsed page, does no network I/O. The rules match the iOS port's
 * `MicrodataRecipeParser`, and both are tested against the same pages.
 */
internal object MicrodataRecipeParser {

    /** How deep [steps] will recurse through nested blocks before giving up. */
    private const val MAX_DEPTH = 50

    /** Elements that end one step and start the next. */
    private val BLOCKS = setOf(
        "p", "li", "div", "ol", "ul", "section", "article", "blockquote", "br", "table", "tr",
        "h1", "h2", "h3", "h4", "h5", "h6"
    )

    fun parse(html: String, sourceUrl: String): Recipe? = parse(Jsoup.parse(html, sourceUrl), sourceUrl)

    fun parse(doc: Document, sourceUrl: String): Recipe? {
        val root = doc.select("[itemscope][itemtype]").firstOrNull { isRecipeType(it.attr("itemtype")) }
            ?: return null

        val name = props(root, "name").map(::value).firstOrNull { it.isNotBlank() } ?: return null
        val ingredients = props(root, "recipeIngredient").ifEmpty { props(root, "ingredients") }
            .map(::value).filter { it.isNotBlank() }
        val instructions = props(root, "recipeInstructions").flatMap { steps(it, 0) }.ifEmpty {
            root.selectFirst(".jetpack-recipe-directions")?.let { steps(it, 0) }.orEmpty()
        }
        if (ingredients.isEmpty() && instructions.isEmpty()) return null

        return Recipe(
            name = name,
            image = props(root, "image").map(::value).firstOrNull { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.ifBlank { null },
            ingredients = ingredients,
            instructions = instructions,
            prepTime = duration(root, "prepTime"),
            cookTime = duration(root, "cookTime"),
            totalTime = duration(root, "totalTime"),
            yield = Servings.pickYield(props(root, "recipeYield").map(::value).filter { it.isNotBlank() }),
            sourceUrl = sourceUrl
        )
    }

    /** `itemtype` is a space-separated list of type URLs; any one naming schema.org's Recipe. */
    private fun isRecipeType(itemtype: String): Boolean =
        itemtype.split(Regex("\\s+")).any { it.trimEnd('/').lowercase().endsWith("schema.org/recipe") }

    /**
     * The elements carrying property [name] that belong to [scope] itself: not ones inside a
     * nested item such as an author's Person, whose own `name` is not the recipe's.
     */
    private fun props(scope: Element, name: String): List<Element> =
        scope.select("[itemprop]").filter { el ->
            name in el.attr("itemprop").split(Regex("\\s+")) && owner(el) == scope
        }

    /** The nearest enclosing item: the closest ancestor with `itemscope`. */
    private fun owner(el: Element): Element? {
        var p = el.parent()
        while (p != null && !p.hasAttr("itemscope")) p = p.parent()
        return p
    }

    /**
     * A property's value, as the microdata spec reads it: a `content` attribute when there is
     * one (schema.org's own examples put it on any element), a URL for links and media, a
     * `datetime` for `<time>`, `value` for `<data>` and `<meter>`, else the element's text.
     */
    private fun value(el: Element): String = when {
        el.hasAttr("content") -> el.attr("content")
        el.normalName() in setOf("a", "area", "link") -> el.absUrl("href")
        el.normalName() in setOf("img", "audio", "video", "source", "embed", "iframe", "track") -> el.absUrl("src")
        el.normalName() == "time" && el.hasAttr("datetime") -> el.attr("datetime")
        el.normalName() in setOf("data", "meter") -> el.attr("value")
        else -> el.text()
    }.trim()

    private fun duration(root: Element, name: String): String? =
        props(root, name).firstOrNull()?.let { JsonLdRecipeParser.formatDuration(value(it)) }

    /**
     * The steps in one instructions element. A nested HowToStep or HowToSection item gives its
     * `text` (or its sub-steps); anything else is split at block boundaries: each `<p>` or
     * `<li>` is a step, and so is a run of bare text between them.
     */
    private fun steps(el: Element, depth: Int): List<String> {
        if (depth > MAX_DEPTH) return listOfNotNull(el.text().trim().ifEmpty { null })
        if (el.hasAttr("itemscope")) {
            val nested = props(el, "itemListElement") + props(el, "step")
            if (nested.isNotEmpty()) return nested.flatMap { steps(it, depth + 1) }
            val text = props(el, "text").map(::value).filter { it.isNotBlank() }
            if (text.isNotEmpty()) return text
            return listOfNotNull(el.text().trim().ifEmpty { null })
        }

        val out = mutableListOf<String>()
        val inline = StringBuilder()
        fun flush() {
            Jsoup.parse(inline.toString()).text().trim().takeIf { it.isNotEmpty() }?.let(out::add)
            inline.setLength(0)
        }
        for (node in el.childNodes()) {
            if (node is Element && node.normalName() in BLOCKS) {
                flush()
                if (node.normalName() == "br") continue
                if (node.children().any { it.normalName() in BLOCKS }) {
                    out += steps(node, depth + 1)
                } else {
                    node.text().trim().takeIf { it.isNotEmpty() }?.let(out::add)
                }
            } else {
                inline.append(node.outerHtml())
            }
        }
        flush()
        return out
    }
}
