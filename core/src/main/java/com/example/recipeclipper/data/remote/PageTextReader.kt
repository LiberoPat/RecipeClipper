package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.PageText
import com.example.recipeclipper.data.model.WebImageUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * A page's HTML as [PageText] (#103): one line per block, each the Jsoup `text()` of the text
 * between block boundaries, as the microdata parser splits steps. Pure: parsing a string does
 * no network I/O. The iOS app's `PageTextReader` walks its `HtmlTree` the same way.
 *
 * Two marks for the on-device model and `PageRecipeCheck` (#271): a short list item is one line
 * even when a `<div>` or `<p>` sits inside it (a card's note: "100g butter" and "softened" are
 * one ingredient), and each section heading's line (`<h2>`–`<h6>`) is kept in [PageText.headings],
 * so a heading the model picks can show as one. The text itself is left as written: marking
 * headings in the model's window made the 3B stand-in copy lines worse.
 */
internal object PageTextReader {

    /** Elements that end one line and start the next. */
    private val BLOCKS = setOf(
        "address", "article", "aside", "blockquote", "br", "dd", "details", "div", "dl", "dt",
        "fieldset", "figcaption", "figure", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr",
        "li", "main", "ol", "p", "pre", "section", "summary", "table", "td", "th", "tr", "ul"
    )

    /** Elements whose text is never recipe text. */
    private val SKIPPED = setOf(
        "head", "script", "style", "noscript", "template", "svg", "iframe", "nav", "footer",
        "select", "textarea", "button", "title"
    )

    /** Inside a list item, these don't end a line (#271). */
    private val JOINED_IN_ITEM = setOf("div", "p")

    /** A list item longer than this is read block by block, as before (#271). */
    private const val ITEM_MAX = 600

    /** Section headings, kept in [PageText.headings] (#271); `<h1>` is the page's title. */
    private val HEADINGS = setOf("h2", "h3", "h4", "h5", "h6")

    /** How deep the walk goes; deeper content is read as one line. */
    private const val MAX_DEPTH = 200

    fun read(html: String, url: String): PageText = read(Jsoup.parse(html, url))

    fun read(doc: Document): PageText {
        val lines = mutableListOf<String>()
        val headings = mutableSetOf<String>()
        val run = StringBuilder()
        fun flush(heading: Boolean = false) {
            val line = jsoupText(run)
            if (line.isNotEmpty()) {
                lines += line
                if (heading) headings += line
            }
            run.setLength(0)
        }
        fun walk(node: Node, depth: Int, inItem: Boolean) {
            for (child in node.childNodes()) {
                if (child is TextNode) { run.append(child.wholeText); continue }
                if (child !is Element) continue
                val name = child.normalName()
                when {
                    name in SKIPPED -> Unit
                    depth >= MAX_DEPTH -> { flush(); run.append(child.text()); flush() }
                    inItem && name in JOINED_IN_ITEM -> { run.append(' '); walk(child, depth + 1, true); run.append(' ') }
                    name in BLOCKS -> {
                        val item = when (name) {
                            "li" -> child.text().length <= ITEM_MAX
                            "ul", "ol" -> false
                            else -> inItem
                        }
                        flush(); walk(child, depth + 1, item); flush(heading = name in HEADINGS)
                    }
                    else -> walk(child, depth + 1, inItem)
                }
            }
        }
        doc.body()?.let { walk(it, 0, false) }
        flush()

        val title = doc.selectFirst("body h1")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: doc.title().trim().ifEmpty { null }
        val image = WebImageUrl.of(doc.selectFirst("meta[property=og:image]")?.absUrl("content"))
        return PageText(title, lines, JsonLdRecipeParser.pageLanguage(doc), image, headings)
    }

    /** Decoded text as Jsoup's `text()` gives it: whitespace runs (NBSP too) as one space, no
     *  zero-width space or soft hyphen, trimmed as Java trims. */
    private fun jsoupText(text: CharSequence): String {
        val out = StringBuilder()
        var lastWhite = true
        for (c in text) {
            when (c) {
                ' ', '\t', '\n', '\u000C', '\r', ' ' -> if (!lastWhite) { out.append(' '); lastWhite = true }
                '​', '­' -> Unit
                else -> { out.append(c); lastWhite = false }
            }
        }
        // Java's trim: anything up to U+0020, control characters included, not Kotlin's isWhitespace.
        var start = 0
        var end = out.length
        while (start < end && out[start] <= ' ') start++
        while (end > start && out[end - 1] <= ' ') end--
        return out.substring(start, end)
    }
}
