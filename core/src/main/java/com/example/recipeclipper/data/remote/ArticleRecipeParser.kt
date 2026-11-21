package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ArticleRecipe
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.WebImageUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Finds the recipes written inside an article (#435): no recipe data, just a list of amounts
 * followed by the method in paragraphs, once or a few times. **Only where the page has a list of
 * amounts**, never from prose alone. Pure: a parsed page in, data out. The iOS
 * `ArticleRecipeParser` walks its `HtmlTree` the same way, rule for rule.
 *
 * - **The article** is the page's first `<article>`, else its first `<main>`, else the body.
 * - **The language** is `<html lang>` unless the article's words clearly say another (#14's rule),
 *   read from its paragraphs and list items; a language with no tables finds nothing.
 * - **A list of amounts** is a `<ul>` or `<ol>` in the article (not inside a nav, header, footer,
 *   aside or form, nor inside another list's item) with at least [MIN_AMOUNTS] items that read as
 *   an amount ([RecipeTextSplitter.isAmount], the language's own words) and more than half of its
 *   items that do. An item counts only when it is at most [MAX_ITEM] characters and isn't just a
 *   link (a list of links to "10 easy dinners" is a menu, not ingredients). Every item of that
 *   list is an ingredient, as written.
 * - **Its steps** are the paragraphs, and the items of an `<ol>`, after it in document order,
 *   through any containers, up to the next heading (`<h1>`–`<h6>`, whatever it says), another
 *   `<ul>`, or the end of the article. A paragraph that is a steps header ("Preparación:", the
 *   splitter's words) is passed over; one that is an ingredients or notes header ends them.
 *   Figures, tables, asides and the like are passed over. At most [MAX_STEPS]. The site's known
 *   noise is then dropped as for any page ([SiteRules.steps]). No step, no recipe.
 * - **Every such list is read**, in page order, each with its own steps (an `<ol>` read as steps
 *   is never a list of amounts itself), at most [MAX_RECIPES].
 * - **Each one's name** is the nearest heading above its list in the article, else the page's
 *   headline: its first `<h1>`, else `og:title`, else `<title>`. No name, no recipe.
 */
object ArticleRecipeParser {

    /** At least this many items must read as an amount. */
    const val MIN_AMOUNTS = 3

    /** An item longer than this is a sentence, not an ingredient line. */
    const val MAX_ITEM = 200

    /** At most this many steps are taken for one recipe. */
    const val MAX_STEPS = 40

    /** At most this many recipes are read from one page. */
    const val MAX_RECIPES = 6

    /** At most this many paragraphs and items are read to tell the article's language. */
    private const val DETECTION_BLOCKS = 300

    /** Lists inside these are the page's furniture, never ingredients. */
    private val FURNITURE = setOf("nav", "header", "footer", "aside", "form")

    /** Passed over between the list and the end of the steps: never steps. */
    private val PASSED_OVER = setOf(
        "nav", "header", "footer", "aside", "form", "figure", "figcaption", "table", "blockquote",
        "script", "style", "noscript", "template", "iframe", "button", "select", "textarea", "svg", "picture"
    )

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /** The page's recipes written inside its article, in page order; empty when there are none. */
    fun parse(doc: Document, url: String): List<ArticleRecipe> {
        val body = doc.body() ?: return emptyList()
        val root = body.selectFirst("article") ?: body.selectFirst("main") ?: body
        val detection = root.select("p, li").take(DETECTION_BLOCKS).joinToString("\n") { it.text() }
        val language = LanguageWords.resolve(null, JsonLdRecipeParser.pageLanguage(doc)) { detection }
        val words = LanguageWords.forTag(language) ?: return emptyList()
        val headline = title(doc)
        val image = WebImageUrl.of(doc.selectFirst("meta[property=og:image]")?.absUrl("content"))

        val all = root.allElements
        val recipes = mutableListOf<ArticleRecipe>()
        val readAsSteps = mutableSetOf<Element>()
        for (list in root.select("ul, ol")) {
            if (recipes.size >= MAX_RECIPES) break
            if (readAsSteps.any { it === list } || !isAmountList(list, root, words)) continue
            val steps = SiteRules.steps(url, stepsAfter(list, root, words, readAsSteps)).filter { it.isNotEmpty() }
            if (steps.isEmpty()) continue
            val name = headingAbove(list, all) ?: headline ?: continue
            recipes += ArticleRecipe(
                name = name,
                image = image,
                ingredients = items(list).map { it.text() }.filter { it.isNotEmpty() },
                steps = steps,
                language = language.substringBefore('-'),
                sourceUrl = url,
                headline = headline ?: name
            )
        }
        return recipes
    }

    /** The page's headline, as `PageTextReader` reads its title. */
    private fun title(doc: Document): String? =
        doc.selectFirst("body h1")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: doc.title().trim().ifEmpty { null }

    /** The text of the last heading before [list] among [all], the article's elements in page order. */
    private fun headingAbove(list: Element, all: List<Element>): String? {
        val at = all.indexOfFirst { it === list }
        for (i in at - 1 downTo 0) {
            val element = all[i]
            if (element.normalName() in HEADINGS) element.text().trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun items(list: Element): List<Element> = list.children().filter { it.normalName() == "li" }

    /** Whether [list] is a list of amounts (see the class comment). */
    private fun isAmountList(list: Element, root: Element, words: LanguageWords): Boolean {
        var parent = list.parent()
        while (parent != null && parent !== root) {
            val name = parent.normalName()
            if (name in FURNITURE || name == "li") return false
            parent = parent.parent()
        }
        val items = items(list)
        val amounts = items.count { item ->
            val text = item.text()
            text.isNotEmpty() && text.length <= MAX_ITEM && !linkOnly(item, text) &&
                RecipeTextSplitter.isAmount(text, words)
        }
        return amounts >= MIN_AMOUNTS && amounts * 2 > items.size
    }

    /** An item that is nothing but a link. */
    private fun linkOnly(item: Element, text: String): Boolean = item.select("a").any { it.text() == text }

    /** The section [text] is a header of, in the splitter's words; null for an ordinary line. */
    private fun section(text: String, words: LanguageWords): RecipeTextSplitter.Section? =
        if (text.isEmpty()) null else RecipeTextSplitter.header(RecipeTextSplitter.Line(text), words)?.section

    /** The method after [list] (see the class comment); each `<ol>` read goes in [readAsSteps]. */
    private fun stepsAfter(list: Element, root: Element, words: LanguageWords, readAsSteps: MutableSet<Element>): List<String> {
        val steps = mutableListOf<String>()
        // False once the steps end.
        fun visit(element: Element): Boolean {
            if (steps.size >= MAX_STEPS) return false
            val name = element.normalName()
            return when {
                name in HEADINGS -> false
                name == "ul" -> false
                name in PASSED_OVER -> true
                name == "ol" -> {
                    readAsSteps += element
                    items(element).map { it.text() }.filter { it.isNotEmpty() }.forEach { if (steps.size < MAX_STEPS) steps += it }
                    true
                }
                name == "p" -> {
                    val text = element.text()
                    when (section(text, words)) {
                        null -> { if (text.isNotEmpty()) steps += text; true }
                        RecipeTextSplitter.Section.INSTRUCTIONS -> true
                        else -> false
                    }
                }
                else -> element.children().all { visit(it) }
            }
        }
        var cursor: Element = list
        while (cursor !== root) {
            var next = cursor.nextElementSibling()
            while (next != null) {
                if (!visit(next)) return steps
                next = next.nextElementSibling()
            }
            cursor = cursor.parent() ?: break
        }
        return steps
    }
}
