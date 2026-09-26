package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.PageText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * A page's HTML as [PageText] (#103): one line per block, each the Jsoup `text()` of the text
 * between block boundaries, as the microdata parser splits steps. Pure: parsing a string does
 * no network I/O. The iOS app's `PageTextReader` walks its `HtmlTree` the same way.
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

    /** How deep the walk goes; deeper content is read as one line. */
    private const val MAX_DEPTH = 200

    fun read(html: String, url: String): PageText = read(Jsoup.parse(html, url))

    fun read(doc: Document): PageText {
        val lines = mutableListOf<String>()
        val run = StringBuilder()
        fun flush() {
            val line = jsoupText(run)
            if (line.isNotEmpty()) lines += line
            run.setLength(0)
        }
        fun walk(node: Node, depth: Int) {
            for (child in node.childNodes()) {
                when {
                    child is TextNode -> run.append(child.wholeText)
                    child !is Element -> Unit
                    child.normalName() in SKIPPED -> Unit
                    depth >= MAX_DEPTH -> { flush(); run.append(child.text()); flush() }
                    child.normalName() in BLOCKS -> { flush(); walk(child, depth + 1); flush() }
                    else -> walk(child, depth + 1)
                }
            }
        }
        doc.body()?.let { walk(it, 0) }
        flush()

        val title = doc.selectFirst("body h1")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: doc.title().trim().ifEmpty { null }
        val image = doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.ifEmpty { null }
        return PageText(title, lines, JsonLdRecipeParser.pageLanguage(doc), image)
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
        return out.toString().trim { it <= ' ' }
    }
}
